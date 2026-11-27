package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reviews completed trades and proposes settings changes.
 *
 * This is statistics, not intelligence. It measures what has actually
 * happened - win rate, profit factor, and how results differ by direction,
 * style and hold time - and turns clear patterns into suggestions.
 *
 * Two rules it holds to:
 *
 *  - Nothing is applied without the operator agreeing. A proposal that
 *    changes real-money behaviour is shown with its evidence and waits.
 *  - A small sample is called small. Thirty trades cannot distinguish an
 *    edge from luck, and pretending otherwise would encourage exactly the
 *    wrong decision.
 */
public final class LearningEngine {

    public static final String PREFS = "falcon_learning";
    public static final int REVIEW_SIZE = 100;
    /** Below this, differences are noise; say so rather than suggest. */
    public static final int MINIMUM_CONFIDENT_SAMPLE = 30;

    private LearningEngine() {
    }

    /** One measured summary of recent trading. */
    public static final class Review {
        public int reviewed;
        public int wins;
        public int losses;
        public double winRate;
        public double profitFactor;
        public double netPnl;
        public double averageWin;
        public double averageLoss;
        public double averageHoldMinutes;
        public List<Suggestion> suggestions = new ArrayList<Suggestion>();

        public boolean enoughData() {
            return reviewed >= MINIMUM_CONFIDENT_SAMPLE;
        }

        public String headline() {
            if (reviewed == 0) return "No completed trades yet.";
            return String.format(Locale.US,
                    "Last %d trades: %d wins, %d losses, %.1f%% win rate, "
                            + "profit factor %.2f, net P/L %.4f.",
                    reviewed, wins, losses, winRate, profitFactor, netPnl);
        }
    }

    /** A proposed change, with the evidence behind it. */
    public static final class Suggestion {
        public String id;
        public String title;
        public String evidence;
        public String settingKey;
        public String settingValue;
        public int sample;

        public boolean confident() {
            return sample >= MINIMUM_CONFIDENT_SAMPLE;
        }
    }

    private static final class Bucket {
        int n, wins;
        double pnl, grossWin, grossLoss, holdMinutes;

        void add(double p, double minutes) {
            n++;
            pnl += p;
            holdMinutes += minutes;
            if (p > 0) {
                wins++;
                grossWin += p;
            } else {
                grossLoss += Math.abs(p);
            }
        }

        double winRate() {
            return n == 0 ? 0 : (double) wins / n * 100.0;
        }

        double factor() {
            if (grossLoss <= 0) return grossWin > 0 ? 99 : 0;
            return grossWin / grossLoss;
        }
    }

