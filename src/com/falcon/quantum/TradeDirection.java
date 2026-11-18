package com.falcon.quantum;

/**
 * Experimental exact SL/TP exchange of a FINALIZED entry plan. Never run this on exits,
 * held positions, exchange reconciliation or existing stop/target orders.
 *
 * Original signal logic remains untouched. The effective entry side, stop and
 * target are transformed exactly once, BEFORE entry-timing, order direction,
 * risk sizing, updated reward/risk display, and journaling.
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
        // Exact swap: NO recalculation, mirroring, rounding or new price assignment.
        // LONG entry 100 / SL 98 / TP 104 becomes SHORT / SL 104 / TP 98.
        // This reverses the ORIGINAL reward and risk distances, so R:R changes.
        final double originalStop = row.stop;
        final double originalTarget = row.target;
        final String opposite = wasLong ? "SHORT" : "LONG";
        final double effectiveRisk = Math.abs(row.entry - originalTarget);
        final double effectiveReward = Math.abs(row.entry - originalStop);
        final double effectiveRewardRisk = effectiveReward / effectiveRisk;
        if (!Double.isFinite(effectiveRewardRisk) || effectiveRewardRisk <= 0) {
            block(row, "Reversed reward/risk invalid; no entry");
            return;
        }
        row.side = opposite;
        row.stop = originalTarget; // EXACT prior TP is now SL.
        row.target = originalStop; // EXACT prior SL is now TP.
        row.riskReward = effectiveRewardRisk;
        row.directionReversed = true;
        // Existing "Reward/risk" explanation belongs to the original scored
        // signal; replace it so the display describes the effective trade.
        for (int i = row.reasons.size()-1; i >= 0; i--) {
            if (row.reasons.get(i).startsWith("Reward/risk ")) row.reasons.remove(i);
        }
        row.reasons.add(String.format(java.util.Locale.US,
                "Reversed exact SL/TP · effective reward/risk %.2f", effectiveRewardRisk));
        row.warnings.add("Experimental REVERSE: original " + original
                + " signal opens " + opposite + ". Original SL is exact TP, "
                + "original TP is exact SL. Signal score was for " + original + ".");
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
