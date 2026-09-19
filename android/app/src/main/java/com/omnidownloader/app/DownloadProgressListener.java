package com.omnidownloader.app;

/** Callback interface implemented in Java and called from Python yt-dlp progress hook. */
public interface DownloadProgressListener {
    void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds);

    /** Python polls this between chunks so a cancel request actually stops the transfer. */
    boolean isCancelled();
}
