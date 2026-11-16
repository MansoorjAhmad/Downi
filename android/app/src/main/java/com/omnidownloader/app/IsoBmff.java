package com.omnidownloader.app;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * IsoBmff: Container detection, legal entropy atom injection, metadata sanitization,
 * and single-pass streaming hashing for Anti-Repost Shield S2.
 */
public final class IsoBmff {
    private static final String TAG = "IsoBmff";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MP4_EPOCH_DELTA_SEC = 2082844800L;

    public enum ContainerType {
        MP4("mp4", "video/mp4"),
        WEBM("webm", "video/webm"),
        UNKNOWN("", "application/octet-stream");

        public final String extension;
        public final String mimeType;

        ContainerType(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    private IsoBmff() {}

    /**
     * Fast header-sniffing to identify container format.
     */
    public static ContainerType sniff(File file) {
        if (file == null || !file.exists() || file.length() < 8) {
            return ContainerType.UNKNOWN;
        }
        byte[] header = new byte[64];
        int read;
        try (FileInputStream fis = new FileInputStream(file)) {
            read = fis.read(header);
        } catch (Exception e) {
            return ContainerType.UNKNOWN;
        }
        if (read < 8) return ContainerType.UNKNOWN;

        // EBML (Matroska / WebM): starts with 0x1A 0x45 0xDF 0xA3
        if ((header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45
                && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3) {
            return ContainerType.WEBM;
        }

        // ISO-BMFF (MP4, M4V, MOV): first 4 bytes size, next 4 bytes box type
        long boxSize = ((header[0] & 0xFFL) << 24)
                | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8)
                | (header[3] & 0xFFL);
        if (boxSize != 0 && boxSize != 1 && boxSize < 8) {
            return ContainerType.UNKNOWN;
        }
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        if ("ftyp".equalsIgnoreCase(type) || "moov".equalsIgnoreCase(type)
                || "free".equalsIgnoreCase(type) || "mdat".equalsIgnoreCase(type)
                || "skip".equalsIgnoreCase(type) || "wide".equalsIgnoreCase(type)) {
            return ContainerType.MP4;
        }

        return ContainerType.UNKNOWN;
    }

    public static boolean isMp4(File file) {
        return sniff(file) == ContainerType.MP4;
    }

    public static boolean isWebm(File file) {
        return sniff(file) == ContainerType.WEBM;
    }

    /**
     * Generates a 64-byte ISO-compliant 'free' box (ISO/IEC 14496-12 Section 8.1.1):
     * [4 bytes size = 64] [4 bytes 'free'] [8 bytes timestamp] [48 bytes secure random entropy]
     */
    public static byte[] generateIsoFreeBox() {
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.putInt(64);
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

    /**
     * Generates a 64-byte EBML-compliant Void element (RFC 8794 Section 10.1):
     * [1 byte 0xEC] [1 byte VINT size 0xBE (62 bytes)] [8 bytes timestamp] [54 bytes secure random entropy]
     */
    public static byte[] generateEbmlVoidElement() {
        ByteBuffer bb = ByteBuffer.allocate(64);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) 0xEC); // EBML Void element ID
        bb.put((byte) 0xBE); // VINT size = 62 (0x80 | 0x3E)
        bb.putLong(System.currentTimeMillis());
        byte[] entropy = new byte[54];
        RANDOM.nextBytes(entropy);
        bb.put(entropy);
        return bb.array();
    }

    /**
     * Appends an ISO free box to the given target file in-place.
     */
    public static void injectIsoFreeBox(File target) {
        if (target == null || !target.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.seek(raf.length());
            raf.write(generateIsoFreeBox());
        } catch (Exception e) {
            Log.w(TAG, "Could not append ISO free box: " + e.getMessage());
        }
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Computes the SHA-256 hash of a file.
     */
    public static String computeSha256(File file) {
        if (file == null || !file.exists() || file.length() == 0) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = fis.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            return bytesToHex(md.digest());
        } catch (Exception e) {
            Log.w(TAG, "Failed to compute SHA-256: " + e.getMessage());
            return "";
        }
    }

    public static final class CopyResult {
        public final boolean success;
        public final String inputSha256;
        public final String outputSha256;
        public final long bytesWritten;

        public CopyResult(boolean success, String inSha, String outSha, long bytes) {
            this.success = success;
            this.inputSha256 = inSha;
            this.outputSha256 = outSha;
            this.bytesWritten = bytes;
        }
    }

