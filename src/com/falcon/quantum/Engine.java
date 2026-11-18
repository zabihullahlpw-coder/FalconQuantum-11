package com.falcon.quantum;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-device scanner: turns live candles into module scores for both
 * directions, then asks {@link Strategy} whether either side qualifies.
 *
 * This mirrors the desktop Smart Long/Short Entry Engine: each direction is
 * scored independently and a near-tie is treated as no edge rather than a
 * coin-flip entry.
 */
public final class Engine {

    /** One scanned symbol, ready for the UI. */
    public static final class Row {
        public String symbol;
        public long signalCloseTime;
        public Binance.Candles candles;
        public String entryPolicy="BASELINE";
        public String settingsKey="";
        public double price;
        public double longScore, shortScore;
        public String side = "LONG";
        /** Original strategy decision, before optional entry-direction inversion. */
        public String signalSide;
        /** Set once when the finalized plan was inverted; prevents accidental double inversion. */
        public boolean directionReversed;
        public String status = "WAIT";
        public double score;
        public boolean ready;
        public String reason = "";
        public double entry, stop, target, riskReward, atrPct;
        /** Market energy behind this setup, and why it may be skipped. */
        public Momentum.Reading momentum;
        public String momentumVeto;
        /** Detected regime and the weights it implied. */
        public MarketCondition.State market;
        /** How good the entry itself is, separate from the signal. */
        public double entryQuality;
        /** A, B or REJECT. */
        public String grade = "REJECT";
        /** Plain-language support and cautions. */
        public java.util.List<String> reasons = new java.util.ArrayList<String>();
        public java.util.List<String> warnings = new java.util.ArrayList<String>();
    }

    private Engine() {
    }

    /**
     * Score one symbol. Returns null when the series is too short to judge.
     *
     * @param quoteVolume 24h turnover, used for the liquidity module
     */
    public static Row analyse(String symbol, Binance.Candles c, double quoteVolume,
                              Strategy strategy, boolean allowShort) {
        return analyse(symbol, c, quoteVolume, strategy, allowShort, "NORMAL", "15m", false);
    }

    public static Row analyse(String symbol, Binance.Candles c, double quoteVolume,
                              Strategy strategy, boolean allowShort,
                              String tradingStyle, String timeframe,
                              boolean requireConfirmation) {
        if (c == null || c.size < 60) return null;

        strategy = strategy.copy();
        int n = c.size;
        double price = c.close[n - 1];
        double ema9 = Indicators.ema(c.close, 9);
        double ema21 = Indicators.ema(c.close, 21);
        double ema50 = Indicators.ema(c.close, 50);
        double rsi = Indicators.rsi(c.close, 14);
        double[] macd = Indicators.macd(c.close);
        double atr = Indicators.atr(c.high, c.low, c.close, 14);
        double atrPct = price > 0 ? atr / price * 100.0 : 0;
        double volRatio = Indicators.volumeRatio(c.volume, 5, 40);
        double recentHigh = Indicators.highest(c.high, 20, 1);
        double recentLow = Indicators.lowest(c.low, 20, 1);

        Row r = new Row();
        r.symbol = symbol; r.signalCloseTime=c.closeTime[n-1]; r.candles=c;
        r.price = price;
        r.atrPct = atrPct;

        // Decide what kind of market this is before scoring it, and weight
        // the modules accordingly.
        r.market = MarketCondition.detect(c, quoteVolume);
        strategy.useWeights(MarketCondition.weightsFor(r.market.regime));

        double[] longComp = components(true, price, ema9, ema21, ema50, rsi, macd,
                volRatio, recentHigh, recentLow, c, quoteVolume, atrPct);
        double[] shortComp = components(false, price, ema9, ema21, ema50, rsi, macd,
                volRatio, recentHigh, recentLow, c, quoteVolume, atrPct);

        // Provisional scores so the UI can show both sides even when blocked.
        r.longScore = weighted(longComp, strategy);
        r.shortScore = weighted(shortComp, strategy);

        boolean preferLong = r.longScore >= r.shortScore;
        if (!allowShort) preferLong = true;
        double[] chosen = preferLong ? longComp : shortComp;
        r.side = preferLong ? "LONG" : "SHORT";

        // Trade plan: ATR-based stop, 2R target.
        //
        // A market has to actually move to be tradeable. If ATR is a rounding
        // error against price - stablecoin pairs, dead books - the stop and
        // entry collapse onto the same number and the "trade" is meaningless.
        // This is what produced a USDCUSDT short with stop == entry.
        if (Binance.isStablePair(symbol)) return null;
        if (atrPct < 0.15) return null;
        double stopDistance = atr * 1.5;
        if (stopDistance <= 0) return null;
        if (stopDistance / price < 0.0015) return null;
        double riskPct = stopDistance / price * 100.0;
        // Read momentum first: the target is sized to what this market can
        // actually travel, not to a fixed 2R that price often approaches and
        // then retreats from.
        Momentum.Reading preview = Momentum.read(c, preferLong, timeframe, riskPct * 2, riskPct);
        double targetPct = PositionManager.achievableTargetPct(preview, riskPct, 10);
        double targetDistance = price * targetPct / 100.0;

        r.entry = price;
        if (preferLong) {
            r.stop = price - stopDistance;
            r.target = price + targetDistance;
        } else {
            r.stop = price + stopDistance;
            r.target = price - targetDistance;
        }
        r.riskReward = stopDistance <= 0 ? 0 : targetDistance / stopDistance;

        List<String> vetoes = new ArrayList<String>();
        if (!preferLong && !allowShort) {
            vetoes.add("SHORT entries are disabled");
        }
        // Refuse to chase a move that has already extended far from the mean.
        double extension = Math.abs(price - ema21) / (atr <= 0 ? 1 : atr);
        if (extension > 2.2) {
            vetoes.add("Entry is late: price is " + String.format("%.1f", extension) + " ATR from EMA21");
        }
        if (Math.abs(r.longScore - r.shortScore) < 5) {
            vetoes.add("Long and short are too close to call");
        }

        // Momentum gate: a setup that cannot travel far enough, fast enough,
        // is rejected before it becomes a position that sits for hours.
        double finalTargetPct = Math.abs(r.target - price) / price * 100.0;
        double stopPct = Math.abs(price - r.stop) / price * 100.0;
        r.momentum = Momentum.read(c, preferLong, timeframe, finalTargetPct, stopPct);
        r.momentumVeto = Momentum.reject(r.momentum, tradingStyle, requireConfirmation);
        if (r.momentumVeto != null) vetoes.add(r.momentumVeto);

        // Entry quality: the signal can be right while this particular
        // moment to act on it is poor.
        r.entryQuality = entryQuality(chosen, r, spreadFriendly(quoteVolume));
        if (r.entryQuality < 55) {
            vetoes.add(String.format("entry quality %.0f — poor place to act on a good signal",
                    r.entryQuality));
        }

        Strategy.Result res = strategy.evaluate(chosen, r.riskReward, vetoes);
        strategy.clearDynamicWeights();
        r.score = res.score;
        buildExplanation(r, chosen, volRatio, rsi);
        r.grade = grade(r, res.ready);
        r.ready = res.ready;
        r.status = res.status;
        r.reason = res.reason;
        return r;
    }

