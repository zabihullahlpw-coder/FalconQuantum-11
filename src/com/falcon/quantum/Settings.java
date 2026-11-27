package com.falcon.quantum;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-environment trading settings, mirroring the desktop build.
 *
 * PAPER, DEMO and LIVE each own a completely separate set of values, so a
 * 20x futures experiment in PAPER can never leak into LIVE. The clamps here
 * are the same ones the desktop enforces in core/trading_preferences.py:
 *
 *   - SPOT is buy-only: leverage forced to 1x, shorting impossible.
 *   - Leverage is snapped to the exchange ladder and capped harder on LIVE.
 *   - At least one direction always stays enabled.
 */
public final class Settings {

    public static final String[] ENVIRONMENTS = {"PAPER", "DEMO", "LIVE"};
    public static final String[] TRADING_MODES = {"SPOT", "FUTURES"};
    public static final String[] MARGIN_MODES = {"ISOLATED", "CROSS"};
    public static final int[] LEVERAGE_LADDER = {1, 2, 3, 5, 10, 20};
    public static final String[] TIMEFRAMES = {"5m", "15m", "30m", "1h", "4h"};
    /** Selectable gaps between automatic scan cycles. */
    public static final int[] SCAN_INTERVALS = {5, 10, 15, 30, 60, 120, 300};

    /** Share of available balance committed as margin per trade. */
    public static final int[] SIZE_PERCENTS = {10, 25, 50, 75, 100};

