package com.falcon.quantum;

/** Experiments share this implementation in PAPER and replay. No automatic promotion. */
public final class ExitPolicy {
    private ExitPolicy() {}
    public static PositionManager.Verdict review(String policy,boolean isLong,double entry,double mark,
            double stop,double target,double initialRisk,double peak,Momentum.Reading momentum,
            boolean thesisValid,boolean slTpOnly,double feeBps,double slipBps) {
        if("LEGACY".equals(policy)) return PositionManager.review(isLong,entry,mark,stop,target,peak,momentum,thesisValid,slTpOnly);
        PositionManager.Verdict v=new PositionManager.Verdict();
        double moved=isLong?mark-entry:entry-mark;
        v.reason="Fixed original stop and target";
        if("EARLY".equals(policy) && !thesisValid && moved<=0) {
            v.action="CLOSE"; v.reason="EXPERIMENT_EARLY: closed-candle thesis reversed";
        } else if("TRAIL".equals(policy) && initialRisk>0) {
            // R is the ORIGINAL risk, never the distance to a moving stop.
            double locked=PositionManager.lockedProfitMultiple(Math.max(0,peak),initialRisk);
            double costs=2*entry*(feeBps+slipBps)/10000.0;
            double delta=Math.max(initialRisk*locked,costs);
            double candidate=isLong?entry+delta:entry-delta;
            if(locked>0 && (isLong?candidate>stop && candidate<mark:candidate<stop && candidate>mark)) {
                v.action="PROTECT";v.newStop=candidate;v.reason="EXPERIMENT_TRAIL: initial-R ladder including estimated costs";
            }
        }
        return v;
    }
}
