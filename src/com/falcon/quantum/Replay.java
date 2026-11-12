package com.falcon.quantum;
import java.util.ArrayList;
import java.util.List;

/** Closed-candle signals and next-minute fills. No Android or network access. */
public final class Replay {
    private Replay() {}
    public static final class Funding { public long at;public double rate,mark; }
    public static final class Config {
        public double equity=1000,riskPercent=.5,dailyLossPercent=2,feeBps=5,slipBps=2;
        public double step=.001,minQty=.001,minNotional=5;
        public int leverage=3;
        public String entry="BASELINE",exit="LEGACY",style="NORMAL";
        public boolean confirmation=false,slTpOnly=true;
    }
    public static final class Trade {
        public long entryAt,exitAt;public double entry,exit,stop,target,qty,gross,fees,funding,net,initialRisk,peak;
        public boolean buy;public String reason;
    }
    public static final class Report {
        public final List<Trade> trades=new ArrayList<Trade>();
        public double initial,ending,drawdown,wins,grossProfit,grossLoss,fees,funding;
        public long start,end;public int ambiguousBars,skippedRisk,skippedTiming;
        public double factor(){return grossLoss==0?Double.NaN:grossProfit/grossLoss;}
    }
    /** Returns STOP first for ambiguous bars. A gap stop fills at the adverse contract open. */
    public static String hit(boolean buy,double stop,double target,double markHigh,double markLow) {
        if(buy?markLow<=stop:markHigh>=stop)return "STOP";
        if(buy?markHigh>=target:markLow<=target)return "TARGET";
        return null;
    }
    public static double stopFill(boolean buy,double stop,double contractOpen,double markOpen) {
        // Basis at bar open converts a mark trigger into an estimated contract execution.
        double trigger=stop+(contractOpen-markOpen);
        return buy?Math.min(trigger,contractOpen):Math.max(trigger,contractOpen);
    }
    public static Report run(String symbol,Binance.Candles contract,Binance.Candles mark,List<Funding> funding,
                              Strategy strategy,Config cfg,long start,long end) {
        if(contract.size!=mark.size)throw new IllegalArgumentException("Contract/mark series length differs");
        for(int k=0;k<contract.size;k++)if(contract.openTime[k]!=mark.openTime[k])throw new IllegalArgumentException("Mark timestamp mismatch");
        Binance.Candles signals=CandleSeries.aggregate(contract,900000L),hour=CandleSeries.aggregate(contract,3600000L),four=CandleSeries.aggregate(contract,14400000L);
        Report result=new Report();result.start=start;result.end=end;result.initial=cfg.equity;
        double wallet=cfg.equity,peakEquity=wallet,dayBase=wallet,dayNet=0;long day=-1,lastSignal=-1;boolean halted=false;
        Trade trade=null;int fundIndex=0;
        for(int i=0;i<contract.size;i++) {
            long now=contract.openTime[i];if(now<start)continue;if(now>=end)break;
            long thisDay=RiskMath.utcDay(now);if(thisDay!=day){day=thisDay;dayBase=wallet;dayNet=0;halted=false;}
            while(fundIndex<funding.size()&&funding.get(fundIndex).at<=now) {
                Funding f=funding.get(fundIndex++);
                if(trade!=null&&f.at>trade.entryAt) {double amount=(trade.buy?-1:1)*trade.qty*f.mark*f.rate;trade.funding+=amount;wallet+=amount;dayNet+=amount;}
            }
            int n=CandleSeries.closedBy(signals,now);
            Engine.Row row=null;
            if(n>=60&&signals.closeTime[n-1]!=lastSignal) {
                lastSignal=signals.closeTime[n-1];
                Binance.Candles w=CandleSeries.window(signals,n,200);
                double volume=0;for(int k=Math.max(0,n-96);k<n;k++)volume+=signals.volume[k]*signals.close[k];
                row=Engine.analyse(symbol,w,volume,strategy,true,cfg.style,"15m",cfg.confirmation);
                if(row!=null&&"PULLBACK".equals(cfg.entry)) {
                    String gate=EntryTiming.pullback(row,CandleSeries.window(hour,CandleSeries.closedBy(hour,now),200),CandleSeries.window(four,CandleSeries.closedBy(four,now),200));
                    if(gate!=null)row.ready=false;
                }
                // New levels/early exits use ONLY the preceding closed 15m candle.
                if(trade!=null && row!=null) {
                    PositionManager.Verdict v=ExitPolicy.review(cfg.exit,trade.buy,trade.entry,contract.open[i],trade.stop,trade.target,
                            trade.initialRisk,trade.peak,row.momentum,(trade.buy?"LONG":"SHORT").equals(row.side),cfg.slTpOnly,cfg.feeBps,cfg.slipBps);
                    if(v.closes()) {
                        finish(trade,contract.open[i],now,v.reason,cfg,result);wallet+=trade.gross-trade.exit*trade.qty*cfg.feeBps/10000.0;
                        dayNet+=trade.gross-trade.exit*trade.qty*cfg.feeBps/10000.0;trade=null;
                    }else {if(v.newStop>0)trade.stop=v.newStop;if(v.newTarget>0)trade.target=v.newTarget;}
                }
            }
            if(trade==null&&row!=null&&row.ready&&!halted) {
                String refuse=EntryTiming.check(row,contract.open[i],now);
                if(refuse==null) {
                    boolean buy="LONG".equals(row.side);double entry=RiskMath.adverseFill(contract.open[i],buy,cfg.slipBps);
                    double reward=(buy?row.target-entry:entry-row.target)-(entry+row.target)*(cfg.feeBps+cfg.slipBps)/10000.0;
                    double budget=dayBase*cfg.dailyLossPercent/100.0+Math.min(0,dayNet);
                    double quantity=RiskMath.size(wallet,wallet,entry,row.stop,cfg.riskPercent,25,cfg.leverage,cfg.feeBps,cfg.slipBps,budget);
                    quantity=Math.floor(quantity/cfg.step)*cfg.step;
                    if(quantity>=cfg.minQty&&quantity*entry>=cfg.minNotional&&reward/RiskMath.perUnit(entry,row.stop,cfg.feeBps,cfg.slipBps)>=strategy.minRiskReward) {
                        trade=new Trade();trade.entryAt=now;trade.buy=buy;trade.entry=entry;trade.stop=row.stop;trade.target=row.target;
                        trade.initialRisk=Math.abs(entry-row.stop);trade.qty=quantity;trade.fees=entry*quantity*cfg.feeBps/10000.0;
                        wallet-=trade.fees;dayNet-=trade.fees;
                    }else result.skippedRisk++;
                }else result.skippedTiming++;
            }
            if(trade!=null) {
                // Non-boundary funding times can fall within this minute. Charge held
                // positions before modeled intrabar exits; exact tick order is unavailable.
                while(fundIndex<funding.size()&&funding.get(fundIndex).at<now+60000L) {
                    Funding f=funding.get(fundIndex++);
                    if(f.at>trade.entryAt){double amount=(trade.buy?-1:1)*trade.qty*f.mark*f.rate;
                        trade.funding+=amount;wallet+=amount;dayNet+=amount;}
                }
                boolean stop=trade.buy?mark.low[i]<=trade.stop:mark.high[i]>=trade.stop;
                boolean target=trade.buy?mark.high[i]>=trade.target:mark.low[i]<=trade.target;
                if(stop&&target)result.ambiguousBars++;
                String hit=hit(trade.buy,trade.stop,trade.target,mark.high[i],mark.low[i]);
                if(hit!=null) {
                    double raw=stop?stopFill(trade.buy,trade.stop,contract.open[i],mark.open[i]):trade.target+(contract.open[i]-mark.open[i]);
                    finish(trade,raw,contract.closeTime[i],hit,cfg,result);
                    wallet+=trade.gross-trade.exit*trade.qty*cfg.feeBps/10000.0;dayNet+=trade.gross-trade.exit*trade.qty*cfg.feeBps/10000.0;trade=null;
                }else trade.peak=Math.max(trade.peak,trade.buy?contract.high[i]-trade.entry:trade.entry-contract.low[i]);
            }
            double openPnl=trade==null?0:(trade.buy?contract.close[i]-trade.entry:trade.entry-contract.close[i])*trade.qty;
            double equity=wallet+openPnl;peakEquity=Math.max(peakEquity,equity);
            result.drawdown=Math.max(result.drawdown,peakEquity<=0?1:(peakEquity-equity)/peakEquity);
            if(dayNet+Math.min(0,openPnl)<=-dayBase*cfg.dailyLossPercent/100.0)halted=true;
        }
        if(trade!=null) {
            int last=Math.min(contract.size-1,CandleSeries.closedBy(contract,end)-1);
            finish(trade,contract.close[last],contract.closeTime[last],"END_OF_WINDOW",cfg,result);
            wallet+=trade.gross-trade.exit*trade.qty*cfg.feeBps/10000.0;
        }
        result.drawdown=Math.max(result.drawdown,peakEquity<=0?1:(peakEquity-wallet)/peakEquity);
        result.ending=wallet;return result;
    }
    private static void finish(Trade t,double price,long at,String why,Config cfg,Report report) {
        t.exit=RiskMath.adverseFill(price,!t.buy,cfg.slipBps);t.exitAt=at;t.reason=why;
        t.gross=(t.buy?t.exit-t.entry:t.entry-t.exit)*t.qty;t.fees+=t.exit*t.qty*cfg.feeBps/10000.0;
        t.net=RiskMath.net(t.gross,t.fees,t.funding);report.trades.add(t);
        if(t.net>0){report.wins++;report.grossProfit+=t.net;}else report.grossLoss-=t.net;
        report.fees+=t.fees;report.funding+=t.funding;
    }
}
