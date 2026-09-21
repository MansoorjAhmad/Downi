package com.omnidownloader.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * Tiny loopback HTTP server that streams MediaStore items into the app's
 * WebView so the Vault can play them in-app (WebView cannot read
 * content:// URIs directly from an https-origin page).
 *
 * Binds to 127.0.0.1 on an ephemeral port, serves only /media/<MediaStore id>
 * with ?type=video|audio, and supports HTTP Range requests so <video> seeking
 * works. One thread per connection — Chromium opens a second one to pre-buffer.
 */
public class MediaStreamServer {
    private static volatile MediaStreamServer instance;
    private volatile ServerSocket serverSocket;
    private final Context context;

    /** Random per-process token so other apps on this device cannot pull media over loopback. */
    private final String token = java.util.UUID.randomUUID().toString().replace("-", "");

    public String getToken() { return token; }

    public static MediaStreamServer get(Context context) {
        if (instance == null) {
            synchronized (MediaStreamServer.class) {
                if (instance == null) instance = new MediaStreamServer(context.getApplicationContext());
            }
        }
        return instance;
    }

    private MediaStreamServer(Context context) {
        this.context = context;
    }

    /** Starts (or reuses) the server; returns the bound local port. */
    public synchronized int start() throws Exception {
        if (serverSocket != null && !serverSocket.isClosed()) return serverSocket.getLocalPort();
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final ServerSocket socket = serverSocket;
        Thread accept = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    final Socket client = socket.accept();
                    new Thread(() -> handle(client), "DowniMediaConn").start();
                } catch (Exception ignored) {
                    // socket closed by stop() or spurious wakeup — loop exits via isClosed()
                    if (socket.isClosed()) return;
                }
            }
        }, "DowniMediaServer");
        accept.setDaemon(true);
        accept.start();
        return serverSocket.getLocalPort();
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;

            long rangeStart = -1, rangeEnd = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.startsWith("range:")) {
                    String spec = line.substring(6).trim();
                    if (spec.startsWith("bytes=")) {
                        String[] seg = spec.substring(6).split("-");
                        try { rangeStart = Long.parseLong(seg[0].trim()); } catch (Exception ignored) {}
                        if (seg.length > 1) {
                            try { rangeEnd = Long.parseLong(seg[1].trim()); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            if (!path.contains("t=" + token)) {
                writeSimple(client, 403, "forbidden");
                return;
            }
            boolean audio = path.contains("type=audio");
            String idPart = path.replace("/media/", "");
            int q = idPart.indexOf('?');
            if (q >= 0) idPart = idPart.substring(0, q);

            long id;
            try {
                id = Long.parseLong(idPart.trim());
            } catch (Exception e) {
                id = -1;
            }
            if (id < 0) {
                writeSimple(client, 404, "media not found");
                return;
            }

            Uri uri = Uri.withAppendedPath(
                audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                String.valueOf(id));
            ContentResolver resolver = context.getContentResolver();
            ParcelFileDescriptor pfd;
            try {
                pfd = resolver.openFileDescriptor(uri, "r");
            } catch (SecurityException e) {
                pfd = null;
            }
            if (pfd == null) {
                writeSimple(client, 404, "media not found");
                return;
            }

            long total = pfd.getStatSize();
            long start = 0, end = total - 1;
            boolean partial = false;
            if (rangeStart >= 0 && total > 0) {
                start = Math.min(rangeStart, Math.max(0, total - 1));
                if (rangeEnd >= 0) end = Math.min(rangeEnd, total - 1);
                if (end < start) end = total - 1;
                partial = true;
            }
            long length = end - start + 1;

            FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
            fis.getChannel().position(start);

            OutputStream out = client.getOutputStream();
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
            head.append("Content-Type: ").append(queryMime(resolver, uri, audio)).append("\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (partial) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("ISO-8859-1"));
            out.flush();

            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            try (InputStream stream = fis) {
                while (remaining > 0) {
                    int read = stream.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (read < 0) break;
                    out.write(buf, 0, read);
                    remaining -= read;
                }
                out.flush();
            } catch (Exception ignored) {
                // client aborted mid-stream (seek or close) — expected
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private String queryMime(ContentResolver resolver, Uri uri, boolean audio) {
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.MediaColumns.MIME_TYPE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String m = c.getString(0);
                if (m != null && !m.isEmpty()) return m;
            }
        } catch (Exception ignored) {}
        return audio ? "audio/mp4" : "video/mp4";
    }

    private String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return any ? sb.toString() : null;
    }

    private void writeSimple(Socket client, int code, String message) {
        try {
            byte[] body = message.getBytes("UTF-8");
            OutputStream out = client.getOutputStream();
            String head = "HTTP/1.1 " + code + " \r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
            out.write(head.getBytes("ISO-8859-1"));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {}
    }
}
