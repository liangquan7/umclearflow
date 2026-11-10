package com.um.clearflow;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Starts / stops the periodic weather check according to the user's switch. Safe to call repeatedly. */
public final class WeatherAlertScheduler {
    private WeatherAlertScheduler() {}

    static final String WORK_NAME = "um_clearflow_weather_alerts";

    /** Call on every app start and whenever the switch changes. KEEP means a running schedule is not reset. */
    public static void apply(Context context) {
        Context app = context.getApplicationContext();
        WorkManager wm = WorkManager.getInstance(app);
        if (!new AlertPrefs(app).isEnabled()) {
            wm.cancelUniqueWork(WORK_NAME);
            return;
        }
        Constraints needsNetwork = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        // 15 minutes is WorkManager's minimum period
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(WeatherAlertWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(needsNetwork)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build();
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /** One immediate background run (for the "Run check now" button and for demos). */
    public static void runNow(Context context, boolean simulate) {
        OneTimeWorkRequest.Builder b = new OneTimeWorkRequest.Builder(WeatherAlertWorker.class)
                .setInputData(new Data.Builder().putBoolean(WeatherAlertWorker.KEY_SIMULATE, simulate).build());
        if (!simulate) {
            b.setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build());
        }
        WorkManager.getInstance(context.getApplicationContext()).enqueue(b.build());
    }
}
