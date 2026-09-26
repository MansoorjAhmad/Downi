package com.omnidownloader.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * The Fetcher's self-healing heartbeat (Wave 3, experiment D-V2-4).
 *
 * Runs every ~30 minutes while the guards allow it, re-applies the Fetcher's accessibility
 * binding if the vendor wiped it, and finishes immediately. One log line per run so the
 * experiment's cells (survival, battery, ABE's reaction) read straight from logcat.
 */
public class FetcherKeepAliveJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        String outcome = FetcherRecovery.ensureArmed(this);
        android.util.Log.i("DOWNI", "fetcher keep-alive " + outcome);
        jobFinished(params, false);
        return false;   // the work is done synchronously
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