    /**
     * Copies input to output, injecting container-appropriate entropy (for MP4, appends ISO 'free';
     * for WebM, places EBML 'Void' inside the Segment element), while hashing input and output.
     */
    public static CopyResult copyAndInjectEntropy(File input, File output, ContainerType container) {
        if (input == null || !input.exists() || input.length() == 0 || output == null) {
            return new CopyResult(false, "", "", 0);
        }
        MessageDigest inMd;
        MessageDigest outMd;
        try {
            inMd = MessageDigest.getInstance("SHA-256");
            outMd = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return new CopyResult(false, "", "", 0);
        }

        long bytesWritten = 0;
        try (FileInputStream in = new FileInputStream(input);
             FileOutputStream out = new FileOutputStream(output)) {
            byte[] buf = new byte[128 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                inMd.update(buf, 0, len);
                outMd.update(buf, 0, len);
                out.write(buf, 0, len);
                bytesWritten += len;
            }

            if (container == ContainerType.MP4) {
                byte[] entropy = generateIsoFreeBox();
                outMd.update(entropy);
                out.write(entropy);
                bytesWritten += entropy.length;
                out.flush();
                return new CopyResult(true, bytesToHex(inMd.digest()), bytesToHex(outMd.digest()), bytesWritten);
            }
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "copyAndInjectEntropy failed: " + e.getMessage(), e);
            try { if (output.exists()) output.delete(); } catch (Exception ignored) {}
            return new CopyResult(false, "", "", 0);
        }

        if (container == ContainerType.WEBM) {
            if (!injectWebmVoidInsideSegment(output)) {
                try { if (output.exists()) output.delete(); } catch (Exception ignored) {}
                return new CopyResult(false, "", "", 0);
            }
            String inSha = bytesToHex(inMd.digest());
            String outSha = computeSha256(output);
            return new CopyResult(!outSha.isEmpty(), inSha, outSha, output.length());
        }

        String inSha = bytesToHex(inMd.digest());
        String outSha = bytesToHex(outMd.digest());
        return new CopyResult(true, inSha, outSha, bytesWritten);
    }