    /** Analyse the most recent completed trades for an environment. */
    public static Review review(Journal journal, String env) {
        Review r = new Review();
        List<Journal.Trade> all = journal.history(env, 400);

        List<Journal.Trade> closed = new ArrayList<Journal.Trade>();
        for (int i = 0; i < all.size() && closed.size() < REVIEW_SIZE; i++) {
            Journal.Trade t = all.get(i);
            if (!"OPEN".equals(t.status)) closed.add(t);
        }
        r.reviewed = closed.size();
        if (r.reviewed == 0) return r;

        Bucket overall = new Bucket();
        Bucket longs = new Bucket(), shorts = new Bucket();
        Bucket quick = new Bucket(), slow = new Bucket();
        Bucket stopped = new Bucket(), targeted = new Bucket(), managed = new Bucket();

        for (int i = 0; i < closed.size(); i++) {
            Journal.Trade t = closed.get(i);
            double minutes = (t.closedAt > 0 && t.openedAt > 0)
                    ? (t.closedAt - t.openedAt) / 60000.0 : 0;
            overall.add(t.pnl, minutes);
            if ("LONG".equals(t.side)) longs.add(t.pnl, minutes);
            else shorts.add(t.pnl, minutes);
            if (minutes <= 30) quick.add(t.pnl, minutes);
            else slow.add(t.pnl, minutes);
            if ("STOP".equals(t.status)) stopped.add(t.pnl, minutes);
            else if ("TARGET".equals(t.status)) targeted.add(t.pnl, minutes);
            else managed.add(t.pnl, minutes);
        }

        r.wins = overall.wins;
        r.losses = overall.n - overall.wins;
        r.winRate = overall.winRate();
        r.profitFactor = overall.factor();
        r.netPnl = overall.pnl;
        r.averageWin = overall.wins == 0 ? 0 : overall.grossWin / overall.wins;
        r.averageLoss = r.losses == 0 ? 0 : overall.grossLoss / r.losses;
        r.averageHoldMinutes = overall.n == 0 ? 0 : overall.holdMinutes / overall.n;

        if (!r.enoughData()) return r;   // measured, but no proposals yet

        // One side clearly losing while the other works.
        if (longs.n >= 12 && shorts.n >= 12) {
            if (shorts.pnl < 0 && longs.pnl > 0 && shorts.winRate() + 15 < longs.winRate()) {
                Suggestion s = new Suggestion();
                s.id = "disable_shorts";
                s.title = "Stop taking SHORT trades";
                s.evidence = String.format(Locale.US,
                        "Shorts: %d trades, %.0f%% win, net %.4f. Longs: %d trades, %.0f%% win, "
                                + "net %.4f.", shorts.n, shorts.winRate(), shorts.pnl,
                        longs.n, longs.winRate(), longs.pnl);
                s.settingKey = "allowShort";
                s.settingValue = "false";
                s.sample = shorts.n + longs.n;
                r.suggestions.add(s);
            } else if (longs.pnl < 0 && shorts.pnl > 0 && longs.winRate() + 15 < shorts.winRate()) {
                Suggestion s = new Suggestion();
                s.id = "disable_longs";
                s.title = "Stop taking LONG trades";
                s.evidence = String.format(Locale.US,
                        "Longs: %d trades, %.0f%% win, net %.4f. Shorts: %d trades, %.0f%% win, "
                                + "net %.4f.", longs.n, longs.winRate(), longs.pnl,
                        shorts.n, shorts.winRate(), shorts.pnl);
                s.settingKey = "allowLong";
                s.settingValue = "false";
                s.sample = shorts.n + longs.n;
                r.suggestions.add(s);
            }
        }

        // Trades that linger lose; fast ones work.
        if (quick.n >= 10 && slow.n >= 10 && slow.pnl < 0 && quick.pnl > 0) {
            Suggestion s = new Suggestion();
            s.id = "tighten_style";
            s.title = "Switch trading style to FAST_MOVE";
            s.evidence = String.format(Locale.US,
                    "Trades closed within 30 min: %d, net %.4f. Trades held longer: %d, "
                            + "net %.4f. The slow ones are losing.",
                    quick.n, quick.pnl, slow.n, slow.pnl);
            s.settingKey = "tradingStyle";
            s.settingValue = "FAST_MOVE";
            s.sample = quick.n + slow.n;
            r.suggestions.add(s);
        }

        // Stops hit far more often than targets: the target is too ambitious
        // relative to the stop, or entries are early.
        if (stopped.n + targeted.n >= 20 && stopped.n > targeted.n * 2) {
            Suggestion s = new Suggestion();
            s.id = "raise_entry_score";
            s.title = "Raise the minimum entry score by 5";
            s.evidence = String.format(Locale.US,
                    "%d trades hit the stop against %d that reached target. Entries are "
                            + "firing on setups that do not follow through.",
                    stopped.n, targeted.n);
            s.settingKey = "entryScore";
            s.settingValue = "+5";
            s.sample = stopped.n + targeted.n;
            r.suggestions.add(s);
        }

        // Losing overall with a poor factor: reduce exposure rather than
        // hunting for a cleverer setting.
        if (r.profitFactor < 0.8 && r.netPnl < 0) {
            Suggestion s = new Suggestion();
            s.id = "reduce_size";
            s.title = "Halve the position size percent";
            s.evidence = String.format(Locale.US,
                    "Profit factor %.2f over %d trades, net %.4f. Losing strategies lose "
                            + "faster with larger size; this limits damage while the settings "
                            + "are worked out.", r.profitFactor, r.reviewed, r.netPnl);
            s.settingKey = "sizePercent";
            s.settingValue = "half";
            s.sample = r.reviewed;
            r.suggestions.add(s);
        }

        // Managed exits rescuing value the stop would have lost.
        if (managed.n >= 10 && managed.pnl > 0 && stopped.pnl < 0) {
            Suggestion s = new Suggestion();
            s.id = "keep_manager";
            s.title = "Keep active position management on";
            s.evidence = String.format(Locale.US,
                    "Manager-closed trades: %d, net %.4f. Stop-closed trades: %d, net %.4f.",
                    managed.n, managed.pnl, stopped.n, stopped.pnl);
            s.settingKey = "";
            s.settingValue = "";
            s.sample = managed.n + stopped.n;
            r.suggestions.add(s);
        }

        return r;
    }

    // ------------------------------------------------------------ state

    public static boolean isDismissed(Context ctx, String id) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("dismissed_" + id, false);
    }

    public static void dismiss(Context ctx, String id) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("dismissed_" + id, true).apply();
    }

    public static void markApplied(Context ctx, String id) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putLong("applied_" + id, System.currentTimeMillis())
                .putBoolean("dismissed_" + id, true).apply();
    }

    public static long appliedAt(Context ctx, String id) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("applied_" + id, 0);
    }

    /**
     * Apply an accepted suggestion to the environment's settings.
     * Returns a description of what changed.
     */
    public static String apply(Context ctx, Settings cfg, Suggestion s) {
        if (s.settingKey == null || s.settingKey.length() == 0) {
            markApplied(ctx, s.id);
            return "Noted — no setting needed changing.";
        }
        String changed;
        if ("allowShort".equals(s.settingKey)) {
            cfg.allowShort = false;
            changed = "SHORT entries disabled for " + cfg.env;
        } else if ("allowLong".equals(s.settingKey)) {
            cfg.allowLong = false;
            changed = "LONG entries disabled for " + cfg.env;
        } else if ("tradingStyle".equals(s.settingKey)) {
            cfg.tradingStyle = s.settingValue;
            changed = "Trading style set to " + s.settingValue;
        } else if ("sizePercent".equals(s.settingKey)) {
            int before = cfg.sizePercent;
            int target = Math.max(10, before / 2);
            int best = Settings.SIZE_PERCENTS[0];
            for (int i = 0; i < Settings.SIZE_PERCENTS.length; i++) {
                if (Settings.SIZE_PERCENTS[i] <= target) best = Settings.SIZE_PERCENTS[i];
            }
            cfg.sizePercent = best;
            changed = "Position size " + before + "% to " + best + "%";
        } else if ("entryScore".equals(s.settingKey)) {
            Strategy st = Strategy.load(ctx);
            double before = st.entryScore;
            st.entryScore = before + 5;
            st.save(ctx);
            changed = String.format(Locale.US, "Entry score %.0f to %.0f", before, st.entryScore);
        } else {
            return "Unknown suggestion.";
        }
        cfg.save(ctx);
        markApplied(ctx, s.id);
        return changed;
    }
}
