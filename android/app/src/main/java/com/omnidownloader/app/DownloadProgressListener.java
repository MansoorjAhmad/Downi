package com.omnidownloader.app;

/** Callback interface implemented in Java and called from Python yt-dlp progress hook. */
public interface DownloadProgressListener {
    void onProgress(double percent, long downloadedBytes, long totalBytes, double speedBytesPerSec, long etaSeconds);
}
