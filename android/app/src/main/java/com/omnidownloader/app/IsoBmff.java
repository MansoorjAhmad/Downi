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
 * IsoBmff: Container detection, legal entropy atom injection, metadata rewriting,
 * and single-pass streaming hashing for Anti-Repost Shield S2.
 */
public final class IsoBmff {
    private static final String TAG = "IsoBmff";
    private static final SecureRandom RANDOM = new SecureRandom();

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
            byte[] buf = new byte[64 * 1024];
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
     * Copies input to output, injecting container-appropriate entropy, while hashing
     * both input and output streams simultaneously in a single pass.
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

            byte[] entropy = null;
            if (container == ContainerType.MP4) {
                entropy = generateIsoFreeBox();
            } else if (container == ContainerType.WEBM) {
                entropy = generateEbmlVoidElement();
            }

            if (entropy != null) {
                outMd.update(entropy);
                out.write(entropy);
                bytesWritten += entropy.length;
            }
            out.flush();

            String inSha = bytesToHex(inMd.digest());
            String outSha = bytesToHex(outMd.digest());
            return new CopyResult(true, inSha, outSha, bytesWritten);
        } catch (Exception e) {
            Log.e(TAG, "copyAndInjectEntropy failed: " + e.getMessage(), e);
            try { if (output.exists()) output.delete(); } catch (Exception ignored) {}
            return new CopyResult(false, "", "", 0);
        }
    }

    /**
     * Safely updates creation & modification timestamps in the ISO-BMFF mvhd box.
     * Parses the top-level box structure with strict boundary checks to avoid file corruption.
     * Returns true if timestamps were rewritten, false otherwise.
     */
    public static boolean rewriteMvhdTimestamp(File mp4File) {
        if (mp4File == null || !mp4File.exists() || mp4File.length() < 16) return false;
        try (RandomAccessFile raf = new RandomAccessFile(mp4File, "rw")) {
            long fileLen = raf.length();
            long pos = 0;
            while (pos < fileLen - 8) {
                raf.seek(pos);
                long boxSize = raf.readInt() & 0xFFFFFFFFL;
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                long headerSize = 8;
                if (boxSize == 1) {
                    if (pos + 16 > fileLen) break;
                    boxSize = raf.readLong();
                    headerSize = 16;
                } else if (boxSize == 0) {
                    boxSize = fileLen - pos;
                }
                if (boxSize < headerSize || pos + boxSize > fileLen) break;

                if ("moov".equalsIgnoreCase(type)) {
                    long moovStart = pos + headerSize;
                    long moovEnd = pos + boxSize;
                    long subPos = moovStart;
                    while (subPos < moovEnd - 8) {
                        raf.seek(subPos);
                        long subSize = raf.readInt() & 0xFFFFFFFFL;
                        byte[] subTypeBytes = new byte[4];
                        raf.readFully(subTypeBytes);
                        String subType = new String(subTypeBytes, StandardCharsets.ISO_8859_1);
                        long subHeaderSize = 8;
                        if (subSize == 1) {
                            if (subPos + 16 > moovEnd) break;
                            subSize = raf.readLong();
                            subHeaderSize = 16;
                        } else if (subSize == 0) {
                            subSize = moovEnd - subPos;
                        }
                        if (subSize < subHeaderSize || subPos + subSize > moovEnd) break;

                        if ("mvhd".equalsIgnoreCase(subType)) {
                            raf.seek(subPos + subHeaderSize);
                            int version = raf.readByte() & 0xFF;
                            raf.skipBytes(3); // flags
                            // Seconds since Jan 1, 1904 UTC (delta = 2082844800s)
                            long mp4EpochDelta = 2082844800L;
                            long nowMp4Sec = (System.currentTimeMillis() / 1000L) + mp4EpochDelta;
                            if (version == 0) {
                                raf.writeInt((int) (nowMp4Sec & 0xFFFFFFFFL));
                                raf.writeInt((int) (nowMp4Sec & 0xFFFFFFFFL));
                            } else if (version == 1) {
                                raf.writeLong(nowMp4Sec);
                                raf.writeLong(nowMp4Sec);
                            }
                            return true;
                        }
                        subPos += subSize;
                    }
                    break;
                }
                pos += boxSize;
            }
        } catch (Exception e) {
            Log.w(TAG, "rewriteMvhdTimestamp failed (non-fatal): " + e.getMessage());
        }
        return false;
    }
}
