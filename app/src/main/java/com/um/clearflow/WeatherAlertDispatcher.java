package com.um.clearflow;

import android.content.Context;

import java.util.List;

/**
 * Evaluates a reading and posts the notifications that are due. The Weather tab and the background worker both call
 * this, and both share the cooldown stored in {@link AlertPrefs}, so a user never gets the same alert twice.
 */
public final class WeatherAlertDispatcher {
    private WeatherAlertDispatcher() {}

    public static final class Result {
        public final int applicable;   // alerts whose threshold is exceeded
        public final int posted;       // notifications actually shown
        public final String summary;

        Result(int applicable, int posted, String summary) {
            this.applicable = applicable;
            this.posted = posted;
            this.summary = summary;
        }
    }

    /** @param demo true for simulated data: ignores the cooldown, prefixes the title, never consumes the real cooldown */
    public static Result dispatch(Context context, WeatherSnapshot snapshot, boolean demo) {
        CampusRepository repo = CampusRepository.getInstance();
        AlertPrefs prefs = new AlertPrefs(context);
        long now = System.currentTimeMillis();
        List<AlertEngine.Alert> alerts = AlertEngine.evaluate(snapshot);

        int posted = 0;
        StringBuilder names = new StringBuilder();
        for (AlertEngine.Alert a : alerts) {
            if (!demo && !AlertEngine.shouldNotify(prefs, a, now)) continue;
            String title = demo ? "[DEMO] " + a.title : a.title;
            if (repo.sendLocalAlertNotification(a.notificationId(), title, a.message)) {
                posted++;
                if (names.length() > 0) names.append(", ");
                names.append(a.type);
                if (!demo) prefs.recordNotified(a.type, a.level, now);
            }
        }

        String summary;
        if (alerts.isEmpty()) {
            summary = "No threshold exceeded";
        } else if (posted > 0) {
            summary = "Alert sent: " + names;
        } else {
            summary = "Threshold exceeded, no notification (cooldown or notifications blocked)";
        }
        return new Result(alerts.size(), posted, summary);
    }
}
