package com.falcon.quantum;

/** Timing restrictions run before sizing and again immediately before an entry. */
public final class EntryTiming {
    private EntryTiming() {}
    public static String check(Engine.Row r,double executable,long now) {
        if(r==null || !r.ready) return "Setup is not READY";
        if(!RiskMath.positive(executable)||!RiskMath.positive(r.entry)||!RiskMath.positive(r.stop)
                ||!RiskMath.positive(r.target)) return "Invalid plan/quote";
        if(r.signalCloseTime<=0 || now-r.signalCloseTime>120000L || now<r.signalCloseTime)
            return "Signal is stale; entries allowed only within 120 seconds of candle close";
        boolean buy="LONG".equals(r.side);
        double distance=Math.abs(r.entry-r.stop);
        if((buy && (r.stop>=executable || r.target<=executable))
                ||(!buy && (r.stop<=executable || r.target>=executable))) return "Original stop/target already crossed";
        if(Math.abs(executable-r.entry)>distance*0.25) return "Quote moved over 0.25R from signal; wait for next setup";
        Binance.Candles c=r.candles;
        if(c!=null && c.size>=20) {
            double atr=Indicators.atr(c.high,c.low,c.close,14);
            int n=c.size-1;
            if(atr>0 && (c.high[n]-c.low[n])>atr*2.0) return "Large signal candle (>2 ATR); wait for a pullback";
        }
        return null;
    }
    public static String pullback(Engine.Row r,Binance.Candles oneHour,Binance.Candles fourHour) {
        if(r==null) return "No setup";
        if(oneHour==null||fourHour==null||oneHour.size<60||fourHour.size<60) return "Not enough closed 1h/4h history";
        boolean buy="LONG".equals(r.side);
        if(!aligned(oneHour,buy)||!aligned(fourHour,buy)) return "1h and 4h trends do not agree";
        Binance.Candles c=r.candles;
        if(c==null||c.size<25) return "No pullback history";
        double ema=Indicators.ema(c.close,21),atr=Indicators.atr(c.high,c.low,c.close,14);
        int n=c.size-1;
        boolean holds=buy?c.low[n]<=ema+atr*.25 && c.close[n]>ema && c.close[n]>c.open[n]
                :c.high[n]>=ema-atr*.25 && c.close[n]<ema && c.close[n]<c.open[n];
        if(!holds || Math.abs(c.close[n]-ema)>atr) return "Wait for a closed candle holding the EMA21 pullback";
        return null;
    }
    private static boolean aligned(Binance.Candles c,boolean buy) {
        double a=Indicators.ema(c.close,21),b=Indicators.ema(c.close,50),p=c.lastClose();
        return buy?p>a&&a>b:p<a&&a<b;
    }
    public static Engine.Row analyse(String symbol,Binance.Candles c,double volume,Strategy strategy,Settings cfg) throws Exception {
        Engine.Row r=Engine.analyse(symbol,c,volume,strategy,cfg.allowShort,cfg.tradingStyle,cfg.timeframe,cfg.requireConfirmation);
        if(r==null) return null;
        r.entryPolicy=cfg.entryPolicy;
        r.settingsKey=Readiness.key(cfg,strategy);
        if("PULLBACK".equals(cfg.entryPolicy)) {
            String why=!"15m".equals(cfg.timeframe)?"PULLBACK experiment requires 15m entries":
                pullback(r,Binance.klines(symbol,"1h",200,cfg.isFutures()),Binance.klines(symbol,"4h",200,cfg.isFutures()));
            if(why!=null) { r.ready=false; r.status="WAIT"; r.reason=why; }
            else r.reasons.add("Closed 1h/4h trend and EMA21 pullback agree (experimental)");
        }
        return r;
    }
}
