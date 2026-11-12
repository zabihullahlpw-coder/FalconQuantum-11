package com.falcon.quantum;

/**
 * Rapid-move filter.
 *
 * A high strategy score says a setup looks right; it says nothing about
 * whether the market is moving fast enough to reach the target before the
 * idea goes stale. This scores the market's current energy and estimates
 * how long a move of the required size would plausibly take.
 *
 * On the estimate: it is an extrapolation from recent realised volatility,
 * not a prediction. Markets stall and gap without warning. It is useful for
 * REJECTING setups that clearly cannot travel far enough, and should not be
 * read as a schedule.
 */
public final class Momentum {

    private Momentum() {
    }

    /** Trading styles, from patient to fastest. */
    public static final String[] STYLES = {"NORMAL", "MOMENTUM", "FAST_MOVE", "SCALPING"};

    /** Minimum momentum score per style. */
    public static double minimumScore(String style) {
        if ("SCALPING".equals(style)) return 80;
        if ("FAST_MOVE".equals(style)) return 75;
        if ("MOMENTUM".equals(style)) return 65;
        return 0;   // NORMAL applies no momentum gate
    }

    /** Largest acceptable estimated minutes to target, per style. */
    public static double maximumMinutesToTarget(String style) {
        // Shortened deliberately. A four-hour horizon accepted setups that
        // drift, stall and hand back their profit; the estimate is also far
        // less reliable the further out it reaches.
        if ("SCALPING".equals(style)) return 10;
        if ("FAST_MOVE".equals(style)) return 25;
        if ("MOMENTUM".equals(style)) return 60;
        return 180;   // even NORMAL now refuses the truly glacial
    }

    /** Volume acceleration required, as a multiple of the recent average. */
    public static double minimumVolumeAcceleration(String style) {
        if ("SCALPING".equals(style)) return 1.6;
        if ("FAST_MOVE".equals(style)) return 1.35;
        if ("MOMENTUM".equals(style)) return 1.1;
        return 0;
    }

    public static final class Reading {
        /** 0-100 composite of speed, participation and candle conviction. */
        public double score;
        /** Recent volume divided by its longer-run average. */
        public double volumeAcceleration;
        /** Recent candle range divided by its longer-run average. */
        public double rangeAcceleration;
        /** Share of recent candles closing with the trade's direction. */
        public double directionalAgreement;
        /** Typical distance travelled per candle, as a percentage. */
        public double percentPerCandle;
        /** Estimated candles to reach the target, then minutes. */
        public double candlesToTarget;
        public double minutesToTarget;
        public double minutesToStop;
        /** Did the most recent candle confirm the direction? */
        public boolean confirmed;
        public String summary = "";
    }

