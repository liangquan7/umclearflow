package com.um.clearflow;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent state for weather alerts: the on/off switch, last check, and per-type cooldown bookkeeping. */
public final class AlertPrefs implements AlertEngine.Store {
    private static final String FILE = "um_clearflow_alerts";
    private static final String KEY_ENABLED = "bg_alerts_enabled";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_LAST_SUMMARY = "last_check_summary";

    private final SharedPreferences sp;

    public AlertPrefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Default on: the request uses fixed campus coordinates and sends nothing about the user. */
    public boolean isEnabled() {
        return sp.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public void recordCheck(long nowMs, String summary) {
        sp.edit().putLong(KEY_LAST_CHECK, nowMs).putString(KEY_LAST_SUMMARY, summary).apply();
    }

    public long lastCheckMs() {
        return sp.getLong(KEY_LAST_CHECK, 0L);
    }

    public String lastSummary() {
        return sp.getString(KEY_LAST_SUMMARY, "");
    }

    public void recordNotified(String type, int level, long nowMs) {
        sp.edit().putLong("notified_ms_" + type, nowMs).putInt("notified_level_" + type, level).apply();
    }

    @Override
    public long lastNotifiedMs(String type) {
        return sp.getLong("notified_ms_" + type, 0L);
    }

    @Override
    public int lastLevel(String type) {
        return sp.getInt("notified_level_" + type, 0);
    }
}
