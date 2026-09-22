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
 * audio streams. Android's own MediaMuxer can join an H.264 video track with an
 * AAC audio track natively: no 80 MB ffmpeg dependency, no re-encode, and it
 * finishes in milliseconds.
 */
public final class Mp4Merger {

    // 1080p H.264 IDR frames can exceed 2 MB — a too-small buffer aborts the copy.
    private static final int BUFFER_SIZE = 8 * 1024 * 1024;

    private Mp4Merger() {}

    /** @return true when outFile was produced successfully. */
    public static boolean merge(File videoFile, File audioFile, File outFile) {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
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

            // Container support gates: VP8/VP9 in MP4 needs API 29+, AV1 needs API 34+.
            // Fail fast with the honest error instead of aborting mid-merge.
            String vMime = videoFormat.getString(MediaFormat.KEY_MIME);
            if (vMime != null) {
                if (vMime.startsWith("video/x-vnd.on2") && Build.VERSION.SDK_INT < 29) return false;
                if (vMime.startsWith("video/av01") && Build.VERSION.SDK_INT < 34) return false;
            }

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            videoExtractor.selectTrack(videoTrack);
            audioExtractor.selectTrack(audioTrack);
            interleaveSamples(videoExtractor, videoOut, audioExtractor, audioOut, muxer);

            muxer.stop();
            started = false;
            return outFile.exists() && outFile.length() > 0;
        } catch (Exception e) {
            try { if (outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
            return false;
        } finally {
            try { if (muxer != null) { if (started) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
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
     * Write samples in presentation-time order from both extractors at once.
     * Dumping the whole video track and then the whole audio track produces a
     * badly interleaved file (strict players hitch on it); walking both cursors
     * keeps the output naturally interleaved, the way players expect.
     */
    private static void interleaveSamples(MediaExtractor video, int videoOut,
                                          MediaExtractor audio, int audioOut,
                                          MediaMuxer muxer) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean videoDone = false, audioDone = false;
        while (true) {
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
            info.presentationTimeUs = current.getSampleTime();
            info.flags = current.getSampleFlags();
            muxer.writeSampleData(outTrack, buffer, info);
            current.advance();
        }
    }
}
