package com.omnidownloader.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

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

    private static final int BUFFER_SIZE = 2 * 1024 * 1024;

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

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            videoExtractor.selectTrack(videoTrack);
            copySamples(videoExtractor, muxer, videoOut);

            audioExtractor.selectTrack(audioTrack);
            copySamples(audioExtractor, muxer, audioOut);

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

    private static void copySamples(MediaExtractor extractor, MediaMuxer muxer, int outTrack) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(outTrack, buffer, info);
            extractor.advance();
        }
    }
}
