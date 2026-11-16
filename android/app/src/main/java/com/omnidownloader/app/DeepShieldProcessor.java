package com.omnidownloader.app;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Tier B ("Deep Creator Shield") hardware transcode processor.
 *
 * Uses Android MediaCodec + SurfaceTexture + OpenGL ES 2.0 + EGL14 + MediaMuxer
 * to produce a visibly indistinguishable video with a distinct encoded bitstream:
 * - ~1.5% edge micro-crop scaled back to target frame
 * - Imperceptible +/-1.5% brightness/contrast/warmth shader micro-shift
 * - ~100 ms start micro-trim when input duration exceeds 3 seconds
 * - Clean AAC audio copy (aligned to trim offset) + IsoBmff atom sanitization
 *
 * Any hardware codec, EGL, or post-transcode validation failure returns null
 * cleanly so {@link VideoUniquifier} falls back to Standard Shield S2+ without
 * failing the user's download.
 */
public final class DeepShieldProcessor {

    private static final String TAG = "DeepShield";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int COLOR_TRANSFER_ST2084 = 6;
    private static final int COLOR_TRANSFER_HLG = 7;

    static final float DEFAULT_CROP_FRACTION = 0.015f;
    static final long TRIM_THRESHOLD_US = 3_000_000L;
    static final long START_TRIM_US = 100_000L;
    static final int MAX_4K_LONG_EDGE = 3840;
    static final int MAX_4K_SHORT_EDGE = 2160;
    private static final int VALIDATION_BUFFER_SIZE = 8 * 1024 * 1024;
    private static final int CODEC_TIMEOUT_US = 10_000;

    private static final String VERTEX_SHADER =
            "uniform mat4 uSTMatrix;\n" +
            "uniform mat4 uCropMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vec4 cropped = uCropMatrix * aTextureCoord;\n" +
            "  vTextureCoord = (uSTMatrix * cropped).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uBrightness;\n" +
            "uniform float uContrast;\n" +
            "uniform float uWarmth;\n" +
            "void main() {\n" +
            "  vec4 c = texture2D(sTexture, vTextureCoord);\n" +
            "  vec3 rgb = (c.rgb - 0.5) * uContrast + 0.5 + uBrightness;\n" +
            "  rgb.r += uWarmth;\n" +
            "  rgb.b -= uWarmth;\n" +
            "  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);\n" +
            "}\n";

