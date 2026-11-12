package com.falcon.quantum;

/**
 * What kind of market is this, and which signals should matter in it.
 *
 * One fixed set of weights cannot fit every regime. A breakout score is
 * the most informative thing in a range and among the least informative in
 * a strong trend, where trend alignment carries the move. Detecting the
 * regime first, then weighting accordingly, is the difference between a
 * scanner and a strategy.
 */
public final class MarketCondition {

    public static final String TRENDING = "TRENDING";
    public static final String RANGING = "RANGING";
    public static final String VOLATILE = "HIGH VOLATILITY";
    public static final String ILLIQUID = "LOW LIQUIDITY";

    private MarketCondition() {
    }

    public static final class State {
        public String regime = RANGING;
        public double trendStrength;   // 0-100
        public double atrPercent;
        public double rangePercent;    // span of the recent window
        public boolean thin;
        public String summary = "";
    }

    /** Classify the market from candles and 24h turnover. */
    public static State detect(Binance.Candles c, double quoteVolume) {
        State s = new State();
        int n = c.size;
        if (n < 60) {
            s.summary = "not enough history";
            return s;
        }

        double price = c.close[n - 1];
        double ema21 = Indicators.ema(c.close, 21);
        double ema50 = Indicators.ema(c.close, 50);
        double ema200 = Indicators.ema(c.close, Math.min(200, n - 1));
        double atr = Indicators.atr(c.high, c.low, c.close, 14);
        s.atrPercent = price > 0 ? atr / price * 100.0 : 0;

        double high = Indicators.highest(c.high, 50, 1);
        double low = Indicators.lowest(c.low, 50, 1);
        s.rangePercent = low > 0 ? (high - low) / low * 100.0 : 0;

        // Trend strength: how consistently the EMAs are stacked, and how far
        // price has actually travelled relative to the noise around it.
        double stack = 0;
        if (ema21 > ema50 && ema50 > ema200) stack = 100;
        else if (ema21 < ema50 && ema50 < ema200) stack = 100;
        else if (ema21 > ema50 || ema21 < ema50) stack = 50;

        double drift = Math.abs(price - ema50) / (atr <= 0 ? 1 : atr);
        double driftScore = Indicators.clamp(drift * 35, 0, 100);
        s.trendStrength = Indicators.clamp(stack * 0.6 + driftScore * 0.4, 0, 100);

        s.thin = quoteVolume > 0 && quoteVolume < 10_000_000d;

        if (s.thin) {
            s.regime = ILLIQUID;
        } else if (s.atrPercent > 4.0) {
            s.regime = VOLATILE;
        } else if (s.trendStrength >= 65) {
            s.regime = TRENDING;
        } else {
            s.regime = RANGING;
        }

        s.summary = String.format("%s · trend strength %.0f · ATR %.2f%%",
                s.regime, s.trendStrength, s.atrPercent);
        return s;
    }

    /**
     * Module weights for a regime, in the order
     * {trend, breakout, momentum, volume, ai, liquidity}.
     */
    public static double[] weightsFor(String regime) {
        if (TRENDING.equals(regime)) {
            // Follow the move; a breakout signal adds little once trending.
            return new double[]{30, 15, 25, 15, 10, 5};
        }
        if (RANGING.equals(regime)) {
            // The edge of a range is where something happens.
            return new double[]{15, 30, 15, 20, 15, 5};
        }
        if (VOLATILE.equals(regime)) {
            // Participation and liquidity matter more when price is wild.
            return new double[]{20, 15, 20, 20, 10, 15};
        }
        // Thin books: weight execution safety heavily.
        return new double[]{18, 12, 18, 14, 13, 25};
    }

    /** Extra caution the regime warrants, as a score penalty. */
    public static double regimePenalty(String regime) {
        if (ILLIQUID.equals(regime)) return 12;
        if (VOLATILE.equals(regime)) return 6;
        return 0;
    }
}