    /** Rough spread proxy: deeper books trade tighter. */
    private static double spreadFriendly(double quoteVolume) {
        if (quoteVolume >= 200_000_000d) return 98;
        if (quoteVolume >= 50_000_000d) return 90;
        if (quoteVolume >= 10_000_000d) return 78;
        if (quoteVolume > 0) return 60;
        return 50;
    }

    /**
     * How good is this entry, as opposed to this signal?
     *
     * A strong setup entered late, into a thin book, or far from the mean
     * is still a poor trade.
     */
    private static double entryQuality(double[] components, Row r, double spreadScore) {
        double trend = components[0];
        double timing = components[2];          // momentum stands in for timing
        double liquidity = components[5];
        // Being stretched from the mean is the commonest late entry.
        double extensionPenalty = Indicators.clamp((r.atrPct - 2.0) * 8, 0, 30);
        double quality = trend * 0.25 + timing * 0.3 + liquidity * 0.2 + spreadScore * 0.25;
        return Indicators.clamp(quality - extensionPenalty, 0, 100);
    }

    /** Support and cautions in plain language. */
    private static void buildExplanation(Row r, double[] c, double volRatio, double rsi) {
        r.reasons.clear();
        r.warnings.clear();
        if (c[0] >= 70) r.reasons.add("Trend aligned (" + Math.round(c[0]) + ")");
        if (c[1] >= 70) r.reasons.add("Breakout confirmed (" + Math.round(c[1]) + ")");
        if (c[2] >= 70) r.reasons.add("Momentum strong (" + Math.round(c[2]) + ")");
        if (volRatio >= 1.5) {
            r.reasons.add(String.format("Volume x%.2f above average", volRatio));
        }
        if (r.riskReward >= 1.8) {
            r.reasons.add(String.format("Reward/risk %.2f", r.riskReward));
        }

        if (c[0] < 50) r.warnings.add("Trend not supportive");
        if (volRatio < 1.0) r.warnings.add("Volume below average — move may not carry");
        if (rsi >= 80) r.warnings.add("RSI " + Math.round(rsi) + " — already stretched");
        if (rsi <= 20) r.warnings.add("RSI " + Math.round(rsi) + " — already stretched");
        if (r.atrPct > 4) {
            r.warnings.add(String.format("High volatility (ATR %.1f%%)", r.atrPct));
        }
        if (r.market != null && MarketCondition.ILLIQUID.equals(r.market.regime)) {
            r.warnings.add("Thin book — slippage likely");
        }
        if (r.entryQuality < 70) {
            r.warnings.add(String.format("Entry quality only %.0f", r.entryQuality));
        }
    }

    /**
     * A grade the operator can act on without reading six numbers.
     * A high score with poor reward/risk is not an A.
     */
    private static String grade(Row r, boolean ready) {
        if (!ready) return "REJECT";
        if (r.score >= 85 && r.riskReward >= 1.8 && r.entryQuality >= 75) return "A";
        if (r.score >= 75) return "B";
        return "REJECT";
    }

