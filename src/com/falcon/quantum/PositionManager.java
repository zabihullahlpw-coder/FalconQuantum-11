package com.falcon.quantum;

/**
 * Decides what to do with a position that is already open.
 *
 * A fixed 2R target assumes the market will keep going. Often it does not:
 * price runs most of the way, stalls, and hands the profit back. Two
 * changes address that - a target sized to what the market can actually
 * travel, and a stop that stops being a loss once the trade has worked.
 */
public final class PositionManager {

    private PositionManager() {
    }

    /** What should happen to this position now. */
    public static final class Verdict {
        public String action = "HOLD";
        /** New stop, 0 to leave alone. */
        public double newStop;
        /** New target, 0 to leave alone. */
        public double newTarget;
        public String reason = "";
        public double progressPct;      // how far towards target, 0-100
        public double drawdownFromPeak; // give-back from best point, percent

        public boolean closes() {
            return "CLOSE".equals(action);
        }

        public boolean retargets() {
            return newStop > 0 || newTarget > 0;
        }
    }

    /**
     * How much profit to lock in, as a multiple of risk, given how far the
     * trade has travelled.
     *
     * The ladder deliberately trails well behind price. Locking too close
     * gets the position stopped out by ordinary noise before the move
     * finishes.
     */
    public static double lockedProfitMultiple(double moved, double risk) {
        if (risk <= 0) return 0;
        double r = moved / risk;
        if (r >= 3.0) return r - 1.0;   // trail a full R behind
        if (r >= 2.0) return 1.2;
        if (r >= 1.5) return 0.8;
        if (r >= 1.2) return 0.4;
        if (r >= 0.8) return 0.1;       // break-even plus a little
        return 0;
    }

    /**
     * Target distance the market can plausibly reach.
     *
     * Rather than always asking for 2R, take the smaller of 2R and what
     * recent movement supports over a modest number of candles. A target
     * beyond the market's demonstrated reach is not ambition, it is a
     * trade that gives its profit back.
     */
    public static double achievableTargetPct(Momentum.Reading m, double riskPct,
                                             int candlesAllowed) {
        double ambitious = riskPct * 2.0;
        if (m == null || m.percentPerCandle <= 0) return ambitious;
        double reach = m.percentPerCandle * (m.directionalAgreement / 100.0) * candlesAllowed;
        // Never ask for less than 0.8R, or the fees and spread eat the edge.
        double floor = riskPct * 0.8;
        double chosen = Math.min(ambitious, Math.max(floor, reach));
        return chosen;
    }

    /**
     * Review an open position.
     *
     * @param peakFavourable best unrealised profit seen, in price terms
     */
    public static Verdict review(boolean isLong, double entry, double mark,
                                 double stop, double target,
                                 double peakFavourable, Momentum.Reading now,
                                 boolean thesisStillValid) {
        return review(isLong, entry, mark, stop, target, peakFavourable, now,
                thesisStillValid, true);
    }

