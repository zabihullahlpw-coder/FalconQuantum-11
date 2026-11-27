package com.falcon.quantum;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Binance public market data.
 *
 * Read-only endpoints only: no API key, no signing, no order placement.
 * That is a deliberate boundary - the phone build analyses the market and
 * trades on paper; sending real orders stays on the desktop app where the
 * risk engine and account preflight live.
 */
public final class Binance {

    private static final String BASE = "https://api.binance.com";
    private static final String FUTURES_BASE = "https://fapi.binance.com";

    /**
     * Which market the scanner reads.
     *
     * Spot and futures are different symbol universes: CRCLBUSDT exists on
     * spot but not futures, and spot's PEPEUSDT is 1000PEPEUSDT on futures.
     * Scanning spot while ordering on futures offered coins that could not
     * be traded and failed with -1121, so the scanner follows the market the
     * orders will actually go to.
     */
    private static volatile boolean futuresMarket = true;

    public static void useFuturesMarket(boolean futures) {
        if (futuresMarket != futures) {
            futuresMarket = futures;
            synchronized (Binance.class) {
                universeCache = null;
                symbolCache = null;
            }
        }
    }

    public static boolean isFuturesMarket() {
        return futuresMarket;
    }

    private static String host() {
        return futuresMarket ? FUTURES_BASE : BASE;
    }

    private static String apiPrefix() {
        return futuresMarket ? "/fapi/v1" : "/api/v3";
    }
    private static final int TIMEOUT_MS = 12000;

    private Binance() {
    }

    /** One candle series. Parallel arrays keep the scanner allocation-light. */
    public static final class Candles {
        public double[] open, high, low, close, volume;
        public int size;

        Candles(int n) {
            open = new double[n];
            high = new double[n];
            low = new double[n];
            close = new double[n];
            volume = new double[n];
            size = n;
        }

        public double lastClose() {
            return size == 0 ? 0 : close[size - 1];
        }
    }

    /** A tradeable market with its 24h turnover. */
    public static final class Market {
        public final String symbol;
        public final double quoteVolume;
        public final double priceChangePct;

        Market(String symbol, double quoteVolume, double priceChangePct) {
            this.symbol = symbol;
            this.quoteVolume = quoteVolume;
            this.priceChangePct = priceChangePct;
        }
    }

    private static String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(host() + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "FalconQuantum/7.3.0");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("Binance HTTP " + code);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Candles for one symbol, oldest first. */
    public static Candles klines(String symbol, String interval, int limit) throws Exception {
        String body = get(apiPrefix() + "/klines?symbol=" + symbol
                + "&interval=" + interval + "&limit=" + limit);
        JSONArray arr = new JSONArray(body);
        Candles c = new Candles(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONArray k = arr.getJSONArray(i);
            c.open[i] = k.getDouble(1);
            c.high[i] = k.getDouble(2);
            c.low[i] = k.getDouble(3);
            c.close[i] = k.getDouble(4);
            c.volume[i] = k.getDouble(5);
        }
        return c;
    }

    /**
     * Top USDT markets by 24h turnover. Used when the operator has not
     * pinned a custom watchlist.
     */
    public static List<Market> topMarkets(int limit) throws Exception {
        String body = get(apiPrefix() + "/ticker/24hr");
        JSONArray arr = new JSONArray(body);
        java.util.Set<String> allowed = cryptoAllowList();
        List<Market> out = new ArrayList<Market>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            String s = o.optString("symbol", "");
            if (!s.endsWith("USDT")) continue;
            // Leveraged tokens are not spot markets and distort scoring.
            if (s.contains("UP") || s.contains("DOWN") || s.contains("BULL") || s.contains("BEAR")) continue;
            // A stablecoin priced in another stablecoin barely moves; its ATR
            // rounds to nothing and every "signal" is noise.
            if (isStablePair(s)) continue;
            if (isBlocked(s)) continue;
            // The ticker feed carries no product type, so cross-check the
            // symbol against the exchange's own contract list. Without this
            // the scanner ranked silver and stock perps beside crypto.
            if (allowed != null && !allowed.contains(s)) continue;
            if (isKnownTradFiTicker(s)) continue;
            double qv = o.optDouble("quoteVolume", 0);
            if (qv < 5000000) continue;
            out.add(new Market(s, qv, o.optDouble("priceChangePercent", 0)));
        }
        Collections.sort(out, new Comparator<Market>() {
            public int compare(Market a, Market b) {
                return Double.compare(b.quoteVolume, a.quoteVolume);
            }
        });
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    // The 24h ticker call costs 80 request weight - far more than the
    // klines it selects. Refreshing it every 15s would waste most of the
    // budget, so the ranking is cached; turnover leaders do not reshuffle
    // in seconds.
    private static final long UNIVERSE_TTL_MS = 180000;
    private static java.util.List<Market> universeCache = null;
    private static long universeCachedAt = 0;
    private static int universeCachedLimit = 0;

