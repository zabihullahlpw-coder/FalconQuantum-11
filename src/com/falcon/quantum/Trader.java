package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed Binance client for the DEMO and LIVE environments.
 *
 * DEMO talks to Binance's public testnets, which use fake funds - the right
 * place to prove the whole order path before any real money is involved.
 * LIVE talks to the real exchange and is therefore locked behind an explicit
 * arming flag that the operator has to turn on deliberately.
 *
 * PAPER never reaches this class at all; it is simulated locally.
 *
 * Deliberate omission: there is no withdrawal call anywhere in this client,
 * and none should ever be added. Trading keys should be created without
 * withdrawal permission.
 */
public final class Trader {

    public static final String SPOT_LIVE = "https://api.binance.com";
    public static final String SPOT_TEST = "https://testnet.binance.vision";
    public static final String FUT_LIVE = "https://fapi.binance.com";
    public static final String FUT_TEST = "https://demo-fapi.binance.com";

    private static final String PREFS = "falcon_keys";
    private static final int TIMEOUT = 15000;

    private final String apiKey;
    private final String apiSecret;
    private final boolean futures;
    private final boolean testnet;
    /** For the protection ledger. Null only in the probe constructor. */
    private Context appContext;
    private String environment = "";
    private boolean writeAllowed, managementOnly;
    public static final Object ORDER_LOCK = new Object();
    public long serverNow() { return System.currentTimeMillis()+timeOffset; }
    public String accountKey() {
        try {
            byte[] b=java.security.MessageDigest.getInstance("SHA-256").digest((base()+apiKey).getBytes("UTF-8"));
            StringBuilder out=new StringBuilder(); for(int i=0;i<12;i++) out.append(String.format("%02x",b[i]&255));
            return out.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void audit(String symbol,String kind,String detail) {
        if(appContext==null) return;
        Journal j=new Journal(appContext);
        try {
            long tradeId=0;int matches=0;
            for(Journal.Trade t:j.history(environment,10000))
                if(t.closedAt==0&&!"ENTRY_REJECTED".equals(t.status)&&symbol.equals(t.symbol)&&accountKey().equals(t.accountKey)) {
                    tradeId=t.id;matches++;
                }
            j.event(matches==1?tradeId:0,environment,symbol,kind,detail);
        } finally { j.close(); }
    }


    private Trader(String apiKey, String apiSecret, boolean futures, boolean testnet) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.futures = futures;
        this.testnet = testnet;
    }

    // ------------------------------------------------------------- key store

    public static void saveKeys(Context ctx, String env, String key, String secret) {
        SecureKeys.save(ctx, env, clean(key), clean(secret));
    }

    /**
     * Copying a key from the Binance site often drags in spaces, newlines or
     * zero-width characters. Any of those silently breaks the signature and
     * surfaces as a confusing -2015, so strip everything non-printable.
     */
    private static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c > ' ' && c != '\u200b' && c != '\ufeff') sb.append(c);
        }
        return sb.toString();
    }

    public static String key(Context ctx, String env) {
        return SecureKeys.load(ctx, env)[0];
    }

    public static String secret(Context ctx, String env) {
        return SecureKeys.load(ctx, env)[1];
    }

    public static boolean hasKeys(Context ctx, String env) {
        String[] pair = SecureKeys.load(ctx, env);
        return pair[0].length() > 0 && pair[1].length() > 0;
    }

    /** Safety preview: previous arming preferences cannot enable new real-money entries. */
    public static boolean liveArmed(Context ctx) {
        return false;
    }

    public static void setLiveArmed(Context ctx, boolean armed) {
        // Deliberately never persists an ON switch in the safety preview.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("live_armed").commit();
    }

    /** Shared by manual entries, foreground/background automation and client creation. */
    public static String entryBlockReason(Context ctx, Settings cfg) {
        if (cfg == null || (!"DEMO".equals(cfg.env) && !"LIVE".equals(cfg.env)))
            return "Exchange entries require DEMO or LIVE; PAPER is simulated locally";
        if (!cfg.isFutures()) return "Exchange entries require Futures; SPOT uses PAPER only";
        if ("LIVE".equals(cfg.env))
            return "LIVE entries disabled in Falcon 11.0 safety preview";
        if (!hasKeys(ctx, cfg.env)) return "No " + cfg.env + " trading credentials";
        return null;
    }

    /** A local refusal before any entry bytes are sent; not an unknown exchange outcome. */
    public static final class EntryNotSubmittedException extends IllegalStateException {
        EntryNotSubmittedException(String message) { super(message); }
    }

    /** Re-read the switch so an already-created client cannot outlive disarming. */
    void requireEntryPermission() {
        if (managementOnly || !writeAllowed)
            throw new EntryNotSubmittedException("This client cannot open positions");
        if (!futures || (!"DEMO".equals(environment) && !"LIVE".equals(environment)))
            throw new EntryNotSubmittedException("Exchange entries require DEMO or LIVE Futures");
        if ("LIVE".equals(environment))
            throw new EntryNotSubmittedException("LIVE entries disabled in Falcon 11.0 safety preview");
    }

    /**
     * Build a client for an environment, or return null when that
     * environment must not send orders.
     */
    public static Trader forEnvironment(Context ctx, Settings cfg) {
        if (entryBlockReason(ctx, cfg) != null) return null;
        boolean testnet = "DEMO".equals(cfg.env);
        Trader t = new Trader(key(ctx, cfg.env), secret(ctx, cfg.env), cfg.isFutures(), testnet);
        t.appContext = ctx.getApplicationContext();
        t.environment = cfg.env;
        t.writeAllowed=true;
        t.syncTime();
        return t;
    }

    /**
     * Client for READING an account (balance, positions). Reading cannot
     * move funds, so it does not require LIVE to be armed - the operator
     * should always be able to see the truth about their account.
     */
    public static Trader forReading(Context ctx, Settings cfg) {
        if (!"DEMO".equals(cfg.env) && !"LIVE".equals(cfg.env)) return null;
        if (!hasKeys(ctx, cfg.env)) return null;
        Trader t = new Trader(key(ctx, cfg.env), secret(ctx, cfg.env),
                cfg.isFutures(), "DEMO".equals(cfg.env));
        t.appContext = ctx.getApplicationContext();
        t.environment = cfg.env;
        t.syncTime();
        return t;
    }

    /** Risk-reducing operations remain available when new entries are disarmed. */
    public static Trader forManagement(Context ctx,Settings cfg) {
        Trader t=forReading(ctx,cfg);
        if(t!=null) { t.writeAllowed=true; t.managementOnly=true; }
        return t;
    }

    private String base() {
        if (futures) return testnet ? FUT_TEST : FUT_LIVE;
        return testnet ? SPOT_TEST : SPOT_LIVE;
    }

    // --------------------------------------------------------------- signing

    private String sign(String query) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] raw = mac.doFinal(query.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (int i = 0; i < raw.length; i++) {
            String h = Integer.toHexString(raw[i] & 0xff);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    /** Difference between exchange time and device clock, in ms. */
    private long timeOffset = 0;

    /** Public, unsigned. Removes device-clock skew as a failure cause. */
    public void syncTime() {
        try {
            String path = futures ? "/fapi/v1/time" : "/api/v3/time";
            HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            c.disconnect();
            long server = new JSONObject(sb.toString()).optLong("serverTime", 0);
            if (server > 0) timeOffset = server - System.currentTimeMillis();
        } catch (Exception ignored) {
            timeOffset = 0;
        }
    }

    private String call(String method, String path, String query) throws Exception {
        return callHost(base(), method, path, query);
    }

    /**
     * Signed request with retry for transient network faults.
     *
     * "Unable to resolve host fapi.binance.com" appeared mid-session on a
     * mobile connection. A protection order lost to a DNS blip leaves a
     * live position bare, so the network layer retries before giving up.
     */
    private String callHost(String host, String method, String path, String query) throws Exception {
        if (!"GET".equals(method)) {
            if(!writeAllowed) throw new IllegalStateException("Read-only client");
            // A timeout/503 is UNKNOWN execution status. Never replay a mutation here.
            return callHostOnce(host,method,path,query);
        }
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return callHostOnce(host, method, path, query);
            } catch (java.net.UnknownHostException e) {
                last = e;
            } catch (java.net.SocketTimeoutException e) {
                last = e;
            } catch (java.io.IOException e) {
                String m = String.valueOf(e.getMessage());
                // Only retry transport faults; a rejection is a real answer.
                if (m.contains("Unable to resolve host") || m.contains("timeout")
                        || m.contains("Connection reset")) {
                    last = e;
                } else {
                    throw e;
                }
            }
            try {
                Thread.sleep(600L * (attempt + 1));
            } catch (InterruptedException ignored) {
            }
        }
        throw last == null ? new RuntimeException("network unavailable") : last;
    }

    private String callHostOnce(String host, String method, String path, String query) throws Exception {
        return callHostOnce(host,method,path,query,false);
    }

    private String callHostOnce(String host, String method, String path, String query, boolean openingEntry) throws Exception {
        if (openingEntry) requireEntryPermission(); // Refuse before opening a connection.
        String q = query + (query.length() > 0 ? "&" : "") + "timestamp="
                + (System.currentTimeMillis() + timeOffset) + "&recvWindow=10000";
        String signed = q + "&signature=" + sign(q);
        URL url = new URL(host + path + ("GET".equals(method) ? "?" + signed : ""));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("X-MBX-APIKEY", apiKey);
        c.setRequestProperty("User-Agent", "FalconQuantum/"+Readiness.MODEL_VERSION);
        try {
            if (!"GET".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                // Last local check before opening the connection to send an entry.
                if(openingEntry) requireEntryPermission();
                OutputStream os = c.getOutputStream();
                os.write(signed.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            java.io.InputStream response=code>=400?c.getErrorStream():c.getInputStream();
            if(response==null)throw new java.io.IOException("Binance HTTP "+code+": empty response body");
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    response, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            if (code >= 400) throw new RuntimeException("Binance " + code + ": " + sb);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ----------------------------------------------------------- operations

    /** A real position as reported by the exchange. */
    public static final class Position {
        public String symbol;
        public String side;          // LONG or SHORT
        public double qty;           // absolute size in base asset
        public double entry;
        public double mark;
        public double unrealised;
        public double liquidation;   // 0 when the exchange reports none
        public int leverage = 1;
        public String marginMode = "";
        public String positionSide="BOTH";
    }

    /**
     * Open positions straight from the exchange. Spot has no concept of a
     * position, so this returns empty there and the balances view is used
     * instead.
     */
    public java.util.List<Position> positions() throws Exception {
        java.util.List<Position> out = new java.util.ArrayList<Position>();
        if (!futures) return out;
        String body = call("GET", "/fapi/v2/positionRisk", "");
        org.json.JSONArray arr = new org.json.JSONArray(body);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            double amt = o.optDouble("positionAmt", 0);
            if (Math.abs(amt) < 1e-12) continue;   // flat, not a position
            Position p = new Position();
            p.symbol = o.optString("symbol", "");
            p.side = amt > 0 ? "LONG" : "SHORT";
            p.positionSide=o.optString("positionSide","BOTH");
            p.qty = Math.abs(amt);
            p.entry = o.optDouble("entryPrice", 0);
            p.mark = o.optDouble("markPrice", 0);
            p.unrealised = o.optDouble("unRealizedProfit", 0);
            double liq = o.optDouble("liquidationPrice", 0);
            p.liquidation = (liq > 0 && !Double.isInfinite(liq)) ? liq : 0;
            p.leverage = (int) o.optDouble("leverage", 1);
            p.marginMode = o.optString("marginType", "");
            out.add(p);
        }
        return out;
    }

    /**
     * Close an open futures position with a reduce-only market order, so it
     * can never accidentally flip into the opposite direction.
     */
    public String closePosition(Position p) throws Exception {
        return closePosition(p,"fq_close_"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,22));
    }
    public String closePosition(Position p,String id) throws Exception {
        synchronized(ORDER_LOCK) {
            if(!futures) throw new IllegalStateException("Futures close only");
            // Re-read size/side; never close a cached quantity into a newly opened trade.
            Position current=null;
            for(Position q:positions()) if(q.symbol.equals(p.symbol)&&q.side.equals(p.side)) current=q;
            if(current==null) throw new IllegalStateException("Position is already flat");
            if(p.entry>0&&(Math.abs(current.entry-p.entry)>Math.max(1e-9,p.entry*1e-8)
                    ||!current.positionSide.equals(p.positionSide)))throw new IllegalStateException("Position changed since request; inspect before closing");
            String query="symbol="+current.symbol+"&side="+("LONG".equals(current.side)?"SELL":"BUY")
                +"&type=MARKET&quantity="+quantise(current.symbol,current.qty)+"&newClientOrderId="+id+"&newOrderRespType=RESULT";
            if(!"BOTH".equals(current.positionSide)) query+="&positionSide="+current.positionSide;
            else query+="&reduceOnly=true";
            audit(p.symbol,"CLOSE_INTENT",id+" | "+p.side+" | qty="+current.qty);
            return submitKnownOrder("/fapi/v1/order",query,p.symbol,id,false).toString();
        }
    }

    public String emergencyClose(String symbol,boolean isLong,double qty) {
        try {
            Position p=new Position();p.symbol=symbol;p.side=isLong?"LONG":"SHORT";p.qty=qty;
            closePosition(p); // Keep existing protection until a filled close/flat position is confirmed.
            return null;
        } catch(Exception e) { audit(symbol,"EMERGENCY_CLOSE_ERROR",String.valueOf(e));return String.valueOf(e.getMessage()); }
    }

    public static final String ALGO_ORDER_PATH="/fapi/v1/algoOrder";
    public static final String ALGO_OPEN_PATH="/fapi/v1/openAlgoOrders";
    public static final String[] VARIANT_NAMES={"CONDITIONAL closePosition STOP_MARKET / MARK_PRICE"};
    public void rememberStopSupport(boolean supported) { /* no cached acceptance claim */ }
    public Boolean stopSupport() { return null; }
    public Integer knownVariant() { return null; }
    public void rememberVariant(int index) { }
    public String algoLabel(String symbol) {
        if(appContext==null) return "unverified";
        StringBuilder b=new StringBuilder();
        for(ProtectionLedger.Record r:ProtectionLedger.all(appContext))
            if(r.id.startsWith(ledgerEnv()+"|")&&symbol.equals(ProtectionLedger.symbolOf(r.id)))
                b.append("SL ").append(r.stopId).append(" / TP ").append(r.targetId)
                 .append(" | checked ").append(Journal.when(r.verifiedAt)).append(" | ").append(r.state);
        return b.length()==0?"unverified":b.toString();
    }
    private String ledgerEnv() { return environment+":"+accountKey(); }
    private String positionKey(String symbol,boolean isLong,double entry) {
        return ProtectionLedger.positionId(ledgerEnv(),symbol,isLong?"LONG":"SHORT",entry);
    }

    /** POST once, then resolve ambiguous outcomes by the SAME durable client ID. */
    private JSONObject submitKnownOrder(String path,String query,String symbol,String clientId,boolean algo) throws Exception {
        return submitKnownOrder(path,query,symbol,clientId,algo,false);
    }

    private JSONObject submitKnownOrder(String path,String query,String symbol,String clientId,boolean algo,boolean openingEntry) throws Exception {
        try { return new JSONObject(openingEntry?callHostOnce(base(),"POST",path,query,true):call("POST",path,query)); }
        catch(EntryNotSubmittedException refused) { throw refused; }
        catch(Exception send) {
            audit(symbol,"ORDER_SUBMIT_ERROR",clientId+" | "+send.getMessage());
            try {
                JSONObject found=algo?queryAlgoClient(clientId):queryOrderClient(symbol,clientId);
                audit(symbol,"ORDER_RECOVERED",found.toString()); return found;
            } catch(Exception lookup) {
                throw new IllegalStateException("Order outcome requires reconciliation for "+clientId
                    +". No retry sent. Submit: "+send.getMessage()+" | Query: "+lookup.getMessage());
            }
        }
    }
    public JSONObject queryOrderClient(String symbol,String id) throws Exception {
        return new JSONObject(call("GET","/fapi/v1/order","symbol="+symbol+"&origClientOrderId="+id));
    }
    public JSONObject queryOrder(String symbol,long id) throws Exception {
        return new JSONObject(call("GET","/fapi/v1/order","symbol="+symbol+"&orderId="+id));
    }
    public JSONObject queryAlgo(long id) throws Exception {
        return new JSONObject(call("GET",ALGO_ORDER_PATH,"algoId="+id));
    }
    private JSONObject queryAlgoClient(String id) throws Exception {
        return new JSONObject(call("GET",ALGO_ORDER_PATH,"clientAlgoId="+id));
    }
    public org.json.JSONArray openAlgoOrders(String symbol) throws Exception {
        String body=call("GET",ALGO_OPEN_PATH,"symbol="+symbol);
        if(body.trim().startsWith("[")) return new org.json.JSONArray(body);
        JSONObject w=new JSONObject(body);
        org.json.JSONArray a=w.optJSONArray("orders");
        if(a==null) a=w.optJSONArray("data");
        if(a==null) throw new IllegalStateException("Unrecognised algo response; protection UNKNOWN");
        return a;
    }
    public org.json.JSONArray openOrders(String symbol) throws Exception {
        return new org.json.JSONArray(call("GET","/fapi/v1/openOrders","symbol="+symbol));
    }
    public boolean probeStopSupport(String symbol) {
        // Readability is NOT proof Binance will accept a new stop. Never place live probe orders.
        try { openAlgoOrders(symbol);return true; } catch(Exception e) { audit(symbol,"PROTECTION_QUERY_ERROR",e.toString());return false; }
    }
    public static final class ProtectionReport {
        public int workingIndex=-1; public final StringBuilder log=new StringBuilder();
        public boolean anyWorked() { return workingIndex>=0; }
    }
    public ProtectionReport findWorkingProtection(String symbol,boolean side,double trigger,double qty) {
        ProtectionReport r=new ProtectionReport();
        try { r.log.append("Read-only inspection. No test orders sent.\n").append(openAlgoOrders(symbol).toString(2))
                .append("\nOrder submission/trigger behaviour must be tested on DEMO."); }
        catch(Exception e) { r.log.append("UNKNOWN: ").append(e.getMessage()); }
        return r;
    }
    public boolean[] protectionStatus(String symbol) {
        try { boolean[] p=protectionPresent(symbol);return new boolean[]{p[0],p[1],true}; }
        catch(Exception e) { audit(symbol,"PROTECTION_UNKNOWN",e.toString());return new boolean[]{false,false,false}; }
    }
    public boolean[] protectionPresent(String symbol) throws Exception {
        java.util.List<Position> held=new java.util.ArrayList<Position>();
        for(Position p:positions()) if(symbol.equals(p.symbol)) held.add(p);
        if(held.size()!=1) throw new IllegalStateException("Protection needs an exact position side");
        return protectionPresent(held.get(0),0,0);
    }
    public boolean[] protectionPresent(Position p,double sl,double tp) throws Exception {
        org.json.JSONArray a=openAlgoOrders(p.symbol);
        boolean stop=false,target=false;
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            stop|=ProtectionRules.matches(o,p.symbol,p.positionSide,"LONG".equals(p.side),p.qty,true,sl,tickFor(p.symbol));
            target|=ProtectionRules.matches(o,p.symbol,p.positionSide,"LONG".equals(p.side),p.qty,false,tp,tickFor(p.symbol));
        }
        return new boolean[]{stop,target};
    }
    public int importExistingProtection() { return 0; } // adoption occurs only after exact side/price/coverage verification
    public int tidyProtection(String symbol) { return 0; } // never delete an arbitrary user's order
    public int cancelAllConditional(String symbol) {
        try { for(Position p:positions()) if(symbol.equals(p.symbol)) return 0; }
        catch(Exception e) { return 0; }
        cancelProtection(symbol);return 1;
    }
    public void cancelOpenOrders(String symbol) throws Exception { cancelProtection(symbol); }
    public void cancelProtection(String symbol) {
        synchronized(ORDER_LOCK) {
            try {
                // Never remove any protection while either book is still open.
                for(Position p:positions()) if(symbol.equals(p.symbol)) return;
                org.json.JSONArray a=openAlgoOrders(symbol);
                for(int i=0;i<a.length();i++) {
                    JSONObject o=a.getJSONObject(i);
                    if(o.optString("clientAlgoId","").startsWith("fq_")) cancelAlgo(o.optLong("algoId"),symbol);
                }
                if(appContext!=null) for(ProtectionLedger.Record r:ProtectionLedger.all(appContext))
                    if(r.id.startsWith(ledgerEnv()+"|")&&symbol.equals(ProtectionLedger.symbolOf(r.id))) {
                        if(r.stopId>0) cancelAlgo(r.stopId,symbol);
                        if(r.targetId>0) cancelAlgo(r.targetId,symbol);
                        ProtectionLedger.remove(appContext,r.id);
                    }
            } catch(Exception e) { audit(symbol,"CLEANUP_ERROR",e.toString()); }
        }
    }
    private void cancelAlgo(long id,String symbol) throws Exception {
        try { call("DELETE",ALGO_ORDER_PATH,"algoId="+id); }
        catch(Exception e) {
            JSONObject q=queryAlgo(id);
            String state=q.optString("algoStatus","");
            if("NEW".equals(state)||state.length()==0) throw e;
        }
        audit(symbol,"PROTECTION_CANCELLED","algoId="+id);
    }
    public int reconcileProtection() {
        if(appContext==null) return 0;
        int n=0;
        for(ProtectionLedger.Record r:ProtectionLedger.all(appContext)) if(r.id.startsWith(ledgerEnv()+"|")) {
            cancelProtection(ProtectionLedger.symbolOf(r.id));n++;
        }
        return n;
    }
    public String retarget(Position p,double sl,double tp) {
        synchronized(ORDER_LOCK) {
            try {
                if(sl<=0||tp<=0) return "Missing planned levels";
                ProtectionLedger.Record old=appContext==null?null:ProtectionLedger.get(appContext,positionKey(p.symbol,"LONG".equals(p.side),p.entry));
                if(old==null||old.stopId<=0) return "Cannot replace unverified protection";
                if("LONG".equals(p.side)?sl<old.stopPrice:sl>old.stopPrice) return "Refused to loosen original protection";
                return protect(p,sl,tp,true);
            } catch(Exception e) { audit(p.symbol,"RETARGET_ERROR",e.toString());return e.getMessage(); }
        }
    }
    public String ensureProtection(Position p,double sl,double tp) {
        synchronized(ORDER_LOCK) {
            try {
                if(sl<=0||tp<=0) return "No original plan; automatic stop invention is disabled";
                return protect(p,sl,tp,false);
            } catch(Exception e) {
                audit(p.symbol,"PROTECTION_UNKNOWN",e.toString());return String.valueOf(e.getMessage());
            }
        }
    }
    private String protect(Position p,double stop,double target,boolean replacement) throws Exception {
        boolean isLong="LONG".equals(p.side);
        double sl=Double.parseDouble(quantisePrice(p.symbol,stop)),tp=Double.parseDouble(quantisePrice(p.symbol,target));
        if(sl<=0||tp<=0) return "Invalid rounded protection price";
        String id=positionKey(p.symbol,isLong,p.entry);
        ProtectionLedger.Record old=appContext==null?null:ProtectionLedger.get(appContext,id);
        if(appContext!=null) ProtectionLedger.state(appContext,id,"CHECKING",0);
        org.json.JSONArray open=openAlgoOrders(p.symbol); // any failure is UNKNOWN; no new orders
        long sid=findProtection(open,p,true,sl),tid=findProtection(open,p,false,tp);
        double mark=markPrice(p.symbol);
        if(sid==0 && (isLong?mark<=sl:mark>=sl)) return "STOP_BREACHED: original stop crossed; do not widen it";
        if(tid==0 && (isLong?mark>=tp:mark<=tp)) return "TARGET_BREACHED: original target crossed";
        if(sid==0) sid=placeVerifiedLeg(p,true,sl,id);
        // Persist the new stop before placing/cancelling any other leg.
        long retainedTarget=tid; double retainedLevel=tp;
        if(tid==0 && old!=null && old.targetId>0 && old.targetPrice>0) {
            JSONObject previous=queryAlgo(old.targetId);
            if(ProtectionRules.matches(previous,p.symbol,p.positionSide,isLong,p.qty,false,old.targetPrice,tickFor(p.symbol))) {
                retainedTarget=old.targetId;retainedLevel=old.targetPrice;
            }
        }
        if(appContext!=null) ProtectionLedger.exact(appContext,id,sid,retainedTarget,sl,retainedLevel,"STOP_VERIFIED");
        updateProtectionEvidence(p,sid,retainedTarget,sl,retainedLevel);
        if(replacement && old!=null && old.stopId>0 && old.stopId!=sid) cancelAlgo(old.stopId,p.symbol);
        if(tid==0) tid=placeVerifiedLeg(p,false,tp,id);
        if(appContext!=null) ProtectionLedger.exact(appContext,id,sid,tid,sl,tp,"VERIFIED");
        updateProtectionEvidence(p,sid,tid,sl,tp);
        if(replacement && old!=null && old.targetId>0 && old.targetId!=tid) cancelAlgo(old.targetId,p.symbol);
        return null;
    }
    private long findProtection(org.json.JSONArray a,Position p,boolean stop,double level) throws Exception {
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);
            if(ProtectionRules.matches(o,p.symbol,p.positionSide,"LONG".equals(p.side),p.qty,stop,level,tickFor(p.symbol)))
                return o.optLong("algoId",0);
        }
        return 0;
    }
    private long placeVerifiedLeg(Position p,boolean stop,double level,String posId) throws Exception {
        String pendingKey=posId+(stop?"_pending_sl":"_pending_tp");
        SharedPreferences prefs=appContext==null?null:appContext.getSharedPreferences("falcon_protection",Context.MODE_PRIVATE);
        String client=prefs==null?"":prefs.getString(pendingKey,"");
        JSONObject ack;
        if(client.length()>0) {
            // A crash/timeout may have accepted this order. Query it; never blindly resubmit.
            ack=queryAlgoClient(client);
        } else {
            client="fq_"+(stop?"sl_":"tp_")+java.util.UUID.randomUUID().toString().replace("-","").substring(0,24);
            if(prefs!=null&&!prefs.edit().putString(pendingKey,client).commit()) throw new IllegalStateException("Cannot save protection intent");
            String q="symbol="+p.symbol+"&side="+("LONG".equals(p.side)?"SELL":"BUY")
                    +"&algoType=CONDITIONAL&type="+(stop?"STOP_MARKET":"TAKE_PROFIT_MARKET")
                    +"&positionSide="+p.positionSide+"&triggerPrice="+quantisePrice(p.symbol,level)
                    +"&closePosition=true&workingType=MARK_PRICE&priceProtect=false&clientAlgoId="+client;
            audit(p.symbol,"PROTECTION_INTENT",client+" | "+(stop?"SL":"TP")+"="+level+" | "+p.positionSide);
            ack=submitKnownOrder(ALGO_ORDER_PATH,q,p.symbol,client,true);
        }
        long order=ack.optLong("algoId",0);
        if(order<=0) throw new IllegalStateException("No algoId in protection acknowledgement");
        JSONObject verified=queryAlgo(order);
        audit(p.symbol,"PROTECTION_QUERY",verified.toString());
        if(!ProtectionRules.matches(verified,p.symbol,p.positionSide,"LONG".equals(p.side),p.qty,stop,level,tickFor(p.symbol)))
            throw new IllegalStateException("Stop/target not active or mismatched: "+verified.toString());
        if(prefs!=null) prefs.edit().remove(pendingKey).commit();
        return order;
    }
    private void updateProtectionEvidence(Position p,long sid,long tid,double sl,double tp) {
        if(appContext==null) return;
        Journal j=new Journal(appContext);
        try {
            Journal.Trade t=j.active(environment,p.symbol,p.side);
            if(t==null||!accountKey().equals(t.accountKey)) return;
            android.content.ContentValues v=new android.content.ContentValues();
            v.put("stop_order_id",sid);v.put("target_order_id",tid);v.put("sl",sl);v.put("tp",tp);
            v.put("protection","SL verified "+Journal.when(serverNow())+(tid>0?" / TP verified":" / TP pending"));
            j.update(t.id,v);
            if(t.stopOrderId!=sid||t.targetOrderId!=tid||t.sl!=sl||t.tp!=tp)
                j.event(t.id,environment,p.symbol,"PROTECTION_VERIFIED","SL="+sid+" @ "+sl+" | TP="+tid+" @ "+tp);
        } finally { j.close(); }
    }

    /**
     * Realised profit for a symbol since a point in time.
     *
     * A position closed by its own stop, or closed in the Binance app, is
     * never seen by Falcon. Reading the income record is the only way to
     * complete the history rather than leaving those trades open forever.
     */
    public org.json.JSONArray income(String symbol,long start,long end) throws Exception {
        org.json.JSONArray out=new org.json.JSONArray();
        for(int page=1;page<=100;page++) {
            org.json.JSONArray a=new org.json.JSONArray(call("GET","/fapi/v1/income",
                    (symbol.length()>0?"symbol="+symbol+"&":"")+"startTime="+start+"&endTime="+end+"&limit=1000&page="+page));
            for(int i=0;i<a.length();i++) out.put(a.getJSONObject(i));
            if(a.length()<1000) return out;
        }
        throw new IllegalStateException("Income pagination incomplete");
    }
    public org.json.JSONArray fills(String symbol,long start,long end) throws Exception {
        org.json.JSONArray out=new org.json.JSONArray();
        java.util.Set<Long> seen=new java.util.HashSet<Long>();
        for(long from=start;from<=end;from+=7L*86400000L) {
            long until=Math.min(end,from+7L*86400000L-1),after=-1;
            boolean complete=false;
            for(int page=0;page<100;page++) {
                String query="symbol="+symbol+"&limit=1000"+(after<0?"&startTime="+from+"&endTime="+until:"&fromId="+after);
                org.json.JSONArray a=new org.json.JSONArray(call("GET","/fapi/v1/userTrades",query));
                long maxId=-1;boolean past=false;
                for(int i=0;i<a.length();i++) {
                    JSONObject f=a.getJSONObject(i);long id=f.getLong("id"),time=f.getLong("time");
                    maxId=Math.max(maxId,id);past|=time>until;
                    if(time>=from&&time<=until&&seen.add(id)) out.put(f);
                }
                if(a.length()<1000||past) { complete=true;break; }
                if(maxId<after) break;
                after=maxId+1;
            }
            if(!complete) throw new IllegalStateException("Fill pagination incomplete");
        }
        return out;
    }
    public double dailyNet() throws Exception {
        org.json.JSONArray a=income("",RiskMath.utcDay(serverNow()),serverNow());double net=0;
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.getJSONObject(i);String type=o.optString("incomeType");
            if(type.equals("REALIZED_PNL")||type.equals("COMMISSION")||type.equals("FUNDING_FEE")||type.equals("INSURANCE_CLEAR")) {
                if(!"USDT".equals(o.optString("asset"))) throw new IllegalStateException("Daily risk needs non-USDT conversion; entries blocked");
                net+=o.getDouble("income");
            }
        }
        return net;
    }

    /** Total wallet balance, used for display alongside free margin. */
    public double walletBalance() throws Exception {
        if (!futures) return usdtBalance();
        JSONObject o = new JSONObject(call("GET", "/fapi/v2/account", ""));
        return o.optDouble("totalWalletBalance", 0);
    }

    /** Sum of unrealised profit across all open futures positions. */
    public double unrealisedTotal() throws Exception {
        if (!futures) return 0;
        JSONObject o = new JSONObject(call("GET", "/fapi/v2/account", ""));
        return o.optDouble("totalUnrealizedProfit", 0);
    }

    /** Confirms the key works and returns the USDT balance. */
    public double usdtBalance() throws Exception {
        if (futures) {
            String body = call("GET", "/fapi/v2/account", "");
            JSONObject o = new JSONObject(body);
            return o.optDouble("availableBalance", 0);
        }
        String body = call("GET", "/api/v3/account", "");
        org.json.JSONArray arr = new JSONObject(body).optJSONArray("balances");
        if (arr == null) return 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.getJSONObject(i);
            if ("USDT".equals(b.optString("asset"))) return b.optDouble("free", 0);
        }
        return 0;
    }

    /**
     * Binance futures runs in one of two position modes and the order
     * parameters differ between them:
     *
     *   ONE-WAY  one position per symbol; positionSide is BOTH (or omitted)
     *            and closing uses reduceOnly.
     *   HEDGE    separate LONG and SHORT books; EVERY order must name its
     *            positionSide, and reduceOnly is rejected outright.
     *
     * Sending one-way parameters to a hedge-mode account fails with -4061,
     * so the mode is read from the account rather than assumed.
     */
    private static final java.util.Map<String, Boolean> HEDGE =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    public boolean hedgeMode() {
        if (!futures) return false;
        String cacheKey = (testnet ? "T" : "L") + ":" + apiKey;
        Boolean cached = HEDGE.get(cacheKey);
        if (cached != null) return cached.booleanValue();
        try {
            String body = call("GET", "/fapi/v1/positionSide/dual", "");
            boolean dual = new JSONObject(body).optBoolean("dualSidePosition", false);
            HEDGE.put(cacheKey, Boolean.valueOf(dual));
            return dual;
        } catch (Exception e) {
            throw new IllegalStateException("Could not verify position mode",e);
        }
    }

    /** Clears the cached mode, e.g. after the user changes it on Binance. */
    public static void forgetAccountModes() {
        HEDGE.clear();
    }

    /** Futures only: set leverage for a symbol before opening. */
    public void setLeverage(String symbol, int leverage) throws Exception {
        if (!futures) return;
        requireEntryPermission();
        call("POST", "/fapi/v1/leverage", "symbol=" + symbol + "&leverage=" + leverage);
    }

    /** Futures only: ISOLATED or CROSSED. Already-set is not an error. */
    public void setMarginMode(String symbol, String marginMode) throws Exception {
        if (!futures) return;
        requireEntryPermission();
        String type = "CROSS".equals(marginMode) ? "CROSSED" : "ISOLATED";
        try {
            call("POST", "/fapi/v1/marginType", "symbol=" + symbol + "&marginType=" + type);
        } catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            // -4046 = no change needed; anything else is a real failure.
            if (!m.contains("-4046")) throw e;
        }
    }

    /**
     * Place a market order. BUY opens a long, SELL opens a short on futures.
     * Quantity must already respect the symbol's step size.
     */
    public String marketOrder(String symbol,boolean buy,double quantity) throws Exception {
        throw new IllegalStateException("Use durable entry intent via Execution.open");
    }
    public JSONObject enter(String symbol,boolean buy,double quantity,String clientId) throws Exception {
        requireEntryPermission();
        String q="symbol="+symbol+"&side="+(buy?"BUY":"SELL")+"&type=MARKET&quantity="+quantise(symbol,quantity)
                +"&newClientOrderId="+clientId+"&newOrderRespType=RESULT";
        if(hedgeMode()) q+="&positionSide="+(buy?"LONG":"SHORT");
        // Symbol/account lookups can take time. Honor a switch-off during that work.
        requireEntryPermission();
        return submitKnownOrder("/fapi/v1/order",q,symbol,clientId,false,true);
    }

    /**
     * Per-symbol trading rules from the exchange: {stepSize, minQty, minNotional}.
     *
     * Every symbol defines its own lot step - BTCUSDT allows 0.001, SOLUSDT
     * only whole units. Sending more decimal places than the symbol permits
     * is rejected with -1111, which is what a blanket 6-decimal rounding
     * produced. These are cached because exchangeInfo is a large response
     * and the rules almost never change.
     */
    private static final java.util.Map<String, double[]> RULES =
            new java.util.concurrent.ConcurrentHashMap<String, double[]>();

    /**
     * Load lot and price rules for EVERY symbol in one pass.
     *
     * Binance's futures exchangeInfo ignores a ?symbol= filter and always
     * returns the full list, so reading symbols[0] silently applied the
     * wrong coin's rules — SOLUSDT was being quantised with BTCUSDT's step
     * size, which Binance rejects with -1111. Match by name, never by index.
     */
    private void loadAllRules() throws Exception {
        String path = futures ? "/fapi/v1/exchangeInfo" : "/api/v3/exchangeInfo";
        HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", "FalconQuantum/7.6");
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        } finally {
            c.disconnect();
        }

        org.json.JSONArray syms = new JSONObject(sb.toString()).optJSONArray("symbols");
        String prefix = (futures ? "F:" : "S:") + (testnet ? "T:" : "L:");
        for (int i = 0; syms != null && i < syms.length(); i++) {
            JSONObject sy = syms.getJSONObject(i);
            String name = sy.optString("symbol", "");
            if (name.length() == 0) continue;

            double step = 0, minQty = 0, minNotional = 0, tick = 0;
            org.json.JSONArray filters = sy.optJSONArray("filters");
            for (int k = 0; filters != null && k < filters.length(); k++) {
                JSONObject f = filters.getJSONObject(k);
                String type = f.optString("filterType", "");
                if ("LOT_SIZE".equals(type) || "MARKET_LOT_SIZE".equals(type)) {
                    double st = f.optDouble("stepSize", 0);
                    // MARKET_LOT_SIZE can be coarser; the stricter one wins.
                    if (st > 0 && st > step) step = st;
                    double mq = f.optDouble("minQty", 0);
                    if (mq > minQty) minQty = mq;
                } else if ("MIN_NOTIONAL".equals(type) || "NOTIONAL".equals(type)) {
                    double mn = f.optDouble("minNotional", f.optDouble("notional", 0));
                    if (mn > minNotional) minNotional = mn;
                } else if ("PRICE_FILTER".equals(type)) {
                    tick = f.optDouble("tickSize", 0);
                }
            }
            if (step <= 0 && sy.has("quantityPrecision")) {
                step = Math.pow(10, -sy.optInt("quantityPrecision", 3));
            }
            if (tick <= 0 && sy.has("pricePrecision")) {
                tick = Math.pow(10, -sy.optInt("pricePrecision", 2));
            }
            if (step <= 0) step = 0.001;
            if (tick <= 0) tick = 0.01;
            if (minNotional <= 0) minNotional = futures ? 5.0 : 10.0;

            RULES.put(prefix + name, new double[]{step, minQty, minNotional});
            TICKS.put(prefix + name, Double.valueOf(tick));
        }
    }

    private double[] rulesFor(String symbol) {
        String cacheKey = (futures ? "F:" : "S:") + (testnet ? "T:" : "L:") + symbol;
        double[] cached = RULES.get(cacheKey);
        if (cached != null) return cached;
        try {
            loadAllRules();
        } catch (Exception ignored) {
        }
        cached = RULES.get(cacheKey);
        if (cached != null) return cached;
        // Unknown symbol: refuse rather than guess a step size.
        return null;
    }

    /** Per-symbol price tick, filled by loadAllRules(). */
    private static final java.util.Map<String, Double> TICKS =
            new java.util.concurrent.ConcurrentHashMap<String, Double>();

    private double tickFor(String symbol) {
        String cacheKey = (futures ? "F:" : "S:") + (testnet ? "T:" : "L:") + symbol;
        Double cached = TICKS.get(cacheKey);
        if (cached != null) return cached.doubleValue();
        try {
            loadAllRules();
        } catch (Exception ignored) {
        }
        cached = TICKS.get(cacheKey);
        if(cached==null) throw new IllegalStateException("No price tick for "+symbol);
        return cached.doubleValue();
    }

    public String quantisePrice(String symbol, double price) {
        double tick = tickFor(symbol);
        java.math.BigDecimal p = java.math.BigDecimal.valueOf(price);
        java.math.BigDecimal t = java.math.BigDecimal.valueOf(tick);
        java.math.BigDecimal snapped = p.divide(t, 0, java.math.RoundingMode.HALF_UP).multiply(t);
        return snapped.setScale(decimalsFor(tick), java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Live mark price, so an order is sized against the market it will fill in. */
    public double markPrice(String symbol) throws Exception {
        if(!futures) throw new IllegalStateException("Futures only");
        double p=new JSONObject(call("GET","/fapi/v1/premiumIndex","symbol="+symbol)).optDouble("markPrice",0);
        if(!RiskMath.positive(p)) throw new IllegalStateException("No valid mark price");
        return p;
    }
    public double takerFeeBps(String symbol) throws Exception {
        JSONObject o=new JSONObject(call("GET","/fapi/v1/commissionRate","symbol="+symbol));
        double rate=o.getDouble("takerCommissionRate");
        if(Double.isNaN(rate)||Double.isInfinite(rate)||rate<0)throw new IllegalStateException("Unknown taker commission rate");
        return rate*10000.0;
    }
    public double executablePrice(String symbol,boolean buy) throws Exception {
        JSONObject q=new JSONObject(call("GET","/fapi/v1/ticker/bookTicker","symbol="+symbol));
        double p=q.optDouble(buy?"askPrice":"bidPrice",0);
        if(!RiskMath.positive(p)) throw new IllegalStateException("No executable quote");
        return p;
    }

    public String attachStopAndTarget(String symbol,boolean isLong,double stop,double target) throws Exception {
        return attachStopAndTarget(symbol,isLong,stop,target,0,0);
    }
    public String attachStopAndTarget(String symbol,boolean isLong,double stop,double target,double qty) throws Exception {
        return attachStopAndTarget(symbol,isLong,stop,target,qty,0);
    }
    public String attachStopAndTarget(String symbol,boolean isLong,double stop,double target,double qty,double entry) throws Exception {
        for(Position p:positions()) if(p.symbol.equals(symbol)&&p.side.equals(isLong?"LONG":"SHORT"))
            return ensureProtection(p,stop,target);
        return "Position is not open; protection not confirmed";
    }

    /** Decimal places implied by a step size, e.g. 0.001 -> 3, 1 -> 0. */
    private static int decimalsFor(double step) {
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(step).stripTrailingZeros();
        int scale = b.scale();
        return scale < 0 ? 0 : scale;
    }

    /**
     * Round a quantity DOWN onto the symbol's lot grid and format it without
     * scientific notation. Rounding down matters: rounding up could exceed
     * the balance the size percent authorised.
     */
    public String quantise(String symbol, double quantity) {
        double[] rules = rulesFor(symbol);
        if (rules == null) {
            throw new IllegalStateException("No exchange rules for " + symbol
                    + ". Falcon will not guess a lot size.");
        }
        double step = rules[0];
        java.math.BigDecimal q = java.math.BigDecimal.valueOf(quantity);
        java.math.BigDecimal st = java.math.BigDecimal.valueOf(step);
        java.math.BigDecimal snapped = q.divide(st, 0, java.math.RoundingMode.DOWN).multiply(st);
        snapped = snapped.setScale(decimalsFor(step), java.math.RoundingMode.DOWN);
        return snapped.toPlainString();
    }

    /** Human-readable check before an order is sent. Null means it is fine. */
    public String validateOrder(String symbol, double quantity, double price) {
        double[] rules = rulesFor(symbol);
        if (rules == null) {
            return "Could not read Binance's trading rules for " + symbol
                    + ". Check the connection and try again.";
        }
        String qty;
        try {
            qty = quantise(symbol, quantity);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
        double q = Double.parseDouble(qty);
        if (q <= 0) {
            return symbol + " trades in steps of " + trimNumber(rules[0])
                    + "; your size is smaller than one step. Raise the size percent or leverage.";
        }
        if (rules[1] > 0 && q < rules[1]) {
            return symbol + " has a minimum size of " + trimNumber(rules[1])
                    + " (yours: " + qty + "). Raise the size percent or leverage.";
        }
        double notional = q * price;
        // The only minimum enforced is the exchange's own for this symbol.
        // Falcon adds no floor of its own: the size percent is the operator's
        // decision, and different symbols require very different minimums.
        if (notional < rules[2]) {
            return symbol + " requires at least " + trimNumber(rules[2])
                    + " USDT notional on Binance (this order: "
                    + String.format("%.2f", notional) + " USDT). That limit is the exchange's, "
                    + "not Falcon's — raise the size percent or leverage, or pick a symbol "
                    + "with a lower minimum.";
        }
        return null;
    }

    private static String trimNumber(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /**
     * Work out WHY an account call fails. Binance returns the same -2015 for
     * an IP restriction, a missing permission and a wrong key, so test each
     * surface separately and report what actually happened.
     */
    public static String diagnose(Context ctx, Settings cfg) {
        StringBuilder out = new StringBuilder();
        String env = cfg.env;
        boolean testnet = "DEMO".equals(env);

        if (!hasKeys(ctx, env)) return "No API key saved for " + env + ".";
        String k = key(ctx, env);
        out.append("Key length ").append(k.length()).append(" (Binance keys are 64)\n");
        if (k.length() != 64) {
            out.append("-> Wrong length: the key was probably truncated or "
                    + "pasted with something extra.\n");
        }

        // Spot surface
        out.append("\nSPOT  ");
        try {
            Trader spot = new Trader(key(ctx, env), secret(ctx, env), false, testnet);
            spot.syncTime();
            double b = spot.usdtBalance();
            out.append("OK - reachable, USDT ").append(String.format("%.4f", b));
        } catch (Exception e) {
            out.append("FAIL - ").append(shorten(e.getMessage()));
        }

        // Futures surface
        out.append("\n\nFUTURES  ");
        try {
            Trader fut = new Trader(key(ctx, env), secret(ctx, env), true, testnet);
            fut.syncTime();
            double b = fut.usdtBalance();
            out.append("OK - reachable, USDT ").append(String.format("%.4f", b));
        } catch (Exception e) {
            out.append("FAIL - ").append(shorten(e.getMessage()));
        }

        out.append("\n\n").append(advice(ctx, env, testnet));
        return out.toString();
    }

    /**
     * Turn a raw Binance error into something actionable. The API answers
     * with a bare code and a terse phrase; without this the operator sees
     * JSON and has to go looking it up.
     */
    public static String explain(String raw) {
        String text = raw == null ? "" : raw;
        String hint = "";
        if (text.contains("-4061") || text.contains("-4059")) {
            hint = "The order did not name the right position side. The account is in Hedge "
                    + "Mode, so every order must say LONG or SHORT. Falcon re-detects the mode "
                    + "and retries automatically; if this persists, the position-mode lookup "
                    + "is being blocked by the network.";
        } else if (text.contains("-4500")) {
            hint = "The algo service rejected the algoType. Conditional stops require "
                    + "algoType=CONDITIONAL with the specific order in the type field.";
        } else if (text.contains("-4120")) {
            hint = "Binance moved every conditional order type to its Algo Order service. "
                    + "Falcon places stops on /fapi/v1/algoOrder; if this still appears, "
                    + "run TEST STOP ORDERS in the ACCOUNT tab and send the result.";
        } else if (text.contains("-4411")) {
            hint = "This is a TradFi Perp — a perpetual future on a stock or index, not "
                    + "crypto. Binance needs you to sign its TradFi-Perps agreement before "
                    + "trading those. Falcon has removed this symbol from scanning; sign the "
                    + "agreement in the Binance app if you do want to trade stock perps.";
        } else if (text.contains("-4061")) {
            hint = "Your futures account is in Hedge Mode, so every order must name its "
                    + "position side. Falcon now detects this automatically — if it repeats, "
                    + "reopen the app so the account mode is re-read.";
        } else if (text.contains("-1106")) {
            hint = "reduceOnly was sent where it is not accepted. This happens right after "
                    + "switching between Hedge and One-way mode; reopen the app.";
        } else if (text.contains("-1111")) {
            hint = "The quantity had more decimals than the symbol allows. Falcon rounds to "
                    + "each symbol's lot step; if this repeats the exchange rules could not be read.";
        } else if (text.contains("-2019") || text.contains("-2018")) {
            hint = "Not enough margin for this order. Lower the size percent or the leverage.";
        } else if (text.contains("-4164") || text.contains("-2010")) {
            hint = "The order is below Binance's minimum notional. Raise the size percent "
                    + "or the leverage.";
        } else if (text.contains("-2015")) {
            hint = "Binance rejected the key: an IP allowlist, a missing permission, or a "
                    + "wrong key. Use DIAGNOSE KEY PROBLEM in the ACCOUNT tab.";
        } else if (text.contains("-1021")) {
            hint = "The request timestamp was outside Binance's window. Falcon resyncs the "
                    + "clock automatically; a repeat means the device clock drifted badly.";
        } else if (text.contains("-1022")) {
            hint = "The signature did not match. Re-enter the API secret.";
        } else if (text.contains("-4059") || text.contains("-4046")) {
            hint = "No change was needed for margin mode or leverage.";
        } else if (text.contains("451")) {
            hint = "Binance refused the request for this location. A VPN exit in an "
                    + "unsupported country causes this.";
        }
        return hint.length() == 0 ? text : text + "\n\n" + hint;
    }

    private static String shorten(String m) {
        if (m == null) return "unknown error";
        // Binance names the offending field in these messages, which is the
        // whole point of the test, so keep far more of it than a toast would.
        return m.length() > 400 ? m.substring(0, 400) + "…" : m;
    }

    private static String advice(Context ctx, String env, boolean testnet) {
        StringBuilder a = new StringBuilder("If SPOT works but FUTURES fails:\n"
                + "  the key does not have Futures enabled, or the Futures\n"
                + "  account has never been opened on this Binance account.\n\n"
                + "If BOTH fail with -2015:\n"
                + "  1. The key has an IP allowlist and your current IP is not\n"
                + "     on it. Your requests are coming from a VPN/datacenter\n"
                + "     IP, which changes - either turn the VPN off or set the\n"
                + "     key allowlist to your approved static IP.\n"
                + "  2. Or the key/secret is wrong, or was made on a different\n"
                + "     Binance site (binance.us / regional) than api.binance.com.\n");
        if (testnet) {
            a.append("\nDEMO note: testnet keys come from testnet.binance.vision\n"
                    + "(spot) or testnet.binancefuture.com (futures). Real\n"
                    + "binance.com keys will NOT work on DEMO, and vice versa.\n");
        }
        return a.toString();
    }

    public String describe() {
        return (futures ? "FUTURES" : "SPOT") + (testnet ? " testnet" : " LIVE") + " @ " + base();
    }
}
