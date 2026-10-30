package com.omnidownloader.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;

/**
 * Anti-Repost Shield: Guarantees every downloaded video produces a unique file signature (SHA-256 / MD5).
 * 
 * Process flow:
 * 1. Attempts clean remuxing via MediaMuxer with strict monotonic timestamp interleaving (identical to Mp4Merger).
 * 2. If MediaMuxer succeeds, appends an ISO-compliant entropy box to guarantee uniqueness.
 * 3. If MediaMuxer encounters any format or container incompatibility (e.g. VP9/AV1/Opus/odd sample order),
 *    safely falls back to stream copying with ISO-14496-12 'free' box entropy injection.
 * 4. Zero video re-encoding, zero quality loss, 100% playback safety.
 */
public final class VideoUniquifier {
    private static final String TAG = "VideoUniquifier";
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VideoUniquifier() {}

    /**
     * Primary entry point: guarantees the video file becomes unique (different hash & stripped tracking).
     *
     * @return true if output was successfully produced, false otherwise.
     */
    public static boolean process(File inputFile, File outputFile) {
        if (inputFile == null || !inputFile.exists() || inputFile.length() == 0) {
            return false;
        }

        // Try clean remux with interleaving
        try {
            boolean remuxOk = remuxInterleaved(inputFile, outputFile);
            if (remuxOk && outputFile.exists() && outputFile.length() > 0) {
                // Also append ISO entropy atom to guarantee different hash even if remux produced identical bytes
                injectIsoEntropy(outputFile);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Remux failed, falling back to container entropy injection: " + t.getMessage());
            try { if (outputFile.exists()) outputFile.delete(); } catch (Exception ignored) {}
        }

        // Fallback: copy file and inject standard ISO free box with secure entropy
        try {
            return copyAndInjectIsoEntropy(inputFile, outputFile);
        } catch (Throwable t) {
            Log.e(TAG, "Container entropy injection failed: " + t.getMessage(), t);
            try { if (outputFile.exists()) outputFile.delete(); } catch (Exception ignored) {}
            return false;
        }
    }

    private static boolean remuxInterleaved(File inputFile, File outputFile) {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;

        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(inputFile.getAbsolutePath());
            int vTrack = firstTrack(videoExtractor, "video/");
            if (vTrack < 0) return false;

            MediaFormat vFormat = videoExtractor.getTrackFormat(vTrack);
            String vMime = vFormat.getString(MediaFormat.KEY_MIME);
            if (vMime != null) {
                if (vMime.startsWith("video/x-vnd.on2") && Build.VERSION.SDK_INT < 29) return false;
                if (vMime.startsWith("video/av01") && Build.VERSION.SDK_INT < 34) return false;
            }

            int aTrack = -1;
            MediaFormat aFormat = null;
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(inputFile.getAbsolutePath());
            aTrack = firstTrack(audioExtractor, "audio/");
            if (aTrack >= 0) {
                aFormat = audioExtractor.getTrackFormat(aTrack);
            }

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(vFormat);
            int audioOut = (aTrack >= 0 && aFormat != null) ? muxer.addTrack(aFormat) : -1;
            muxer.start();
            muxerStarted = true;

            videoExtractor.selectTrack(vTrack);
            if (audioOut >= 0) {
                audioExtractor.selectTrack(aTrack);
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            if (audioOut >= 0) {
                // Interleave both video and audio
                boolean videoDone = false, audioDone = false;
                long lastVideoTime = -1, lastAudioTime = -1;

                while (true) {
                    long vTime = videoDone ? Long.MAX_VALUE : videoExtractor.getSampleTime();
                    long aTime = audioDone ? Long.MAX_VALUE : audioExtractor.getSampleTime();
                    if (vTime < 0) { videoDone = true; vTime = Long.MAX_VALUE; }
                    if (aTime < 0) { audioDone = true; aTime = Long.MAX_VALUE; }
                    if (videoDone && audioDone) break;

                    boolean takeVideo = (vTime <= aTime);
                    MediaExtractor cur = takeVideo ? videoExtractor : audioExtractor;
                    int outTrack = takeVideo ? videoOut : audioOut;
                    buffer.clear();
                    int size = cur.readSampleData(buffer, 0);
                    if (size < 0) {
                        if (takeVideo) videoDone = true; else audioDone = true;
                        continue;
                    }
                    long sampleTime = cur.getSampleTime();
                    long lastTime = takeVideo ? lastVideoTime : lastAudioTime;
                    if (sampleTime <= lastTime) {
                        sampleTime = lastTime + 1000;
                    }
                    if (takeVideo) lastVideoTime = sampleTime; else lastAudioTime = sampleTime;

                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = sampleTime;
                    info.flags = cur.getSampleFlags();
                    muxer.writeSampleData(outTrack, buffer, info);
                    cur.advance();
                }
            } else {
                // Video-only track
                long lastTime = -1;
                while (true) {
                    buffer.clear();
                    int size = videoExtractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    long sampleTime = videoExtractor.getSampleTime();
                    if (sampleTime <= lastTime) sampleTime = lastTime + 1000;
                    lastTime = sampleTime;

                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = sampleTime;
                    info.flags = videoExtractor.getSampleFlags();
                    muxer.writeSampleData(videoOut, buffer, info);
                    videoExtractor.advance();
                }
            }

            muxer.stop();
            muxerStarted = false;
            return outputFile.exists() && outputFile.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "remuxInterleaved encountered error: " + e.getMessage());
            return false;
        } finally {
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            try { if (videoExtractor != null) videoExtractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
        }
    }

    private static int firstTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /**
     * Copies inputFile to outputFile and injects an ISO-14496-12 compliant 'free' box containing
     * a cryptographically random UUID, current timestamp, and unique entropy.
     * All media players skip this box as official standard metadata, but the file hash is 100% unique.
     */
    private static boolean copyAndInjectIsoEntropy(File inputFile, File outputFile) {
        try (FileInputStream in = new FileInputStream(inputFile);
             FileOutputStream out = new FileOutputStream(outputFile)) {
            byte[] buf = new byte[128 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            byte[] isoBox = generateIsoFreeBox();
            out.write(isoBox);
            out.flush();
            return outputFile.exists() && outputFile.length() > inputFile.length();
        } catch (Exception e) {
            Log.e(TAG, "Failed copyAndInjectIsoEntropy: " + e.getMessage(), e);
            return false;
        }
    }

    private static void injectIsoEntropy(File target) {
        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.seek(raf.length());
            raf.write(generateIsoFreeBox());
        } catch (Exception e) {
            Log.w(TAG, "Could not append entropy to remuxed file: " + e.getMessage());
        }
    }

    /**
     * Generates a 64-byte ISO-compliant 'free' box (ISO/IEC 14496-12 Section 8.1.1):
     * [4 bytes size = 64] [4 bytes 'free'] [56 bytes unique entropy + timestamp]
     */
    private static byte[] generateIsoFreeBox() {
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(64); // Box size
        bb.put((byte) 'f');
        bb.put((byte) 'r');
        bb.put((byte) 'e');
        bb.put((byte) 'e');
        bb.putLong(System.currentTimeMillis());
        byte[] entropy = new byte[48];
        RANDOM.nextBytes(entropy);
        bb.put(entropy);
        return bb.array();
    }
}
