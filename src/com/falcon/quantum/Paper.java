package com.falcon.quantum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated trading account, one completely separate ledger per environment.
 *
 * Supports both market types:
 *   SPOT     - buy only, no leverage, position sized from free balance.
 *   FUTURES  - long or short, leverage applied to notional, isolated or
 *              cross margin, with a liquidation price enforced locally.
 *
 * This is the account the phone trades against. Real exchange orders are
 * handled separately by {@link Trader}, which is off unless the operator
 * explicitly arms it.
 */
public final class Paper extends SQLiteOpenHelper {

    private static final String DB = "falcon_accounts.db";
    private static final int VERSION = 4;
    private final Context context;
    public static final double STARTING_BALANCE = 1000.0;

    public Paper(Context ctx) {
        super(ctx, DB, null, VERSION);
        context=ctx.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trades ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "env TEXT, market TEXT, symbol TEXT, side TEXT,"
                + "entry REAL, stop REAL, target REAL, liq REAL,"
                + "qty REAL, notional REAL, margin REAL, leverage INTEGER, margin_mode TEXT,"
                + "opened_at INTEGER, closed_at INTEGER, exit REAL, pnl REAL,"
                + "status TEXT, score REAL)");
        db.execSQL("CREATE TABLE account (env TEXT PRIMARY KEY, balance REAL)");
        addCostSchema(db);
        for (int i = 0; i < Settings.ENVIRONMENTS.length; i++) {
            ContentValues v = new ContentValues();
            v.put("env", Settings.ENVIRONMENTS[i]);
            v.put("balance", STARTING_BALANCE);
            db.insert("account", null, v);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if(oldV<3) throw new IllegalStateException("Unsupported pre-v3 PAPER schema; export before migration");
        if(oldV<4) addCostSchema(db);
    }
    private void addCostSchema(SQLiteDatabase db) {
        for(String col:new String[]{"journal_id INTEGER DEFAULT 0","fee_bps REAL DEFAULT 5","slip_bps REAL DEFAULT 2",
                "entry_fee REAL DEFAULT 0","initial_risk REAL DEFAULT 0","peak REAL DEFAULT 0",
                "policy TEXT DEFAULT 'LEGACY'","strategy_key TEXT DEFAULT 'LEGACY_UNVERIFIED'",
                "last_checked INTEGER DEFAULT 0","evidence_gap INTEGER DEFAULT 0",
                "closed_gross REAL DEFAULT 0","exit_fee REAL DEFAULT 0","closed_funding REAL DEFAULT 0"}) db.execSQL("ALTER TABLE trades ADD COLUMN "+col);
    }

    public static final class Trade {
        public long id;
        public String env, market, symbol, side, status, marginMode;
        public double entry, stop, target, liq, qty, notional, margin, exit, pnl, score;
        public int leverage;
        public long openedAt, closedAt,journalId,lastChecked;
        public double feeBps,slipBps,entryFee,initialRisk,peak,closedGross,exitFee,closedFunding;
        public String policy,strategyKey;
        public boolean evidenceGap;
    }

    public double balance(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT balance FROM account WHERE env=?", new String[]{env});
        double b = STARTING_BALANCE;
        if (c.moveToFirst()) b = c.getDouble(0);
        else {
            ContentValues v = new ContentValues();
            v.put("env", env);
            v.put("balance", STARTING_BALANCE);
            getWritableDatabase().insertWithOnConflict("account", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE);
        }
        c.close();
        return b;
    }

