package com.um.clearflow;

/** One reading of campus weather: current conditions plus the next 6 hours. Immutable, no Android dependencies. */
public final class WeatherSnapshot {
    public final double tempC;
    public final double currentRainMm;   // mm in the current hour
    public final double windKmh;
    public final double next3hRainMm;
    public final double next6hRainMm;
    public final int maxProbPct;         // max precipitation probability in the next 6 h

    public WeatherSnapshot(double tempC, double currentRainMm, double windKmh,
                           double next3hRainMm, double next6hRainMm, int maxProbPct) {
        this.tempC = tempC;
        this.currentRainMm = currentRainMm;
        this.windKmh = windKmh;
        this.next3hRainMm = next3hRainMm;
        this.next6hRainMm = next6hRainMm;
        this.maxProbPct = maxProbPct;
    }

    /** Demo data for the "Simulate squall" buttons. Never mixed with real readings. */
    public static WeatherSnapshot simulatedSquall() {
        return new WeatherSnapshot(25.4, 14.5, 32.0, 38.0, 55.0, 95);
    }
}
