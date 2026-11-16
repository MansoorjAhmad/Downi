package com.omnidownloader.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Anti-Repost Shield S2:
 * Guarantees that every downloaded video produces a unique file signature (SHA-256)
 * while preserving 100% container integrity and upload decode compatibility.
 *
 * S2 Architecture:
 * - Never touch or delete the original file until the new file passes strict validation.
 * - Container-preserving: .webm stays .webm (video/webm), .mp4 stays .mp4 (video/mp4).
 * - Audio-only media is skipped untouched.
 * - Safe Remux Lane (L2): Remuxes standard MP4 + H.264 (AVC <= High) or 8-bit HEVC (Main) + AAC.
 *   Preserves original presentation timestamps (PTS) and orientation hint.
 * - Safe Identity Lane (L0/L1): For non-remuxable containers/codecs (WebM, VP9, AV1, 10-bit/HDR, etc.),
 *   performs container-safe identity modification via ISO 'free' boxes or EBML 'Void' elements
 *   inside WebM Segment, along with mvhd/tkhd/mdhd timestamp updates and udta/meta scrubbing.
 * - Validation Gate: Output must open in MediaExtractor, match tracks, codecs, dimensions,
 *   duration (±150ms), audio track integrity, decodable keyframe, and unique SHA-256.
 * - Skipped Mode: If validation fails, original is saved unchanged with an honest report.
 */
public final class VideoUniquifier {
    private static final String TAG = "VideoUniquifier";
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;
    private static final int COLOR_TRANSFER_ST2084 = 6;
    private static final int COLOR_TRANSFER_HLG = 7;

    public static final class Result {
        public final boolean success;
        public final File file;
        public final String extension;
        public final String mimeType;
        public final String mode; // "remux", "identity", "deep", "skipped"
        public final String reason;
        public final String inputSha256;
        public final String outputSha256;

        public Result(boolean success, File file, String extension, String mimeType,
                      String mode, String reason, String inputSha256, String outputSha256) {
            this.success = success;
            this.file = file;
            this.extension = extension;
            this.mimeType = mimeType;
            this.mode = mode;
            this.reason = reason;
            this.inputSha256 = inputSha256;
            this.outputSha256 = outputSha256;
        }

        public static boolean isAppliedMode(String mode) {
            return "remux".equals(mode) || "identity".equals(mode) || "deep".equals(mode);
        }

        public static Result skipped(File originalFile, String extension, String mimeType, String reason, String inputSha) {
            return new Result(false, originalFile, extension, mimeType, "skipped", reason, inputSha, inputSha);
        }

        public static Result remux(File uniqueFile, String inputSha, String outputSha) {
            return new Result(true, uniqueFile, "mp4", "video/mp4", "remux", "Verified MP4 remux", inputSha, outputSha);
        }

        public static Result deep(File uniqueFile, String inputSha, String outputSha) {
            return new Result(true, uniqueFile, "mp4", "video/mp4", "deep", "Hardware Creator Shield", inputSha, outputSha);
        }

        public static Result identity(File uniqueFile, String extension, String mimeType, String inputSha, String outputSha) {
            return new Result(true, uniqueFile, extension, mimeType, "identity", "Safe container identity (" + extension + ")", inputSha, outputSha);
        }
    }

    private VideoUniquifier() {}

    /**
     * Primary entry point (Standard Shield S2+): guarantees the video file becomes unique.
     */
    public static Result process(File inputFile, File candidateOutputFile) {
        return process(inputFile, candidateOutputFile, "standard");
    }