    /**
     * Appends an EBML Void element inside the WebM Segment element, updating the Segment's
     * VINT size header in-place when finite (preserving all SeekHead/Cluster/Cue offsets).
     */
    public static boolean injectWebmVoidInsideSegment(File webmFile) {
        if (webmFile == null || !webmFile.exists() || webmFile.length() < 16) return false;
        try (RandomAccessFile raf = new RandomAccessFile(webmFile, "rw")) {
            long fileLen = raf.length();
            raf.seek(0);
            byte[] ebmlId = new byte[4];
            raf.readFully(ebmlId);
            if ((ebmlId[0] & 0xFF) != 0x1A || (ebmlId[1] & 0xFF) != 0x45
                    || (ebmlId[2] & 0xFF) != 0xDF || (ebmlId[3] & 0xFF) != 0xA3) {
                return false;
            }
            Vint ebmlSize = readEbmlVint(raf, fileLen);
            if (ebmlSize == null || ebmlSize.unknown) return false;
            long segPos = raf.getFilePointer() + ebmlSize.value;
            if (segPos + 5 > fileLen) return false;

            raf.seek(segPos);
            byte[] segId = new byte[4];
            raf.readFully(segId);
            if ((segId[0] & 0xFF) != 0x18 || (segId[1] & 0xFF) != 0x53
                    || (segId[2] & 0xFF) != 0x80 || (segId[3] & 0xFF) != 0x67) {
                return false;
            }

            long segSizePos = raf.getFilePointer();
            Vint segSize = readEbmlVint(raf, fileLen);
            if (segSize == null) return false;

            byte[] voidElem = generateEbmlVoidElement();
            if (segSize.unknown) {
                // Unknown Segment size spans to EOF; appending at EOF places Void inside Segment.
                raf.seek(fileLen);
                raf.write(voidElem);
                return true;
            }

            long segDataStart = segSizePos + segSize.width;
            if (segDataStart + segSize.value != fileLen) {
                // Segment does not terminate at EOF (e.g. multi-segment); avoid corrupting bounds.
                return false;
            }
            long newSize = segSize.value + voidElem.length;
            long maxVal = (1L << (7 * segSize.width)) - 2L;
            if (newSize > maxVal) {
                return false;
            }
            byte[] encodedSize = encodeEbmlVint(newSize, segSize.width);
            raf.seek(segSizePos);
            raf.write(encodedSize);
            raf.seek(fileLen);
            raf.write(voidElem);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "injectWebmVoidInsideSegment failed: " + e.getMessage());
            return false;
        }
    }

    private static final class Vint {
        final int width;
        final long value;
        final boolean unknown;

        Vint(int width, long value, boolean unknown) {
            this.width = width;
            this.value = value;
            this.unknown = unknown;
        }
    }

    private static Vint readEbmlVint(RandomAccessFile raf, long fileLen) throws Exception {
        long pos = raf.getFilePointer();
        if (pos >= fileLen) return null;
        int first = raf.readUnsignedByte();
        if (first == 0) return null;
        int leadingZeros = Integer.numberOfLeadingZeros(first) - 24;
        int width = leadingZeros + 1;
        if (width < 1 || width > 8 || pos + width > fileLen) return null;

        long mask = (1L << (8 - width)) - 1L;
        long val = first & mask;
        for (int i = 1; i < width; i++) {
            val = (val << 8) | raf.readUnsignedByte();
        }
        long unknownSentinel = (1L << (7 * width)) - 1L;
        return new Vint(width, val, val == unknownSentinel);
    }

    private static byte[] encodeEbmlVint(long value, int width) {
        byte[] out = new byte[width];
        for (int i = width - 1; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        out[0] |= (byte) (1 << (8 - width));
        return out;
    }

    /**
     * Tier A MP4 metadata sanitization:
     * - Updates creation & modification timestamps in mvhd, tkhd, and mdhd.
     * - Scrubs tracking/metadata boxes (udta, meta, uuid) in-place by turning them into
     *   same-size 'free' boxes with zeroed payloads so all stco/co64 chunk offsets stay valid.
     */
    public static boolean sanitizeMp4Atoms(File mp4File) {
        if (mp4File == null || !mp4File.exists() || mp4File.length() < 16) return false;
        boolean updatedAny = false;
        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw")) {
            long fileLen = raf.length();
            long nowMp4Sec = (System.currentTimeMillis() / 1000L) + MP4_EPOCH_DELTA_SEC;
            updatedAny = walkMp4Container(raf, 0L, fileLen, nowMp4Sec, 0);
        } catch (Exception e) {
            Log.w(TAG, "sanitizeMp4Atoms failed (non-fatal): " + e.getMessage());
        }
        return updatedAny;
    }

    /** Backwards-compatible wrapper that updates mvhd (+ tkhd/mdhd + scrubs udta/meta/uuid). */
    public static boolean rewriteMvhdTimestamp(File mp4File) {
        return sanitizeMp4Atoms(mp4File);
    }

    private static boolean walkMp4Container(RandomAccessFile raf, long start, long end,
                                            long nowMp4Sec, int depth) throws Exception {
        if (depth > 4) return false;
        boolean updated = false;
        long pos = start;
        byte[] typeBytes = new byte[4];
        while (pos <= end - 8) {
            raf.seek(pos);
            long boxSize = raf.readInt() & 0xFFFFFFFFL;
            raf.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
            long headerSize = 8;
            if (boxSize == 1) {
                if (pos + 16 > end) break;
                boxSize = raf.readLong();
                headerSize = 16;
            } else if (boxSize == 0) {
                boxSize = end - pos;
            }
            if (boxSize < headerSize || pos + boxSize > end) break;

            long payloadStart = pos + headerSize;
            long boxEnd = pos + boxSize;

            if ("mvhd".equalsIgnoreCase(type) || "tkhd".equalsIgnoreCase(type) || "mdhd".equalsIgnoreCase(type)) {
                if (rewriteFullBoxTimestamps(raf, payloadStart, boxEnd, nowMp4Sec)) {
                    updated = true;
                }
            } else if ("udta".equalsIgnoreCase(type) || "meta".equalsIgnoreCase(type) || "uuid".equalsIgnoreCase(type)) {
                scrubBoxToFree(raf, pos, payloadStart, boxEnd);
                updated = true;
            } else if ("moov".equalsIgnoreCase(type) || "trak".equalsIgnoreCase(type) || "mdia".equalsIgnoreCase(type)) {
                if (walkMp4Container(raf, payloadStart, boxEnd, nowMp4Sec, depth + 1)) {
                    updated = true;
                }
            }
            pos = boxEnd;
        }
        return updated;
    }

    private static boolean rewriteFullBoxTimestamps(RandomAccessFile raf, long payloadStart,
                                                    long boxEnd, long nowMp4Sec) throws Exception {
        if (payloadStart + 12 > boxEnd) return false;
        raf.seek(payloadStart);
        int version = raf.readByte() & 0xFF;
        raf.skipBytes(3); // flags
        if (version == 0) {
            if (payloadStart + 12 > boxEnd) return false;
            raf.writeInt((int) (nowMp4Sec & 0xFFFFFFFFL));
            raf.writeInt((int) (nowMp4Sec & 0xFFFFFFFFL));
            return true;
        } else if (version == 1) {
            if (payloadStart + 20 > boxEnd) return false;
            raf.writeLong(nowMp4Sec);
            raf.writeLong(nowMp4Sec);
            return true;
        }
        return false;
    }

    private static void scrubBoxToFree(RandomAccessFile raf, long boxStart,
                                       long payloadStart, long boxEnd) throws Exception {
        raf.seek(boxStart + 4);
        raf.write(new byte[]{'f', 'r', 'e', 'e'});
        long remaining = boxEnd - payloadStart;
        if (remaining <= 0) return;
        raf.seek(payloadStart);
        byte[] zeros = new byte[(int) Math.min(remaining, 16 * 1024)];
        while (remaining > 0) {
            int toWrite = (int) Math.min(remaining, zeros.length);
            raf.write(zeros, 0, toWrite);
            remaining -= toWrite;
        }
    }
}
