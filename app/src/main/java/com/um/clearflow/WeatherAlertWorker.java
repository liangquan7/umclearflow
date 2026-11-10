package com.um.clearflow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;

import java.io.IOException;

/**
 * Periodic background check (FR 5.3). WorkManager runs it even when the app is closed (not force-stopped),
 * roughly every 15 minutes at best: the OS batches work in Doze / battery saver, so timing is approximate.
 */
public class WeatherAlertWorker extends Worker {

    public static final String KEY_SIMULATE = "simulate";
    private static final int MAX_NETWORK_RETRIES = 3;

    public WeatherAlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        CampusRepository.init(ctx);   // the process may have been started just for this job
        AlertPrefs prefs = new AlertPrefs(ctx);
        boolean simulate = getInputData().getBoolean(KEY_SIMULATE, false);
        if (!simulate && !prefs.isEnabled()) {
            return Result.success();
        }

        long now = System.currentTimeMillis();
        WeatherSnapshot snapshot;
        try {
            snapshot = simulate ? WeatherSnapshot.simulatedSquall() : WeatherService.fetch();
        } catch (IOException e) {
            prefs.recordCheck(now, "Weather service unreachable");
            return getRunAttemptCount() < MAX_NETWORK_RETRIES ? Result.retry() : Result.success();
        } catch (JSONException e) {
            prefs.recordCheck(now, "Unexpected weather response");
            return Result.success();   // retrying the same bad payload will not help
        }

        if (!simulate) {
            CampusRepository.getInstance().updateWeatherMetrics(snapshot.currentRainMm, snapshot.next3hRainMm);
        }
        WeatherAlertDispatcher.Result r = WeatherAlertDispatcher.dispatch(ctx, snapshot, simulate);
        prefs.recordCheck(now, (simulate ? "[Simulated] " : "") + r.summary);
        return Result.success();
    }
}