    /**
     * Shield entry point supporting {@code "standard"} (lossless S2+) and {@code "deep"}
     * (optional hardware MediaCodec + GLES micro-crop/color re-encode with automatic
     * fallback to Standard S2+ on any device/codec failure).
     */
    public static Result process(File inputFile, File candidateOutputFile, String tier) {
        if (inputFile == null || !inputFile.exists() || inputFile.length() == 0) {
            return Result.skipped(inputFile, "mp4", "video/mp4", "Input file missing or empty", "");
        }

        String rawExt = getFileExtension(inputFile);
        if (MediaStoreSaver.isAudioExtension(rawExt)) {
            String inSha = IsoBmff.computeSha256(inputFile);
            return Result.skipped(inputFile, rawExt, MediaStoreSaver.mimeForExtension(rawExt),
                    "Audio-only media preserved without modification", inSha);
        }

        IsoBmff.ContainerType container = IsoBmff.sniff(inputFile);
        String inputExt = container.extension.isEmpty() ? rawExt : container.extension;
        String inputMime = container.mimeType;

        if (isAudioOnlyMedia(inputFile)) {
            String inSha = IsoBmff.computeSha256(inputFile);
            return Result.skipped(inputFile, inputExt, inputMime,
                    "Audio-only media preserved without modification", inSha);
        }

        File parent = inputFile.getParentFile();
        if (parent == null) parent = inputFile.getAbsoluteFile().getParentFile();
        String baseName = stripExtension(inputFile.getName());

        // 0. Optional Tier B: Deep Creator Shield (hardware re-encode, falls back cleanly to Standard S2+)
        if ("deep".equalsIgnoreCase(DeepShieldProcessor.normalizeTier(tier))
                && container == IsoBmff.ContainerType.MP4) {
            File deepTarget = new File(parent, baseName + "-deep-" + System.currentTimeMillis() + ".mp4");
            boolean keepDeepTemp = false;
            try {
                String inSha = IsoBmff.computeSha256(inputFile);
                Result deepRes = DeepShieldProcessor.process(inputFile, deepTarget, inSha);
                if (deepRes != null && deepRes.success) {
                    File finalOutput = ensureCorrectExtension(candidateOutputFile, "mp4");
                    if (finalOutput != null) {
                        if (finalOutput.exists()) finalOutput.delete();
                        if (deepTarget.renameTo(finalOutput)) {
                            return Result.deep(finalOutput, deepRes.inputSha256, deepRes.outputSha256);
                        }
                    }
                    keepDeepTemp = true;
                    return deepRes;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Deep Shield failed; falling back to Standard S2+: " + t.getMessage());
            } finally {
                if (!keepDeepTemp) {
                    try { if (deepTarget.exists()) deepTarget.delete(); } catch (Exception ignored) {}
                }
            }
            Log.i(TAG, "Deep Shield bypassed or failed validation; falling back to Standard S2+.");
        }

        // 1. Safe Remux Lane (L2): MP4 + H.264 (AVC <= High) or 8-bit HEVC + AAC only
        if (canRemux(inputFile, container)) {
            File remuxTarget = new File(parent, baseName + "-remux-" + System.currentTimeMillis() + ".mp4");
            boolean keepTemp = false;
            try {
                if (remuxInterleaved(inputFile, remuxTarget)) {
                    final String[] shaHolder = new String[2];
                    Thread tIn = new Thread(() -> shaHolder[0] = IsoBmff.computeSha256(inputFile), "downi-sha-in");
                    tIn.start();
                    shaHolder[1] = IsoBmff.computeSha256(remuxTarget);
                    try { tIn.join(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    String inSha = shaHolder[0] != null ? shaHolder[0] : "";
                    String outSha = shaHolder[1] != null ? shaHolder[1] : "";
                    if (validateRemuxOutput(inputFile, remuxTarget, inSha, outSha)) {
                        File finalOutput = ensureCorrectExtension(candidateOutputFile, "mp4");
                        if (finalOutput != null) {
                            if (finalOutput.exists()) finalOutput.delete();
                            if (remuxTarget.renameTo(finalOutput)) {
                                return Result.remux(finalOutput, inSha, outSha);
                            }
                        }
                        keepTemp = true;
                        return Result.remux(remuxTarget, inSha, outSha);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Remux lane threw exception: " + t.getMessage());
            } finally {
                if (!keepTemp) {
                    try { if (remuxTarget.exists()) remuxTarget.delete(); } catch (Exception ignored) {}
                }
            }
            Log.i(TAG, "Remux lane bypassed or failed validation; falling back to safe identity lane.");
        }

        // 2. Safe Identity Lane (L0/L1): Container-preserving entropy & metadata
        File identityTarget = new File(parent, baseName + "-ident-" + System.currentTimeMillis() + "." + inputExt);
        boolean keepIdentTemp = false;
        try {
            IsoBmff.CopyResult copyRes = IsoBmff.copyAndInjectEntropy(inputFile, identityTarget, container);
            if (copyRes.success) {
                String inSha = copyRes.inputSha256;
                String outSha = copyRes.outputSha256;

                if (container == IsoBmff.ContainerType.MP4) {
                    if (IsoBmff.sanitizeMp4Atoms(identityTarget)) {
                        outSha = IsoBmff.computeSha256(identityTarget);
                    }
                }

                if (validateIdentityOutput(inputFile, identityTarget, inSha, outSha)) {
                    File finalOutput = ensureCorrectExtension(candidateOutputFile, inputExt);
                    if (finalOutput != null) {
                        if (finalOutput.exists()) finalOutput.delete();
                        if (identityTarget.renameTo(finalOutput)) {
                            return Result.identity(finalOutput, inputExt, inputMime, inSha, outSha);
                        }
                    }
                    keepIdentTemp = true;
                    return Result.identity(identityTarget, inputExt, inputMime, inSha, outSha);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Identity lane threw exception: " + t.getMessage());
        } finally {
            if (!keepIdentTemp) {
                try { if (identityTarget.exists()) identityTarget.delete(); } catch (Exception ignored) {}
            }
        }

        // 3. Skipped: Both lanes failed or container cannot be safely modified; preserve original
        String inSha = IsoBmff.computeSha256(inputFile);
        return Result.skipped(inputFile, inputExt, inputMime, "Container format preserved without modification", inSha);
    }

    /** Pure-Java codec/profile/HDR gate for safe MP4 remux (testable on JVM). */
    static boolean isVideoCodecRemuxSafe(String mime, boolean hasProfile, int profile,
                                         boolean hasColorTransfer, int colorTransfer) {
        if (mime == null) return false;
        if (hasColorTransfer && (colorTransfer == COLOR_TRANSFER_ST2084 || colorTransfer == COLOR_TRANSFER_HLG)) {
            return false; // Preserve HDR10/HLG via Identity lane
        }
        if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(mime)) {
            // Profile > 8 corresponds to High 10 / High 4:2:2 / High 4:4:4 (10-bit/HDR)
            return !hasProfile || profile <= 8;
        }
        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(mime)) {
            // Allow 8-bit Main (1); exclude Main10 (2) and HDR10 profiles (4096/8192)
            return !hasProfile || profile <= 1;
        }
        return false;
    }

    private static boolean isAudioOnlyMedia(File inputFile) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());
            int count = extractor.getTrackCount();
            boolean hasVideo = false;
            boolean hasAudio = false;
            for (int i = 0; i < count; i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/")) hasVideo = true;
                if (mime.startsWith("audio/")) hasAudio = true;
            }
            return !hasVideo && hasAudio;
        } catch (Exception e) {
            return false;
        } finally {
            if (extractor != null) try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Checks if the media qualifies for the safe remux lane:
     * - Sniffed as MP4 container
     * - Exactly 1 video track (8-bit H.264 <= High or 8-bit HEVC Main)
     * - Exactly 0 or 1 audio track with AAC
     * - No extra unrecognized tracks
     */
    private static boolean canRemux(File inputFile, IsoBmff.ContainerType container) {
        if (container != IsoBmff.ContainerType.MP4) return false;
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            int vCount = 0;
            int aCount = 0;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) return false;
                if (mime.startsWith("video/")) {
                    vCount++;
                    boolean hasProfile = f.containsKey(MediaFormat.KEY_PROFILE);
                    int profile = hasProfile ? f.getInteger(MediaFormat.KEY_PROFILE) : 0;
                    boolean hasCt = f.containsKey(MediaFormat.KEY_COLOR_TRANSFER);
                    int ct = hasCt ? f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) : 0;
                    if (!isVideoCodecRemuxSafe(mime, hasProfile, profile, hasCt, ct)) {
                        return false;
                    }
                } else if (mime.startsWith("audio/")) {
                    aCount++;
                    if (!MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mime)) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
            return vCount == 1 && (aCount == 0 || aCount == 1) && (vCount + aCount == trackCount);
        } catch (Exception e) {
            return false;
        } finally {
            if (extractor != null) try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Clean remux with strict monotonic timestamp preservation and explicit orientation hint passing.
     */
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

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(inputFile.getAbsolutePath());
            int aTrack = firstTrack(audioExtractor, "audio/");
            MediaFormat aFormat = aTrack >= 0 ? audioExtractor.getTrackFormat(aTrack) : null;

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int rotation = 0;
            if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = vFormat.getInteger(MediaFormat.KEY_ROTATION);
            } else if (vFormat.containsKey("rotation-degrees")) {
                rotation = vFormat.getInteger("rotation-degrees");
            }
            if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation);
            }

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
                boolean videoDone = false, audioDone = false;
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
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = Math.max(0L, sampleTime);
                    info.flags = cur.getSampleFlags();
                    muxer.writeSampleData(outTrack, buffer, info);
                    cur.advance();
                }
            } else {
                while (true) {
                    buffer.clear();
                    int size = videoExtractor.readSampleData(buffer, 0);
                    if (size < 0) break;

                    long sampleTime = videoExtractor.getSampleTime();
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = Math.max(0L, sampleTime);
                    info.flags = videoExtractor.getSampleFlags();
                    muxer.writeSampleData(videoOut, buffer, info);
                    videoExtractor.advance();
                }
            }

            muxer.stop();
            muxerStarted = false;

            IsoBmff.injectIsoFreeBox(outputFile);
            return outputFile.exists() && outputFile.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "remuxInterleaved failed: " + e.getMessage());
            return false;
        } finally {
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            try { if (videoExtractor != null) videoExtractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Strict validation gate for remux output before swapping with original:
     * - Must exist and have non-zero size
     * - Hashes must differ
     * - Track count and video/audio MIMEs must match
     * - Dimensions (width/height) must match
     * - Duration within ±150ms
     * - Audio sample rate/channel count match and first audio sample readable (when present)
     * - First video keyframe sample must be successfully readable (8 MB buffer)
     */
    private static boolean validateRemuxOutput(File inputFile, File outputFile, String inputSha, String outputSha) {
        if (outputFile == null || !outputFile.exists() || outputFile.length() == 0) return false;
        if (inputSha != null && inputSha.equalsIgnoreCase(outputSha)) return false;

        MediaExtractor inExt = null;
        MediaExtractor outExt = null;
        try {
            inExt = new MediaExtractor();
            inExt.setDataSource(inputFile.getAbsolutePath());

            outExt = new MediaExtractor();
            outExt.setDataSource(outputFile.getAbsolutePath());

            if (inExt.getTrackCount() != outExt.getTrackCount()) return false;

            int vIn = firstTrack(inExt, "video/");
            int vOut = firstTrack(outExt, "video/");
            if (vIn < 0 || vOut < 0) return false;

            MediaFormat fIn = inExt.getTrackFormat(vIn);
            MediaFormat fOut = outExt.getTrackFormat(vOut);

            String mimeIn = fIn.getString(MediaFormat.KEY_MIME);
            String mimeOut = fOut.getString(MediaFormat.KEY_MIME);
            if (mimeOut == null || (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(mimeOut)
                    && !MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(mimeOut))) {
                return false;
            }
            if (mimeIn != null && !mimeIn.equalsIgnoreCase(mimeOut)) return false;

            int wIn = fIn.getInteger(MediaFormat.KEY_WIDTH);
            int wOut = fOut.getInteger(MediaFormat.KEY_WIDTH);
            int hIn = fIn.getInteger(MediaFormat.KEY_HEIGHT);
            int hOut = fOut.getInteger(MediaFormat.KEY_HEIGHT);
            if (wIn != wOut || hIn != hOut) return false;

            // Duration tolerance ±150ms
            if (fIn.containsKey(MediaFormat.KEY_DURATION) && fOut.containsKey(MediaFormat.KEY_DURATION)) {
                long dIn = fIn.getLong(MediaFormat.KEY_DURATION);
                long dOut = fOut.getLong(MediaFormat.KEY_DURATION);
                if (Math.abs(dIn - dOut) > 150_000) return false;
            }

            // Validate audio track if input has one
            int aIn = firstTrack(inExt, "audio/");
            int aOut = firstTrack(outExt, "audio/");
            if (aIn >= 0) {
                if (aOut < 0) return false;
                MediaFormat afIn = inExt.getTrackFormat(aIn);
                MediaFormat afOut = outExt.getTrackFormat(aOut);
                String aMimeIn = afIn.getString(MediaFormat.KEY_MIME);
                String aMimeOut = afOut.getString(MediaFormat.KEY_MIME);
                if (aMimeIn != null && !aMimeIn.equalsIgnoreCase(aMimeOut)) return false;
                if (afIn.containsKey(MediaFormat.KEY_SAMPLE_RATE) && afOut.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    if (afIn.getInteger(MediaFormat.KEY_SAMPLE_RATE) != afOut.getInteger(MediaFormat.KEY_SAMPLE_RATE)) {
                        return false;
                    }
                }
                if (afIn.containsKey(MediaFormat.KEY_CHANNEL_COUNT) && afOut.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    if (afIn.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != afOut.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
                        return false;
                    }
                }
                outExt.selectTrack(aOut);
                ByteBuffer aBuf = ByteBuffer.allocate(256 * 1024);
                int ar = outExt.readSampleData(aBuf, 0);
                if (ar <= 0) return false;
                outExt.unselectTrack(aOut);
            }

            // Verify video keyframe readability with 8 MB buffer matching remux buffer size
            outExt.selectTrack(vOut);
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            int r = outExt.readSampleData(buf, 0);
            if (r <= 0) return false;
            if ((outExt.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                return false;
            }

            return true;
        } catch (Exception e) {
            Log.w(TAG, "validateRemuxOutput failed: " + e.getMessage());
            return false;
        } finally {
            if (inExt != null) try { inExt.release(); } catch (Exception ignored) {}
            if (outExt != null) try { outExt.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Strict validation gate for identity output:
     * - Output exists and is non-empty
     * - Length is greater than input length
     * - Hashes differ
     * - Can be opened by MediaExtractor
     */
    private static boolean validateIdentityOutput(File inputFile, File outputFile, String inputSha, String outputSha) {
        if (outputFile == null || !outputFile.exists()) return false;
        if (outputFile.length() <= inputFile.length()) return false;
        if (inputSha == null || inputSha.isEmpty() || inputSha.equalsIgnoreCase(outputSha)) return false;

        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(outputFile.getAbsolutePath());
            return extractor.getTrackCount() > 0;
        } catch (Exception e) {
            Log.w(TAG, "validateIdentityOutput failed: " + e.getMessage());
            return false;
        } finally {
            if (extractor != null) try { extractor.release(); } catch (Exception ignored) {}
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

    private static String getFileExtension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "mp4";
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static File ensureCorrectExtension(File target, String requiredExt) {
        if (target == null) return null;
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(target.getParentFile(), base + "." + requiredExt);
    }
}
