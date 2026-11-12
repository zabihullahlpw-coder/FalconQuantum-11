package com.falcon.quantum;

/** Shared by execution, PAPER and replay. Values are estimates, never a loss guarantee. */
public final class RiskMath {
    private RiskMath() {}
    public static boolean positive(double x) { return x>0 && !Double.isNaN(x) && !Double.isInfinite(x); }
    public static double perUnit(double entry,double stop,double feeBps,double slipBps) {
        if (!positive(entry)||!positive(stop)||entry==stop) throw new IllegalArgumentException("Invalid stop distance");
        return Math.abs(entry-stop)+(entry+stop)*(feeBps+slipBps)/10000.0;
    }
    public static double size(double equity,double available,double entry,double stop,
                              double riskPercent,double marginPercent,int leverage,
                              double feeBps,double slipBps,double remainingDailyRisk) {
        if (!positive(equity)||!positive(available)||!positive(remainingDailyRisk)) return 0;
        double budget=Math.min(equity*riskPercent/100.0,remainingDailyRisk);
        double riskQty=budget/perUnit(entry,stop,feeBps,slipBps);
        // Reserve estimated entry fees as well as margin.
        double marginQty=available*marginPercent/100.0/(entry/Math.max(1,leverage)+entry*feeBps/10000.0);
        return Math.max(0,Math.min(riskQty,marginQty));
    }
    public static double net(double gross,double commission,double funding) { return gross-commission+funding; }
    public static double adverseFill(double price,boolean buy,double bps) {
        return price*(1+(buy?1:-1)*bps/10000.0);
    }
    public static long utcDay(long now) { return Math.floorDiv(now,86400000L)*86400000L; }
}
