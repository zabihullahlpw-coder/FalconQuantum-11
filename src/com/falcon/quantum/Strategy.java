package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device port of the desktop Adaptive Strategy Control System.
 *
 * Same six modules, same weights, same renormalisation rule and the same
 * safety clamps: a preset can lower selectivity but never remove it, a
 * module that is switched off stops scoring AND stops vetoing, and the
 * liquidity module is always on.
 */
public final class Strategy {

    public static final String[] MODULES = {"trend", "breakout", "momentum", "volume", "ai", "liquidity"};
    public static final String[] MODULE_LABELS =
            {"Trend", "Breakout", "Momentum", "Volume", "AI Prediction", "Liquidity"};

    private static final double[] WEIGHTS = {22, 12, 20, 14, 18, 14};

    /** Slack on a module floor, so 44.6 against 45 is not a rejection. */
    public static final double FLOOR_TOLERANCE = 0.5;

    /** Per-regime weights, when a market condition has been detected. */
    private double[] dynamicWeights = null;

    public void useWeights(double[] weights) {
        if (weights != null && weights.length == MODULE_WEIGHTS_LENGTH) {
            dynamicWeights = weights;
        }
    }

    public void clearDynamicWeights() {
        dynamicWeights = null;
    }

    private static final int MODULE_WEIGHTS_LENGTH = 6;

    public static final String[] MODES = {"CONSERVATIVE", "BALANCED", "AGGRESSIVE"};

    // floor, ceiling, default entry score, default min reward/risk
    private static final double[][] BANDS = {
            {85, 95, 88, 2.0},
            {70, 85, 78, 1.8},
            {55, 70, 62, 1.5},
    };
    private static final double[][] SENSITIVITY = {
            {70, 60, 65, 60, 85, 70},
            {55, 45, 55, 45, 72, 55},
            {40, 30, 40, 30, 60, 45},
    };

    private static final String PREFS = "falcon_strategy";

    public String mode = "BALANCED";
    public double entryScore = 78;
    public double minRiskReward = 1.8;
    public boolean[] enabled = {true, true, true, true, true, true};
    public double[] sensitivity = {55, 45, 55, 45, 72, 55};

    public static int modeIndex(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 1;
    }

    public static Strategy load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Strategy s = new Strategy();
        s.mode = p.getString("mode", "BALANCED");
        int mi = modeIndex(s.mode);
        s.entryScore = p.getFloat("entry", (float) BANDS[mi][2]);
        s.minRiskReward = p.getFloat("rr", (float) BANDS[mi][3]);
        for (int i = 0; i < MODULES.length; i++) {
            s.enabled[i] = p.getBoolean("mod_" + MODULES[i], true);
            s.sensitivity[i] = p.getFloat("sens_" + MODULES[i], (float) SENSITIVITY[mi][i]);
        }
        return s.validated();
    }

    public void save(Context ctx) {
        validated();
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("mode", mode);
        e.putFloat("entry", (float) entryScore);
        e.putFloat("rr", (float) minRiskReward);
        for (int i = 0; i < MODULES.length; i++) {
            e.putBoolean("mod_" + MODULES[i], enabled[i]);
            e.putFloat("sens_" + MODULES[i], (float) sensitivity[i]);
        }
        e.apply();
    }

    /** Reset every tunable to the chosen preset's defaults. */
    public void applyPreset(String newMode) {
        mode = newMode;
        int mi = modeIndex(mode);
        entryScore = BANDS[mi][2];
        minRiskReward = BANDS[mi][3];
        for (int i = 0; i < MODULES.length; i++) {
            enabled[i] = true;
            sensitivity[i] = SENSITIVITY[mi][i];
        }
    }

    public Strategy validated() {
        int mi = modeIndex(mode);
        entryScore = Indicators.clamp(entryScore, BANDS[mi][0], BANDS[mi][1]);
        minRiskReward = Indicators.clamp(minRiskReward, 1.2, 5.0);
        // Liquidity protection is never optional.
        enabled[5] = true;
        boolean any = false;
        for (int i = 0; i < enabled.length; i++) if (enabled[i]) any = true;
        if (!any) enabled[0] = true;
        for (int i = 0; i < sensitivity.length; i++) {
            sensitivity[i] = Indicators.clamp(sensitivity[i], 0, 100);
        }
        return this;
    }

    public double floorFor(String mode) {
        return BANDS[modeIndex(mode)][0];
    }

    public double ceilingFor(String mode) {
        return BANDS[modeIndex(mode)][1];
    }

    /** Enabled module weights, renormalised so the score still reads 0-100. */
    public Map<String, Double> weights() {
        Map<String, Double> w = new LinkedHashMap<String, Double>();
        double[] base = dynamicWeights == null ? WEIGHTS : dynamicWeights;
        double total = 0;
        for (int i = 0; i < MODULES.length; i++) if (enabled[i]) total += base[i];
        if (total <= 0) return w;
        for (int i = 0; i < MODULES.length; i++) {
            if (enabled[i]) w.put(MODULES[i], base[i] * 100.0 / total);
        }
        return w;
    }

    /** Result of scoring one direction on one symbol. */
    public static final class Result {
        public double score;
        public boolean ready;
        public String status = "WAIT";
        public String reason = "";
        public List<String> blockers = new ArrayList<String>();
        public double[] components = new double[MODULES.length];
    }

    /**
     * Score one setup. Components are 0-100 readings per module; a disabled
     * module is skipped entirely rather than counted as zero.
     */
    public Result evaluate(double[] components, double riskReward, List<String> hardVetoes) {
        Result r = new Result();
        r.components = components;
        Map<String, Double> w = weights();
        if (w.isEmpty()) {
            r.reason = "No strategy module is enabled";
            r.blockers.add(r.reason);
            return r;
        }
        double score = 0;
        for (int i = 0; i < MODULES.length; i++) {
            if (!enabled[i]) continue;
            double weight = w.get(MODULES[i]);
            double value = Indicators.clamp(components[i], 0, 100);
            score += value * weight / 100.0;
            // Compare with a small tolerance and report the real numbers.
            //
            // A module reading 44.6 against a floor of 45 displayed as
            // "Breakout 45 below required 45", which looks like a bug
            // because it reads like one. Half a point either way is not a
            // meaningful difference in a 0-100 score.
            if (value < sensitivity[i] - FLOOR_TOLERANCE) {
                r.blockers.add(String.format("%s %.2f below required %.2f",
                        MODULE_LABELS[i], value, sensitivity[i]));
            }
        }
        r.score = Math.round(score * 10.0) / 10.0;
        if (r.score < entryScore - FLOOR_TOLERANCE) {
            r.blockers.add(String.format("Entry score %.2f below required %.2f",
                    r.score, entryScore));
        }
        if (riskReward > 0 && riskReward < minRiskReward) {
            r.blockers.add(String.format("Reward/risk %.2f below required %.2f", riskReward, minRiskReward));
        }
        if (hardVetoes != null) r.blockers.addAll(hardVetoes);

        r.ready = r.blockers.isEmpty();
        if (r.ready) {
            r.status = "READY";
            r.reason = "All enabled modules passed";
        } else {
            if (r.score >= entryScore * 0.9) r.status = "NEAR ENTRY";
            else if (r.score >= entryScore * 0.75) r.status = "WATCH";
            else r.status = "WAIT";
            r.reason = r.blockers.get(0);
        }
        return r;
    }
}