    private static final float[] QUAD_VERTICES = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 0.0f, 1.0f, 1.0f
    };

    private DeepShieldProcessor() {}

    // ---------- Pure-Java gates & math (JVM unit-testable) ----------

    public static String normalizeTier(String tier) {
        return DowniNativeUtils.normalizeShieldTier(tier);
    }

    static int evenDimension(int dim) {
        int clamped = Math.max(16, dim);
        return clamped & ~1;
    }

    static long computeStartTrimUs(long durationUs) {
        return durationUs > TRIM_THRESHOLD_US ? START_TRIM_US : 0L;
    }

    static int computeTargetBitrate(int width, int height, int sourceBitrate) {
        long pixels = (long) Math.max(64, width) * Math.max(64, height);
        int floor;
        if (pixels >= 3840L * 2000L) {
            floor = 16_000_000;
        } else if (pixels >= 2560L * 1400L) {
            floor = 10_000_000;
        } else if (pixels >= 1920L * 1000L) {
            floor = 5_000_000;
        } else if (pixels >= 1280L * 700L) {
            floor = 2_800_000;
        } else {
            floor = 1_500_000;
        }
        int target = sourceBitrate > 0 ? Math.max(sourceBitrate, floor) : floor;
        return Math.min(target, 35_000_000);
    }

    /**
     * Builds a column-major 4x4 texture coordinate matrix that crops {@code cropFraction}
     * from every edge and scales back to [0, 1], e.g. u' = u * (1 - 2c) + c.
     */
    static float[] buildMicroCropStMatrix(float cropFraction) {
        float c = Math.max(0.0f, Math.min(0.10f, cropFraction));
        float scale = 1.0f - (2.0f * c);
        return new float[] {
                scale, 0.0f,  0.0f, 0.0f,
                0.0f,  scale, 0.0f, 0.0f,
                0.0f,  0.0f,  1.0f, 0.0f,
                c,     c,     0.0f, 1.0f
        };
    }

    static boolean isOutputDurationAcceptable(long expectedUs, long actualUs) {
        if (expectedUs <= 0 || actualUs <= 0) return false;
        long toleranceUs = Math.max(350_000L, expectedUs / 20L);
        return Math.abs(expectedUs - actualUs) <= toleranceUs;
    }

    static boolean isDeepModeAllowed(boolean isMp4, long durationUs, int width, int height, boolean hasHdr) {
        if (!isMp4 || hasHdr) return false;
        if (durationUs <= 1_000_000L) return false;
        if (width < 64 || height < 64) return false;
        int longEdge = Math.max(width, height);
        int shortEdge = Math.min(width, height);
        return longEdge <= MAX_4K_LONG_EDGE && shortEdge <= MAX_4K_SHORT_EDGE;
    }

    // ---------- Hardware MediaCodec + EGL14 / GLES 2.0 Pipeline ----------

    /**
     * Attempts Deep Creator Shield hardware transcode on {@code inputFile} into {@code outputFile}.
     *
     * @return verified {@link VideoUniquifier.Result} with {@code mode = "deep"}, or {@code null} on
     * any error/validation failure so the caller falls back cleanly to Standard Shield.
     */
    public static VideoUniquifier.Result process(File inputFile, File outputFile, String inputSha) {
        if (inputFile == null || !inputFile.exists() || inputFile.length() == 0 || outputFile == null) {
            return null;
        }
        MediaExtractor probe = null;
        int width = 0, height = 0, rotation = 0, fps = 30, srcBitrate = 0;
        long durationUs = 0L;
        MediaFormat vInFormat = null;
        MediaFormat aInFormat = null;
        int vTrackIdx = -1;
        int aTrackIdx = -1;
        int totalTracks = 0;

        try {
            probe = new MediaExtractor();
            probe.setDataSource(inputFile.getAbsolutePath());
            totalTracks = probe.getTrackCount();
            for (int i = 0; i < totalTracks; i++) {
                MediaFormat f = probe.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) return null;
                if (mime.startsWith("video/") && vTrackIdx < 0) {
                    vTrackIdx = i;
                    vInFormat = f;
                } else if (mime.startsWith("audio/") && aTrackIdx < 0) {
                    if (!MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mime)) {
                        return null;
                    }
                    aTrackIdx = i;
                    aInFormat = f;
                } else {
                    return null;
                }
            }
            if (vTrackIdx < 0 || vInFormat == null) return null;

            String vMime = vInFormat.getString(MediaFormat.KEY_MIME);
            if (!(MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(vMime)
                    || MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(vMime))) {
                return null;
            }
            width = vInFormat.containsKey(MediaFormat.KEY_WIDTH) ? vInFormat.getInteger(MediaFormat.KEY_WIDTH) : 0;
            height = vInFormat.containsKey(MediaFormat.KEY_HEIGHT) ? vInFormat.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            durationUs = vInFormat.containsKey(MediaFormat.KEY_DURATION) ? vInFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            int ct = vInFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ? vInFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) : 0;
            boolean hasHdr = ct == COLOR_TRANSFER_ST2084 || ct == COLOR_TRANSFER_HLG;
            if (!isDeepModeAllowed(true, durationUs, width, height, hasHdr)) {
                return null;
            }

            if (vInFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = vInFormat.getInteger(MediaFormat.KEY_ROTATION);
            } else if (vInFormat.containsKey("rotation-degrees")) {
                rotation = vInFormat.getInteger("rotation-degrees");
            }
            if (vInFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int fRate = vInFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (fRate > 0 && fRate <= 120) fps = fRate;
            }
            if (vInFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                srcBitrate = vInFormat.getInteger(MediaFormat.KEY_BIT_RATE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Probe failed for Deep Shield: " + t.getMessage());
            return null;
        } finally {
            if (probe != null) try { probe.release(); } catch (Exception ignored) {}
        }

        int targetW = evenDimension(width);
        int targetH = evenDimension(height);
        long trimUs = computeStartTrimUs(durationUs);
        int bitrate = computeTargetBitrate(targetW, targetH, srcBitrate);

        boolean ok = transcodeWithGl(
                inputFile, outputFile, vTrackIdx, vInFormat, aTrackIdx, aInFormat,
                targetW, targetH, rotation, fps, bitrate, trimUs
        );
        if (!ok) {
            try { if (outputFile.exists()) outputFile.delete(); } catch (Exception ignored) {}
            return null;
        }

        IsoBmff.injectIsoFreeBox(outputFile);
        IsoBmff.sanitizeMp4Atoms(outputFile);

        String inSha = (inputSha != null && !inputSha.isEmpty()) ? inputSha : IsoBmff.computeSha256(inputFile);
        String outSha = IsoBmff.computeSha256(outputFile);
        if (!validateDeepOutput(inputFile, outputFile, inSha, outSha, totalTracks, targetW, targetH, durationUs, trimUs, aTrackIdx >= 0)) {
            try { if (outputFile.exists()) outputFile.delete(); } catch (Exception ignored) {}
            return null;
        }

        return VideoUniquifier.Result.deep(outputFile, inSha, outSha);
    }

    private static boolean transcodeWithGl(
            File inputFile,
            File outputFile,
            int vTrackIdx,
            MediaFormat vInFormat,
            int aTrackIdx,
            MediaFormat aInFormat,
            int targetW,
            int targetH,
            int rotation,
            int fps,
            int bitrate,
            long trimUs) {

        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Surface encoderSurface = null;
        Surface decoderSurface = null;
        SurfaceTexture surfaceTexture = null;
        HandlerThread frameThread = null;
        EglHelper egl = null;
        boolean muxerStarted = false;

        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(inputFile.getAbsolutePath());
            videoExtractor.selectTrack(vTrackIdx);

            if (aTrackIdx >= 0) {
                audioExtractor = new MediaExtractor();
                audioExtractor.setDataSource(inputFile.getAbsolutePath());
                audioExtractor.selectTrack(aTrackIdx);
            }

            encoder = createAvcEncoder(targetW, targetH, bitrate, fps);
            if (encoder == null) return false;
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            egl = new EglHelper(encoderSurface);
            egl.makeCurrent();

            GlQuadRenderer renderer = new GlQuadRenderer();
            int texId = renderer.getTextureId();

            frameThread = new HandlerThread("DowniDeepGlFrame");
            frameThread.start();
            Handler frameHandler = new Handler(frameThread.getLooper());

            final Object frameSync = new Object();
            final boolean[] frameAvailable = new boolean[] { false };
            surfaceTexture = new SurfaceTexture(texId);
            surfaceTexture.setOnFrameAvailableListener(st -> {
                synchronized (frameSync) {
                    frameAvailable[0] = true;
                    frameSync.notifyAll();
                }
            }, frameHandler);

            decoderSurface = new Surface(surfaceTexture);
            String vMime = vInFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(vMime);
            decoder.configure(vInFormat, decoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation);
            }

            int muxerVideoTrack = -1;
            int muxerAudioTrack = -1;

            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            ByteBuffer audioBuf = aTrackIdx >= 0 ? ByteBuffer.allocateDirect(512 * 1024) : null;
            MediaCodec.BufferInfo audioInfo = aTrackIdx >= 0 ? new MediaCodec.BufferInfo() : null;
            boolean audioDone = (aTrackIdx < 0);

            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            float[] stMatrix = new float[16];
            float[] cropMatrix = buildMicroCropStMatrix(DEFAULT_CROP_FRACTION);

            while (!encoderDone) {
                // 1. Feed video extractor into decoder
                if (!extractorDone) {
                    int inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            int size = videoExtractor.readSampleData(inBuf, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                extractorDone = true;
                            } else {
                                long pts = Math.max(0L, videoExtractor.getSampleTime());
                                decoder.queueInputBuffer(inIdx, 0, size, pts, 0);
                                videoExtractor.advance();
                            }
                        }
                    }
                }

                // 2. Drain decoder -> render via GLES20 onto encoder surface
                if (!decoderDone) {
                    int decOutIdx = decoder.dequeueOutputBuffer(decInfo, CODEC_TIMEOUT_US);
                    if (decOutIdx >= 0) {
                        boolean isEos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = decInfo.size > 0 && decInfo.presentationTimeUs >= trimUs;
                        decoder.releaseOutputBuffer(decOutIdx, render);
                        if (render) {
                            synchronized (frameSync) {
                                long deadline = System.currentTimeMillis() + 3000L;
                                while (!frameAvailable[0]) {
                                    long rem = deadline - System.currentTimeMillis();
                                    if (rem <= 0) return false;
                                    frameSync.wait(rem);
                                }
                                frameAvailable[0] = false;
                            }
                            surfaceTexture.updateTexImage();
                            surfaceTexture.getTransformMatrix(stMatrix);
                            GLES20.glViewport(0, 0, targetW, targetH);
                            renderer.draw(stMatrix, cropMatrix);
                            long adjPtsNs = Math.max(0L, decInfo.presentationTimeUs - trimUs) * 1000L;
                            egl.setPresentationTime(adjPtsNs);
                            egl.swapBuffers();
                        }
                        if (isEos) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }

                // 3. Drain encoder -> muxer (and interleave AAC audio)
                while (true) {
                    int encOutIdx = encoder.dequeueOutputBuffer(encInfo, CODEC_TIMEOUT_US);
                    if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) return false;
                        MediaFormat outVFormat = encoder.getOutputFormat();
                        muxerVideoTrack = muxer.addTrack(outVFormat);
                        if (aInFormat != null) {
                            muxerAudioTrack = muxer.addTrack(aInFormat);
                        }
                        muxer.start();
                        muxerStarted = true;
                    } else if (encOutIdx >= 0) {
                        ByteBuffer encBuf = encoder.getOutputBuffer(encOutIdx);
                        if (encBuf != null && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0) {
                            if (!muxerStarted) return false;
                            encBuf.position(encInfo.offset);
                            encBuf.limit(encInfo.offset + encInfo.size);
                            encInfo.presentationTimeUs = Math.max(0L, encInfo.presentationTimeUs);
                            muxer.writeSampleData(muxerVideoTrack, encBuf, encInfo);

                            if (!audioDone && audioExtractor != null && muxerAudioTrack >= 0) {
                                audioDone = flushAudioUpTo(audioExtractor, muxer, muxerAudioTrack,
                                        encInfo.presentationTimeUs, trimUs, audioBuf, audioInfo);
                            }
                        }
                        boolean encEos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(encOutIdx, false);
                        if (encEos) {
                            encoderDone = true;
                            break;
                        }
                    }
                }
            }

            // Flush any trailing audio samples cleanly
            if (!audioDone && audioExtractor != null && muxerAudioTrack >= 0 && muxerStarted) {
                flushAudioUpTo(audioExtractor, muxer, muxerAudioTrack, Long.MAX_VALUE, trimUs, audioBuf, audioInfo);
            }

            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
            return outputFile.exists() && outputFile.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "transcodeWithGl failed: " + t.getMessage());
            return false;
        } finally {
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (decoderSurface != null) decoderSurface.release(); } catch (Exception ignored) {}
            try { if (surfaceTexture != null) surfaceTexture.release(); } catch (Exception ignored) {}
            try { if (encoderSurface != null) encoderSurface.release(); } catch (Exception ignored) {}
            try { if (egl != null) egl.release(); } catch (Exception ignored) {}
            try { if (frameThread != null) frameThread.quitSafely(); } catch (Exception ignored) {}
            try { if (videoExtractor != null) videoExtractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
        }
    }

    private static boolean flushAudioUpTo(
            MediaExtractor audioExtractor,
            MediaMuxer muxer,
            int muxerAudioTrack,
            long maxAdjustedPtsUs,
            long trimUs,
            ByteBuffer audioBuf,
            MediaCodec.BufferInfo audioInfo) {
        while (true) {
            long samplePts = audioExtractor.getSampleTime();
            if (samplePts < 0) return true;
            if (samplePts < trimUs) {
                audioExtractor.advance();
                continue;
            }
            long adjPts = samplePts - trimUs;
            if (adjPts > maxAdjustedPtsUs) {
                return false;
            }
            audioBuf.clear();
            int size = audioExtractor.readSampleData(audioBuf, 0);
            if (size < 0) return true;
            audioInfo.offset = 0;
            audioInfo.size = size;
            audioInfo.presentationTimeUs = adjPts;
            audioInfo.flags = audioExtractor.getSampleFlags();
            muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo);
            audioExtractor.advance();
        }
    }

    /** Returns the upper height bound supported by the default hardware/platform AVC encoder, or 0 if unknown. */
    public static int queryMaxAvcEncoderHeight() {
        MediaCodec probe = null;
        try {
            probe = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.VideoCapabilities caps = probe.getCodecInfo()
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getVideoCapabilities();
            if (caps != null && caps.getSupportedHeights() != null) {
                return caps.getSupportedHeights().getUpper();
            }
        } catch (Throwable ignored) {
        } finally {
            if (probe != null) try { probe.release(); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static MediaCodec createAvcEncoder(int width, int height, int bitrate, int fps) {
        MediaCodec probed = null;
        try {
            probed = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.VideoCapabilities caps = probed.getCodecInfo()
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .getVideoCapabilities();
            if (caps != null && !caps.isSizeSupported(width, height)) {
                probed.release();
                return null;
            }
        } catch (Exception e) {
            if (probed != null) try { probed.release(); } catch (Exception ignored) {}
            return null;
        }
        MediaFormat highFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        highFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        highFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        highFmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        highFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        highFmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        try {
            probed.configure(highFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return probed;
        } catch (Exception ignored) {
            try { probed.release(); } catch (Exception ignored2) {}
            try {
                MediaFormat baseFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                baseFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                baseFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                baseFmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                baseFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                codec.configure(baseFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                return codec;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static boolean validateDeepOutput(
            File inputFile,
            File outputFile,
            String inputSha,
            String outputSha,
            int expectedTrackCount,
            int expectedWidth,
            int expectedHeight,
            long inputDurationUs,
            long trimUs,
            boolean expectAudio) {
        if (outputFile == null || !outputFile.exists() || outputFile.length() == 0) return false;
        if (inputSha != null && inputSha.equalsIgnoreCase(outputSha)) return false;

        MediaExtractor outExt = null;
        try {
            outExt = new MediaExtractor();
            outExt.setDataSource(outputFile.getAbsolutePath());
            if (outExt.getTrackCount() != expectedTrackCount) return false;

            int vOut = -1;
            int aOut = -1;
            for (int i = 0; i < outExt.getTrackCount(); i++) {
                String mime = outExt.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && vOut < 0) vOut = i;
                if (mime != null && mime.startsWith("audio/") && aOut < 0) aOut = i;
            }
            if (vOut < 0) return false;
            if (expectAudio && aOut < 0) return false;

            MediaFormat vf = outExt.getTrackFormat(vOut);
            int wOut = vf.getInteger(MediaFormat.KEY_WIDTH);
            int hOut = vf.getInteger(MediaFormat.KEY_HEIGHT);
            if (wOut != expectedWidth || hOut != expectedHeight) return false;

            long expectedDurationUs = Math.max(1L, inputDurationUs - trimUs);
            if (vf.containsKey(MediaFormat.KEY_DURATION)) {
                long dOut = vf.getLong(MediaFormat.KEY_DURATION);
                if (!isOutputDurationAcceptable(expectedDurationUs, dOut)) return false;
            }

            if (aOut >= 0) {
                outExt.selectTrack(aOut);
                ByteBuffer aBuf = ByteBuffer.allocate(256 * 1024);
                if (outExt.readSampleData(aBuf, 0) <= 0) return false;
                outExt.unselectTrack(aOut);
            }

            outExt.selectTrack(vOut);
            ByteBuffer vBuf = ByteBuffer.allocateDirect(VALIDATION_BUFFER_SIZE);
            int r = outExt.readSampleData(vBuf, 0);
            if (r <= 0) return false;
            return (outExt.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        } catch (Exception e) {
            Log.w(TAG, "validateDeepOutput failed: " + e.getMessage());
            return false;
        } finally {
            if (outExt != null) try { outExt.release(); } catch (Exception ignored) {}
        }
    }

    // ---------- Minimal EGL14 & GLES 2.0 helper classes ----------

    private static final class EglHelper {
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;

        EglHelper(Surface windowSurface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("No EGL display");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("eglInitialize failed");
            }
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                throw new IllegalStateException("eglChooseConfig failed");
            }
            int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext failed");
            int[] surfAttribs = { EGL14.EGL_NONE };
            surface = EGL14.eglCreateWindowSurface(display, configs[0], windowSurface, surfAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface failed");
        }

        void makeCurrent() {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("eglMakeCurrent failed");
            }
        }

        void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(display, surface, nsecs);
        }

        void swapBuffers() {
            EGL14.eglSwapBuffers(display, surface);
        }

        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
        }
    }

    private static final class GlQuadRenderer {
        private final FloatBuffer vertices;
        private final int program;
        private final int textureId;
        private final int uSTMatrixLoc;
        private final int uCropMatrixLoc;
        private final int aPositionLoc;
        private final int aTextureLoc;
        private final int uBrightnessLoc;
        private final int uContrastLoc;
        private final int uWarmthLoc;

        GlQuadRenderer() {
            vertices = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertices.put(QUAD_VERTICES).position(0);

            int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            if (linked[0] != GLES20.GL_TRUE) {
                throw new IllegalStateException("Shader link failed: " + GLES20.glGetProgramInfoLog(program));
            }

            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
            aTextureLoc = GLES20.glGetAttribLocation(program, "aTextureCoord");
            uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix");
            uCropMatrixLoc = GLES20.glGetUniformLocation(program, "uCropMatrix");
            uBrightnessLoc = GLES20.glGetUniformLocation(program, "uBrightness");
            uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast");
            uWarmthLoc = GLES20.glGetUniformLocation(program, "uWarmth");

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            textureId = tex[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        int getTextureId() {
            return textureId;
        }

        void draw(float[] stMatrix, float[] cropMatrix) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

            vertices.position(0);
            GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 20, vertices);
            GLES20.glEnableVertexAttribArray(aPositionLoc);

            vertices.position(3);
            GLES20.glVertexAttribPointer(aTextureLoc, 2, GLES20.GL_FLOAT, false, 20, vertices);
            GLES20.glEnableVertexAttribArray(aTextureLoc);

            GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0);
            GLES20.glUniformMatrix4fv(uCropMatrixLoc, 1, false, cropMatrix, 0);
            // Imperceptible +/-1.5% visual micro-shift
            GLES20.glUniform1f(uBrightnessLoc, 0.008f);
            GLES20.glUniform1f(uContrastLoc, 1.015f);
            GLES20.glUniform1f(uWarmthLoc, 0.010f);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("Shader compile failed: " + log);
            }
            return shader;
        }
    }
}