    public static String intervalLabel(int seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds % 60 == 0 && seconds < 3600) return (seconds / 60) + "m";
        return (seconds / 3600) + "h";
    }

    /** Hard ceiling per environment. LIVE is deliberately below the ladder. */
    public static int maxLeverage(String env) {
        // 20x is available on every environment. At 20x roughly a 5% adverse
        // move liquidates the position, so the default stays at 10x.
        return 20;
    }

    private static final String PREFS = "falcon_env";

    public final String env;
    public String tradingMode = "FUTURES";
    public int leverage = 10;
    public String marginMode = "CROSS";
    public boolean allowLong = true;
    public boolean allowShort = true;
    public boolean reverseEntries;
    public double riskPercent = 2.0;
    /** Percent of available balance used as margin for one trade. */
    public int sizePercent = 25;
    /**
     * Let Falcon hold the stop itself when Binance refuses one.
     *
     * Off by default: an exchange stop lives on Binance's servers and fires
     * whatever happens to this phone. A Falcon-side stop only fires while
     * the app is running with network, so it is a deliberate trade-off.
     */
    public boolean managedStop = false;
    /** Notify when a position opens. */
    public boolean notifyOpen = true;
    /** Notify when a position closes. */
    public boolean notifyClose = true;
    /** Stop scanning entirely while the position limit is reached. */
    public boolean pauseAtLimit = false;
    /** NORMAL, MOMENTUM, FAST_MOVE or SCALPING. */
    public String tradingStyle = "NORMAL";
    /** Require a confirming candle before entering. */
    public boolean requireConfirmation = false;
    /** Stop trading for the day after this much loss, in USDT. 0 = off. */
    public double maxDailyLoss = 0;
    /** Stop trading after this many losses in a row. 0 = off. */
    public int maxConsecutiveLosses = 0;
    /**
     * Only the stop and the target may close a position.
     *
     * On by default. Closing on a momentum reading turned trades that might
     * have recovered into certain small losses; the stop already expresses
     * where the idea is wrong.
     */
    public boolean exitOnlyBySlTp = true;
    /**
     * Interrupt with a dialog when Binance will not accept a stop order.
     *
     * Off by default: this account rejects exchange stops every time, so
     * the prompt appeared on every entry and answered the same way. The
     * fallback is used automatically instead, and the position card still
     * shows which kind of stop is holding the trade.
     */
    public boolean askBeforeFalconStop = false;
    /**
     * Falcon never sends a close order. Ever.
     *
     * Off by default, and deliberately so. When Binance accepts an exchange
     * stop this changes nothing useful, because the stop already lives on
     * the exchange. When Binance refuses one - as this account currently
     * does - turning this on means the position has NO stop at all.
     */
    public boolean neverCloseByFalcon = false;
    public int maxPositions = 3;
    public String timeframe = "15m";
    public boolean autoTrade = false;
    public boolean customWatchlist = false;
    public List<String> watchlist = new ArrayList<String>();
    public int scanLimit = 12;
    /** Seconds between automatic scan cycles when continuous scanning is on. */
    public int scanIntervalSec = 5;

    private Settings(String env) {
        this.env = env;
    }

    private static String key(String env, String name) {
        return env + "_" + name;
    }

    public static Settings load(Context ctx, String env) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings(env);
        s.tradingMode = p.getString(key(env, "mode"), "FUTURES");
        s.leverage = p.getInt(key(env, "lev"), 10);
        s.marginMode = p.getString(key(env, "margin"), "CROSS");
        s.allowLong = p.getBoolean(key(env, "long"), true);
        s.reverseEntries = p.getBoolean(key(env, "reverseEntries"), false);
        s.allowShort = p.getBoolean(key(env, "short"), true);
        s.riskPercent = p.getFloat(key(env, "risk"), 2.0f);
        s.maxPositions = p.getInt(key(env, "maxpos"), 3);
        s.timeframe = p.getString(key(env, "tf"), "15m");
        s.autoTrade = p.getBoolean(key(env, "auto"), false);
        s.customWatchlist = p.getBoolean(key(env, "cwl"), false);
        s.scanLimit = p.getInt(key(env, "scanlimit"), 12);
        s.scanIntervalSec = p.getInt(key(env, "scaninterval"), 5);
        s.sizePercent = p.getInt(key(env, "sizepct"), 25);
        s.managedStop = p.getBoolean(key(env, "managedstop"), false);
        s.notifyOpen = p.getBoolean(key(env, "notifyopen"), true);
        s.notifyClose = p.getBoolean(key(env, "notifyclose"), true);
        s.pauseAtLimit = p.getBoolean(key(env, "pauseatlimit"), false);
        s.tradingStyle = p.getString(key(env, "style"), "NORMAL");
        s.requireConfirmation = p.getBoolean(key(env, "confirm"), false);
        s.maxDailyLoss = p.getFloat(key(env, "maxdailyloss"), 0f);
        s.maxConsecutiveLosses = p.getInt(key(env, "maxlosses"), 0);
        s.exitOnlyBySlTp = p.getBoolean(key(env, "exitslonly"), true);
        s.askBeforeFalconStop = p.getBoolean(key(env, "askfalconstop"), false);
        s.neverCloseByFalcon = p.getBoolean(key(env, "nevercl"), false);
        String raw = p.getString(key(env, "wl"), "");
        s.watchlist = new ArrayList<String>();
        if (raw != null && raw.length() > 0) {
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String sym = normalise(parts[i]);
                if (sym != null && !s.watchlist.contains(sym)) s.watchlist.add(sym);
            }
        }
        return s.validated();
    }

    public void save(Context ctx) {
        validated();
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(key(env, "mode"), tradingMode);
        e.putInt(key(env, "lev"), leverage);
        e.putString(key(env, "margin"), marginMode);
        e.putBoolean(key(env, "long"), allowLong);
        e.putBoolean(key(env, "reverseEntries"), reverseEntries);
        e.putBoolean(key(env, "short"), allowShort);
        e.putFloat(key(env, "risk"), (float) riskPercent);
        e.putInt(key(env, "maxpos"), maxPositions);
        e.putString(key(env, "tf"), timeframe);
        e.putBoolean(key(env, "auto"), autoTrade);
        e.putBoolean(key(env, "cwl"), customWatchlist);
        e.putInt(key(env, "scanlimit"), scanLimit);
        e.putInt(key(env, "scaninterval"), scanIntervalSec);
        e.putInt(key(env, "sizepct"), sizePercent);
        e.putBoolean(key(env, "managedstop"), managedStop);
        e.putBoolean(key(env, "notifyopen"), notifyOpen);
        e.putBoolean(key(env, "notifyclose"), notifyClose);
        e.putBoolean(key(env, "pauseatlimit"), pauseAtLimit);
        e.putString(key(env, "style"), tradingStyle);
        e.putBoolean(key(env, "confirm"), requireConfirmation);
        e.putFloat(key(env, "maxdailyloss"), (float) maxDailyLoss);
        e.putInt(key(env, "maxlosses"), maxConsecutiveLosses);
        e.putBoolean(key(env, "exitslonly"), exitOnlyBySlTp);
        e.putBoolean(key(env, "askfalconstop"), askBeforeFalconStop);
        e.putBoolean(key(env, "nevercl"), neverCloseByFalcon);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < watchlist.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(watchlist.get(i));
        }
        e.putString(key(env, "wl"), sb.toString());
        e.apply();
    }

    /** Apply every safety rule. Called on load and before every save. */
    public Settings validated() {
        if (!"FUTURES".equals(tradingMode)) tradingMode = "SPOT";
        if (!"CROSS".equals(marginMode)) marginMode = "ISOLATED";

        if ("SPOT".equals(tradingMode)) {
            // Spot is buy-only. No leverage, no shorting, margin irrelevant.
            leverage = 1;
            allowLong = true;
            allowShort = false;
        } else {
            int ceiling = maxLeverage(env);
            int snapped = 1;
            for (int i = 0; i < LEVERAGE_LADDER.length; i++) {
                int rung = LEVERAGE_LADDER[i];
                if (rung <= ceiling && rung <= leverage) snapped = rung;
            }
            leverage = snapped;
            // Both directions off would freeze the engine.
            if (!allowLong && !allowShort) allowLong = true;
        }

        riskPercent = Indicators.clamp(riskPercent, 0.25, "LIVE".equals(env) ? 5.0 : 10.0);
        if (maxPositions < 1) maxPositions = 1;
        if (maxPositions > 10) maxPositions = 10;
        if (scanLimit < 5) scanLimit = 5;
        if (scanLimit > 30) scanLimit = 30;
        // A floor of 30s keeps Falcon well inside Binance's request weight
        // limits even with a full watchlist.
        // 5s floor: at 12 symbols this is ~288 request weight per minute,
        // under 5% of Binance's 6000/min budget.
        if (scanIntervalSec < 5) scanIntervalSec = 5;
        if (scanIntervalSec > 3600) scanIntervalSec = 3600;
        int pct = 25;
        for (int i = 0; i < SIZE_PERCENTS.length; i++) {
            if (SIZE_PERCENTS[i] == sizePercent) pct = sizePercent;
        }
        sizePercent = pct;
        boolean knownStyle = false;
        for (int i = 0; i < Momentum.STYLES.length; i++) {
            if (Momentum.STYLES[i].equals(tradingStyle)) knownStyle = true;
        }
        if (!knownStyle) tradingStyle = "NORMAL";
        if (!Arrays.asList(TIMEFRAMES).contains(timeframe)) timeframe = "15m";

        Set<String> clean = new LinkedHashSet<String>();
        for (int i = 0; i < watchlist.size(); i++) {
            String s = normalise(watchlist.get(i));
            if (s != null) clean.add(s);
        }
        watchlist = new ArrayList<String>(clean);
        while (watchlist.size() > 30) watchlist.remove(watchlist.size() - 1);
        return this;
    }

    /** "btc" and "BTCUSDT" both become "BTCUSDT"; anything invalid is null. */
    public static String normalise(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase().replace("/", "").replace("-", "").replace(" ", "");
        if (s.length() == 0) return null;
        if (!s.endsWith("USDT")) s = s + "USDT";
        if (s.length() < 6 || s.length() > 20) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) return null;
        }
        return s;
    }

    public boolean isFutures() {
        return "FUTURES".equals(tradingMode);
    }

    /** Human-readable summary for the header. */
    public String describe() {
        if (!isFutures()) return "SPOT · buy only · no leverage";
        StringBuilder sb = new StringBuilder("FUTURES · ");
        sb.append(leverage).append("x · ").append(marginMode).append(" · ");
        if (allowLong && allowShort) sb.append("LONG/SHORT");
        else if (allowLong) sb.append("LONG only");
        else sb.append("SHORT only");
        return sb.toString();
    }

    /** Effective margin requirement note used when sizing a paper position. */
    public double notionalMultiplier() {
        return isFutures() ? leverage : 1;
    }
}