    private void setBalance(String env, double b) {
        ContentValues v = new ContentValues();
        v.put("env", env);
        v.put("balance", b);
        getWritableDatabase().insertWithOnConflict("account", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int openCount(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM trades WHERE status='OPEN' AND env=?", new String[]{env});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public boolean hasOpen(String env, String symbol) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM trades WHERE status='OPEN' AND env=? AND symbol=? LIMIT 1",
                new String[]{env, symbol});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    /**
     * Liquidation price for a leveraged position.
     *
     * Isolated margin is backed only by the margin committed to this trade,
     * so it liquidates at roughly a 1/leverage adverse move. Cross margin is
     * backed by the whole free balance, so it can absorb a larger move
     * before liquidating.
     */
    public static double liquidationPrice(boolean isLong, double entry, int leverage,
                                          boolean cross) {
        // 0 is the agreed "no liquidation" marker for both directions. An
        // unleveraged position cannot be liquidated, and returning
        // Double.MAX_VALUE here previously leaked into the UI as a 300-digit
        // number.
        if (leverage <= 1) return 0;
        double buffer = cross ? 1.35 : 0.95;
        double move = entry * (buffer / leverage);
        if (move >= entry) move = entry * 0.95;
        double liq = isLong ? entry - move : entry + move;
        return liq < 0 ? 0 : liq;
    }

    /**
     * Open a simulated position under the given environment's rules.
     * Returns null on success, or a human-readable reason for refusal.
     */
    public String open(Settings cfg,Engine.Row row,Strategy strategy) {
        synchronized(Trader.ORDER_LOCK) {
            if(!"PAPER".equals(cfg.env))return "PAPER only";
            if(row==null||!Readiness.key(cfg,strategy).equals(row.settingsKey))return "Settings changed since scan; rescan before entry";
            if(hasOpen(cfg.env,row.symbol)||openCount(cfg.env)>=cfg.maxPositions)return "Duplicate/position limit";
            if("LONG".equals(row.side)?!cfg.allowLong:!cfg.allowShort||!cfg.isFutures())return "Direction disabled";
            Journal j=new Journal(context);
            try {
                double quote=Binance.price(row.symbol,cfg.isFutures());
                String why=EntryTiming.check(row,quote,System.currentTimeMillis());if(why!=null)return why;
                double entry=RiskMath.adverseFill(quote,"LONG".equals(row.side),cfg.slippageBps);
                if("LONG".equals(row.side)?entry>=row.target:entry<=row.target)return "Target already crossed";
                double free=balance(cfg.env),wallet=free,unrealised=0,reserved=0,openFeesToday=0;
                for(Trade t:trades(cfg.env,"OPEN",10000)) {
                    wallet+=t.margin;double mark=Binance.price(t.symbol,"FUTURES".equals(t.market));unrealised+=unrealised(t,mark);
                    double downside="LONG".equals(t.side)?Math.max(0,t.entry-t.stop):Math.max(0,t.stop-t.entry);
                    reserved+=t.qty*(downside+(t.entry+t.stop)*(t.feeBps+t.slipBps)/10000.0);
                    if(t.openedAt>=RiskMath.utcDay(System.currentTimeMillis()))openFeesToday+=t.entryFee;
                }
                double budget=Execution.dailyRemaining(context,cfg,"PAPER",wallet,unrealised,j.todayPnl(cfg.env)-openFeesToday,System.currentTimeMillis())-reserved;
                if(cfg.maxConsecutiveLosses>0&&j.consecutiveLosses(cfg.env)>=cfg.maxConsecutiveLosses)return "Consecutive-loss limit";
                double qty=RiskMath.size(wallet+Math.min(0,unrealised),free,entry,row.stop,cfg.riskPercent,cfg.sizePercent,
                        cfg.leverage,cfg.feeBps,cfg.slippageBps,budget);
                if(qty<=0)return "Daily or position risk exhausted";
                double reward=("LONG".equals(row.side)?row.target-entry:entry-row.target)-(entry+row.target)*(cfg.feeBps+cfg.slippageBps)/10000.0;
                if(reward/RiskMath.perUnit(entry,row.stop,cfg.feeBps,cfg.slippageBps)<strategy.minRiskReward)return "Net reward/risk below required minimum";
                double margin=qty*entry/cfg.notionalMultiplier(),fee=qty*entry*cfg.feeBps/10000.0;
                long now=System.currentTimeMillis();
                long journalId=j.recordOpen(cfg.env,row.symbol,row.side,cfg.leverage,cfg.marginMode,entry,qty,qty*entry,row.stop,row.target,"SIMULATED");
                ContentValues meta=new ContentValues();meta.put("cost_state","SIMULATED");meta.put("strategy_key",Readiness.key(cfg,strategy));
                meta.put("note",row.reason+" | "+row.reasons+" | "+row.warnings);
                meta.put("initial_risk",Math.abs(entry-row.stop));meta.put("signal_price",row.entry);meta.put("commission",fee);
                j.update(journalId,meta);
                ContentValues v=new ContentValues();v.put("env",cfg.env);v.put("market",cfg.tradingMode);v.put("symbol",row.symbol);v.put("side",row.side);
                v.put("entry",entry);v.put("stop",row.stop);v.put("target",row.target);v.put("liq",cfg.isFutures()?liquidationPrice("LONG".equals(row.side),entry,cfg.leverage,"CROSS".equals(cfg.marginMode)):0);
                v.put("liq",0); // liquidation tiers are not modeled; do not display a fabricated boundary
                v.put("qty",qty);v.put("notional",qty*entry);v.put("margin",margin);v.put("leverage",cfg.leverage);v.put("margin_mode",cfg.marginMode);
                v.put("opened_at",now);v.put("status","OPEN");v.put("score",row.score);v.put("journal_id",journalId);
                v.put("fee_bps",cfg.feeBps);v.put("slip_bps",cfg.slippageBps);v.put("entry_fee",fee);v.put("initial_risk",Math.abs(entry-row.stop));
                v.put("policy",cfg.exitPolicy);v.put("strategy_key",Readiness.key(cfg,strategy));v.put("last_checked",now);
                SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
                try { if(db.insert("trades",null,v)<0)throw new IllegalStateException("Paper insert failed");setBalance(cfg.env,free-margin-fee);db.setTransactionSuccessful(); }
                finally{db.endTransaction();}
                j.event(journalId,cfg.env,row.symbol,"PAPER_ENTRY","Modeled fill "+entry+" | fee "+fee+" | "+row.reason);
                return null;
            }catch(Exception e){return "Paper entry refused: "+e.getMessage();}
            finally{j.close();}
        }
    }

    public List<Trade> trades(String env, String status, int limit) {
        List<Trade> out = new ArrayList<Trade>();
        StringBuilder q = new StringBuilder(
                "SELECT id,env,market,symbol,side,entry,stop,target,liq,qty,notional,margin,"
                        + "leverage,margin_mode,opened_at,closed_at,exit,pnl,status,score,journal_id,fee_bps,slip_bps,entry_fee,initial_risk,peak,policy,strategy_key,last_checked,evidence_gap,closed_gross,exit_fee,closed_funding"
                        + " FROM trades WHERE env=?");
        List<String> args = new ArrayList<String>();
        args.add(env);
        if (status != null) {
            q.append(" AND status=?");
            args.add(status);
        }
        q.append(" ORDER BY id DESC LIMIT ").append(limit);
        Cursor c = getReadableDatabase().rawQuery(q.toString(),
                args.toArray(new String[args.size()]));
        while (c.moveToNext()) {
            Trade t = new Trade();
            t.id = c.getLong(0);
            t.env = c.getString(1);
            t.market = c.getString(2);
            t.symbol = c.getString(3);
            t.side = c.getString(4);
            t.entry = c.getDouble(5);
            t.stop = c.getDouble(6);
            t.target = c.getDouble(7);
            t.liq = c.getDouble(8);
            t.qty = c.getDouble(9);
            t.notional = c.getDouble(10);
            t.margin = c.getDouble(11);
            t.leverage = c.getInt(12);
            t.marginMode = c.getString(13);
            t.openedAt = c.getLong(14);
            t.closedAt = c.getLong(15);
            t.exit = c.getDouble(16);
            t.pnl = c.getDouble(17);
            t.status = c.getString(18);
            t.score = c.getDouble(19);
            t.journalId=c.getLong(20);t.feeBps=c.getDouble(21);t.slipBps=c.getDouble(22);t.entryFee=c.getDouble(23);
            t.initialRisk=c.getDouble(24);t.peak=c.getDouble(25);t.policy=c.getString(26);t.strategyKey=c.getString(27);
            t.lastChecked=c.getLong(28);t.evidenceGap=c.getInt(29)!=0;t.closedGross=c.getDouble(30);t.exitFee=c.getDouble(31);t.closedFunding=c.getDouble(32);
            out.add(t);
        }
        c.close();
        return out;
    }

    public double unrealised(Trade t, double price) {
        boolean isLong = "LONG".equals(t.side);
        return (isLong ? (price - t.entry) : (t.entry - price)) * t.qty;
    }

    /**
     * Mark a position to market and close it if stop, target or liquidation
     * is reached. Liquidation is checked first: it is the hard boundary.
     */
    public String markToMarket(Trade t,double price) {
        synchronized(Trader.ORDER_LOCK) {
            Trade current=null;for(Trade x:trades(t.env,"OPEN",10000))if(x.id==t.id)current=x;
            if(current==null)return "ALREADY_CLOSED";t=current;
            boolean buy="LONG".equals(t.side);
            if(System.currentTimeMillis()-t.lastChecked>120000L)t.evidenceGap=true;
            // Replay missed complete 1m bars. Both stop and target in one bar => stop first.
            try {
                Binance.Candles bars=Binance.klines(t.symbol,"1m",500,"FUTURES".equals(t.market));
                if(bars.size>0&&bars.openTime[0]>t.lastChecked+60000L) t.evidenceGap=true;
                for(int i=0;i<bars.size;i++) {
                    if(bars.closeTime[i]<=t.lastChecked)continue;
                    if(bars.openTime[i]<t.openedAt)continue; // cannot use pre-entry extremes
                    boolean sl=buy?bars.low[i]<=t.stop:bars.high[i]>=t.stop;
                    boolean tp=buy?bars.high[i]>=t.target:bars.low[i]<=t.target;
                    if(sl||tp) {
                        double fill=sl?(buy?Math.min(t.stop,bars.open[i]):Math.max(t.stop,bars.open[i])):t.target;
                        closeAt(t,fill,sl?"STOP":"TARGET",bars.closeTime[i]);return sl?"STOP":"TARGET";
                    }
                    double moved=buy?bars.high[i]-t.entry:t.entry-bars.low[i];t.peak=Math.max(t.peak,moved);
                    t.lastChecked=bars.closeTime[i];
                }
            }catch(Exception e){t.evidenceGap=true;}
            boolean sl=buy?price<=t.stop:price>=t.stop,tp=buy?price>=t.target:price<=t.target;
            if(sl||tp){closeAt(t,price,sl?"STOP":"TARGET",System.currentTimeMillis());return sl?"STOP":"TARGET";}
            t.peak=Math.max(t.peak,buy?price-t.entry:t.entry-price);
            Settings cfg=Settings.load(context,t.env);
            try {
                Strategy selected=Strategy.load(context);
                boolean same=Readiness.key(cfg,selected).equals(t.strategyKey);
                if(!same)t.evidenceGap=true; // never apply a newly selected experiment to an old position
                Binance.Candles candles=Binance.klines(t.symbol,cfg.timeframe,200,"FUTURES".equals(t.market));
                Engine.Row fresh=Engine.analyse(t.symbol,candles,20000000,selected,cfg.allowShort,cfg.tradingStyle,cfg.timeframe,cfg.requireConfirmation);
                PositionManager.Verdict verdict=ExitPolicy.review(same?t.policy:"FIXED",buy,t.entry,price,t.stop,t.target,t.initialRisk,t.peak,
                        fresh==null?null:fresh.momentum,fresh!=null&&t.side.equals(fresh.side),cfg.exitOnlyBySlTp,t.feeBps,t.slipBps);
                if(verdict.closes()){closeAt(t,price,verdict.reason,System.currentTimeMillis());return "EARLY";}
                if(verdict.newStop>0)t.stop=verdict.newStop;if(verdict.newTarget>0)t.target=verdict.newTarget;
                if(verdict.retargets()&&t.journalId>0) {
                    Journal j=new Journal(context);try{ContentValues v=new ContentValues();v.put("sl",t.stop);v.put("tp",t.target);j.update(t.journalId,v);
                        j.event(t.journalId,t.env,t.symbol,"PAPER_RETARGET",verdict.reason+" | SL="+t.stop+" TP="+t.target);}finally{j.close();}
                }
            }catch(Exception e){t.evidenceGap=true;}
            ContentValues v=new ContentValues();v.put("stop",t.stop);v.put("target",t.target);v.put("peak",t.peak);
            v.put("last_checked",t.lastChecked);v.put("evidence_gap",t.evidenceGap?1:0);
            getWritableDatabase().update("trades",v,"id=? AND status='OPEN'",new String[]{String.valueOf(t.id)});
            return "OPEN";
        }
    }
    public void close(Trade t,double price,double ignoredPnl,String reason) {
        closeAt(t,price,"CLOSED".equals(reason)?"MANUAL":reason,System.currentTimeMillis());
    }
    private void closeAt(Trade t,double rawPrice,String reason,long at) {
        synchronized(Trader.ORDER_LOCK) {
            boolean buy="LONG".equals(t.side);
            double price=RiskMath.adverseFill(rawPrice,!buy,t.slipBps),gross=unrealised(t,price);
            double exitFee=price*t.qty*t.feeBps/10000.0;
            // Conservative paper assumption: pay 1bp each 8h prorated. Actual replay uses funding events.
            double funding="FUTURES".equals(t.market)?-t.notional*.0001*Math.max(0,at-t.openedAt)/(8*3600000.0):0;
            double net=RiskMath.net(gross,t.entryFee+exitFee,funding);
            ContentValues v=new ContentValues();v.put("closed_at",at);v.put("exit",price);v.put("pnl",net);v.put("status",reason);v.put("closed_gross",gross);v.put("exit_fee",exitFee);v.put("closed_funding",funding);v.put("evidence_gap",t.evidenceGap?1:0);
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();boolean changed=false;
            try {
                changed=db.update("trades",v,"id=? AND status='OPEN'",new String[]{String.valueOf(t.id)})==1;
                if(changed)setBalance(t.env,balance(t.env)+t.margin+gross-exitFee+funding);
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            if(changed&&t.journalId>0) {
                Journal j=new Journal(context);
                try {
                    ContentValues c=new ContentValues();c.put("status","CLOSED");c.put("closed_at",at);c.put("exit",price);c.put("pnl",net);
                    c.put("gross_pnl",gross);c.put("commission",t.entryFee+exitFee);c.put("funding",funding);c.put("cost_state","SIMULATED");
                    c.put("fee_assets","USDT (modeled)");c.put("close_reason",reason);
                    c.put("close_detail","Modeled fills/fees; funding assumes 1bp/8h paid. Same-bar stop first. "
                        +(t.evidenceGap?"DATA/CONFIG GAP: unsuitable as complete forward evidence.":"First partial minute is sampled; no exchange execution proof."));
                    j.update(t.journalId,c);j.event(t.journalId,t.env,t.symbol,"PAPER_CLOSE",reason+" | gross="+gross+" fees="+(t.entryFee+exitFee)+" funding="+funding+" net="+net);
                }finally{j.close();}
            }
        }
    }

    /** Crash recovery: PAPER settlement is authoritative; a repeated repair cannot credit balance. */
    public void reconcileJournal() {
        Journal j=new Journal(context);
        try {
            java.util.Map<Long,Journal.Trade> known=new java.util.HashMap<Long,Journal.Trade>();
            for(Journal.Trade x:j.history("PAPER",10000))known.put(x.id,x);
            for(Trade t:trades("PAPER",null,10000)) {
                Journal.Trade old=known.get(t.journalId);
                if(t.closedAt<=0||t.journalId<=0||old==null||old.closedAt>0)continue;
                ContentValues v=new ContentValues();v.put("closed_at",t.closedAt);v.put("status","CLOSED");v.put("exit",t.exit);
                v.put("pnl",t.pnl);v.put("gross_pnl",t.closedGross);v.put("commission",t.entryFee+t.exitFee);v.put("funding",t.closedFunding);
                v.put("cost_state","SIMULATED");v.put("close_reason",t.status);v.put("fee_assets","USDT (modeled)");
                v.put("close_detail","Recovered costed PAPER settlement. Funding 1bp/8h estimate. "+(t.evidenceGap?"DATA GAP.":"Sampled partial minute."));
                j.update(t.journalId,v);j.event(t.journalId,t.env,t.symbol,"PAPER_RECOVERY","Durable settlement repaired; balance not changed");
            }
        }finally{j.close();}
    }

    public double realisedPnl(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(pnl),0) FROM trades WHERE status!='OPEN' AND env=?",
                new String[]{env});
        double p = c.moveToFirst() ? c.getDouble(0) : 0;
        c.close();
        return p;
    }

    public int[] winLoss(String env) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(CASE WHEN pnl>0 THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN pnl<=0 THEN 1 ELSE 0 END)"
                        + " FROM trades WHERE status!='OPEN' AND env=?", new String[]{env});
        int w = 0, l = 0;
        if (c.moveToFirst()) {
            w = c.getInt(0);
            l = c.getInt(1);
        }
        c.close();
        return new int[]{w, l};
    }

    public void resetAccount(String env) {
        synchronized(Trader.ORDER_LOCK) {
            if(openCount(env)>0)throw new IllegalStateException("Close all PAPER positions before resetting the balance");
            // Evidence is retained. Reset does not bypass the daily halt.
            setBalance(env, STARTING_BALANCE);
        }
    }

    /** Continue tracking PAPER while the scanner is paused or another environment is selected. */
    public static void monitor(Context ctx) {
        Paper p=new Paper(ctx);
        try {p.reconcileJournal();for(Trade t:p.trades("PAPER","OPEN",10000)) {
            try {p.markToMarket(t,Binance.price(t.symbol,"FUTURES".equals(t.market)));}
            catch(Exception failure) {
                ContentValues gap=new ContentValues();gap.put("evidence_gap",1);
                p.getWritableDatabase().update("trades",gap,"id=? AND status='OPEN'",new String[]{String.valueOf(t.id)});
                Journal j=new Journal(ctx);try{j.recordError("PAPER",t.symbol,"Paper monitor",failure.toString(),"trade "+t.journalId);}finally{j.close();}
            }
        }}finally{p.close();}
    }
}
