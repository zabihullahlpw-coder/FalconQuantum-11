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
    public static final String FUT_TEST = "https://testnet.binancefuture.com";

    private static final String PREFS = "falcon_keys";
    private static final int TIMEOUT = 15000;

    private final String apiKey;
    private final String apiSecret;
    private final boolean futures;
    private final boolean testnet;
    /** For the protection ledger. Null only in the probe constructor. */
    private Context appContext;
    private String environment = "";

    private Trader(String apiKey, String apiSecret, boolean futures, boolean testnet) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.futures = futures;
        this.testnet = testnet;
    }

    // ------------------------------------------------------------- key store

    public static void saveKeys(Context ctx, String env, String key, String secret) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(env + "_key", clean(key))
                .putString(env + "_secret", clean(secret))
                .apply();
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
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(env + "_key", "");
    }

    public static String secret(Context ctx, String env) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(env + "_secret", "");
    }

    public static boolean hasKeys(Context ctx, String env) {
        return key(ctx, env).length() > 0 && secret(ctx, env).length() > 0;
    }

    /** LIVE order sending stays off until the operator explicitly arms it. */
    public static boolean liveArmed(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("live_armed", false);
    }

    public static void setLiveArmed(Context ctx, boolean armed) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("live_armed", armed).apply();
    }

    /**
     * Build a client for an environment, or return null when that
     * environment must not send orders.
     */
    public static Trader forEnvironment(Context ctx, Settings cfg) {
        if ("PAPER".equals(cfg.env)) return null;
        if (!hasKeys(ctx, cfg.env)) return null;
        if ("LIVE".equals(cfg.env) && !liveArmed(ctx)) return null;
        boolean testnet = "DEMO".equals(cfg.env);
        Trader t = new Trader(key(ctx, cfg.env), secret(ctx, cfg.env), cfg.isFutures(), testnet);
        t.appContext = ctx.getApplicationContext();
        t.environment = cfg.env;
        t.syncTime();
        return t;
    }

    /**
     * Client for READING an account (balance, positions). Reading cannot
     * move funds, so it does not require LIVE to be armed - the operator
     * should always be able to see the truth about their account.
     */
    public static Trader forReading(Context ctx, Settings cfg) {
        if ("PAPER".equals(cfg.env)) return null;
        if (!hasKeys(ctx, cfg.env)) return null;
        Trader t = new Trader(key(ctx, cfg.env), secret(ctx, cfg.env),
                cfg.isFutures(), "DEMO".equals(cfg.env));
        t.appContext = ctx.getApplicationContext();
        t.environment = cfg.env;
        t.syncTime();
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
        String q = query + (query.length() > 0 ? "&" : "") + "timestamp="
                + (System.currentTimeMillis() + timeOffset) + "&recvWindow=10000";
        String signed = q + "&signature=" + sign(q);
        URL url = new URL(host + path + ("GET".equals(method) ? "?" + signed : ""));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("X-MBX-APIKEY", apiKey);
        c.setRequestProperty("User-Agent", "FalconQuantum/7.3.0");
        try {
            if (!"GET".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(signed.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
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
        if (!futures) throw new RuntimeException("Spot positions are closed by selling the asset");
        StringBuilder query = new StringBuilder("symbol=" + p.symbol
                + "&side=" + ("LONG".equals(p.side) ? "SELL" : "BUY")
                + "&type=MARKET"
                + "&quantity=" + URLEncoder.encode(quantise(p.symbol, p.qty), "UTF-8"));
        if (hedgeMode()) {
            // In hedge mode the book is named explicitly and reduceOnly is
            // rejected; naming the side is what makes this reduce-only.
            query.append("&positionSide=").append("LONG".equals(p.side) ? "LONG" : "SHORT");
        } else {
            query.append("&reduceOnly=true");
        }
        return orderWithPositionModeRetry("/fapi/v1/order", query.toString(),
                "LONG".equals(p.side), true);
    }

    /**
     * Flatten a position immediately with a market order.
     *
     * Used when protection cannot be placed. An unprotected leveraged
     * position is a worse outcome than a small round-trip fee, so the
     * position is closed rather than left running.
     */
    public String emergencyClose(String symbol, boolean positionIsLong, double quantity) {
        try {
            // Cancel protection first: a resting stop that triggers after the
            // close would open a fresh position in the opposite direction.
            cancelProtection(symbol);
            StringBuilder q = new StringBuilder("symbol=" + symbol
                    + "&side=" + (positionIsLong ? "SELL" : "BUY")
                    + "&type=MARKET&quantity="
                    + URLEncoder.encode(quantise(symbol, quantity), "UTF-8"));
            if (hedgeMode()) {
                q.append("&positionSide=").append(positionIsLong ? "LONG" : "SHORT");
            } else {
                q.append("&reduceOnly=true");
            }
            orderWithPositionModeRetry("/fapi/v1/order", q.toString(), positionIsLong, true);
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Can this account place a stop order at all?
     *
     * Some accounts reject STOP_MARKET on the standard order endpoint with
     * -4120. There is no point opening a position that cannot then be
     * protected, so this is checked once and cached.
     */
    private static final java.util.Map<String, Boolean> STOPS_OK =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    public void rememberStopSupport(boolean supported) {
        STOPS_OK.put((testnet ? "T" : "L") + ":" + apiKey, Boolean.valueOf(supported));
    }

    /** null = not yet known. */
    public Boolean stopSupport() {
        return STOPS_OK.get((testnet ? "T" : "L") + ":" + apiKey);
    }

    /**
     * Find out whether this account accepts stop orders — WITHOUT opening
     * anything.
     *
     * A deliberately undersized STOP_MARKET is sent: below the symbol's
     * minimum quantity, so Binance must reject it. What matters is WHICH
     * rejection comes back. -4120 means the order type itself is refused;
     * any other rejection (quantity, price) means the type is fine and only
     * the deliberately bad size was caught.
     *
     * This replaces the old behaviour of discovering the problem only after
     * a real position was already open, which meant opening a trade and
     * closing it seconds later.
     */
    public static final String PAPI_BASE = "https://papi.binance.com";

    /** Which protection variant this account accepts, once discovered. */
    private static final java.util.Map<String, Integer> WORKING_VARIANT =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /** Human-readable names, index-aligned with buildVariant(). */
    public static final String[] VARIANT_NAMES = {
            "algo CONDITIONAL + triggerPrice + quantity",
            "algo CONDITIONAL + triggerPrice + quantity + workingType",
            "algo CONDITIONAL + triggerPrice + closePosition",
            "algo CONDITIONAL + triggerPrice + quantity + priceProtect",
            "algo CONDITIONAL + strategyType instead of type",
            "algo CONDITIONAL + triggerPrice + quantity, no positionSide",
            "algo CONDITIONAL stop-limit + triggerPrice + price",
            "legacy fapi STOP_MARKET + closePosition",
            "legacy fapi STOP_MARKET + quantity",
    };

    /** Conditional orders now live on their own endpoint. */
    public static final String ALGO_ORDER_PATH = "/fapi/v1/algoOrder";
    public static final String ALGO_OPEN_PATH = "/fapi/v1/openAlgoOrders";

    /**
     * Build one candidate protection order.
     *
     * Binance exposes several conditional-order shapes and more than one
     * endpoint. A -4120 only tells us the shape we happened to send is not
     * accepted here - not that no shape is. Each of these is a legitimate
     * way to attach a stop; the account decides which it will take.
     */
    private String[] buildVariant(int index, String symbol, boolean positionIsLong,
                                  double trigger, double quantity, boolean isStop)
            throws Exception {
        String exit = positionIsLong ? "SELL" : "BUY";
        String posSide = positionIsLong ? "LONG" : "SHORT";
        boolean hedge = hedgeMode();
        String type = isStop ? "STOP_MARKET" : "TAKE_PROFIT_MARKET";
        String stopArg = "&stopPrice=" + URLEncoder.encode(quantisePrice(symbol, trigger), "UTF-8");
        String qtyArg = "&quantity=" + URLEncoder.encode(quantise(symbol, quantity), "UTF-8");
        String posArg = hedge ? "&positionSide=" + posSide : "";
        String head = "symbol=" + symbol + "&side=" + exit;

        // The algo service names the level triggerPrice, not stopPrice, and
        // requires algoType=CONDITIONAL with the specific order in `type`.
        // Establishing that took a round of live probing; these are ordered
        // with the confirmed schema first.
        String triggerArg = "&triggerPrice="
                + URLEncoder.encode(quantisePrice(symbol, trigger), "UTF-8");
        // Hedge mode names the book; one-way uses reduceOnly. Sending both
        // is rejected, so they are mutually exclusive here.
        String closeArg = hedge ? posArg : "&reduceOnly=true";

        switch (index) {
            case 0:
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&type=" + type
                                + triggerArg + qtyArg + closeArg};
            case 1:
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&type=" + type
                                + triggerArg + qtyArg + closeArg
                                + "&workingType=MARK_PRICE"};
            case 2:
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&type=" + type
                                + triggerArg + posArg + "&closePosition=true"};
            case 3:
                // priceProtect is required on some conditional schemas.
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&type=" + type
                                + triggerArg + qtyArg + closeArg
                                + "&workingType=MARK_PRICE&priceProtect=true"};
            case 4:
                // Some Binance algo variants name the order strategyType.
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&strategyType=" + type
                                + triggerArg + qtyArg + closeArg};
            case 5:
                // Hedge accounts sometimes reject positionSide on algo orders.
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&type=" + type
                                + triggerArg + qtyArg};
            case 6: {
                double limit = positionIsLong ? trigger * 0.995 : trigger * 1.005;
                return new String[]{base(), ALGO_ORDER_PATH,
                        head + "&algoType=CONDITIONAL&type=" + (isStop ? "STOP" : "TAKE_PROFIT")
                                + triggerArg + qtyArg + closeArg
                                + "&price=" + URLEncoder.encode(
                                        quantisePrice(symbol, limit), "UTF-8")
                                + "&timeInForce=GTC"};
            }
            case 7:
                return new String[]{base(), "/fapi/v1/order",
                        head + "&type=" + type + stopArg + posArg
                                + "&closePosition=true&workingType=MARK_PRICE"};
            default:
                return new String[]{base(), "/fapi/v1/order",
                        head + "&type=" + type + stopArg + posArg + qtyArg
                                + "&workingType=MARK_PRICE"};
        }
    }

    /**
     * Algo order ids per symbol, so protection can be cancelled when the
     * position closes. A stop left behind after a close is not harmless: it
     * can open a NEW position in the opposite direction when it triggers.
     */
    /**
     * Positions currently having protection placed.
     *
     * The scan thread, the stop watcher and the UI can all reach this at
     * once. Without a lock, two of them can both find "no protection" and
     * both create a pair.
     */
    private static final java.util.Set<String> PROTECTING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** When protection was last placed per symbol, to stop runaway repeats. */
    private static final java.util.Map<String, Long> LAST_PROTECTION_AT =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private static final java.util.Map<String, java.util.List<Long>> ALGO_IDS =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.List<Long>>();

    /** Last algo ids placed, so the UI can show them as proof of placement. */
    private static final java.util.Map<String, String> LAST_ALGO_LABEL =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    public String algoLabel(String symbol) {
        return LAST_ALGO_LABEL.get(variantKey() + ":" + symbol.toUpperCase());
    }

    /**
     * Cancel every working conditional order for a symbol.
     *
     * Recovery for an account that already accumulated duplicates: clear
     * the lot, then let one clean pair be placed.
     */
    public int cancelAllConditional(String symbol) {
        int removed = 0;
        try {
            call("DELETE", "/fapi/v1/algoOpenOrders", "symbol=" + symbol);
            removed++;
        } catch (Exception ignored) {
        }
        try {
            call("DELETE", "/fapi/v1/allOpenOrders", "symbol=" + symbol);
            removed++;
        } catch (Exception ignored) {
        }
        ALGO_IDS.remove(variantKey() + ":" + symbol.toUpperCase());
        LAST_PROTECTION_AT.remove(variantKey() + ":" + symbol.toUpperCase());
        return removed;
    }

    /**
     * Replace a position's protection with new levels.
     *
     * Cancels what is working, clears the record, then places the new pair.
     * That order matters: leaving the old orders up while adding new ones is
     * how a position ends up with four conditional orders instead of two.
     */
    public String retarget(Position p, double newStop, double newTarget) {
        try {
            cancelProtection(p.symbol);
            boolean isLong = "LONG".equals(p.side);
            if (newStop <= 0 && newTarget <= 0) return "no new levels supplied";
            return attachStopAndTarget(p.symbol, isLong, newStop, newTarget, p.qty,
                    p.entry > 0 ? p.entry : p.mark);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Adopt protection that already exists on the exchange.
     *
     * After a restart Falcon has no records, sees "no protection" and
     * creates another pair. Importing what Binance already holds means a
     * restart attaches to the existing orders instead of duplicating them.
     */
    public int importExistingProtection() {
        if (appContext == null) return 0;
        int imported = 0;
        try {
            for (Position held : positions()) {
                String posId = positionKey(held.symbol, "LONG".equals(held.side),
                        held.entry > 0 ? held.entry : held.mark);
                if (ProtectionLedger.get(appContext, posId) != null) continue;

                long stopId = 0, targetId = 0;
                org.json.JSONArray algo = openAlgoOrders(held.symbol);
                for (int i = 0; i < algo.length(); i++) {
                    JSONObject o = algo.getJSONObject(i);
                    if (!held.symbol.equalsIgnoreCase(o.optString("symbol", ""))) continue;
                    String type = o.optString("type", o.optString("algoType", ""));
                    long id = o.optLong("algoId", o.optLong("orderId", 0));
                    if (id <= 0) continue;
                    if (type.contains("TAKE_PROFIT") && targetId == 0) targetId = id;
                    else if (type.contains("STOP") && stopId == 0) stopId = id;
                }
                if (stopId > 0 || targetId > 0) {
                    ProtectionLedger.put(appContext, posId, stopId, targetId);
                    imported++;
                }
            }
        } catch (Exception ignored) {
        }
        return imported;
    }

    /** Working conditional orders for a symbol, tolerant of response shape. */
    private org.json.JSONArray openAlgoOrders(String symbol) throws Exception {
        String body = call("GET", ALGO_OPEN_PATH, "symbol=" + symbol);
        if (body.trim().startsWith("{")) {
            JSONObject w = new JSONObject(body);
            org.json.JSONArray a = w.optJSONArray("orders");
            if (a == null) a = w.optJSONArray("data");
            return a == null ? new org.json.JSONArray() : a;
        }
        return new org.json.JSONArray(body);
    }

    /**
     * Cancel protection for positions that no longer exist.
     *
     * Without this, a position closed by its own stop leaves the opposite
     * leg working. On a hedge account that surviving order can open a fresh
     * position when it triggers.
     */
    public int reconcileProtection() {
        if (appContext == null) return 0;
        int cleaned = 0;
        try {
            java.util.Set<String> live = new java.util.HashSet<String>();
            for (Position held : positions()) {
                live.add(positionKey(held.symbol, "LONG".equals(held.side), held.entry));
                // Entry price can drift slightly between reads, so also key
                // on the symbol alone for the liveness test below.
                live.add(held.symbol.toUpperCase());
            }
            java.util.List<ProtectionLedger.Record> records = ProtectionLedger.all(appContext);
            for (int i = 0; i < records.size(); i++) {
                ProtectionLedger.Record r = records.get(i);
                if (!environment.equals(ProtectionLedger.envOf(r.id))) continue;
                String symbol = ProtectionLedger.symbolOf(r.id);
                if (live.contains(symbol)) continue;
                cancelProtection(symbol);
                cleaned++;
            }
        } catch (Exception ignored) {
        }
        return cleaned;
    }

    /** Remove stray duplicate protection, keeping one stop and one target. */
    public int tidyProtection(String symbol) {
        int removed = 0;
        try {
            org.json.JSONArray algo;
            String body = call("GET", ALGO_OPEN_PATH, "symbol=" + symbol);
            if (body.trim().startsWith("{")) {
                JSONObject w = new JSONObject(body);
                algo = w.optJSONArray("orders");
                if (algo == null) algo = w.optJSONArray("data");
                if (algo == null) algo = new org.json.JSONArray();
            } else {
                algo = new org.json.JSONArray(body);
            }
            boolean keptStop = false, keptTarget = false;
            for (int i = 0; i < algo.length(); i++) {
                JSONObject o = algo.getJSONObject(i);
                if (!symbol.equalsIgnoreCase(o.optString("symbol", ""))) continue;
                String type = o.optString("type", o.optString("algoType", ""));
                boolean isStop = type.contains("STOP") && !type.contains("TAKE_PROFIT");
                boolean isTarget = type.contains("TAKE_PROFIT");
                boolean duplicate = (isStop && keptStop) || (isTarget && keptTarget);
                if (isStop) keptStop = true;
                if (isTarget) keptTarget = true;
                if (!duplicate) continue;
                long id = o.optLong("algoId", o.optLong("orderId", 0));
                if (id > 0) {
                    try {
                        call("DELETE", ALGO_ORDER_PATH, "algoId=" + id);
                        removed++;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return removed;
    }

    private void rememberAlgoId(String symbol, String response) {
        try {
            JSONObject o = new JSONObject(response);
            long id = o.optLong("algoId", o.optLong("orderId", 0));
            if (id <= 0) return;
            String labelKey = variantKey() + ":" + symbol.toUpperCase();
            String existing = LAST_ALGO_LABEL.get(labelKey);
            LAST_ALGO_LABEL.put(labelKey,
                    existing == null ? String.valueOf(id) : existing + ", " + id);
            String key = variantKey() + ":" + symbol.toUpperCase();
            java.util.List<Long> ids = ALGO_IDS.get(key);
            if (ids == null) {
                ids = java.util.Collections.synchronizedList(new java.util.ArrayList<Long>());
                ALGO_IDS.put(key, ids);
            }
            if (!ids.contains(Long.valueOf(id))) ids.add(Long.valueOf(id));
        } catch (Exception ignored) {
        }
    }

    /**
     * Remove any protection still working for a symbol. Called after a
     * position is closed so a stray stop cannot re-open the trade.
     */
    public void cancelProtection(String symbol) {
        // Drop every ledger record for this symbol: the position is gone, so
        // its protection must not linger and fire into a new one.
        if (appContext != null) {
            java.util.List<ProtectionLedger.Record> records = ProtectionLedger.all(appContext);
            for (int i = 0; i < records.size(); i++) {
                ProtectionLedger.Record r = records.get(i);
                if (symbol.equalsIgnoreCase(ProtectionLedger.symbolOf(r.id))) {
                    if (r.stopId > 0) {
                        try {
                            call("DELETE", ALGO_ORDER_PATH, "algoId=" + r.stopId);
                        } catch (Exception ignored) {
                        }
                    }
                    if (r.targetId > 0) {
                        try {
                            call("DELETE", ALGO_ORDER_PATH, "algoId=" + r.targetId);
                        } catch (Exception ignored) {
                        }
                    }
                    ProtectionLedger.remove(appContext, r.id);
                }
            }
        }
        String key = variantKey() + ":" + symbol.toUpperCase();
        java.util.List<Long> ids = ALGO_IDS.remove(key);
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                try {
                    call("DELETE", ALGO_ORDER_PATH, "algoId=" + ids.get(i));
                } catch (Exception ignored) {
                }
            }
        }
        // Belt and braces: clear anything the exchange still lists.
        try {
            call("DELETE", "/fapi/v1/algoOpenOrders", "symbol=" + symbol);
        } catch (Exception ignored) {
        }
        try {
            call("DELETE", "/fapi/v1/allOpenOrders", "symbol=" + symbol);
        } catch (Exception ignored) {
        }
    }

    /** Result of trying every protection variant against the live account. */
    public static final class ProtectionReport {
        public int workingIndex = -1;
        public final StringBuilder log = new StringBuilder();

        public boolean anyWorked() {
            return workingIndex >= 0;
        }
    }

    /**
     * Try each variant until one is accepted, cancelling any test order.
     * The winner is remembered and used for all later protection orders.
     */
    public ProtectionReport findWorkingProtection(String symbol, boolean positionIsLong,
                                                  double trigger, double quantity) {
        ProtectionReport report = new ProtectionReport();
        for (int i = 0; i < VARIANT_NAMES.length; i++) {
            String[] call;
            try {
                call = buildVariant(i, symbol, positionIsLong, trigger, quantity, true);
            } catch (Exception e) {
                report.log.append(VARIANT_NAMES[i]).append(" -> could not build: ")
                        .append(e.getMessage()).append("\n\n");
                continue;
            }
            try {
                String response = callHost(call[0], "POST", call[1], call[2]);
                report.workingIndex = i;
                report.log.append("ACCEPTED: ").append(VARIANT_NAMES[i]).append("\n\n");
                cancelTestOrder(call[0], symbol, response);
                WORKING_VARIANT.put(variantKey(), Integer.valueOf(i));
                break;
            } catch (Exception e) {
                report.log.append(VARIANT_NAMES[i]).append("\n   ")
                        .append(shorten(String.valueOf(e.getMessage()))).append("\n\n");
            }
        }
        return report;
    }

    private String variantKey() {
        return (testnet ? "T" : "L") + ":" + apiKey;
    }

    public Integer knownVariant() {
        return WORKING_VARIANT.get(variantKey());
    }

    public void rememberVariant(int index) {
        WORKING_VARIANT.put(variantKey(), Integer.valueOf(index));
    }

    private void cancelTestOrder(String host, String symbol, String response) {
        try {
            JSONObject o = new JSONObject(response);
            long id = o.optLong("orderId", 0);
            String strategyId = o.optString("strategyId", "");
            long algoId = o.optLong("algoId", o.optLong("clientAlgoId", 0));
            if (algoId > 0) {
                callHost(host, "DELETE", ALGO_ORDER_PATH, "algoId=" + algoId);
            } else if (id > 0 || strategyId.length() > 0) {
                boolean papi = host.equals(PAPI_BASE);
                callHost(host, "DELETE",
                        papi ? "/papi/v1/um/conditional/order" : "/fapi/v1/order",
                        "symbol=" + symbol + (papi
                                ? "&strategyId=" + (strategyId.length() > 0 ? strategyId : id)
                                : "&orderId=" + id));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Establish, before risking money, whether ANY protection shape works.
     *
     * The earlier version tried a single shape and treated its rejection as
     * proof that the account could not place stops at all. That was wrong:
     * it only proved that one shape was refused.
     */
    public boolean probeStopSupport(String symbol) {
        Boolean known = stopSupport();
        if (known != null) return known.booleanValue();
        if (knownVariant() != null) return true;

        boolean supported = false;
        try {
            double mark = markPrice(symbol);
            if (mark <= 0) mark = 1;
            double[] rules = rulesFor(symbol);
            double qty = rules == null ? 0.001 : Math.max(rules[1], rules[0]);
            // Trigger far below the market: it can never fire during the test.
            ProtectionReport report = findWorkingProtection(symbol, true, mark * 0.5, qty);
            supported = report.anyWorked();
        } catch (Exception ignored) {
        }
        rememberStopSupport(supported);
        return supported;
    }

    /** Open orders for one symbol, used to see whether a stop already exists. */
    public org.json.JSONArray openOrders(String symbol) throws Exception {
        if (!futures) return new org.json.JSONArray();
        return new org.json.JSONArray(call("GET", "/fapi/v1/openOrders", "symbol=" + symbol));
    }

    /** True when both a stop and a take-profit are already working. */
    /**
     * Protection status for a symbol: {hasStop, hasTarget, verified}.
     *
     * The third flag matters more than the first two. If the exchange could
     * not be queried we must NOT assume "no protection exists" and create
     * more - that is how one position ended up with 200 conditional orders.
     */
    public boolean[] protectionStatus(String symbol) {
        try {
            boolean[] present = protectionPresent(symbol);
            return new boolean[]{present[0], present[1], true};
        } catch (Exception e) {
            return new boolean[]{false, false, false};
        }
    }

    public boolean[] protectionPresent(String symbol) throws Exception {
        boolean stop = false, target = false;
        org.json.JSONArray orders = openOrders(symbol);
        for (int i = 0; i < orders.length(); i++) {
            String type = orders.getJSONObject(i).optString("type", "");
            if (type.contains("STOP")) stop = true;
            if (type.contains("TAKE_PROFIT")) target = true;
        }
        // Conditional orders are reported by the algo service, not by the
        // classic open-orders list, so a stop placed there is invisible here.
        try {
            String body = call("GET", ALGO_OPEN_PATH, "symbol=" + symbol);
            org.json.JSONArray algo;
            if (body.trim().startsWith("{")) {
                // Some responses wrap the list in an object.
                JSONObject wrapper = new JSONObject(body);
                algo = wrapper.optJSONArray("orders");
                if (algo == null) algo = wrapper.optJSONArray("data");
                if (algo == null) algo = new org.json.JSONArray();
            } else {
                algo = new org.json.JSONArray(body);
            }
            for (int i = 0; i < algo.length(); i++) {
                JSONObject o = algo.getJSONObject(i);
                if (!symbol.equalsIgnoreCase(o.optString("symbol", symbol))) continue;
                String type = o.optString("type", o.optString("algoType", ""));
                if (type.contains("STOP")) stop = true;
                if (type.contains("TAKE_PROFIT")) target = true;
            }
        } catch (Exception ignored) {
        }
        return new boolean[]{stop, target};
    }

    /** Cancel every working order for a symbol (used when flattening). */
    public void cancelOpenOrders(String symbol) throws Exception {
        if (!futures) return;
        call("DELETE", "/fapi/v1/allOpenOrders", "symbol=" + symbol);
        try {
            call("DELETE", "/fapi/v1/algoOpenOrders", "symbol=" + symbol);
        } catch (Exception ignored) {
        }
    }

    /**
     * Make sure an open position has a stop and a target on the exchange.
     *
     * Binance has no atomic bracket order for a market entry, so protection
     * is always a second request — and a crash, a dropped connection or a
     * rejected price can leave a position bare. This is called after every
     * entry AND on every account refresh, so an unprotected position gets
     * covered on the next pass instead of staying exposed.
     *
     * Returns null when protection is in place, or a reason when it is not.
     */
    public String ensureProtection(Position p, double stopPrice, double targetPrice) {
        try {
            boolean[] have = protectionStatus(p.symbol);
            if (!have[2]) return "could not verify protection for " + p.symbol;
            if (have[0] && have[1]) return null;

            double entry = p.entry > 0 ? p.entry : p.mark;
            if (entry <= 0) return "no entry price for " + p.symbol;
            boolean isLong = "LONG".equals(p.side);

            // Fall back to a 1% stop / 2% target when the original plan is
            // unknown, e.g. after a restart.
            if (stopPrice <= 0) stopPrice = isLong ? entry * 0.99 : entry * 1.01;
            if (targetPrice <= 0) targetPrice = isLong ? entry * 1.02 : entry * 0.98;

            // A stop on the wrong side of the market triggers instantly and
            // is rejected, so push it past the current mark if needed.
            double mark = p.mark > 0 ? p.mark : entry;
            if (isLong && stopPrice >= mark) stopPrice = mark * 0.995;
            if (!isLong && stopPrice <= mark) stopPrice = mark * 1.005;
            if (isLong && targetPrice <= mark) targetPrice = mark * 1.005;
            if (!isLong && targetPrice >= mark) targetPrice = mark * 0.995;

            return attachStopAndTarget(p.symbol, isLong,
                    have[0] ? 0 : stopPrice, have[1] ? 0 : targetPrice, p.qty,
                    p.entry > 0 ? p.entry : p.mark);
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Realised profit for a symbol since a point in time.
     *
     * A position closed by its own stop, or closed in the Binance app, is
     * never seen by Falcon. Reading the income record is the only way to
     * complete the history rather than leaving those trades open forever.
     */
    public double realisedPnlSince(String symbol, long sinceMillis) throws Exception {
        if (!futures) return 0;
        String body = call("GET", "/fapi/v1/income",
                "symbol=" + symbol + "&incomeType=REALIZED_PNL&startTime="
                        + Math.max(0, sinceMillis - 60000L) + "&limit=200");
        org.json.JSONArray arr = new org.json.JSONArray(body);
        double total = 0;
        for (int i = 0; i < arr.length(); i++) {
            total += arr.getJSONObject(i).optDouble("income", 0);
        }
        return total;
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
            // A failed lookup must NOT be cached. Storing "false" here meant
            // one dropped request made every later order omit positionSide
            // and fail with -4061 until the app was restarted. Assume hedge
            // on failure: naming the side is refused in one-way mode with a
            // clear error, whereas omitting it in hedge mode is the bug we
            // are fixing.
            return true;
        }
    }

    /** Forget the cached mode for this key so the next call re-detects it. */
    private void invalidateHedgeCache() {
        HEDGE.remove((testnet ? "T" : "L") + ":" + apiKey);
    }

    /**
     * Send an order, and if Binance says the position side is wrong, flip
     * the assumption and send it once more.
     *
     * -4061 and -4059 mean our view of the account's position mode is stale,
     * which can happen if the setting is changed on Binance while Falcon is
     * running. Re-detecting and retrying is better than failing the trade.
     */
    private String orderWithPositionModeRetry(String path, String query,
                                              boolean positionIsLong,
                                              boolean closing) throws Exception {
        try {
            return call("POST", path, query);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains("-4061") && !message.contains("-4059")) throw e;

            invalidateHedgeCache();
            boolean hedge = hedgeMode();
            String corrected = query
                    .replaceAll("&positionSide=(LONG|SHORT|BOTH)", "")
                    .replaceAll("&reduceOnly=true", "");
            if (hedge) {
                corrected += "&positionSide=" + (positionIsLong ? "LONG" : "SHORT");
            } else if (closing) {
                corrected += "&reduceOnly=true";
            }
            return call("POST", path, corrected);
        }
    }

    /** Clears the cached mode, e.g. after the user changes it on Binance. */
    public static void forgetAccountModes() {
        HEDGE.clear();
    }

    /** Futures only: set leverage for a symbol before opening. */
    public void setLeverage(String symbol, int leverage) throws Exception {
        if (!futures) return;
        call("POST", "/fapi/v1/leverage", "symbol=" + symbol + "&leverage=" + leverage);
    }

    /** Futures only: ISOLATED or CROSSED. Already-set is not an error. */
    public void setMarginMode(String symbol, String marginMode) throws Exception {
        if (!futures) return;
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
    public String marketOrder(String symbol, boolean buy, double quantity) throws Exception {
        String qty = quantise(symbol, quantity);
        double[] rules = rulesFor(symbol);
        if (Double.parseDouble(qty) <= 0) {
            throw new RuntimeException(symbol + " trades in steps of "
                    + (rules == null ? "?" : trimNumber(rules[0]))
                    + "; the requested size rounds to zero. Raise the size percent.");
        }
        String path = futures ? "/fapi/v1/order" : "/api/v3/order";
        StringBuilder query = new StringBuilder("symbol=" + symbol
                + "&side=" + (buy ? "BUY" : "SELL")
                + "&type=MARKET&quantity=" + URLEncoder.encode(qty, "UTF-8"));
        if (futures && hedgeMode()) {
            // Opening: BUY creates the LONG book, SELL creates the SHORT book.
            query.append("&positionSide=").append(buy ? "LONG" : "SHORT");
        }
        if (!futures) return call("POST", path, query.toString());
        return orderWithPositionModeRetry(path, query.toString(), buy, false);
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
        return cached == null ? 0.01 : cached.doubleValue();
    }

    private String quantisePrice(String symbol, double price) {
        double tick = tickFor(symbol);
        java.math.BigDecimal p = java.math.BigDecimal.valueOf(price);
        java.math.BigDecimal t = java.math.BigDecimal.valueOf(tick);
        java.math.BigDecimal snapped = p.divide(t, 0, java.math.RoundingMode.HALF_UP).multiply(t);
        return snapped.setScale(decimalsFor(tick), java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Live mark price, so an order is sized against the market it will fill in. */
    public double markPrice(String symbol) throws Exception {
        if (!futures) return Binance.price(symbol);
        String body = call("GET", "/fapi/v1/premiumIndex", "symbol=" + symbol);
        double p = new JSONObject(body).optDouble("markPrice", 0);
        if (p <= 0) p = Binance.price(symbol);
        return p;
    }

    /**
     * Attach stop-loss and take-profit to a position that is already open.
     * Both use closePosition=true so they always flatten whatever size the
     * position ends up being, and they survive partial fills.
     */
    public String attachStopAndTarget(String symbol, boolean positionIsLong,
                                      double stopPrice, double targetPrice) throws Exception {
        return attachStopAndTarget(symbol, positionIsLong, stopPrice, targetPrice, 0);
    }

    /**
     * Attach stop-loss and take-profit to an open position.
     *
     * Binance accepts more than one shape for these orders and rejects the
     * others depending on account and position mode. Relying on a single
     * shape left a filled position unprotected when it came back -4120, so
     * this tries each accepted form in turn and only reports failure when
     * every one is refused.
     *
     * @param quantity position size, used by the forms that need one; pass
     *                 0 to rely on close-all.
     */
    public String attachStopAndTarget(String symbol, boolean positionIsLong,
                                      double stopPrice, double targetPrice,
                                      double quantity) throws Exception {
        return attachStopAndTarget(symbol, positionIsLong, stopPrice, targetPrice,
                quantity, 0);
    }

    private String positionKey(String symbol, boolean isLong, double entry) {
        return ProtectionLedger.positionId(environment, symbol, isLong ? "LONG" : "SHORT", entry);
    }

    public String attachStopAndTarget(String symbol, boolean positionIsLong,
                                      double stopPrice, double targetPrice,
                                      double quantity, double entryForId) throws Exception {
        if (!futures) return "spot: protection orders not supported here";

        // A trigger that rounds onto the entry, or sits within a tick of the
        // market, is either rejected by Binance or fires instantly. On a coin
        // priced at 0.0037 a 1% stop is only a few ticks away, so this has to
        // be checked rather than assumed.
        double tick = tickFor(symbol);
        double reference = markPrice(symbol);
        if (reference > 0) {
            double minGap = Math.max(tick * 3, reference * 0.0015);
            if (stopPrice > 0 && Math.abs(reference - stopPrice) < minGap) {
                stopPrice = positionIsLong ? reference - minGap : reference + minGap;
            }
            if (targetPrice > 0 && Math.abs(targetPrice - reference) < minGap) {
                targetPrice = positionIsLong ? reference + minGap : reference - minGap;
            }
        }
        if (stopPrice <= 0 && targetPrice <= 0) {
            return "no usable stop or target level for " + symbol;
        }

        // What this position already has, from our own record first. The
        // ledger is the authority: a position either has a protection record
        // or it does not, which makes duplication structurally impossible
        // rather than merely unlikely.
        String posId = positionKey(symbol, positionIsLong, entryForId);
        ProtectionLedger.Record record =
                appContext == null ? null : ProtectionLedger.get(appContext, posId);
        if (record != null && record.complete()) return null;
        if (record != null && record.hasStop()) stopPrice = 0;
        if (record != null && record.hasTarget()) targetPrice = 0;

        // Then confirm against the exchange, and FAIL CLOSED if it cannot be
        // confirmed. An unverifiable query used to fall through to "create
        // both", which a 5s sweep turned into hundreds of orders.
        boolean[] have = protectionStatus(symbol);
        if (!have[2]) {
            return "could not verify existing protection for " + symbol
                    + " — not creating more orders";
        }
        if (have[0]) stopPrice = 0;
        if (have[1]) targetPrice = 0;
        if (stopPrice <= 0 && targetPrice <= 0) return null;

        // Lock this position while we work. Released in the finally below.
        if (!PROTECTING.add(posId)) {
            return null;   // another thread is already placing it
        }
        try {
            return placeProtection(symbol, positionIsLong, stopPrice, targetPrice,
                    quantity, posId);
        } finally {
            PROTECTING.remove(posId);
        }
    }

    private String placeProtection(String symbol, boolean positionIsLong,
                                   double stopPrice, double targetPrice,
                                   double quantity, String posId) throws Exception {
        // Backstop: never place protection for the same symbol twice a minute.
        String rateKey = variantKey() + ":" + symbol.toUpperCase();
        Long last = LAST_PROTECTION_AT.get(rateKey);
        long now = System.currentTimeMillis();
        if (last != null && now - last.longValue() < 60000L) {
            return null;
        }
        LAST_PROTECTION_AT.put(rateKey, Long.valueOf(now));

        StringBuilder problems = new StringBuilder();

        for (int leg = 0; leg < 2; leg++) {
            boolean isStop = (leg == 0);
            double trigger = isStop ? stopPrice : targetPrice;
            if (trigger <= 0) continue;

            // Prefer the shape this account has already accepted; otherwise
            // walk the whole table until one is taken.
            Integer known = knownVariant();
            int from = known == null ? 0 : known.intValue();
            String lastError = null;
            boolean placed = false;

            for (int attempt = 0; attempt < VARIANT_NAMES.length && !placed; attempt++) {
                int index = (from + attempt) % VARIANT_NAMES.length;
                try {
                    String[] call = buildVariant(index, symbol, positionIsLong,
                            trigger, quantity, isStop);
                    String response = callHost(call[0], "POST", call[1], call[2]);
                    rememberAlgoId(symbol, response);
                    rememberVariant(index);
                    if (appContext != null) {
                        long newId = 0;
                        try {
                            JSONObject o = new JSONObject(response);
                            newId = o.optLong("algoId", o.optLong("orderId", 0));
                        } catch (Exception ignored) {
                        }
                        ProtectionLedger.put(appContext, posId,
                                isStop ? newId : 0, isStop ? 0 : newId);
                    }
                    placed = true;
                } catch (Exception e) {
                    lastError = String.valueOf(e.getMessage());
                }
            }
            if (!placed) {
                if (problems.length() > 0) problems.append(" | ");
                problems.append(isStop ? "SL: " : "TP: ").append(lastError);
            }
        }
        return problems.length() == 0 ? null : problems.toString();
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
    private String quantise(String symbol, double quantity) {
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
                + "     key to 'Unrestricted'.\n"
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
