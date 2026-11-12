package com.falcon.quantum;
import org.json.JSONObject;

/** Exchange evidence, not an ID in local preferences, establishes protection. */
public final class ProtectionRules {
    private ProtectionRules() {}
    public static String type(JSONObject o) { return o.optString("orderType",o.optString("type","")); }
    public static boolean matches(JSONObject o,String symbol,String positionSide,boolean isLong,
                                   double qty,boolean stop,double desired,double tick) {
        if(!symbol.equals(o.optString("symbol"))) return false;
        if(!(isLong?"SELL":"BUY").equals(o.optString("side"))) return false;
        if(!positionSide.equals(o.optString("positionSide","BOTH"))) return false;
        if(!(stop?"STOP_MARKET":"TAKE_PROFIT_MARKET").equals(type(o))) return false;
        if(!"NEW".equals(o.optString("algoStatus",o.optString("status","")))) return false;
        if(!"MARK_PRICE".equals(o.optString("workingType"))) return false;
        if(o.optBoolean("priceProtect",false)) return false; // can delay trigger; not our policy
        boolean closeAll=o.optBoolean("closePosition",false);
        boolean reducing=!"BOTH".equals(positionSide)||o.optBoolean("reduceOnly",false);
        if(!closeAll && (!reducing || o.optDouble("quantity",o.optDouble("origQty",0))+1e-10<qty)) return false;
        double trigger=o.optDouble("triggerPrice",o.optDouble("stopPrice",0));
        return trigger>0 && (desired<=0 || Math.abs(trigger-desired)<=Math.max(tick*.51,1e-12));
    }
}
