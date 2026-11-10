package com.um.clearflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides WHICH weather alerts apply to a reading and WHETHER one may be shown now. Pure Java (no Android),
 * so the rules are unit-tested on the JVM and shared by the Weather tab and the background worker.
 *
 * Thresholds are illustrative, not validated against MetMalaysia criteria. Every message says so.
 */
public final class AlertEngine {
    private AlertEngine() {}

    public static final int LEVEL_WARNING = 1;
    public static final int LEVEL_SEVERE = 2;

    public static final String RAIN = "RAIN";
    public static final String HEAT = "HEAT";
    public static final String WIND = "WIND";

    /** Same alert type is not repeated within this window unless it gets worse. */
    public static final long COOLDOWN_MS = 30L * 60 * 1000;

    private static final String DISCLAIMER = " Illustrative threshold alert, not an official MetMalaysia warning.";

    public static final class Alert {
        public final String type;
        public final int level;
        public final String title;
        public final String message;

        Alert(String type, int level, String title, String message) {
            this.type = type;
            this.level = level;
            this.title = title;
            this.message = message;
        }

        /** Stable id per type: a newer alert replaces the previous one instead of stacking. */
        public int notificationId() {
            switch (type) {
                case RAIN: return 7001;
                case HEAT: return 7002;
                default: return 7003;
            }
        }
    }

    /** Where the last-notified time/level per type is remembered (SharedPreferences in the app, a map in tests). */
    public interface Store {
        long lastNotifiedMs(String type);

        int lastLevel(String type);
    }

    public static List<Alert> evaluate(WeatherSnapshot s) {
        List<Alert> out = new ArrayList<>();

        int rain = s.currentRainMm >= 10.0 || s.next3hRainMm >= 30.0 ? LEVEL_SEVERE
                : (s.currentRainMm >= 5.0 || s.next3hRainMm >= 15.0 ? LEVEL_WARNING : 0);
        if (rain > 0) {
            out.add(new Alert(RAIN, rain,
                    rain == LEVEL_SEVERE ? "⛈️ UM Severe Rain Alert" : "🌧️ UM Heavy Rain Alert",
                    String.format(Locale.US, "Now %.1f mm/h, next 3h %.1f mm (up to %d%% chance). "
                                    + "Avoid low-lying Lingkungan Budi and Jalan Ilmu culverts; use the KPS covered walkway.%s",
                            s.currentRainMm, s.next3hRainMm, s.maxProbPct, DISCLAIMER)));
        }

        int heat = s.tempC >= 36.0 ? LEVEL_SEVERE : (s.tempC >= 34.5 ? LEVEL_WARNING : 0);
        if (heat > 0) {
            out.add(new Alert(HEAT, heat,
                    heat == LEVEL_SEVERE ? "🔥 UM Severe Heat Alert" : "☀️ UM Heat Alert",
                    String.format(Locale.US, "Air temperature %.1f°C. Hydrate at KPS water stations and limit outdoor "
                            + "walking 12:00-15:00.%s", s.tempC, DISCLAIMER)));
        }

        int wind = s.windKmh >= 40.0 ? LEVEL_SEVERE : (s.windKmh >= 28.0 ? LEVEL_WARNING : 0);
        if (wind > 0) {
            out.add(new Alert(WIND, wind,
                    wind == LEVEL_SEVERE ? "🌪️ UM Severe Wind Alert" : "💨 UM Strong Wind Alert",
                    String.format(Locale.US, "Gusts around %.0f km/h. Watch for falling branches along KK12 and Pantai "
                            + "Gate; shelter inside concrete buildings.%s", s.windKmh, DISCLAIMER)));
        }
        return out;
    }

    /** First alert of a type, an escalation (warning -> severe), or the cooldown has elapsed. */
    public static boolean shouldNotify(Store store, Alert alert, long nowMs) {
        long last = store.lastNotifiedMs(alert.type);
        if (last <= 0L) return true;
        if (alert.level > store.lastLevel(alert.type)) return true;
        return nowMs - last >= COOLDOWN_MS;
    }
}
