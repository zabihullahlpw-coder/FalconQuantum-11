package com.falcon.quantum;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reconciles one exclusive symbol/side lifecycle from executions, with explicit uncertainty. */
public final class TradeAccounting {
    private TradeAccounting() {}
    public static final class Result {
        public double entry,exit,qty,gross,commission,funding,net;
        public long firstAt,lastAt;
        public boolean complete=true;
        public String reason="EXTERNAL_UNKNOWN",detail="",assets="";
        public final Set<Long> exitOrders=new LinkedHashSet<Long>();
        public void uncertain(String why) { complete=false; detail+=(detail.length()>0?" | ":"")+why; }
    }
    public static Result calculate(Journal.Trade t,JSONArray fills,JSONArray income) throws Exception {
        Result r=new Result();double eq=0,ev=0,xq=0,xv=0;
        Set<Long> seen=new LinkedHashSet<Long>();JSONObject assets=new JSONObject();
        long earliest=Long.MAX_VALUE;
        // Locate the actual entry first, regardless of page/order arrival order.
        for(int i=0;i<fills.length();i++) {
            JSONObject f=fills.getJSONObject(i);
            if(f.optLong("orderId")==t.entryOrderId) earliest=Math.min(earliest,f.getLong("time"));
        }
        if(earliest==Long.MAX_VALUE) { r.uncertain("Entry fills unavailable");return r; }
        String entrySide="LONG".equals(t.side)?"BUY":"SELL";
        for(int i=0;i<fills.length();i++) {
            JSONObject f=fills.getJSONObject(i);long at=f.getLong("time"),id=f.getLong("id");
            if(at<earliest||!seen.add(id)) continue;
            if(!t.symbol.equals(f.optString("symbol"))) continue;
            if(!t.positionSide.equals(f.optString("positionSide","BOTH"))) {
                r.uncertain("Other hedge-side activity prevents exact funding allocation");continue;
            }
            boolean entry=f.optLong("orderId")==t.entryOrderId;
            if(!entry && entrySide.equals(f.optString("side"))) {
                r.uncertain("External scale-in/re-entry in lifecycle window");continue;
            }
            double q=f.getDouble("qty"),p=f.getDouble("price");
            if(entry) { eq+=q;ev+=q*p; }
            else { xq+=q;xv+=q*p;r.gross+=f.getDouble("realizedPnl");r.exitOrders.add(f.getLong("orderId")); }
            String asset=f.getString("commissionAsset");double fee=f.getDouble("commission");
            assets.put(asset,assets.optDouble(asset,0)+fee);
            if("USDT".equals(asset)) r.commission+=fee;
            else if(Math.abs(fee)>1e-12) r.uncertain("Commission in "+asset+" needs historical conversion");
            r.firstAt=r.firstAt==0?at:Math.min(r.firstAt,at);r.lastAt=Math.max(r.lastAt,at);
        }
        r.assets=assets.toString();r.qty=eq;r.entry=eq>0?ev/eq:0;r.exit=xq>0?xv/xq:0;
        if(eq<=0||xq<=0||Math.abs(eq-xq)>Math.max(1e-9,eq*1e-6)
                ||Math.abs(eq-t.qty)>Math.max(1e-9,eq*1e-6)) r.uncertain("Entry/exit quantities do not reconcile");
        Set<String> fundingIds=new LinkedHashSet<String>();
        for(int i=0;i<income.length();i++) {
            JSONObject f=income.getJSONObject(i);long at=f.optLong("time");
            if(!"FUNDING_FEE".equals(f.optString("incomeType"))||at<r.firstAt||at>r.lastAt) continue;
            if(!fundingIds.add(f.optString("tranId"))) continue;
            if(!"USDT".equals(f.optString("asset"))) r.uncertain("Non-USDT funding");
            else r.funding+=f.getDouble("income");
        }
        r.net=RiskMath.net(r.gross,r.commission,r.funding);
        return r;
    }
}
