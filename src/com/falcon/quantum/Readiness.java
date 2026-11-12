package com.falcon.quantum;

/** Settings fingerprints and honest status text; LIVE permission is an operator preference. */
public final class Readiness {
    public static final String MODEL_VERSION="11.0.0-safety-preview";
    private Readiness() {}
    private static String n(double value){return String.format(java.util.Locale.US,"%.4f",value);}
    public static String liveOrderStatus(boolean enabled) {
        return "Safety preview: new LIVE entries are disabled, regardless of saved settings. "
            + "Existing LIVE positions can still be inspected and managed. "
            + "No profitability or real-exchange reliability has been established.";
    }
    public static String key(Settings s,Strategy st) {
        StringBuilder b=new StringBuilder(MODEL_VERSION+"|"+s.tradingMode+"|"+s.timeframe+"|"+s.entryPolicy+"|"+s.exitPolicy
            +"|"+s.tradingStyle+"|"+s.requireConfirmation+"|"+s.exitOnlyBySlTp+"|"+n(s.riskPercent)+"|"+n(s.dailyLossPercent)
            +"|"+n(s.feeBps)+"|"+n(s.slippageBps)+"|"+s.leverage+"|"+s.marginMode+"|"+st.mode+"|"+n(st.entryScore)+"|"+n(st.minRiskReward)
            +"|"+s.allowLong+"|"+s.allowShort+"|"+s.sizePercent+"|"+s.maxPositions+"|"+n(s.maxDailyLoss)+"|"+s.maxConsecutiveLosses
            +"|"+s.customWatchlist+"|"+s.watchlist+"|"+s.scanLimit+"|"+s.scanIntervalSec);
        for(int i=0;i<st.enabled.length;i++) b.append('|').append(st.enabled[i]).append(':').append(n(st.sensitivity[i]));
        return b.toString();
    }
}