    /**
     * @param exitOnlyBySlTp when true, this never closes a position. It
     *     moves the stop instead, so an exit still happens through the stop
     *     order sitting on the exchange. Closing on a momentum reading was
     *     turning recoverable trades into certain losses.
     */
    public static Verdict review(boolean isLong, double entry, double mark,
                                 double stop, double target,
                                 double peakFavourable, Momentum.Reading now,
                                 boolean thesisStillValid, boolean exitOnlyBySlTp) {
        Verdict v = new Verdict();
        if (entry <= 0 || mark <= 0) return v;

        double toTarget = Math.abs(target - entry);
        double moved = isLong ? (mark - entry) : (entry - mark);
        v.progressPct = toTarget <= 0 ? 0 : Indicators.clamp(moved / toTarget * 100.0, -100, 100);

        double peakMove = Math.max(0, peakFavourable);
        v.drawdownFromPeak = peakMove <= 0 ? 0
                : Indicators.clamp((peakMove - moved) / peakMove * 100.0, 0, 100);

        double risk = Math.abs(entry - stop);

        // 1. Secure profit in steps as the trade works.
        //
        // A stop that never moves gives the whole gain back on a reversal.
        // Each rung locks in more, and the stop only ever moves in the
        // favourable direction - it is never loosened.
        double locked = lockedProfitMultiple(moved, risk);
        if (locked > 0) {
            double newStop = isLong ? entry + risk * locked : entry - risk * locked;
            boolean better = isLong ? newStop > stop : newStop < stop;
            if (better) {
                v.action = "PROTECT";
                v.newStop = newStop;
                v.reason = locked <= 0.1
                        ? "in profit — stop moved to break-even"
                        : "up " + String.format("%.1f", moved / risk)
                                + "R — stop raised to lock in "
                                + String.format("%.1f", locked) + "R";
            }
        }

        // 2. Ran most of the way, then handed a large part of it back.
        //
        // This must be judged from the PEAK, not from where price is now.
        // Measuring current progress meant a trade that reached 90% of
        // target and fell back to 35% never triggered the rule - which is
        // exactly the case this is meant to catch.
        double peakProgress = toTarget <= 0 ? 0
                : Indicators.clamp(peakMove / toTarget * 100.0, 0, 100);
        if (peakProgress >= 60 && v.drawdownFromPeak >= 50) {
            if (exitOnlyBySlTp) {
                // Do not close. Pull the stop up under the price instead, so
                // the exchange stop takes the trade out with profit intact
                // and the position still has room to recover.
                double tightened = isLong ? mark - risk * 0.25 : mark + risk * 0.25;
                boolean better = isLong ? tightened > stop : tightened < stop;
                if (better) {
                    v.action = "PROTECT";
                    v.newStop = tightened;
                    v.reason = "gave back " + Math.round(v.drawdownFromPeak)
                            + "% from the peak — stop tightened under price";
                    return v;
                }
            } else {
                v.action = "CLOSE";
                v.reason = "reached " + Math.round(peakProgress) + "% of target then gave back "
                        + Math.round(v.drawdownFromPeak) + "% — taking what is left";
                return v;
            }
        }

        // 3 and 4 used to close on a weak reading. Both are disabled when
        // the operator has said the stop and target own the exit - which is
        // the default, because closing early on a momentum dip produced a
        // run of small certain losses on trades that had not yet been wrong.
        if (!exitOnlyBySlTp) {
            if (!thesisStillValid && moved <= 0) {
                v.action = "CLOSE";
                v.reason = "setup no longer valid and the position is not in profit";
                return v;
            }
            if (now != null && v.progressPct < 40 && now.score < 35 && moved <= risk * 0.2) {
                v.action = "CLOSE";
                v.reason = "momentum faded to " + Math.round(now.score)
                        + " with the target still " + Math.round(100 - v.progressPct) + "% away";
                return v;
            }
        }

        // 5. Target is out of reach at the current pace: bring it closer to
        //    where the market can actually go.
        if (now != null && now.percentPerCandle > 0 && v.progressPct < 80) {
            double remainingPct = Math.abs(target - mark) / mark * 100.0;
            double reachPct = now.percentPerCandle * (now.directionalAgreement / 100.0) * 10;
            if (reachPct > 0 && remainingPct > reachPct * 2.5) {
                double closer = isLong ? mark + mark * reachPct / 100.0
                                       : mark - mark * reachPct / 100.0;
                boolean nearer = isLong ? closer < target : closer > target;
                // Only ever pull the target IN, never push it further away.
                if (nearer) {
                    v.action = "RETARGET";
                    v.newTarget = closer;
                    if (v.reason.length() == 0) {
                        v.reason = "target was beyond the market's current reach — moved closer";
                    } else {
                        v.reason += "; target moved closer";
                    }
                }
            }
        }

        // 6. Running hard into the target with momentum still behind it:
        //    give the move room rather than capping it. Only ever when the
        //    stop is already locked in profit, so extending risks nothing.
        if (now != null && v.progressPct >= 85 && now.score >= 70 && locked >= 0.4) {
            double extra = Math.abs(target - entry) * 0.4;
            double extended = isLong ? target + extra : target - extra;
            v.action = v.action.equals("PROTECT") ? "PROTECT" : "RETARGET";
            v.newTarget = extended;
            v.reason += (v.reason.length() > 0 ? "; " : "")
                    + "momentum still strong near target — target extended with profit locked";
        }

        if (v.action.equals("HOLD") && v.reason.length() == 0) {
            v.reason = "on side, " + Math.round(v.progressPct) + "% of the way to target";
        }
        return v;
    }
}
