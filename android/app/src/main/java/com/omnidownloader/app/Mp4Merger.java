package com.omnidownloader.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * On-device MP4 muxing — no ffmpeg.
 *
 * YouTube and other DASH sites serve 1080p and above as separate video and
 * audio streams. Android's own MediaMuxer can join an H.264/VP9/AV1 video track
 * with an AAC audio track natively: no 80 MB ffmpeg dependency, no re-encode,
 * and it finishes in milliseconds.
 */
public final class Mp4Merger {

    // 1080p H.264 IDR frames can exceed 2 MB — a too-small buffer aborts the copy.
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;

    public interface CancelCheck {
        boolean isCancelled();
    }

    private Mp4Merger() {}

    /** @return true when outFile was produced successfully. */
    public static boolean merge(File videoFile, File audioFile, File outFile) {
        return merge(videoFile, audioFile, outFile, null);
    }

    /** @return true when outFile was produced successfully (and not cancelled). */
    public static boolean merge(File videoFile, File audioFile, File outFile, CancelCheck cancelCheck) {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        boolean started = false;
        boolean completed = false;
        try {
            if (cancelCheck != null && cancelCheck.isCancelled()) return false;

            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            int videoTrack = firstTrack(videoExtractor, "video/");
            if (videoTrack < 0) return false;

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(audioFile.getAbsolutePath());
            int audioTrack = firstTrack(audioExtractor, "audio/");
            if (audioTrack < 0) return false;

            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrack);

            String vMime = videoFormat.getString(MediaFormat.KEY_MIME);
            String aMime = audioFormat.getString(MediaFormat.KEY_MIME);
            boolean useWebm = isWebmPairSupported(vMime, aMime);
            if (!useWebm) {
                if (!isVideoMimeSupported(vMime, Build.VERSION.SDK_INT)) return false;
                if (!isAudioMimeSupported(aMime)) return false;
            }

            int outputFormat = useWebm
                    ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            muxer = new MediaMuxer(outFile.getAbsolutePath(), outputFormat);

            // Preserve orientation metadata from the video track (MP4 only; WebM rejects orientation hint on some devices).
            if (!useWebm) {
                int rotation = 0;
                if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
                } else if (videoFormat.containsKey("rotation-degrees")) {
                    rotation = videoFormat.getInteger("rotation-degrees");
                }
                if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                    muxer.setOrientationHint(rotation);
                }
            }

            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);
            if (!interleaveSamples(videoExtractor, videoOut, audioExtractor, audioOut, muxer, cancelCheck)) {
                return false;
            }

            muxer.stop();
            started = false;
            completed = outFile.exists() && outFile.length() > 0;
            return completed;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (muxer != null) { if (started) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            try { if (videoExtractor != null) videoExtractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
            if (!completed && outFile != null) {
                try { if (outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    /** Pure-Java video MIME gate (testable on JVM). */
    static boolean isVideoMimeSupported(String vMime, int sdkInt) {
        if (vMime == null || vMime.isEmpty()) return false;
        if (vMime.startsWith("video/x-vnd.on2") && sdkInt < 29) return false;
        if (vMime.startsWith("video/av01") && sdkInt < 34) return false;
        return true;
    }

    /** Pure-Java audio MIME gate: MP4 MediaMuxer requires AAC (not Opus/Vorbis). */
    static boolean isAudioMimeSupported(String aMime) {
        if (aMime == null || aMime.isEmpty()) return false;
        return MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(aMime)
                || "audio/aac".equalsIgnoreCase(aMime);
    }

    /** Pure-Java WebM mux gate: MediaMuxer WEBM supports VP8/VP9 video paired with Opus/Vorbis audio. */
    static boolean isWebmPairSupported(String vMime, String aMime) {
        if (vMime == null || aMime == null) return false;
        boolean vOk = "video/x-vnd.on2.vp9".equalsIgnoreCase(vMime)
                || "video/x-vnd.on2.vp8".equalsIgnoreCase(vMime);
        boolean aOk = "audio/opus".equalsIgnoreCase(aMime)
                || "audio/vorbis".equalsIgnoreCase(aMime);
        return vOk && aOk;
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
     * Write samples in presentation-time order from both extractors at once.
     * Returns false if cancelled mid-mux.
     */
    private static boolean interleaveSamples(MediaExtractor video, int videoOut,
                                             MediaExtractor audio, int audioOut,
                                             MediaMuxer muxer,
                                             CancelCheck cancelCheck) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean videoDone = false, audioDone = false;
        while (true) {
            if (cancelCheck != null && cancelCheck.isCancelled()) {
                return false;
            }
            long vTime = videoDone ? Long.MAX_VALUE : video.getSampleTime();
            long aTime = audioDone ? Long.MAX_VALUE : audio.getSampleTime();
            if (vTime < 0) { videoDone = true; vTime = Long.MAX_VALUE; }
            if (aTime < 0) { audioDone = true; aTime = Long.MAX_VALUE; }
            if (videoDone && audioDone) break;

            MediaExtractor current = (vTime <= aTime) ? video : audio;
            int outTrack = (vTime <= aTime) ? videoOut : audioOut;
            buffer.clear();
            int size = current.readSampleData(buffer, 0);
            if (size < 0) {
                if (current == video) videoDone = true; else audioDone = true;
                continue;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = Math.max(0L, current.getSampleTime());
            info.flags = current.getSampleFlags();
            muxer.writeSampleData(outTrack, buffer, info);
            current.advance();
        }
        return true;
    }
}
