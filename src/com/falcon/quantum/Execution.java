package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.ContentValues;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;

/** One serialized entry/exit path shared by foreground and background workers. */
public final class Execution {
    private Execution() {}
    private static SharedPreferences state(Context c) { return c.getSharedPreferences("falcon_execution",Context.MODE_PRIVATE); }
    public static String open(Context ctx,Settings cfg,Engine.Row row,Strategy strategy) {
        synchronized(Trader.ORDER_LOCK) {
            String blocked=Trader.entryBlockReason(ctx,cfg);
            if(blocked!=null) return blocked;
            if(row==null||!Readiness.key(cfg,strategy).equals(row.settingsKey))return "Settings changed since scan; rescan before entry";
            Journal j=new Journal(ctx);long id=0;Trader trader=null;
            try {
                cfg.validated();trader=Trader.forEnvironment(ctx,cfg);
                if("LONG".equals(row.side)?!cfg.allowLong:!cfg.allowShort)return "Trade direction disabled";
                if(trader==null) {
                    String refused=Trader.entryBlockReason(ctx,cfg);
                    return refused==null?"Trading permission changed; retry after reviewing ACCOUNT":refused;
                }
                String cooldown=cfg.env+":"+trader.accountKey()+":"+row.symbol;
                if(trader.serverNow()-state(ctx).getLong(cooldown,0)<60000L) return "Entry cooldown after last attempt";
                List<Trader.Position> held=trader.positions();
                reconcile(ctx,trader,cfg,held);
                for(Journal.Trade t:j.history(cfg.env,10000))
                    if(trader.accountKey().equals(t.accountKey)&&t.closedAt==0&&!"ENTRY_REJECTED".equals(t.status))
                        if("ENTRY_PENDING".equals(t.status)||"CLOSE_PENDING".equals(t.status)||"RECONCILE_PENDING".equals(t.status))
                            return "Unresolved order/trade "+t.id+"; reconcile before entering again";
                if(held.size()>=cfg.maxPositions) return "Position limit reached";
                for(Trader.Position p:held) if(row.symbol.equals(p.symbol)) return "Already holding this symbol";
                cfg.feeBps=Math.max(cfg.feeBps,trader.takerFeeBps(row.symbol));
                double quote=trader.executablePrice(row.symbol,"LONG".equals(row.side));
                String why=EntryTiming.check(row,quote,trader.serverNow());if(why!=null) return why;
                double stop=Double.parseDouble(trader.quantisePrice(row.symbol,row.stop));
                double target=Double.parseDouble(trader.quantisePrice(row.symbol,row.target));
                if("LONG".equals(row.side)?stop>=quote||target<=quote:stop<=quote||target>=quote) return "Rounded stop/target invalid";
                double wallet=trader.walletBalance(),upnl=trader.unrealisedTotal(),free=trader.usdtBalance();
                double daily=trader.dailyNet();
                double budget=dailyRemaining(ctx,cfg,trader.accountKey(),wallet,upnl,daily,trader.serverNow());
                for(Trader.Position p:held) {
                    Journal.Trade t=j.active(cfg.env,p.symbol,p.side);
                    if(t==null||!trader.accountKey().equals(t.accountKey)) return "Open external/legacy position has unquantified risk";
                    boolean[] guarded=trader.protectionPresent(p,t.sl,t.tp);
                    if(!guarded[0]) return "An existing position has no verified stop";
                    // Reserve remaining downside to the stop including costs, not favorable unrealised gains.
                    double downside="LONG".equals(p.side)?Math.max(0,p.entry-t.sl):Math.max(0,t.sl-p.entry);
                    budget-=p.qty*(downside+(p.entry+t.sl)*(cfg.feeBps+cfg.slippageBps)/10000.0);
                }
                if(cfg.maxConsecutiveLosses>0&&j.consecutiveLosses(cfg.env)>=cfg.maxConsecutiveLosses) return "Consecutive-loss limit reached";
                double qty=RiskMath.size(wallet+Math.min(0,upnl),free,quote,stop,cfg.riskPercent,cfg.sizePercent,
                        cfg.leverage,cfg.feeBps,cfg.slippageBps,budget);
                if(qty<=0) return "Daily/open risk budget exhausted";
                qty=Double.parseDouble(trader.quantise(row.symbol,qty));
                why=trader.validateOrder(row.symbol,qty,quote);if(why!=null)return why+" Skip this trade; do not increase risk to meet a minimum.";
                double costs=(quote+target)*(cfg.feeBps+cfg.slippageBps)/10000.0;
                double reward=("LONG".equals(row.side)?target-quote:quote-target)-costs;
                if(reward/RiskMath.perUnit(quote,stop,cfg.feeBps,cfg.slippageBps)<strategy.minRiskReward)
                    return "Reward/risk after estimated costs is below strategy minimum";
                if(!trader.probeStopSupport(row.symbol)) return "Cannot query conditional orders; no entry";
                trader.setMarginMode(row.symbol,cfg.marginMode);trader.setLeverage(row.symbol,cfg.leverage);
                why=EntryTiming.check(row,trader.executablePrice(row.symbol,"LONG".equals(row.side)),trader.serverNow());
                if(why!=null)return why;
                blocked=Trader.entryBlockReason(ctx,cfg);
                if(blocked!=null)return blocked;
                String client="fq_entry_"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,22);
                id=j.recordIntent(cfg,row,qty,client,trader.accountKey(),Readiness.key(cfg,strategy));
                state(ctx).edit().putLong(cooldown,trader.serverNow()).commit();
                JSONObject order=trader.enter(row.symbol,"LONG".equals(row.side),qty,client);
                j.event(id,cfg.env,row.symbol,"ENTRY_RESPONSE",order.toString());
                double filled=order.optDouble("executedQty",0),price=order.optDouble("avgPrice",0);
                if(filled<=0||price<=0||!"FILLED".equals(order.optString("status")))
                    throw new IllegalStateException("Entry not fully filled; keep ENTRY_PENDING and reconcile");
                ContentValues v=new ContentValues();v.put("entry_order_id",order.getLong("orderId"));
                v.put("position_side",order.optString("positionSide","BOTH"));v.put("entry",price);v.put("qty",filled);
                v.put("notional",filled*price);v.put("status","OPEN");v.put("sl",stop);v.put("tp",target);
                v.put("initial_risk",Math.abs(price-stop));j.update(id,v);
                ctx.getSharedPreferences("falcon_prefs",Context.MODE_PRIVATE).edit()
                    .putString("plan_"+cfg.env+"_"+row.symbol,stop+"|"+target).commit();
                String guard=trader.attachStopAndTarget(row.symbol,"LONG".equals(row.side),stop,target,filled,price);
                if(filled*RiskMath.perUnit(price,stop,cfg.feeBps,cfg.slippageBps)>
                        Math.min((wallet+Math.min(0,upnl))*cfg.riskPercent/100.0,budget)*1.01)
                    guard="FILL_RISK_EXCEEDED: actual fill breached the authorised risk budget";
                if(guard!=null) {
                    j.event(id,cfg.env,row.symbol,"PROTECTION_FAILURE",guard);
                    Trader.Position p=find(trader.positions(),row.symbol,row.side);
                    if(p!=null) close(ctx,trader,cfg,p,"PROTECTION_FAILURE",guard);
                    return "Entry filled but protection/risk failed: "+guard+". Close submitted; see HISTORY for confirmed fills.";
                }
                return "Opened "+row.symbol+" at actual fill "+price+" | qty "+filled+" | SL/TP verified";
            } catch(Trader.EntryNotSubmittedException e) {
                if(id>0) {
                    ContentValues v=new ContentValues();v.put("status","ENTRY_REJECTED");j.update(id,v);
                    j.event(id,cfg.env,row.symbol,"ENTRY_NOT_SUBMITTED",e.getMessage());
                }
                return "Entry not submitted: "+e.getMessage();
            } catch(Exception e) {
                if(id>0) j.event(id,cfg.env,row.symbol,"ENTRY_ERROR",String.valueOf(e));
                j.recordError(cfg.env,row.symbol,"Execution",String.valueOf(e),"No blind retries; inspect pending trade");
                return "Entry requires attention: "+e.getMessage();
            } finally { j.close(); }
        }
    }
    public static double dailyRemaining(Context ctx,Settings cfg,String account,double wallet,double unrealised,double dayNet,long now) {
        SharedPreferences p=state(ctx);String key=cfg.env+":"+account+":"+RiskMath.utcDay(now);
        double reference=Double.longBitsToDouble(p.getLong(key+":equity",0));
        if(!RiskMath.positive(reference)) {
            reference=Math.max(0,wallet-dayNet); // reconstructed wallet before today's trading P/L
            p.edit().putLong(key+":equity",Double.doubleToLongBits(reference)).commit();
        }
        // Later deposits cannot increase the recorded budget; withdrawals can lower it.
        reference=Math.min(reference,Math.max(0,wallet-dayNet));
        p.edit().putLong(key+":equity",Double.doubleToLongBits(reference)).commit();
        double limit=reference*cfg.dailyLossPercent/100.0;
        if(cfg.maxDailyLoss>0) limit=Math.min(limit,cfg.maxDailyLoss);
        double realisedRemaining=limit+Math.min(0,dayNet);
        if(realisedRemaining+Math.min(0,unrealised)<=0) p.edit().putBoolean(key+":halt",true).commit();
        if(p.getBoolean(key+":halt",false)) return 0;
        return Math.max(0,realisedRemaining);
    }
    public static Trader.Position find(List<Trader.Position> list,String symbol,String side) {
        for(Trader.Position p:list) if(symbol.equals(p.symbol)&&side.equals(p.side)) return p;return null;
    }
    public static void close(Context ctx,Trader trader,Settings cfg,Trader.Position p,String reason,String detail) throws Exception {
        synchronized(Trader.ORDER_LOCK) {
            Journal j=new Journal(ctx);
            try {
                Journal.Trade t=j.active(cfg.env,p.symbol,p.side);
                if(t!=null&&"CLOSE_PENDING".equals(t.status)) throw new IllegalStateException("Close already pending; reconcile instead of repeating");
                if(t!=null&&!trader.accountKey().equals(t.accountKey)) throw new IllegalStateException("Position belongs to another journal account; inspect before closing");
                String client="fq_close_"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,22);
                if(t!=null) {
                    ContentValues v=new ContentValues();v.put("status","CLOSE_PENDING");v.put("requested_close_reason",reason);
                    v.put("requested_close_client",client);
                    j.update(t.id,v);j.event(t.id,cfg.env,p.symbol,"CLOSE_REQUEST",reason+" | "+detail);
                }
                JSONObject order=new JSONObject(trader.closePosition(p,client));
                if(t!=null) { ContentValues v=new ContentValues();v.put("requested_close_id",order.optLong("orderId"));j.update(t.id,v);
                    j.event(t.id,cfg.env,p.symbol,"CLOSE_RESPONSE",order.toString()); }
                reconcile(ctx,trader,cfg,trader.positions());
            } catch(Exception e) { j.recordError(cfg.env,p.symbol,"Close failed/unknown",String.valueOf(e),reason+" | "+detail);throw e; }
            finally {j.close();}
        }
    }
    public static void reconcile(Context ctx,Trader trader,Settings cfg,List<Trader.Position> positions) {
        synchronized(Trader.ORDER_LOCK) {
            Journal j=new Journal(ctx);
            try { for(Journal.Trade t:j.history(cfg.env,10000)) {
                if(t.closedAt>0||"ENTRY_REJECTED".equals(t.status)||!trader.accountKey().equals(t.accountKey)) continue;
                try {
                    if(t.requestedCloseId<=0&&t.requestedCloseClient!=null&&t.requestedCloseClient.length()>0) {
                        try {
                            JSONObject recovered=trader.queryOrderClient(t.symbol,t.requestedCloseClient);
                            t.requestedCloseId=recovered.getLong("orderId");
                            ContentValues closeId=new ContentValues();closeId.put("requested_close_id",t.requestedCloseId);j.update(t.id,closeId);
                            j.event(t.id,cfg.env,t.symbol,"CLOSE_RECOVERY",recovered.toString());
                        }catch(Exception pendingClose){j.event(t.id,cfg.env,t.symbol,"CLOSE_OUTCOME_UNKNOWN",pendingClose.toString());}
                    }
                    if(t.entryOrderId<=0) {
                        JSONObject o=trader.queryOrderClient(t.symbol,t.clientId);
                        j.event(t.id,cfg.env,t.symbol,"ENTRY_RECOVERY",o.toString());
                        if(o.optDouble("executedQty",0)<=0) {
                            if("CANCELED".equals(o.optString("status"))||"REJECTED".equals(o.optString("status"))||"EXPIRED".equals(o.optString("status"))) {
                                ContentValues v=new ContentValues();v.put("status","ENTRY_REJECTED");j.update(t.id,v);
                            }
                            continue;
                        }
                        if(!"FILLED".equals(o.optString("status"))) continue;
                        ContentValues v=new ContentValues();v.put("entry_order_id",o.getLong("orderId"));v.put("qty",o.getDouble("executedQty"));
                        v.put("entry",o.getDouble("avgPrice"));v.put("position_side",o.optString("positionSide","BOTH"));v.put("status","OPEN");j.update(t.id,v);
                        t.entryOrderId=o.getLong("orderId");t.qty=o.getDouble("executedQty");t.entry=o.getDouble("avgPrice");t.positionSide=o.optString("positionSide","BOTH");
                    }
                    if(find(positions,t.symbol,t.side)!=null) continue;
                    ContentValues pending=new ContentValues();pending.put("status","RECONCILE_PENDING");j.update(t.id,pending);
                    JSONArray fills=trader.fills(t.symbol,Math.max(0,t.openedAt-60000),trader.serverNow());
                    // Only this entry order can supply entry fills; earlier round trips cannot leak in.
                    JSONArray income=trader.income(t.symbol,t.openedAt-60000,trader.serverNow());
                    TradeAccounting.Result r=TradeAccounting.calculate(t,fills,income);
                    if(find(positions,t.symbol,"LONG".equals(t.side)?"SHORT":"LONG")!=null)
                        r.uncertain("Opposite hedge position prevents exact funding allocation");
                    if(trader.serverNow()-r.lastAt<60000L) r.uncertain("Waiting 60s for exchange income settlement");
                    java.util.Set<String> causes=new java.util.LinkedHashSet<String>();
                    long stopActual=actual(trader,t.stopOrderId),targetActual=actual(trader,t.targetOrderId);
                    for(Long orderId:r.exitOrders) {
                        JSONObject order=trader.queryOrder(t.symbol,orderId.longValue());
                        j.event(t.id,cfg.env,t.symbol,"EXIT_ORDER",order.toString());
                        String type=order.optString("origType",order.optString("type",""));
                        if(orderId.longValue()==t.requestedCloseId) causes.add(t.requestedCloseReason);
                        else if(orderId.longValue()==stopActual||"STOP_MARKET".equals(type)) causes.add("EXCHANGE_STOP");
                        else if(orderId.longValue()==targetActual||"TAKE_PROFIT_MARKET".equals(type)) causes.add("EXCHANGE_TARGET");
                        else causes.add("EXTERNAL_UNKNOWN ("+type+")");
                    }
                    r.reason=causes.size()==1?causes.iterator().next():"MULTIPLE_EXITS "+causes;
                    ContentValues v=new ContentValues();v.put("gross_pnl",r.gross);v.put("commission",r.commission);v.put("funding",r.funding);
                    v.put("fee_assets",r.assets);v.put("exit",r.exit);v.put("exit_order_ids",r.exitOrders.toString());
                    v.put("close_reason",r.reason);v.put("close_detail",r.detail);v.put("cost_state",r.complete?"CONFIRMED":"UNVERIFIED");
                    if(r.complete) { v.put("pnl",r.net);v.put("closed_at",r.lastAt);v.put("status","CLOSED"); }
                    j.update(t.id,v);j.event(t.id,cfg.env,t.symbol,"FILLS",fills.toString());j.event(t.id,cfg.env,t.symbol,"INCOME",income.toString());
                    trader.cancelProtection(t.symbol);
                } catch(Exception e) { j.recordError(cfg.env,t.symbol,"Reconcile pending",String.valueOf(e),"trade "+t.id+"; no guessed P/L"); }
            } } finally {j.close();}
        }
    }
    private static long actual(Trader trader,long id) {
        if(id<=0)return -1;
        try{return trader.queryAlgo(id).optLong("actualOrderId",-1);}catch(Exception e){return -1;}
    }
}
