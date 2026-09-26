package com.omnidownloader.app;

/** Callback interface implemented in Java and called from Python yt-dlp progress hook. */
public interface DownloadProgressListener {
    void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds);

    /** Python polls this between chunks so a cancel request actually stops the transfer. */
    boolean isCancelled();

    /**
     * D3 (pause/resume): Python polls this beside the cancel flag; when true the transfer
     * raises <code>PausedError</code> and the partial file is KEPT for a later resume.
     * Default false — in-app lanes never pause, only headless grab listeners opt in.
     */
    default boolean isPaused() { return false; }

    /**
     * True when this listener wants .part continuation (headless jobs): Python then downloads
     * into .part files and resumes them on the next call with the same target dir. In-app
     * lanes keep the original engine behaviour exactly.
     */
    default boolean supportsPause() { return false; }
}
