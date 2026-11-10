package com.um.clearflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FR 5.3 evidence: which alerts fire, and that the background + foreground paths never spam the user. */
public class AlertEngineTest {

    private static WeatherSnapshot weather(double temp, double rain, double wind, double next3h, int prob) {
        return new WeatherSnapshot(temp, rain, wind, next3h, next3h * 1.5, prob);
    }

    private static AlertEngine.Alert only(List<AlertEngine.Alert> alerts, String type) {
        for (AlertEngine.Alert a : alerts) if (a.type.equals(type)) return a;
        return null;
    }

    /** In-memory stand-in for AlertPrefs. */
    private static class FakeStore implements AlertEngine.Store {
        final Map<String, Long> ms = new HashMap<>();
        final Map<String, Integer> lvl = new HashMap<>();

        void shown(AlertEngine.Alert a, long at) {
            ms.put(a.type, at);
            lvl.put(a.type, a.level);
        }

        @Override public long lastNotifiedMs(String type) { return ms.containsKey(type) ? ms.get(type) : 0L; }

        @Override public int lastLevel(String type) { return lvl.containsKey(type) ? lvl.get(type) : 0; }
    }

    @Test
    public void calmWeatherRaisesNothing() {
        assertTrue(AlertEngine.evaluate(weather(29, 0.0, 8, 0.5, 20)).isEmpty());
    }

    @Test
    public void rainThresholdsAreWarningThenSevere() {
        assertEquals(AlertEngine.LEVEL_WARNING,
                only(AlertEngine.evaluate(weather(28, 5.0, 8, 2, 60)), AlertEngine.RAIN).level);   // now >= 5
        assertEquals(AlertEngine.LEVEL_WARNING,
                only(AlertEngine.evaluate(weather(28, 0.5, 8, 15.0, 80)), AlertEngine.RAIN).level); // next 3h >= 15
        assertEquals(AlertEngine.LEVEL_SEVERE,
                only(AlertEngine.evaluate(weather(28, 10.0, 8, 5, 60)), AlertEngine.RAIN).level);   // now >= 10
        assertEquals(AlertEngine.LEVEL_SEVERE,
                only(AlertEngine.evaluate(weather(28, 1.0, 8, 30.0, 90)), AlertEngine.RAIN).level); // next 3h >= 30
        assertTrue(AlertEngine.evaluate(weather(28, 4.9, 8, 14.9, 90)).isEmpty());                   // just below
    }

    @Test
    public void heatAndWindHaveTheirOwnAlerts() {
        List<AlertEngine.Alert> hot = AlertEngine.evaluate(weather(34.5, 0, 8, 0, 10));
        assertEquals(1, hot.size());
        assertEquals(AlertEngine.HEAT, hot.get(0).type);
        assertEquals(AlertEngine.LEVEL_SEVERE, only(AlertEngine.evaluate(weather(36.0, 0, 8, 0, 10)), AlertEngine.HEAT).level);

        assertEquals(AlertEngine.LEVEL_WARNING, only(AlertEngine.evaluate(weather(28, 0, 28.0, 0, 10)), AlertEngine.WIND).level);
        assertEquals(AlertEngine.LEVEL_SEVERE, only(AlertEngine.evaluate(weather(28, 0, 40.0, 0, 10)), AlertEngine.WIND).level);
    }

    @Test
    public void severalHazardsAtOnceGiveSeparateAlertsWithDistinctNotificationIds() {
        List<AlertEngine.Alert> all = AlertEngine.evaluate(weather(35, 6, 30, 20, 90));
        assertEquals(3, all.size());
        assertEquals(3, all.stream().map(AlertEngine.Alert::notificationId).distinct().count());
    }

    @Test
    public void everyAlertSaysItIsNotAnOfficialWarning() {
        for (AlertEngine.Alert a : AlertEngine.evaluate(weather(37, 12, 45, 35, 95))) {
            assertTrue(a.type, a.message.contains("not an official MetMalaysia warning"));
        }
    }

    @Test
    public void firstAlertIsShownThenSuppressedDuringCooldown() {
        FakeStore store = new FakeStore();
        AlertEngine.Alert rain = only(AlertEngine.evaluate(weather(28, 6, 8, 16, 70)), AlertEngine.RAIN);
        long t0 = 1_000_000L;

        assertTrue(AlertEngine.shouldNotify(store, rain, t0));
        store.shown(rain, t0);
        assertFalse(AlertEngine.shouldNotify(store, rain, t0 + 5 * 60_000L));
        assertFalse(AlertEngine.shouldNotify(store, rain, t0 + AlertEngine.COOLDOWN_MS - 1));
        assertTrue(AlertEngine.shouldNotify(store, rain, t0 + AlertEngine.COOLDOWN_MS));
    }

    @Test
    public void escalationBypassesTheCooldown() {
        FakeStore store = new FakeStore();
        AlertEngine.Alert warning = only(AlertEngine.evaluate(weather(28, 6, 8, 16, 70)), AlertEngine.RAIN);
        AlertEngine.Alert severe = only(AlertEngine.evaluate(weather(28, 12, 8, 35, 95)), AlertEngine.RAIN);
        store.shown(warning, 1_000_000L);

        assertTrue("warning -> severe must notify immediately", AlertEngine.shouldNotify(store, severe, 1_060_000L));
        store.shown(severe, 1_060_000L);
        assertFalse("de-escalation within the cooldown stays quiet", AlertEngine.shouldNotify(store, warning, 1_120_000L));
    }

    @Test
    public void cooldownIsPerAlertType() {
        FakeStore store = new FakeStore();
        AlertEngine.Alert rain = only(AlertEngine.evaluate(weather(35, 6, 8, 16, 70)), AlertEngine.RAIN);
        AlertEngine.Alert heat = only(AlertEngine.evaluate(weather(35, 6, 8, 16, 70)), AlertEngine.HEAT);
        store.shown(rain, 1_000_000L);

        assertFalse(AlertEngine.shouldNotify(store, rain, 1_060_000L));
        assertTrue("a different hazard type is independent", AlertEngine.shouldNotify(store, heat, 1_060_000L));
    }
}