    private static double weighted(double[] comp, Strategy s) {
        double score = 0;
        java.util.Map<String, Double> w = s.weights();
        for (int i = 0; i < Strategy.MODULES.length; i++) {
            Double weight = w.get(Strategy.MODULES[i]);
            if (weight == null) continue;
            score += Indicators.clamp(comp[i], 0, 100) * weight / 100.0;
        }
        return Math.round(score * 10.0) / 10.0;
    }

    /** Build the six 0-100 module readings for one direction. */
    private static double[] components(boolean isLong, double price, double ema9, double ema21,
                                       double ema50, double rsi, double[] macd, double volRatio,
                                       double recentHigh, double recentLow, Binance.Candles c,
                                       double quoteVolume, double atrPct) {
        double[] out = new double[6];

        // Trend: EMA stack agreement plus distance confirmation.
        // Trend, now including the 200 EMA. Trading against the major
        // direction is the most expensive habit a scanner can have.
        double ema200 = Indicators.ema(c.close, Math.min(200, c.size - 1));
        double trend = 35;
        if (isLong) {
            if (ema9 > ema21) trend += 12;
            if (ema21 > ema50) trend += 13;
            if (price > ema21) trend += 8;
            if (price > ema50) trend += 12;
            if (price > ema200) trend += 20;
        } else {
            if (ema9 < ema21) trend += 12;
            if (ema21 < ema50) trend += 13;
            if (price < ema21) trend += 8;
            if (price < ema50) trend += 12;
            if (price < ema200) trend += 20;
        }
        out[0] = Indicators.clamp(trend, 0, 100);

        // Breakout: a level taken out, confirmed by the close and by volume.
        //
        // Price merely touching a level is the commonest false signal, so a
        // break only scores highly when the candle CLOSED through it with
        // participation behind it.
        double breakout;
        boolean closedThrough = isLong ? price > recentHigh : price < recentLow;
        boolean touching = isLong ? price > recentHigh * 0.998 : price < recentLow * 1.002;
        if (closedThrough) breakout = 80;
        else if (touching) breakout = 55;
        else breakout = 35;
        if (closedThrough && volRatio >= 1.3) breakout += 12;   // volume confirms
        int last = c.size - 1;
        double body = Math.abs(c.close[last] - c.open[last]);
        double candleRange = Math.max(1e-12, c.high[last] - c.low[last]);
        if (closedThrough && body / candleRange >= 0.5) breakout += 8;   // decisive close
        double persist = Indicators.persistence(c.close, 10, isLong) * 100;
        out[1] = Indicators.clamp(breakout * 0.75 + persist * 0.25, 0, 100);

        // Momentum, with an exhaustion filter. A very stretched RSI means
        // the move has largely happened; buying it is buying the top.
        double momentum;
        if (isLong) {
            if (rsi >= 82) momentum = 22;          // exhausted
            else if (rsi >= 70) momentum = 45;
            else if (rsi >= 55) momentum = 85;
            else if (rsi >= 48) momentum = 65;
            else momentum = 30;
            if (macd[2] > 0) momentum += 12;
        } else {
            if (rsi <= 18) momentum = 22;
            else if (rsi <= 30) momentum = 45;
            else if (rsi <= 45) momentum = 85;
            else if (rsi <= 52) momentum = 65;
            else momentum = 30;
            if (macd[2] < 0) momentum += 12;
        }
        out[2] = Indicators.clamp(momentum, 0, 100);

        // Volume, judged on quality not just size. Rising volume against
        // the trade's direction is pressure the other way, not support.
        double vol;
        if (volRatio >= 2.0) vol = 100;
        else if (volRatio >= 1.5) vol = 86;
        else if (volRatio >= 1.2) vol = 72;
        else if (volRatio >= 1.0) vol = 56;
        else if (volRatio >= 0.8) vol = 38;
        else vol = 20;
        double recentDrift = c.close[c.size - 1] - c.close[Math.max(0, c.size - 5)];
        boolean volumeAgrees = (recentDrift >= 0) == isLong;
        if (volRatio > 1.3 && !volumeAgrees) vol *= 0.55;   // heavy trade against us
        out[3] = Indicators.clamp(vol, 0, 100);

        // Composite confidence. This is an indicator-agreement heuristic, not
        // the desktop's trained probability model - it is intentionally
        // conservative so the phone never looks more certain than the PC.
        double agree = (out[0] + out[1] + out[2] + out[3]) / 4.0;
        double volatilityPenalty = atrPct > 6 ? 18 : (atrPct > 3.5 ? 8 : 0);
        out[4] = Indicators.clamp(agree - volatilityPenalty, 0, 100);

        // Liquidity: turnover depth and sane volatility.
        double liq;
        if (quoteVolume >= 200000000.0) liq = 100;
        else if (quoteVolume >= 50000000.0) liq = 88;
        else if (quoteVolume >= 10000000.0) liq = 72;
        else if (quoteVolume > 0) liq = 50;
        else liq = 40;
        if (atrPct > 8) liq -= 25;
        out[5] = Indicators.clamp(liq, 0, 100);

        return out;
    }
}
