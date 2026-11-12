package com.falcon.quantum;

/**
 * Indicator maths for the on-device engine.
 *
 * These mirror the desktop core's calculations (EMA/RSI/MACD/ATR, volume
 * participation and breakout structure) so a signal scored on the phone is
 * built from the same inputs as one scored on the PC.
 *
 * Deliberately dependency-free and allocation-light: the scanner runs these
 * over many symbols on a background thread on battery power.
 */
public final class Indicators {

    private Indicators() {
    }

    /** Exponential moving average of the last value in the series. */
    public static double ema(double[] values, int period) {
        if (values == null || values.length == 0 || period <= 0) return 0;
        double k = 2.0 / (period + 1.0);
        double e = values[0];
        for (int i = 1; i < values.length; i++) {
            e = values[i] * k + e * (1 - k);
        }
        return e;
    }

    /** Full EMA series, needed when a later calculation consumes the curve. */
    public static double[] emaSeries(double[] values, int period) {
        double[] out = new double[values.length];
        if (values.length == 0) return out;
        double k = 2.0 / (period + 1.0);
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = values[i] * k + out[i - 1] * (1 - k);
        }
        return out;
    }

    /** Wilder-smoothed RSI, 0-100. */
    public static double rsi(double[] close, int period) {
        if (close.length <= period) return 50;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = close[i] - close[i - 1];
            if (d >= 0) gain += d; else loss -= d;
        }
        gain /= period;
        loss /= period;
        for (int i = period + 1; i < close.length; i++) {
            double d = close[i] - close[i - 1];
            double g = d > 0 ? d : 0;
            double l = d < 0 ? -d : 0;
            gain = (gain * (period - 1) + g) / period;
            loss = (loss * (period - 1) + l) / period;
        }
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Returns {macd, signal, histogram}. */
    public static double[] macd(double[] close) {
        double[] fast = emaSeries(close, 12);
        double[] slow = emaSeries(close, 26);
        double[] line = new double[close.length];
        for (int i = 0; i < close.length; i++) line[i] = fast[i] - slow[i];
        double[] sig = emaSeries(line, 9);
        int n = close.length - 1;
        if (n < 0) return new double[]{0, 0, 0};
        return new double[]{line[n], sig[n], line[n] - sig[n]};
    }

    /** Average true range over the given period. */
    public static double atr(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        if (n < 2) return 0;
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, n - period); i < n; i++) {
            double tr = Math.max(high[i] - low[i],
                    Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
            sum += tr;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    /** Ratio of recent volume to its longer-run average. */
    public static double volumeRatio(double[] volume, int recent, int baseline) {
        int n = volume.length;
        if (n < baseline + 1) return 1;
        double r = 0;
        for (int i = n - recent; i < n; i++) r += volume[i];
        r /= recent;
        double b = 0;
        for (int i = n - baseline; i < n; i++) b += volume[i];
        b /= baseline;
        return b <= 0 ? 1 : r / b;
    }

    public static double highest(double[] v, int lookback, int offsetFromEnd) {
        int end = v.length - offsetFromEnd;
        int start = Math.max(0, end - lookback);
        double m = -Double.MAX_VALUE;
        for (int i = start; i < end; i++) if (v[i] > m) m = v[i];
        return m == -Double.MAX_VALUE ? 0 : m;
    }

    public static double lowest(double[] v, int lookback, int offsetFromEnd) {
        int end = v.length - offsetFromEnd;
        int start = Math.max(0, end - lookback);
        double m = Double.MAX_VALUE;
        for (int i = start; i < end; i++) if (v[i] < m) m = v[i];
        return m == Double.MAX_VALUE ? 0 : m;
    }

    /** Fraction of the last n candles that closed in the given direction. */
    public static double persistence(double[] close, int n, boolean up) {
        int start = Math.max(1, close.length - n);
        int hits = 0, total = 0;
        for (int i = start; i < close.length; i++) {
            boolean rose = close[i] >= close[i - 1];
            if (rose == up) hits++;
            total++;
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    public static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