    /** Cached variant of {@link #topMarkets(int)} for fast repeat scans. */
    public static synchronized java.util.List<Market> topMarketsCached(int limit) throws Exception {
        long now = System.currentTimeMillis();
        if (universeCache != null && limit <= universeCachedLimit
                && now - universeCachedAt < UNIVERSE_TTL_MS) {
            return universeCache.size() > limit ? universeCache.subList(0, limit) : universeCache;
        }
        java.util.List<Market> fresh = topMarkets(Math.max(limit, 30));
        universeCache = fresh;
        universeCachedAt = now;
        universeCachedLimit = Math.max(limit, 30);
        return fresh.size() > limit ? fresh.subList(0, limit) : fresh;
    }

    // Full tradeable symbol list, used to power the searchable coin picker
    // and to reject typos like "DODGEUSDT" before they reach a scan.
    private static java.util.List<String> symbolCache = null;
    private static boolean symbolCacheFutures = false;
    private static long symbolCachedAt = 0;
    private static final long SYMBOL_TTL_MS = 6L * 60L * 60L * 1000L;

    /** Every USDT symbol currently open for trading, sorted alphabetically. */
    public static synchronized java.util.List<String> tradeableSymbols(boolean futures)
            throws Exception {
        long now = System.currentTimeMillis();
        if (symbolCache != null && symbolCacheFutures == futures
                && now - symbolCachedAt < SYMBOL_TTL_MS) {
            return symbolCache;
        }
        String host = futures ? FUTURES_BASE : BASE;
        String path = futures ? "/fapi/v1/exchangeInfo" : "/api/v3/exchangeInfo";
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new URL(host + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FalconQuantum/7.5");
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        } finally {
            c.disconnect();
        }
        org.json.JSONArray arr = new org.json.JSONObject(sb.toString()).optJSONArray("symbols");
        java.util.List<String> out = new ArrayList<String>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            String sym = o.optString("symbol", "");
            String status = o.optString("status", o.optString("contractStatus", ""));
            if (!sym.endsWith("USDT")) continue;
            if (!"TRADING".equals(status)) continue;
            if (isStablePair(sym)) continue;
            if (isBlocked(sym)) continue;
            if (isNonCrypto(o, sym)) continue;
            out.add(sym);
        }
        java.util.Collections.sort(out);
        symbolCache = out;
        symbolCacheFutures = futures;
        symbolCachedAt = now;
        return out;
    }

    /**
     * Symbols the exchange has refused for product-agreement reasons.
     *
     * Binance lists TradFi Perps — perpetual futures on stocks and indices —
     * on the same futures API as crypto. They need a separate signed
     * agreement, so trading them fails with -4411. Once a symbol is refused
     * it is dropped from scanning instead of being offered again.
     */
    private static final java.util.Set<String> BLOCKED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static void blockSymbol(String symbol) {
        if (symbol != null && symbol.length() > 0) {
            BLOCKED.add(symbol.toUpperCase());
            synchronized (Binance.class) {
                universeCache = null;
                symbolCache = null;
            }
        }
    }

    public static boolean isBlocked(String symbol) {
        return symbol != null && BLOCKED.contains(symbol.toUpperCase());
    }

    public static java.util.Set<String> blockedSymbols() {
        return new java.util.HashSet<String>(BLOCKED);
    }

    /** Reload a previously saved blocklist so refusals survive a restart. */
    public static void restoreBlocked(java.util.Collection<String> symbols) {
        if (symbols == null) return;
        for (String s : symbols) {
            if (s != null && s.length() > 0) BLOCKED.add(s.toUpperCase());
        }
    }

    /**
     * True for contracts that are not crypto: commodities, equities and
     * indices. Binance lists these as "TradFi Perps" on the same futures
     * API and refuses to trade them without a signed agreement (-4411).
     *
     * Detection uses whatever the exchange reports plus a ticker net,
     * because the metadata fields have changed shape over time and a
     * silver contract slipping into a crypto scan is worse than being
     * slightly over-strict.
     */
    static boolean isNonCrypto(org.json.JSONObject info, String symbol) {
        if (info != null) {
            String type = info.optString("underlyingType", "");
            if (type.length() > 0 && !"COIN".equalsIgnoreCase(type)) return true;
            org.json.JSONArray sub = info.optJSONArray("underlyingSubType");
            for (int i = 0; sub != null && i < sub.length(); i++) {
                String tag = String.valueOf(sub.optString(i)).toUpperCase();
                if (tag.contains("INDEX") || tag.contains("EQUITY") || tag.contains("STOCK")
                        || tag.contains("COMMODIT") || tag.contains("TRADFI")
                        || tag.contains("METAL") || tag.contains("FOREX")) {
                    return true;
                }
            }
        }
        return isKnownTradFiTicker(symbol);
    }

    /** Commodity and equity tickers Binance lists as perps. */
    private static boolean isKnownTradFiTicker(String symbol) {
        String base = symbol == null ? "" : symbol.toUpperCase();
        if (base.endsWith("USDT")) base = base.substring(0, base.length() - 4);
        String[] tradfi = {
                // metals and commodities
                "XAG", "XAU", "XPT", "XPD", "WTI", "BRENT", "NGAS",
                // indices
                "SPX", "NDX", "DJI", "US500", "US100", "US30",
                // equities seen on Binance TradFi perps
                "CRCL", "MU", "NVDA", "TSLA", "AAPL", "MSFT", "META", "AMZN",
                "GOOG", "GOOGL", "COIN", "MSTR", "HOOD", "PLTR", "AMD", "NFLX",
                "BABA", "INTC", "SMCI", "GME", "AMC",
        };
        for (int i = 0; i < tradfi.length; i++) {
            if (tradfi[i].equals(base)) return true;
        }
        return false;
    }

    /** Symbols confirmed tradeable as crypto, used to filter the scan list. */
    private static java.util.Set<String> cryptoAllowList = null;
    private static long cryptoAllowAt = 0;

    private static synchronized java.util.Set<String> cryptoAllowList() {
        long now = System.currentTimeMillis();
        if (cryptoAllowList != null && now - cryptoAllowAt < SYMBOL_TTL_MS) {
            return cryptoAllowList;
        }
        try {
            cryptoAllowList = new java.util.HashSet<String>(tradeableSymbols(futuresMarket));
            cryptoAllowAt = now;
        } catch (Exception e) {
            // On failure return null so the scan is not silently emptied.
            return cryptoAllowList;
        }
        return cryptoAllowList;
    }

    /** True for USDC/USDT-style pairs where both legs are stablecoins. */
    public static boolean isStablePair(String symbol) {
        String[] stables = {"USDC", "BUSD", "FDUSD", "TUSD", "DAI", "USDP", "USDD", "PAX", "EUR", "GBP"};
        String base = symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
        for (int i = 0; i < stables.length; i++) {
            if (stables[i].equals(base)) return true;
        }
        return false;
    }

    /** Latest price for one symbol (used to mark open paper trades). */
    public static double price(String symbol) throws Exception {
        String body = get(apiPrefix() + "/ticker/price?symbol=" + symbol);
        return new org.json.JSONObject(body).optDouble("price", 0);
    }
}
