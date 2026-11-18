package com.falcon.quantum;

/**
 * Experimental inversion of a FINALIZED entry plan. Never run this on exits,
 * held positions, exchange reconciliation or existing stop/target orders.
 *
 * Original signal logic remains untouched. The effective entry side, stop and
 * target are transformed exactly once, BEFORE entry-timing, order direction,
 * risk sizing, reward/risk checks, display, and journaling.
 */
public final class TradeDirection {
    private TradeDirection() {}

    public static void apply(Engine.Row row, Settings cfg) {
        if (row == null || cfg == null) return;
        if (row.signalSide == null) row.signalSide = row.side;
        if (!cfg.reverseTradeDirection || row.directionReversed) return;

        String original = row.signalSide;
        boolean wasLong = "LONG".equals(original);
        if (!wasLong && !"SHORT".equals(original)) {
            block(row, "Unrecognised original trade direction");
            return;
        }
        if (!RiskMath.positive(row.entry) || !RiskMath.positive(row.stop)
                || !RiskMath.positive(row.target)
                || (wasLong && !(row.stop < row.entry && row.entry < row.target))
                || (!wasLong && !(row.target < row.entry && row.entry < row.stop))) {
            block(row, "Invalid original stop/target; cannot reverse safely");
            return;
        }
        double risk = Math.abs(row.entry - row.stop);
        double reward = Math.abs(row.target - row.entry);
        String opposite = wasLong ? "SHORT" : "LONG";
        double newStop = wasLong ? row.entry + risk : row.entry - risk;
        double newTarget = wasLong ? row.entry - reward : row.entry + reward;
        if (!RiskMath.positive(newStop) || !RiskMath.positive(newTarget)
                || (wasLong && !(newTarget < row.entry && row.entry < newStop))
                || (!wasLong && !(newStop < row.entry && row.entry < newTarget))) {
            block(row, "Reversed stop/target would be invalid; no entry");
            return;
        }
        row.side = opposite;
        row.stop = newStop;
        row.target = newTarget;
        row.directionReversed = true;
        row.warnings.add("Experimental REVERSE: original " + original
                + " signal opens " + opposite + " instead. Signal score is for " + original + ".");
        if (!cfg.isFutures()) {
            block(row, "Reverse Trade Direction requires FUTURES; SPOT cannot open SHORT");
        } else if (("LONG".equals(opposite) && !cfg.allowLong)
                || ("SHORT".equals(opposite) && !cfg.allowShort)) {
            block(row, "Reversed " + opposite + " entry is disabled in direction permissions");
        }
    }

    private static void block(Engine.Row row, String reason) {
        row.ready = false;
        row.grade = "REJECT";
        row.status = "WAIT";
        row.reason = reason;
        row.warnings.add(reason);
    }
}