    private static double average(double[] values, int from, int to) {
        double sum = 0;
        int n = 0;
        for (int i = Math.max(0, from); i < Math.min(values.length, to); i++) {
            sum += values[i];
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Minutes represented by one candle of the given interval. */
    public static double minutesPerCandle(String timeframe) {
        if (timeframe == null) return 15;
        if (timeframe.endsWith("m")) {
            try {
                return Double.parseDouble(timeframe.substring(0, timeframe.length() - 1));
            } catch (Exception e) {
                return 15;
            }
        }
        if (timeframe.endsWith("h")) {
            try {
                return Double.parseDouble(timeframe.substring(0, timeframe.length() - 1)) * 60;
            } catch (Exception e) {
                return 60;
            }
        }
        return 15;
    }

    /**
     * Measure current market energy for one direction.
     *
     * @param targetDistancePct how far price must travel to reach the target
     * @param stopDistancePct   how far to the stop
     */
    public static Reading read(Binance.Candles c, boolean isLong, String timeframe,
                               double targetDistancePct, double stopDistancePct) {
        Reading r = new Reading();
        int n = c.size;
        if (n < 40) {
            r.summary = "not enough history to judge momentum";
            return r;
        }

        // Participation: are more people trading than usual?
        double recentVolume = average(c.volume, n - 5, n);
        double baseVolume = average(c.volume, n - 40, n - 5);
        r.volumeAcceleration = baseVolume <= 0 ? 1 : recentVolume / baseVolume;

        // Range: are candles bigger than usual?
        double[] ranges = new double[n];
        for (int i = 0; i < n; i++) {
            ranges[i] = c.close[i] > 0 ? (c.high[i] - c.low[i]) / c.close[i] * 100.0 : 0;
        }
        double recentRange = average(ranges, n - 5, n);
        double baseRange = average(ranges, n - 40, n - 5);
        r.rangeAcceleration = baseRange <= 0 ? 1 : recentRange / baseRange;
        r.percentPerCandle = recentRange;

        // Conviction: are recent closes going our way?
        int agree = 0, total = 0;
        for (int i = Math.max(1, n - 8); i < n; i++) {
            boolean up = c.close[i] >= c.close[i - 1];
            if (up == isLong) agree++;
            total++;
        }
        r.directionalAgreement = total == 0 ? 0 : (double) agree / total * 100.0;

        // Confirmation: the latest candle closed in our favour with a body
        // that is not a doji.
        double body = Math.abs(c.close[n - 1] - c.open[n - 1]);
        double range = Math.max(1e-12, c.high[n - 1] - c.low[n - 1]);
        boolean lastUp = c.close[n - 1] >= c.open[n - 1];
        r.confirmed = (lastUp == isLong) && (body / range >= 0.45);

        // How far does price typically travel per candle in our direction?
        double directionalTravel = r.percentPerCandle * (r.directionalAgreement / 100.0);
        if (directionalTravel <= 0.0001) directionalTravel = 0.0001;
        r.candlesToTarget = targetDistancePct / directionalTravel;
        double minutes = minutesPerCandle(timeframe);
        r.minutesToTarget = r.candlesToTarget * minutes;
        r.minutesToStop = (stopDistancePct / directionalTravel) * minutes;

        // Composite. Weighted towards participation and range, because those
        // are what actually carry price, with agreement as a tiebreaker.
        double volumeScore = Indicators.clamp((r.volumeAcceleration - 0.8) * 90, 0, 100);
        double rangeScore = Indicators.clamp((r.rangeAcceleration - 0.8) * 90, 0, 100);
        double agreementScore = Indicators.clamp(r.directionalAgreement, 0, 100);
        double confirmationScore = r.confirmed ? 100 : 40;
        r.score = Indicators.clamp(
                volumeScore * 0.32 + rangeScore * 0.30
                        + agreementScore * 0.23 + confirmationScore * 0.15, 0, 100);

        r.summary = String.format(
                "momentum %.0f · volume x%.2f · range x%.2f · ~%.0f min to target",
                r.score, r.volumeAcceleration, r.rangeAcceleration, r.minutesToTarget);
        return r;
    }

    /**
     * Should this setup be taken under the chosen style? Returns null when
     * acceptable, or the reason to skip it.
     */
    public static String reject(Reading r, String style, boolean requireConfirmation) {
        // NORMAL still has no momentum or volume gate, but it will not take
        // a setup whose target is hours away at the current pace.
        if ("NORMAL".equals(style)) {
            if (r.minutesToTarget > maximumMinutesToTarget(style)) {
                return String.format("target is ~%.0f min away at this pace", r.minutesToTarget);
            }
            return null;
        }

        double minScore = minimumScore(style);
        if (r.score < minScore) {
            return String.format("momentum %.0f below %.0f required for %s",
                    r.score, minScore, style);
        }
        double minVolume = minimumVolumeAcceleration(style);
        if (r.volumeAcceleration < minVolume) {
            return String.format("volume only x%.2f, needs x%.2f", r.volumeAcceleration, minVolume);
        }
        double maxMinutes = maximumMinutesToTarget(style);
        if (r.minutesToTarget > maxMinutes) {
            return String.format("at this pace the target is ~%.0f min away, limit %.0f",
                    r.minutesToTarget, maxMinutes);
        }
        if (requireConfirmation && !r.confirmed) {
            return "waiting for a confirming candle";
        }
        return null;
    }
}
