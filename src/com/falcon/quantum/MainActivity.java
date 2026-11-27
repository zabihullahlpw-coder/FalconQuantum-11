package com.falcon.quantum;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Falcon Quantum mobile - full desktop parity on the phone.
 *
 * PAPER / DEMO / LIVE are fully separate environments, each with its own
 * market type (Spot or Futures), leverage, margin mode, direction
 * permissions, risk settings, watchlist and ledger.
 *
 * PAPER is simulated locally. DEMO trades Binance testnet with fake funds.
 * LIVE can place real orders but only after the operator arms it explicitly.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "falcon_prefs";
    private static final String KEY_URL = "server_url";
    private static final String KEY_ENV = "active_env";

    private static final int BG = 0xFF070C16;
    private static final int PANEL = 0xFF111F36;
    private static final int PANEL2 = 0xFF152943;
    private static final int TEXT = 0xFFEEF4FC;
    private static final int MUTED = 0xFF8EA3C0;
    private static final int ACCENT = 0xFF5FC9FF;
    private static final int GREEN = 0xFF17B881;
    private static final int RED = 0xFFEF4B52;
    private static final int AMBER = 0xFFF2B84B;

    private LinearLayout envBar, tabBar, content;
    private TextView header, statusLine;
    private WebView webView;

    private Strategy strategy;
    private Settings cfg;
    private Paper paper;
    private Journal journal;
    private String env = "PAPER";
    private String tab = "SCAN";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private final List<Engine.Row> lastRows = new ArrayList<Engine.Row>();
    private final java.util.HashMap<String, Double> markCache = new java.util.HashMap<String, Double>();
    private boolean scanning = false;
    /** Continuous scanning state. */
    private boolean autoScan = false;
    private long nextScanAt = 0;
    private int scanCycles = 0;
    private Runnable scanTask, tickTask;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        paper = new Paper(this);
        journal = new Journal(this);
        pool = Executors.newFixedThreadPool(4);
        env = prefs().getString(KEY_ENV, "PAPER");
        strategy = Strategy.load(this);
        cfg = Settings.load(this, env);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        // Extra top room so the header clears tall-display camera cutouts.
        root.setPadding(0, dp(26), 0, 0);

        header = new TextView(this);
        header.setText("FALCON QUANTUM");
        header.setTextColor(TEXT);
        header.setTextSize(18);
        header.setPadding(dp(16), dp(4), dp(16), 0);
        root.addView(header);

        statusLine = new TextView(this);
        statusLine.setTextColor(MUTED);
        statusLine.setTextSize(11);
        statusLine.setMaxLines(4);
        statusLine.setPadding(dp(16), dp(2), dp(16), dp(8));
        root.addView(statusLine);

        envBar = new LinearLayout(this);
        envBar.setOrientation(LinearLayout.HORIZONTAL);
        envBar.setPadding(dp(10), 0, dp(10), dp(6));
        root.addView(envBar);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabBar);
        tabScroll.setPadding(dp(10), 0, dp(10), dp(6));
        root.addView(tabScroll);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(28));
        scroll.addView(content);
        root.addView(scroll);

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        configureWebView();
        root.addView(webView);

        setContentView(root);
        // Survive an app restart: if background scanning was left on, bring
        // the service back rather than silently stopping.
        // Symbols the exchange has permanently refused stay refused.
        Binance.restoreBlocked(prefs().getStringSet("blocked_symbols",
                new java.util.HashSet<String>()));

        show("SCAN");
        // Scanning has exactly one switch: KEY_AUTOSCAN. The background
        // service is not a second one - it only ever carries work that is
        // already running, and only while the app is out of the foreground.
        if (prefs().getBoolean(KEY_AUTOSCAN, false)) startAutoScan();
    }

    // --------------------------------------------------------------- chrome

    private void buildEnvBar() {
        envBar.removeAllViews();
        for (int i = 0; i < Settings.ENVIRONMENTS.length; i++) {
            final String e = Settings.ENVIRONMENTS[i];
            boolean active = e.equals(env);
            Button b = new Button(this);
            b.setText(e);
            b.setTextSize(12);
            b.setTextColor(active ? Color.WHITE : MUTED);
            b.setBackgroundColor(active ? ("LIVE".equals(e) ? 0xFF6E2230 : 0xFF1D3554) : PANEL);
            b.setMinHeight(dp(44));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (autoScan) stopAutoScan(null);
                    env = e;
                    prefs().edit().putString(KEY_ENV, e).apply();
                    cfg = Settings.load(MainActivity.this, e);
                    lastRows.clear();
                    livePositions = new ArrayList<Trader.Position>();
                    liveFree = Double.NaN;
                    liveWallet = Double.NaN;
                    liveUpnl = 0;
                    liveError = null;
                    show(tab);
                }
            });
            envBar.addView(b);
        }
    }

    private void buildTabs() {
        tabBar.removeAllViews();
        String[] tabs = {"SCAN", "HOLDING", "TRADES", "HISTORY", "LEARN", "ERRORS",
                "MARKET", "STRATEGY", "COINS", "ACCOUNT", "PC"};
        for (int i = 0; i < tabs.length; i++) {
            final String t = tabs[i];
            boolean active = t.equals(tab);
            Button b = new Button(this);
            b.setText(t);
            b.setTextSize(11);
            b.setTextColor(active ? Color.WHITE : MUTED);
            b.setBackgroundColor(active ? 0xFF1D3554 : PANEL);
            b.setMinWidth(dp(86));
            b.setMinHeight(dp(42));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    show(t);
                }
            });
            tabBar.addView(b);
        }
    }

    /**
     * Redraw whatever page the user is currently on. Background callbacks
     * use this instead of show("SOMETAB"), so finishing a scan or loading
     * the account can never pull the user off the page they are editing.
     */
    private void refresh() {
        show(tab);
    }

    /** Last exchange load, used to stop a refresh triggering another load. */
    private long lastExchangeLoadAt = 0;

    /** Per symbol: is the stop sitting on Binance, or held by Falcon? */
    private final java.util.HashMap<String, Boolean> exchangeProtected =
            new java.util.HashMap<String, Boolean>();
    /** Binance algo order ids per symbol, shown as proof of placement. */
    private final java.util.HashMap<String, String> algoIds =
            new java.util.HashMap<String, String>();

    /** Hedge or one-way, as reported by the exchange. */
    private String positionModeLabel = null;

    /** Per symbol: why the exchange refused, when it did. */
    private final java.util.HashMap<String, String> protectionError =
            new java.util.HashMap<String, String>();

    /**
     * The stop and target intended for each open symbol, kept on disk so a
     * crash or restart does not lose them. The protection sweep uses these
     * to restore the original levels rather than a generic fallback.
     */
    private void rememberPlan(String symbol, double sl, double tp) {
        prefs().edit().putString("plan_" + env + "_" + symbol, sl + "|" + tp).apply();
    }

    private double[] recallPlan(String symbol) {
        String raw = prefs().getString("plan_" + env + "_" + symbol, "");
        if (raw == null || raw.indexOf('|') < 0) return new double[]{0, 0};
        try {
            String[] parts = raw.split("\\|");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    /** True while show() is building the page, to block re-entry. */
    private boolean rendering = false;

    private void show(String t) {
        if (rendering) {
            // Something called back into show() mid-render. Ignore it: the
            // render in progress will finish with current state anyway.
            tab = t;
            return;
        }
        rendering = true;
        try {
            showInternal(t);
        } finally {
            rendering = false;
        }
    }

    private void showInternal(String t) {
        tab = t;
        cfg = Settings.load(this, env);
        Binance.useFuturesMarket(cfg.isFutures());
        buildEnvBar();
        buildTabs();
        content.removeAllViews();
        statusLine.setText(env + " · " + cfg.describe());

        if ("PC".equals(t)) {
            renderPc();
            return;
        }
        webView.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);

        if ("HOLDING".equals(t)) {
            if (!"PAPER".equals(env) && Trader.hasKeys(this, env) && !liveLoading
                    && System.currentTimeMillis() - lastExchangeLoadAt > 3000) {
                loadExchange();
            }
            renderHolding();
            return;
        }
        if ("LEARN".equals(t)) {
            renderLearn();
            return;
        }
        if ("HISTORY".equals(t)) {
            renderHistory();
            return;
        }
        if ("ERRORS".equals(t)) {
            renderErrors();
            return;
        }
        if ("SCAN".equals(t)) renderScan();
        else if ("TRADES".equals(t)) {
            // Selecting the tab is the request to see current data, but a
            // redraw right after a load must not start another one.
            boolean stale = System.currentTimeMillis() - lastExchangeLoadAt > 3000;
            if (!"PAPER".equals(env) && Trader.hasKeys(this, env) && !liveLoading && stale) {
                loadExchange();
            }
            renderTrades();
        }
        else if ("MARKET".equals(t)) renderMarket();
        else if ("STRATEGY".equals(t)) renderStrategy();
        else if ("COINS".equals(t)) renderCoins();
        else renderAccount();
    }

    // ----------------------------------------------------------------- SCAN

    private void renderScan() {
        LinearLayout c = card();
        c.addView(text(env + " scanner · " + cfg.timeframe + " · " + cfg.describe(), 12, MUTED));
        // One button: starts a repeating scan, and stops it again.
        Button scan = bigButton(autoScan ? "STOP SCAN" : "START SCAN", autoScan ? RED : GREEN);
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (autoScan) stopAutoScan("Scanning stopped");
                else startAutoScan();
            }
        });
        c.addView(scan);

        if (autoScan) {
            c.addView(text(scanning
                    ? "Scanning now…"
                    : "Next scan in " + secondsToNextScan() + "s  ·  cycles " + scanCycles
                      + "  ·  screen kept on", 11, ACCENT));
        } else {
            c.addView(text("Repeats every " + Settings.intervalLabel(cfg.scanIntervalSec)
                    + " until you stop it.", 11, MUTED));
        }

        c.addView(text("Scan every", 12, MUTED));
        String[] opts = new String[Settings.SCAN_INTERVALS.length];
        for (int i = 0; i < opts.length; i++) {
            opts[i] = Settings.intervalLabel(Settings.SCAN_INTERVALS[i]);
        }
        c.addView(choiceRow(opts, Settings.intervalLabel(cfg.scanIntervalSec), new OnChoice() {
            public void pick(String v) {
                for (int i = 0; i < Settings.SCAN_INTERVALS.length; i++) {
                    if (Settings.intervalLabel(Settings.SCAN_INTERVALS[i]).equals(v)) {
                        cfg.scanIntervalSec = Settings.SCAN_INTERVALS[i];
                        break;
                    }
                }
                cfg.save(MainActivity.this);
                if (autoScan) scheduleNextScan();
                show("SCAN");
            }
        }));
        content.addView(c);

        if (lastRows.isEmpty()) {
            content.addView(text("No scan yet. Tap RUN SCAN.", 12, MUTED));
            return;
        }
        for (int i = 0; i < lastRows.size(); i++) {
            final Engine.Row r = lastRows.get(i);
            LinearLayout row = card();
            String gradeTag = "A".equals(r.grade) ? "  [A]" : ("B".equals(r.grade) ? "  [B]" : "");
            row.addView(text(r.symbol + "   " + r.side + "   " + r.score + "%   " + r.status
                    + gradeTag, 15,
                    "READY".equals(r.status) ? 0xFF3EE7AB
                            : "NEAR ENTRY".equals(r.status) ? AMBER : TEXT));
            if (r.market != null) {
                row.addView(text(r.market.summary + "  ·  entry quality "
                        + Math.round(r.entryQuality), 10, MUTED));
            }
            for (int k = 0; k < r.reasons.size(); k++) {
                row.addView(text("+ " + r.reasons.get(k), 10, 0xFF3EE7AB));
            }
            for (int k = 0; k < r.warnings.size(); k++) {
                row.addView(text("! " + r.warnings.get(k), 10, AMBER));
            }
            row.addView(text("L " + Math.round(r.longScore) + " / S " + Math.round(r.shortScore)
                    + "  ·  " + fmt(r.price) + "\n" + r.reason, 11, MUTED));
            if (r.momentum != null && r.momentum.score > 0) {
                row.addView(text(r.momentum.summary
                        + "  (estimate, not a forecast)", 10,
                        r.momentumVeto == null ? MUTED : AMBER));
            }
            if (r.ready) {
                Button open = bigButton("OPEN " + r.side + " (" + env + ")", 0xFF1D3554);
                open.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        confirmAndOpen(r);
                    }
                });
                row.addView(open);
            }
            content.addView(row);
        }
    }

    /**
     * Begin continuous scanning. The screen is kept on because a plain
     * Activity is paused by Android once the display sleeps, which would
     * silently stall the loop; a foreground service would be needed to
     * survive that, and this build deliberately does not run one.
     */
    private static final String KEY_AUTOSCAN = "auto_scan_on";

    private void startAutoScan() {
        prefs().edit().putBoolean(KEY_AUTOSCAN, true).apply();
        autoScan = true;
        scanCycles = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startTicker();
        show("SCAN");
        runScan();
    }

    private void stopAutoScan(String why) {
        // A stop is absolute: the in-app loop, any queued pass, and the
        // background service all end. Nothing restarts until the user says so.
        prefs().edit().putBoolean(KEY_AUTOSCAN, false).apply();
        // Stop the service, but leave the background preference alone: the
        // user chose that setting and stopping a scan is not a request to
        // change it.
        ScanService.stop(this);
        scanning = false;
        autoScan = false;
        nextScanAt = 0;
        if (scanTask != null) ui.removeCallbacks(scanTask);
        if (tickTask != null) ui.removeCallbacks(tickTask);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (why != null) statusLine.setText(why);
        if ("SCAN".equals(tab)) show("SCAN");
    }

    /** Queue the next cycle using the environment's chosen interval. */
    private void scheduleNextScan() {
        if (!autoScan) return;
        if (scanTask != null) ui.removeCallbacks(scanTask);
        long delay = Math.max(15, cfg.scanIntervalSec) * 1000L;
        nextScanAt = System.currentTimeMillis() + delay;
        scanTask = new Runnable() {
            public void run() {
                if (autoScan) runScan();
            }
        };
        ui.postDelayed(scanTask, delay);
    }

    private long secondsToNextScan() {
        long left = (nextScanAt - System.currentTimeMillis() + 999) / 1000;
        return left < 0 ? 0 : left;
    }

    /** Refreshes the countdown once a second while waiting. */
    private void startTicker() {
        if (tickTask != null) ui.removeCallbacks(tickTask);
        tickTask = new Runnable() {
            public void run() {
                if (!autoScan) return;
                if (!scanning && "SCAN".equals(tab)) show("SCAN");  // countdown only on SCAN
                ui.postDelayed(tickTask, 1000);
            }
        };
        ui.postDelayed(tickTask, 1000);
    }

    private void runScan() {
        if (scanning) return;
        // Holding at the limit: there is nothing useful a scan can do until
        // a slot frees up, and scanning anyway just burns battery and quota.
        if (cfg.pauseAtLimit && !"PAPER".equals(env) && !Double.isNaN(liveFree)
                && livePositions.size() >= cfg.maxPositions) {
            statusLine.setText("Paused: " + livePositions.size() + " of " + cfg.maxPositions
                    + " positions open. Scanning resumes when one closes.");
            if (autoScan) scheduleNextScan();
            return;
        }
        // Scan the market the orders will go to, so every coin listed is
        // actually tradeable in this environment.
        Binance.useFuturesMarket(cfg.isFutures());
        scanning = true;
        if ("SCAN".equals(tab)) show("SCAN");
        pool.execute(new Runnable() {
            public void run() {
                final List<Engine.Row> found = new ArrayList<Engine.Row>();
                String error = null;
                try {
                    List<String> symbols = new ArrayList<String>();
                    List<Binance.Market> markets = null;
                    if (cfg.customWatchlist && !cfg.watchlist.isEmpty()) {
                        symbols.addAll(cfg.watchlist);
                    } else {
                        markets = Binance.topMarketsCached(cfg.scanLimit);
                        for (int i = 0; i < markets.size(); i++) symbols.add(markets.get(i).symbol);
                    }
                    for (int i = 0; i < symbols.size(); i++) {
                        String sym = symbols.get(i);
                        double qv = 0;
                        if (markets != null && i < markets.size()) qv = markets.get(i).quoteVolume;
                        try {
                            Binance.Candles k = Binance.klines(sym, cfg.timeframe, 200);
                            if (qv <= 0) qv = 20000000;
                            Engine.Row r = Engine.analyseEntry(sym, k, qv, strategy, cfg);
                            if (r != null && !Binance.isBlocked(sym)) {
                                // Respect this environment's direction permissions.
                                if ("LONG".equals(r.side) && !cfg.allowLong) continue;
                                if ("SHORT".equals(r.side) && !cfg.allowShort) continue;
                                found.add(r);
                            }
                        } catch (Exception ignored) {
                        }
                        final int done = i + 1, total = symbols.size();
                        ui.post(new Runnable() {
                            public void run() {
                                statusLine.setText("Scanning " + done + "/" + total + "…");
                            }
                        });
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String err = error;
                ui.post(new Runnable() {
                    public void run() {
                        scanning = false;
                        lastRows.clear();
                        for (Engine.Row row : found) {
                            if (Engine.directionCurrent(row, cfg)) lastRows.add(row);
                        }
                        java.util.Collections.sort(lastRows, new java.util.Comparator<Engine.Row>() {
                            public int compare(Engine.Row a, Engine.Row b) {
                                if (a.ready != b.ready) return a.ready ? -1 : 1;
                                return Double.compare(b.score, a.score);
                            }
                        });
                        if (err != null) {
                            statusLine.setText("Market error: " + err + " — retrying");
                        } else {
                            scanCycles++;
                            statusLine.setText("Scanned " + found.size() + " markets · "
                                    + readyCount() + " ready");
                            if (cfg.autoTrade) autoTrade();
                        }
                        // Keep the loop going even if this cycle failed; a
                        // dropped connection should not end the session.
                        if (autoScan) scheduleNextScan();
                        // Only redraw when the user is actually looking at the
                        // scanner. Re-rendering while they are editing MARKET
                        // or STRATEGY threw them out of the page mid-change.
                        if ("SCAN".equals(tab)) show("SCAN");
                    }
                });
            }
        });
    }

    private int readyCount() {
        int n = 0;
        for (int i = 0; i < lastRows.size(); i++) if (lastRows.get(i).ready) n++;
        return n;
    }

    /** Guards against firing a second order while one is still in flight. */
    private boolean autoOpening = false;

    /**
     * Open qualifying setups without waiting for a tap.
     *
     * PAPER writes to the local ledger. DEMO and LIVE go through the real
     * order path, which is asynchronous, so only one entry is started per
     * scan cycle: firing several at once would race on the same balance and
     * could blow past the position limit. With a 5s cycle the limit still
     * fills within seconds.
     */
    private void autoTrade() {
        if (!cfg.autoTrade || autoOpening) return;

        if ("PAPER".equals(env)) {
            int opened = 0;
            for (int i = 0; i < lastRows.size(); i++) {
                Engine.Row r = lastRows.get(i);
                if (!r.ready) continue;
                if (paper.open(cfg, r, strategy) == null) opened++;
            }
            if (opened > 0) toast("Auto-opened " + opened + " PAPER position(s)");
            if ("SCAN".equals(tab)) refresh();
            return;
        }

        if (!Trader.hasKeys(this, env)) return;
        if ("LIVE".equals(env) && !Trader.liveArmed(this)) {
            statusLine.setText("Auto-open is on but LIVE is not armed — see the ACCOUNT tab.");
            return;
        }

        // Highest score first; lastRows is already sorted that way.
        Engine.Row best = null;
        for (int i = 0; i < lastRows.size() && best == null; i++) {
            if (lastRows.get(i).ready) best = lastRows.get(i);
        }
        if (best == null) return;

        if (!Double.isNaN(liveFree) && livePositions.size() >= cfg.maxPositions) {
            statusLine.setText("Auto-open paused: " + livePositions.size()
                    + " of " + cfg.maxPositions + " positions already open.");
            return;
        }
        // Already holding it? sendExchangeOrder re-checks against the
        // exchange, but skipping here avoids a pointless round trip.
        for (int i = 0; i < livePositions.size(); i++) {
            if (best.symbol.equals(livePositions.get(i).symbol)) return;
        }

        autoOpening = true;
        statusLine.setText("Auto-opening " + best.symbol + " " + best.side + "…");
        sendExchangeOrder(best);
    }

    private void confirmAndOpen(final Engine.Row r) {
        // Auto-open is an explicit standing instruction; asking every time
        // would defeat it.
        if (cfg.autoTrade) {
            openNow(r);
            return;
        }
        String detail = r.symbol + "  " + r.side + "\n"
                + cfg.describe() + "\n"
                + "entry " + fmt(r.entry) + "\nstop " + fmt(r.stop) + "\ntarget " + fmt(r.target);
        if ("LIVE".equals(env)) {
            detail += "\n\nLIVE uses real money.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Open " + env + " position")
                .setMessage(detail)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openNow(r);
                    }
                }).show();
    }

    /** Opens a position without asking. Shared by auto-open and the dialog. */
    private void openNow(final Engine.Row r) {
        if (!Engine.directionCurrent(r, cfg)) { toast("Direction changed. Scan again."); return; }
        if ("PAPER".equals(env)) {
            String refusal = paper.open(cfg, r, strategy);
            if (refusal != null) {
                toast("Refused: " + refusal);
                refresh();
            } else {
                toast("Opened " + r.symbol + " " + r.side);
                // A position opening is the one event worth navigating for.
                show("TRADES");
            }
            return;
        }
        // DEMO and LIVE have no local ledger: the exchange is the single
        // source of truth. If the order cannot be sent, nothing is recorded.
        if (!Trader.hasKeys(this, env)) {
            toast("No API key saved for " + env + " - see the ACCOUNT tab");
            return;
        }
        if ("LIVE".equals(env) && !Trader.liveArmed(this)) {
            toast("LIVE order sending is not armed - see the ACCOUNT tab");
            return;
        }
        sendExchangeOrder(r);
    }

    /**
     * Send a real order for DEMO or armed LIVE. Position size is derived
     * from the exchange's own available balance - never from a simulated
     * one - and is refused if the account cannot cover it.
     */
    private void sendExchangeOrder(final Engine.Row r) {
        final Settings snapshot = cfg;
        statusLine.setText("Sending " + snapshot.env + " order…");
        pool.execute(new Runnable() {
            public void run() {
                String msg;
                try {
                    Trader trader = Trader.forEnvironment(MainActivity.this, snapshot);
                    if (trader == null) {
                        msg = "Order sending is not available for " + snapshot.env;
                    } else {
                        // Size against the CURRENT price, not the scan price.
                        // A scan price is the last closed candle and can be
                        // minutes old; on a fast mover that overshoots the
                        // percentage the operator chose.
                        // If this account has already refused a stop order,
                        // do not open anything else that cannot be protected.
                        // Ask the exchange whether a stop is even possible
                        // BEFORE committing money. Previously this was only
                        // discovered after the entry filled, so a trade opened
                        // and was closed again seconds later.
                        boolean stopsOk = trader.probeStopSupport(r.symbol);
                        Boolean stopsWork = Boolean.valueOf(stopsOk);
                        if (!stopsOk && !snapshot.managedStop) {
                            // Exits belong entirely to the operator: no stop
                            // is placed and none will be enforced. Allowed,
                            // but recorded and flagged after the fill.
                            if (!snapshot.neverCloseByFalcon && snapshot.askBeforeFalconStop) {
                                final Engine.Row pending = r;
                                ui.post(new Runnable() {
                                    public void run() {
                                        autoOpening = false;
                                        statusLine.setText("Binance refused every stop-order type.");
                                        offerManagedStop(pending);
                                    }
                                });
                                return;
                            }
                            // No prompt. This account cannot hold an exchange
                            // stop, the answer never changes, so take the
                            // fallback and carry on. Which stop is protecting
                            // the trade stays visible on the position card.
                            //
                            // Unless exits have been taken away from Falcon
                            // entirely, in which case the position runs with
                            // no stop and is recorded as such after the fill.
                            if (!snapshot.neverCloseByFalcon) {
                                snapshot.managedStop = true;
                                snapshot.save(MainActivity.this);
                            }
                        }

                        // Safety stops come before anything else. A losing
                        // run is when an automated system does the most
                        // damage, so it is halted rather than left running.
                        String halt = safetyHalt(snapshot);
                        if (halt != null) {
                            journal.recordError(snapshot.env, r.symbol, "Trading halted",
                                    halt, "safety control");
                            final String h = halt;
                            ui.post(new Runnable() {
                                public void run() {
                                    autoOpening = false;
                                    statusLine.setText(h);
                                    showFullError("Trading halted", h);
                                }
                            });
                            return;
                        }

                        // Count and duplicate checks both read from the
                        // exchange, which is the only source that cannot drift
                        // out of date. The limit used to be checked against a
                        // cached list, so it let extra positions through.
                        java.util.List<Trader.Position> openNow = trader.positions();
                        if (openNow.size() >= snapshot.maxPositions) {
                            msg = "Not opening " + r.symbol + ": " + openNow.size()
                                    + " of " + snapshot.maxPositions
                                    + " positions already open on the exchange.";
                            journal.recordError(snapshot.env, r.symbol, "Position limit",
                                    msg, "limit " + snapshot.maxPositions);
                            final String limited = msg;
                            ui.post(new Runnable() {
                                public void run() {
                                    autoOpening = false;
                                    statusLine.setText(limited);
                                }
                            });
                            return;
                        }
                        boolean alreadyOpen = false;
                        for (Trader.Position held : openNow) {
                            if (r.symbol.equals(held.symbol)) alreadyOpen = true;
                        }
                        if (alreadyOpen) {
                            msg = r.symbol + " already has an open position — not opening a second one.";
                            final String already = msg;
                            ui.post(new Runnable() {
                                public void run() {
                                    autoOpening = false;
                                    statusLine.setText(already);
                                }
                            });
                            return;
                        }

                        double price = trader.markPrice(r.symbol);
                        if (price <= 0) price = r.price;
                        double free = trader.usdtBalance();
                        double margin = free * (snapshot.sizePercent / 100.0);
                        double notional = margin * snapshot.notionalMultiplier();
                        double qty = notional / price;

                        String problem = trader.validateOrder(r.symbol, qty, price);
                        if (problem != null) {
                            msg = problem;
                        } else {
                            if (snapshot.isFutures()) {
                                trader.setMarginMode(r.symbol, snapshot.marginMode);
                                trader.setLeverage(r.symbol, snapshot.leverage);
                            }
                            if (!Engine.directionCurrent(r, Settings.load(MainActivity.this, snapshot.env)))
                                throw new IllegalStateException("Direction changed. Scan again.");
                            trader.marketOrder(r.symbol, "LONG".equals(r.side), qty);

                            // Rebuild stop and target around the real fill
                            // price so protection matches what was opened.
                            boolean isLong = "LONG".equals(r.side);
                            double risk = Math.abs(r.entry - r.stop);
                            if (risk <= 0) risk = price * 0.01;
                            double sl = isLong ? price - risk : price + risk;
                            double reward = r.reversed ? Math.abs(r.target - r.entry) : risk * 2;
                            double tp = isLong ? price + reward : price - reward;
                            rememberPlan(r.symbol, sl, tp);
                            String guard = trader.attachStopAndTarget(r.symbol, isLong, sl, tp,
                                    Double.parseDouble(String.valueOf(qty)), price);
                            if (guard == null) {
                                // Confirm with the exchange rather than trusting
                                // that the request was accepted.
                                try {
                                    boolean[] have = trader.protectionPresent(r.symbol);
                                    if (!have[0] || !have[1]) {
                                        guard = "exchange shows "
                                                + (have[0] ? "" : "no stop ")
                                                + (have[1] ? "" : "no target");
                                    }
                                } catch (Exception ignored) {
                                }
                            }

                            msg = "Opened " + r.symbol + " " + r.side + " "
                                    + String.format("%.2f", notional) + " USDT @ " + fmt(price)
                                    + " (" + snapshot.sizePercent + "% × "
                                    + snapshot.leverage + "x)";
                            if (guard == null) {
                                trader.rememberStopSupport(true);
                                trader.tidyProtection(r.symbol);
                                journal.recordOpen(snapshot.env, r.symbol, r.side,
                                        snapshot.leverage, snapshot.marginMode, price,
                                        qty, notional, sl, tp, "Binance");
                                msg += "\nSL " + fmt(sl) + " · TP " + fmt(tp)
                                        + " placed on the exchange.";
                            } else {
                                // No stop means no trade. Closing costs a fee;
                                // an unprotected 10x position can cost far more.
                                trader.rememberStopSupport(false);
                                if (snapshot.managedStop) {
                                    // Operator accepted a Falcon-held stop.
                                    // The level is already saved; the scan
                                    // loop enforces it from here.
                                    journal.recordOpen(snapshot.env, r.symbol, r.side,
                                            snapshot.leverage, snapshot.marginMode, price,
                                            qty, notional, sl, tp,
                                            snapshot.neverCloseByFalcon
                                                    ? "UNPROTECTED" : "Falcon-held");
                                    if (snapshot.neverCloseByFalcon) {
                                        journal.recordError(snapshot.env, r.symbol,
                                                "Opened without protection",
                                                "Binance refused a stop and Falcon is set "
                                                        + "never to close. Nothing will close "
                                                        + "this position except you.",
                                                "neverCloseByFalcon");
                                    }
                                    msg += snapshot.neverCloseByFalcon
                                            ? "\n\nNO STOP — nothing will close this position "
                                              + "except you."
                                            : "\n\nFALCON-HELD STOP at " + fmt(sl)
                                            + " (Binance refused an exchange stop). This only "
                                            + "works while Falcon is running.";
                                    final String kept = msg;
                                    ui.post(new Runnable() {
                                        public void run() {
                                            autoOpening = false;
                                            statusLine.setText(kept);
                                            showFullError("Position open — Falcon-held stop", kept);
                                            loadExchange();
                                        }
                                    });
                                    return;
                                }
                                // Only reachable when the operator has asked
                                // to be prompted AND declined the fallback.
                                // With the default settings a freshly opened
                                // position is never flattened here.
                                if (snapshot.neverCloseByFalcon || !snapshot.askBeforeFalconStop) {
                                    journal.recordOpen(snapshot.env, r.symbol, r.side,
                                            snapshot.leverage, snapshot.marginMode, price,
                                            qty, notional, sl, tp, "UNPROTECTED");
                                    msg += "\n\nNO EXCHANGE STOP — position left open. "
                                            + "Nothing will close it except you.";
                                    final String bare = msg;
                                    ui.post(new Runnable() {
                                        public void run() {
                                            autoOpening = false;
                                            statusLine.setText(bare);
                                            showFullError("Position open with no stop", bare);
                                            loadExchange();
                                        }
                                    });
                                    return;
                                }
                                String closeError = trader.emergencyClose(
                                        r.symbol, isLong, Double.parseDouble(String.valueOf(qty)));
                                if (closeError == null) {
                                    msg = "CLOSED AGAIN IMMEDIATELY: " + r.symbol
                                            + " opened but Binance refused the stop order, so the "
                                            + "position was flattened rather than left unprotected."
                                            + "\n\nReason: " + guard;
                                } else {
                                    msg = "DANGER: " + r.symbol + " IS OPEN WITH NO STOP.\n\n"
                                            + "Protection failed: " + guard
                                            + "\n\nAutomatic close also failed: " + closeError
                                            + "\n\nClose it manually in the Binance app NOW.";
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    String raw = String.valueOf(e.getMessage());
                    journal.recordError(snapshot.env, r.symbol, "Order failed", raw,
                            snapshot.sizePercent + "% x " + snapshot.leverage + "x");
                    // A product-agreement refusal is permanent until the user
                    // signs it, so stop offering the symbol rather than
                    // failing on it again every scan.
                    if (raw.contains("-4411")) {
                        Binance.blockSymbol(r.symbol);
                        prefs().edit().putStringSet("blocked_symbols",
                                Binance.blockedSymbols()).apply();
                    }
                    msg = "Order FAILED: " + Trader.explain(raw);
                }
                final String m = msg;
                final boolean failed = m.startsWith("Order FAILED")
                        || m.startsWith("Too small") || m.contains("trades in steps of")
                        || m.contains("minimum");
                ui.post(new Runnable() {
                    public void run() {
                        autoOpening = false;
                        statusLine.setText(m);
                        if (failed) {
                            lastOrderError = m;
                            showFullError("Order not placed", m);
                            refresh();
                        } else {
                            lastOrderError = null;
                            toast(m);
                            // Opened: show it. Nothing else navigates.
                            show("TRADES");
                        }
                        loadExchange();
                    }
                });
            }
        });
    }

    // --------------------------------------------------------------- TRADES

    /** Live exchange snapshot, refreshed on demand for DEMO and LIVE. */
    private java.util.List<Trader.Position> livePositions = new ArrayList<Trader.Position>();
    private double liveFree = Double.NaN, liveWallet = Double.NaN, liveUpnl = 0;
    private String liveError = null;
    private boolean liveLoading = false;

    private void renderTrades() {
        if ("PAPER".equals(env)) {
            renderPaperTrades();
            return;
        }
        renderExchangeTrades();
    }

    // ---- PAPER: local simulated ledger -----------------------------------

    private void renderPaperTrades() {
        LinearLayout head = card();
        double bal = paper.balance(env);
        double realised = paper.realisedPnl(env);
        int[] wl = paper.winLoss(env);
        head.addView(text("PAPER balance   " + fmt(bal), 18, TEXT));
        head.addView(text("Realised " + fmt(realised) + "   ·   open " + paper.openCount(env)
                + "   ·   " + wl[0] + "W / " + wl[1] + "L", 12,
                realised >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
        head.addView(text("Simulated on this phone. Not a real account.", 11, MUTED));
        content.addView(head);

        Button refresh = bigButton("REFRESH PRICES", 0xFF1D3554);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refreshPaperTrades();
            }
        });
        content.addView(refresh);

        List<Paper.Trade> open = paper.trades(env, "OPEN", 50);
        content.addView(text("Open positions (" + open.size() + ")", 13, ACCENT));
        for (int i = 0; i < open.size(); i++) {
            final Paper.Trade t = open.get(i);
            LinearLayout row = card();
            row.addView(text(t.symbol + "  " + t.side + "  " + t.market
                    + (t.leverage > 1 ? "  " + t.leverage + "x " + t.marginMode : ""), 14, TEXT));

            Double mark = markCache.get(t.symbol);
            if (mark != null) {
                double pnl = paper.unrealised(t, mark.doubleValue());
                double pct = t.margin > 0 ? pnl / t.margin * 100.0 : 0;
                row.addView(text("now " + fmt(mark.doubleValue()) + "    P/L " + fmt(pnl)
                        + "  (" + String.format("%+.2f", pct) + "%)", 14,
                        pnl >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
            } else {
                row.addView(text("Tap REFRESH PRICES for live P/L", 11, MUTED));
            }

            row.addView(text("entry " + fmt(t.entry) + "  ·  stop " + fmt(t.stop)
                    + "  ·  tp " + fmt(t.target) + liqText(t.liq)
                    + "\nmargin " + fmt(t.margin) + "  ·  notional " + fmt(t.notional), 11, MUTED));

            Button close = bigButton("CLOSE AT MARKET", PANEL2);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    closePaperTrade(t);
                }
            });
            row.addView(close);
            content.addView(row);
        }

        content.addView(text("History", 13, ACCENT));
        List<Paper.Trade> all = paper.trades(env, null, 25);
        for (int i = 0; i < all.size(); i++) {
            Paper.Trade t = all.get(i);
            if ("OPEN".equals(t.status)) continue;
            content.addView(text(t.symbol + " " + t.side + "  " + t.status + "   " + fmt(t.pnl),
                    12, t.pnl >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
        }

        Button reset = bigButton("RESET PAPER ACCOUNT", PANEL);
        reset.setTextColor(0xFFFF8A90);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Reset PAPER?")
                        .setMessage("Deletes all PAPER trades and restores the starting balance.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                paper.resetAccount(env);
                                show("TRADES");
                            }
                        }).show();
            }
        });
        content.addView(reset);
    }

    /**
     * 0 is the "no liquidation" marker. Also rejects sentinels and absurd
     * values: Double.MAX_VALUE is finite, so an isInfinite check alone let a
     * 300-digit number reach the screen. No real market prints above 1e12.
     */
    private String liqText(double liq) {
        if (liq <= 0 || Double.isNaN(liq) || Double.isInfinite(liq) || liq >= 1e12) return "";
        return "  ·  liq " + fmt(liq);
    }

    private void closePaperTrade(final Paper.Trade t) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    double p = Binance.price(t.symbol);
                    if (p > 0) paper.close(t, p, paper.unrealised(t, p), "CLOSED");
                } catch (Exception ignored) {
                }
                ui.post(new Runnable() {
                    public void run() {
                        refresh();
                    }
                });
            }
        });
    }

    private void refreshPaperTrades() {
        statusLine.setText("Updating prices…");
        pool.execute(new Runnable() {
            public void run() {
                List<Paper.Trade> open = paper.trades(env, "OPEN", 50);
                for (int i = 0; i < open.size(); i++) {
                    Paper.Trade t = open.get(i);
                    try {
                        double p = Binance.price(t.symbol);
                        if (p > 0) {
                            markCache.put(t.symbol, Double.valueOf(p));
                            paper.markToMarket(t, p);
                        }
                    } catch (Exception ignored) {
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        refresh();
                    }
                });
            }
        });
    }

    // ---- DEMO / LIVE: mirror of the real exchange account ----------------

    private void renderExchangeTrades() {
        boolean hasKeys = Trader.hasKeys(this, env);


        LinearLayout head = card();
        head.addView(text(env + " account", 16, TEXT));
        if (!hasKeys) {
            head.addView(text("No API key saved. Open the ACCOUNT tab to connect "
                    + ("DEMO".equals(env) ? "Binance testnet." : "your Binance account."), 12, AMBER));
            content.addView(head);
            return;
        }
        if (Double.isNaN(liveFree)) {
            head.addView(text(liveLoading ? "Loading from exchange…"
                    : "Tap LOAD FROM EXCHANGE to read your real balance and positions.", 12, MUTED));
        } else {
            head.addView(text("Available   " + fmt(liveFree) + " USDT", 18, TEXT));
            head.addView(text("Wallet " + fmt(liveWallet) + "   ·   unrealised " + fmt(liveUpnl)
                    + "   ·   open " + livePositions.size(), 12,
                    liveUpnl >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
            head.addView(text("Live from Binance" + ("DEMO".equals(env) ? " testnet" : "") + "."
                    + (positionModeLabel == null ? "" : "  " + positionModeLabel), 11, MUTED));
        }
        if (liveError != null) head.addView(text(liveError, 12, 0xFFFF8A90));
        content.addView(head);

        if (lastOrderError != null) {
            LinearLayout box = card();
            box.addView(text("Last order problem", 13, AMBER));
            TextView msg = text(lastOrderError, 12, 0xFFFF8A90);
            msg.setTextIsSelectable(true);
            box.addView(msg);
            Button dismiss = bigButton("DISMISS", PANEL2);
            dismiss.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    lastOrderError = null;
                    show("TRADES");
                }
            });
            box.addView(dismiss);
            content.addView(box);
        }

        Button tidy = bigButton("CANCEL ALL CONDITIONAL ORDERS", PANEL2);
        tidy.setTextColor(AMBER);
        tidy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmCancelConditional();
            }
        });
        content.addView(tidy);

        Button load = bigButton(liveLoading ? "LOADING…" : "LOAD FROM EXCHANGE", 0xFF1D3554);
        load.setEnabled(!liveLoading);
        load.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadExchange();
            }
        });
        content.addView(load);

        if (Double.isNaN(liveFree)) return;

        if (!cfg.isFutures()) {
            content.addView(text("Spot holdings are not positions. Switch this environment to "
                    + "FUTURES in the MARKET tab to track open positions here.", 12, MUTED));
            return;
        }

        content.addView(text("Open positions (" + livePositions.size() + ")", 13, ACCENT));
        if (livePositions.isEmpty()) {
            content.addView(text("No open positions on the exchange.", 12, MUTED));
        }
        for (int i = 0; i < livePositions.size(); i++) {
            final Trader.Position pos = livePositions.get(i);
            LinearLayout row = card();
            row.addView(text(pos.symbol + "  " + pos.side + "  " + pos.leverage + "x "
                    + pos.marginMode, 14, TEXT));
            double basis = pos.entry * pos.qty;
            double pct = basis > 0 ? pos.unrealised / basis * 100.0 * pos.leverage : 0;
            row.addView(text("now " + fmt(pos.mark) + "    P/L " + fmt(pos.unrealised)
                    + "  (" + String.format("%+.2f", pct) + "%)", 14,
                    pos.unrealised >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
            row.addView(text("entry " + fmt(pos.entry) + "  ·  size " + fmt(pos.qty)
                    + liqText(pos.liquidation), 11, MUTED));

            // The stop and target always appear, whoever is enforcing them.
            // Not showing them when Binance refused left the operator unable
            // to see the levels their position was actually running against.
            double[] plan = recallPlan(pos.symbol);
            Boolean onExchange = exchangeProtected.get(pos.symbol);
            if (plan[0] > 0 || plan[1] > 0) {
                boolean live = Boolean.TRUE.equals(onExchange);
                String levels = "SL " + (plan[0] > 0 ? fmt(plan[0]) : "—")
                        + (live && plan[0] > 0 ? " Active" : "")
                        + "   ·   TP " + (plan[1] > 0 ? fmt(plan[1]) : "—")
                        + (live && plan[1] > 0 ? " Active" : "");
                row.addView(text(levels, 14,
                        Boolean.TRUE.equals(onExchange) ? 0xFF3EE7AB : AMBER));
                if (Boolean.TRUE.equals(onExchange)) {
                    row.addView(text("Protection: Binance Active — fires even if this phone "
                            + "is off.", 11, MUTED));
                } else {
                    row.addView(text("Held by Falcon — only fires while the app is running. "
                            + "Checked every 3s.", 11, AMBER));
                    String why = protectionError.get(pos.symbol);
                    if (why != null) {
                        TextView w = text("Binance refused: " + why, 10, 0xFFFF8A90);
                        w.setTextIsSelectable(true);
                        row.addView(w);
                    }
                }
            } else {
                row.addView(text("NO STOP SET — this position is unprotected.", 14, 0xFFFF8A90));
            }
            Button close = bigButton("CLOSE AT MARKET", PANEL2);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    confirmCloseExchange(pos);
                }
            });
            row.addView(close);
            content.addView(row);
        }
    }

    /**
     * Clear every conditional order on the account, then let one clean pair
     * be placed. Recovery for duplicates already sitting on the exchange.
     */
    private void confirmCancelConditional() {
        new AlertDialog.Builder(this)
                .setTitle("Cancel all conditional orders?")
                .setMessage("This cancels every stop and take-profit order on " + env
                        + ", for all symbols.\n\nYour open positions stay open — but they "
                        + "will be unprotected until Falcon places a new stop on the next "
                        + "refresh.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Cancel them", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final Settings snapshot = cfg;
                        statusLine.setText("Cancelling conditional orders…");
                        pool.execute(new Runnable() {
                            public void run() {
                                String result;
                                try {
                                    Trader t = Trader.forEnvironment(MainActivity.this, snapshot);
                                    if (t == null) {
                                        result = "Order sending is not armed for " + snapshot.env;
                                    } else {
                                        java.util.Set<String> symbols =
                                                new java.util.HashSet<String>();
                                        for (Trader.Position held : t.positions()) {
                                            symbols.add(held.symbol);
                                        }
                                        for (int i = 0; i < livePositions.size(); i++) {
                                            symbols.add(livePositions.get(i).symbol);
                                        }
                                        for (String sym : symbols) {
                                            t.cancelAllConditional(sym);
                                        }
                                        result = "Cleared conditional orders on "
                                                + symbols.size() + " symbol(s).";
                                    }
                                } catch (Exception e) {
                                    result = "Cleanup failed: " + e.getMessage();
                                }
                                final String r = result;
                                ui.post(new Runnable() {
                                    public void run() {
                                        statusLine.setText(r);
                                        toast(r);
                                        loadExchange();
                                    }
                                });
                            }
                        });
                    }
                }).show();
    }

    /**
     * Finish any journal row whose position no longer exists, using
     * Binance's own realised-profit record as the source of truth.
     */
    private void reconcileHistory(Trader trader, String env,
                                  java.util.List<Trader.Position> live) {
        java.util.Set<String> open = new java.util.HashSet<String>();
        for (int i = 0; i < live.size(); i++) open.add(live.get(i).symbol.toUpperCase());

        java.util.List<Journal.Trade> rows = journal.history(env, 200);
        for (int i = 0; i < rows.size(); i++) {
            Journal.Trade t = rows.get(i);
            if (!"OPEN".equals(t.status)) continue;
            if (open.contains(t.symbol.toUpperCase())) continue;

            double pnl = 0;
            String how = "CLOSED";
            try {
                pnl = trader.realisedPnlSince(t.symbol, t.openedAt);
            } catch (Exception e) {
                journal.recordError(env, t.symbol, "History reconcile",
                        String.valueOf(e.getMessage()), "realised pnl lookup");
            }
            double exit = t.entry;
            if (t.qty > 0 && t.entry > 0) {
                // Derive the exit implied by the realised result.
                boolean isLong = "LONG".equals(t.side);
                exit = isLong ? t.entry + pnl / t.qty : t.entry - pnl / t.qty;
                if (t.sl > 0 && Math.abs(exit - t.sl) < Math.abs(exit - t.tp)) how = "STOP";
                else if (t.tp > 0) how = "TARGET";
            }
            journal.recordClose(env, t.symbol, exit, pnl, how);
        }
    }

    /** Null when trading may continue, otherwise the reason it must not. */
    private String safetyHalt(Settings cfg) {
        if (cfg.maxDailyLoss > 0) {
            double today = journal.todayPnl(cfg.env);
            if (today <= -Math.abs(cfg.maxDailyLoss)) {
                return String.format("Daily loss limit reached: %.4f USDT lost today, "
                        + "limit %.2f. Trading is halted until tomorrow.",
                        today, cfg.maxDailyLoss);
            }
        }
        if (cfg.maxConsecutiveLosses > 0) {
            int run = journal.consecutiveLosses(cfg.env);
            if (run >= cfg.maxConsecutiveLosses) {
                return run + " losses in a row, limit " + cfg.maxConsecutiveLosses
                        + ". Trading is paused — review the ERRORS and HISTORY tabs before "
                        + "resuming, or raise the limit in MARKET.";
            }
        }
        return null;
    }

    private void loadExchange() {
        final Settings snapshot = cfg;
        liveLoading = true;
        liveError = null;
        lastExchangeLoadAt = System.currentTimeMillis();
        // Deliberately no redraw here. show() calls this while it is midway
        // through building the page; redrawing from inside re-entered show()
        // and appended a second copy of every card.
        pool.execute(new Runnable() {
            public void run() {
                java.util.List<Trader.Position> pos = new ArrayList<Trader.Position>();
                double free = Double.NaN, wallet = 0, upnl = 0;
                String err = null;
                try {
                    Trader t = Trader.forReading(MainActivity.this, snapshot);
                    if (t == null) {
                        err = "No API key saved for " + snapshot.env;
                    } else {
                        // Settle the position mode here, while we are already
                        // talking to the exchange, so the first order never
                        // has to guess whether positionSide is required.
                        final boolean hedge = t.hedgeMode();
                        ui.post(new Runnable() {
                            public void run() {
                                positionModeLabel = hedge ? "Hedge Mode" : "One-way Mode";
                            }
                        });
                        free = t.usdtBalance();
                        wallet = t.walletBalance();
                        upnl = t.unrealisedTotal();
                        pos = t.positions();

                        // Self-heal: any position without a stop gets one now.
                        // This is what covers a crash or a dropped connection
                        // between the entry and its protection.
                        Trader writer = Trader.forEnvironment(MainActivity.this, snapshot);
                        if (writer != null) {
                            // Adopt anything Binance already holds, so a
                            // restart attaches rather than duplicating.
                            writer.importExistingProtection();
                            // Clear protection belonging to positions that
                            // have since closed, before adding anything new.
                            writer.reconcileProtection();
                            // Close out journal rows whose position is gone.
                            // A stop that fires on Binance, or a close done
                            // in the Binance app, is never seen by Falcon -
                            // without this those trades stay OPEN forever
                            // with no P/L, which is why the totals were empty.
                            reconcileHistory(writer, snapshot.env, pos);
                            StringBuilder bare = new StringBuilder();
                            for (int i = 0; i < pos.size(); i++) {
                                Trader.Position held = pos.get(i);
                                double[] plan = recallPlan(held.symbol);
                                String problem = writer.ensureProtection(held, plan[0], plan[1]);
                                exchangeProtected.put(held.symbol, Boolean.valueOf(problem == null));
                                if (problem == null) {
                                    // Protection is in place: drop any earlier
                                    // failure so a stale message cannot linger.
                                    protectionError.remove(held.symbol);
                                    if (lastOrderError != null
                                            && lastOrderError.contains(held.symbol)) {
                                        lastOrderError = null;
                                    }
                                    writer.tidyProtection(held.symbol);
                                } else {
                                    protectionError.put(held.symbol, problem);
                                    journal.recordError(snapshot.env, held.symbol,
                                            "Protection failed", problem, "sweep");
                                    writer.rememberStopSupport(false);
                                    if (bare.length() > 0) bare.append(", ");
                                    bare.append(held.symbol);
                                }
                            }
                            // No stop enforcement here either. Loading the
                            // account is a read; it must not close trades.
                            // The background watcher owns that, with a grace
                            // period after entry and the never-close setting
                            // respected.
                            if (bare.length() > 0) {
                                err = "UNPROTECTED: " + bare + " have no stop on the exchange and "
                                        + "Binance refuses to accept one from the API. Close them "
                                        + "or set TP/SL yourself in the Binance app.";
                            }
                        }
                    }
                } catch (Exception e) {
                    err = "Exchange error: " + e.getMessage();
                }
                final java.util.List<Trader.Position> fpos = pos;
                final double ffree = free, fwallet = wallet, fupnl = upnl;
                final String ferr = err;
                ui.post(new Runnable() {
                    public void run() {
                        liveLoading = false;
                        livePositions = fpos;
                        liveFree = ffree;
                        liveWallet = fwallet;
                        liveUpnl = fupnl;
                        liveError = ferr;
                        lastExchangeLoadAt = System.currentTimeMillis();
                        refresh();
                    }
                });
            }
        });
    }

    private void confirmCloseExchange(final Trader.Position pos) {
        new AlertDialog.Builder(this)
                .setTitle("Close " + pos.symbol + "?")
                .setMessage("Sends a reduce-only market order to close this "
                        + pos.side + " position on " + env + ".")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Close", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final Settings snapshot = cfg;
                        pool.execute(new Runnable() {
                            public void run() {
                                String msg;
                                try {
                                    Trader t = Trader.forEnvironment(MainActivity.this, snapshot);
                                    if (t == null) msg = "Order sending is not armed for " + snapshot.env;
                                    else {
                                        t.closePosition(pos);
                                        // Clear the stop so it cannot trigger
                                        // later and re-open the trade.
                                        t.cancelProtection(pos.symbol);
                                        ProtectionLedger.clear(MainActivity.this, snapshot.env);
                                        journal.recordClose(snapshot.env, pos.symbol,
                                                pos.mark, pos.unrealised, "CLOSED");
                                        prefs().edit()
                                                .remove("plan_" + env + "_" + pos.symbol).apply();
                                        msg = "Close order sent for " + pos.symbol;
                                    }
                                } catch (Exception e) {
                                    msg = "Close FAILED: " + e.getMessage();
                                }
                                final String m = msg;
                                ui.post(new Runnable() {
                                    public void run() {
                                        toast(m);
                                        loadExchange();
                                    }
                                });
                            }
                        });
                    }
                }).show();
    }

    // --------------------------------------------------------------- MARKET

    /**
     * Positions currently open, with the manager's live verdict on each.
     *
     * The scanner's job ends at entry; this is where a position is watched
     * afterwards - whether it is still working, how much of the target it
     * has covered, and how much it has handed back from its best point.
     */
    private void renderHolding() {
        LinearLayout head = card();
        head.addView(text(env + " open positions", 16, TEXT));
        if ("PAPER".equals(env)) {
            head.addView(text("Position management runs on DEMO and LIVE.", 12, MUTED));
            content.addView(head);
            return;
        }
        if (!Trader.hasKeys(this, env)) {
            head.addView(text("Connect an API key in the ACCOUNT tab.", 12, AMBER));
            content.addView(head);
            return;
        }
        head.addView(text(livePositions.size() + " held · reviewed every scan cycle", 12, MUTED));
        content.addView(head);

        Button refresh = bigButton(liveLoading ? "LOADING…" : "REFRESH", 0xFF1D3554);
        refresh.setEnabled(!liveLoading);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadExchange();
            }
        });
        content.addView(refresh);

        if (livePositions.isEmpty()) {
            content.addView(text("Nothing open. The scanner is looking for the next setup.",
                    12, MUTED));
            return;
        }

        for (int i = 0; i < livePositions.size(); i++) {
            final Trader.Position pos = livePositions.get(i);
            boolean isLong = "LONG".equals(pos.side);
            double[] plan = recallPlan(pos.symbol);
            double mark = pos.mark > 0 ? pos.mark : pos.entry;
            double stop = plan[0] > 0 ? plan[0] : (isLong ? pos.entry * 0.99 : pos.entry * 1.01);
            double target = plan[1] > 0 ? plan[1] : (isLong ? pos.entry * 1.02 : pos.entry * 0.98);

            Double peak = peakMove.get(pos.symbol);
            double moved = isLong ? mark - pos.entry : pos.entry - mark;
            double best = Math.max(moved, peak == null ? 0 : peak.doubleValue());
            peakMove.put(pos.symbol, Double.valueOf(best));

            PositionManager.Verdict v = PositionManager.review(isLong, pos.entry, mark,
                    stop, target, best, null, true, cfg.exitOnlyBySlTp);

            LinearLayout row = card();
            row.addView(text(pos.symbol + "  " + pos.side + "  " + pos.leverage + "x", 15, TEXT));
            row.addView(text("now " + fmt(mark) + "   P/L " + fmt(pos.unrealised), 14,
                    pos.unrealised >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
            row.addView(text("entry " + fmt(pos.entry) + "  ·  SL " + fmt(stop)
                    + "  ·  TP " + fmt(target), 11, MUTED));
            row.addView(text(Math.round(v.progressPct) + "% of the way to target"
                    + (v.drawdownFromPeak > 5
                        ? "   ·   given back " + Math.round(v.drawdownFromPeak) + "% from best"
                        : ""), 12,
                    v.drawdownFromPeak >= 50 ? AMBER : MUTED));
            row.addView(text(v.reason, 11, v.closes() ? 0xFFFF8A90 : MUTED));

            Button close = bigButton("CLOSE NOW", PANEL2);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v2) {
                    confirmCloseExchange(pos);
                }
            });
            row.addView(close);
            content.addView(row);
        }

        content.addView(text(cfg.exitOnlyBySlTp
                ? "Exits belong to the stop and target. Falcon only moves the stop up to "
                  + "lock in profit — it will not close a position because a reading "
                  + "weakened."
                : "Falcon may close a position early when it has given back most of its "
                  + "gain or lost its reason. Turn on 'Only SL and TP close positions' in "
                  + "MARKET to stop that.", 11, MUTED));
    }

    /** Best favourable move seen per symbol, for give-back detection. */
    private final java.util.HashMap<String, Double> peakMove =
            new java.util.HashMap<String, Double>();

    /** Every position this environment has taken, with its result. */
    private void renderHistory() {
        double[] sum = journal.summary(env);
        LinearLayout head = card();
        head.addView(text(env + " history", 16, TEXT));
        head.addView(text((int) sum[0] + "W / " + (int) sum[1] + "L    net "
                + String.format("%.4f", sum[2]) + " USDT", 14,
                sum[2] >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
        content.addView(head);

        java.util.List<Journal.Trade> rows = journal.history(env, 100);
        if (rows.isEmpty()) {
            content.addView(text("No positions recorded yet.", 12, MUTED));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Journal.Trade t = rows.get(i);
            LinearLayout row = card();
            boolean open = "OPEN".equals(t.status);
            row.addView(text(t.symbol + "  " + t.side + "  " + t.leverage + "x "
                    + (t.marginMode == null ? "" : t.marginMode)
                    + "   " + (open ? "OPEN" : t.status), 14, open ? ACCENT : TEXT));
            if (!open) {
                row.addView(text("P/L " + String.format("%.4f", t.pnl) + " USDT", 15,
                        t.pnl >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
            }
            row.addView(text("entry " + fmt(t.entry)
                    + (open ? "" : "  ·  exit " + fmt(t.exit))
                    + "  ·  size " + fmt(t.qty)
                    + "  ·  " + String.format("%.2f", t.notional) + " USDT", 11, MUTED));
            row.addView(text("SL " + (t.sl > 0 ? fmt(t.sl) : "—")
                    + "  ·  TP " + (t.tp > 0 ? fmt(t.tp) : "—")
                    + "  ·  " + (t.protection == null ? "—" : t.protection), 11, MUTED));
            row.addView(text("opened " + Journal.when(t.openedAt)
                    + (open ? "" : "   closed " + Journal.when(t.closedAt)), 10, MUTED));
            content.addView(row);
        }
    }

    /**
     * What the recent record actually says, and what to change because of it.
     *
     * Nothing here is applied automatically. Each proposal changes
     * real-money behaviour, so it shows its evidence and waits for a
     * decision.
     */
    private void renderLearn() {
        LearningEngine.Review r = LearningEngine.review(journal, env);

        LinearLayout head = card();
        head.addView(text("Falcon learning review", 16, TEXT));
        head.addView(text("AI learning progress: " + r.reviewed + "/"
                + LearningEngine.REVIEW_SIZE + " reviewed", 12, ACCENT));
        head.addView(text(r.headline(), 13, r.netPnl >= 0 ? 0xFF3EE7AB : 0xFFFF8A90));
        if (r.reviewed > 0) {
            head.addView(text(String.format("average win %.4f · average loss %.4f · "
                    + "average hold %.0f min", r.averageWin, r.averageLoss,
                    r.averageHoldMinutes), 11, MUTED));
        }
        content.addView(head);

        if (!r.enoughData()) {
            LinearLayout note = card();
            note.addView(text("Not enough trades to draw conclusions", 14, AMBER));
            note.addView(text("There are " + r.reviewed + " completed trades. Below "
                    + LearningEngine.MINIMUM_CONFIDENT_SAMPLE + ", the difference between a "
                    + "real edge and luck cannot be told apart, so no changes are proposed. "
                    + "The figures above are still real — they are just not yet evidence.",
                    11, MUTED));
            content.addView(note);
            return;
        }

        java.util.List<LearningEngine.Suggestion> shown =
                new java.util.ArrayList<LearningEngine.Suggestion>();
        for (int i = 0; i < r.suggestions.size(); i++) {
            LearningEngine.Suggestion s = r.suggestions.get(i);
            if (!LearningEngine.isDismissed(this, s.id)) shown.add(s);
        }

        if (shown.isEmpty()) {
            content.addView(text("No changes proposed. Either the record shows no clear "
                    + "pattern, or every suggestion has already been handled.", 12, MUTED));
            return;
        }

        content.addView(text(shown.size() + " suggestion(s) — approval required", 13, ACCENT));
        for (int i = 0; i < shown.size(); i++) {
            final LearningEngine.Suggestion s = shown.get(i);
            LinearLayout row = card();
            row.addView(text(s.title, 15, TEXT));
            row.addView(text(s.evidence, 11, MUTED));
            row.addView(text("Based on " + s.sample + " trades"
                    + (s.confident() ? "" : " — a small sample, treat with caution"), 10,
                    s.confident() ? MUTED : AMBER));

            Button apply = bigButton("APPLY", 0xFF1D3554);
            apply.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    confirmLearning(s);
                }
            });
            row.addView(apply);

            Button skip = bigButton("DISMISS SUGGESTION", PANEL2);
            skip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    LearningEngine.dismiss(MainActivity.this, s.id);
                    refresh();
                }
            });
            row.addView(skip);
            content.addView(row);
        }

        content.addView(text("These come from counting outcomes, not from predicting the "
                + "market. They describe what has already happened on this account and can "
                + "still be wrong about what happens next.", 11, MUTED));
    }

    private void confirmLearning(final LearningEngine.Suggestion s) {
        new AlertDialog.Builder(this)
                .setTitle(s.title)
                .setMessage(s.evidence + "\n\nApply this change to " + env + "?")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String result = LearningEngine.apply(MainActivity.this, cfg, s);
                        cfg = Settings.load(MainActivity.this, env);
                        strategy = Strategy.load(MainActivity.this);
                        toast(result);
                        refresh();
                    }
                }).show();
    }

    /** Every failure, with when and what. Never sent as a notification. */
    private void renderErrors() {
        LinearLayout head = card();
        head.addView(text("Error log", 16, TEXT));
        head.addView(text(journal.errorCount() + " recorded. Errors are listed here rather "
                + "than notified, so the shade only carries real trade events.", 11, MUTED));
        content.addView(head);

        Button clear = bigButton("CLEAR LOG", PANEL2);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                journal.clearErrors();
                refresh();
            }
        });
        content.addView(clear);

        java.util.List<Journal.Problem> rows = journal.errors(200);
        if (rows.isEmpty()) {
            content.addView(text("Nothing logged.", 12, MUTED));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Journal.Problem e = rows.get(i);
            LinearLayout row = card();
            row.addView(text(Journal.when(e.at) + "   " + e.env
                    + (e.symbol == null || e.symbol.length() == 0 ? "" : "   " + e.symbol),
                    12, AMBER));
            row.addView(text(e.stage, 13, TEXT));
            TextView d = text(e.detail, 11, 0xFFFF8A90);
            d.setTextIsSelectable(true);
            row.addView(d);
            if (e.context != null && e.context.length() > 0) {
                row.addView(text(e.context, 10, MUTED));
            }
            content.addView(row);
        }
    }

    private void renderMarket() {
        content.addView(text("Market type for " + env, 13, ACCENT));
        content.addView(choiceRow(Settings.TRADING_MODES, cfg.tradingMode,
                new OnChoice() {
                    public void pick(String v) {
                        cfg.tradingMode = v;
                        cfg.save(MainActivity.this);
                        show("MARKET");
                    }
                }));

        if (!cfg.isFutures()) {
            content.addView(text("Spot is buy-only: no leverage and no shorting. "
                    + "Switch to Futures to enable shorts and leverage.", 12, MUTED));
        } else {
            content.addView(text("Leverage (max " + Settings.maxLeverage(env)
                    + "x for " + env + ")", 13, ACCENT));
            List<String> rungs = new ArrayList<String>();
            for (int i = 0; i < Settings.LEVERAGE_LADDER.length; i++) {
                int r = Settings.LEVERAGE_LADDER[i];
                if (r <= Settings.maxLeverage(env)) rungs.add(r + "x");
            }
            content.addView(choiceRow(rungs.toArray(new String[rungs.size()]),
                    cfg.leverage + "x", new OnChoice() {
                        public void pick(String v) {
                            cfg.leverage = Integer.parseInt(v.replace("x", ""));
                            cfg.save(MainActivity.this);
                            show("MARKET");
                        }
                    }));

            content.addView(text("Margin mode", 13, ACCENT));
            content.addView(choiceRow(Settings.MARGIN_MODES, cfg.marginMode, new OnChoice() {
                public void pick(String v) {
                    cfg.marginMode = v;
                    cfg.save(MainActivity.this);
                    show("MARKET");
                }
            }));

            content.addView(toggle("Reverse Trade Direction", cfg.reverseEntries, new OnToggle() {
                public void set(boolean on) {
                    cfg.reverseEntries = on;
                    cfg.save(MainActivity.this);
                    lastRows.clear();
                    show("MARKET");
                }
            }));
            content.addView(text("Direction permissions", 13, ACCENT));
            content.addView(toggle("Allow LONG entries", cfg.allowLong, new OnToggle() {
                public void set(boolean on) {
                    cfg.allowLong = on;
                    cfg.save(MainActivity.this);
                    show("MARKET");
                }
            }));
            content.addView(toggle("Allow SHORT entries", cfg.allowShort, new OnToggle() {
                public void set(boolean on) {
                    cfg.allowShort = on;
                    cfg.save(MainActivity.this);
                    show("MARKET");
                }
            }));
            content.addView(text("Leverage multiplies losses as well as gains. A "
                    + cfg.leverage + "x position is liquidated after roughly a "
                    + String.format("%.1f", 100.0 / cfg.leverage) + "% adverse move.", 12, AMBER));
        }

        content.addView(text("Timeframe", 13, ACCENT));
        content.addView(choiceRow(Settings.TIMEFRAMES, cfg.timeframe, new OnChoice() {
            public void pick(String v) {
                cfg.timeframe = v;
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));

        content.addView(text("Position size — percent of available balance", 13, ACCENT));
        String[] pcts = new String[Settings.SIZE_PERCENTS.length];
        for (int i = 0; i < pcts.length; i++) pcts[i] = Settings.SIZE_PERCENTS[i] + "%";
        content.addView(choiceRow(pcts, cfg.sizePercent + "%", new OnChoice() {
            public void pick(String v) {
                cfg.sizePercent = Integer.parseInt(v.replace("%", ""));
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));
        content.addView(sizePreview());

        content.addView(text("Max open positions: " + cfg.maxPositions, 13, ACCENT));
        content.addView(slider(cfg.maxPositions, 10, new OnSlide() {
            public void set(int v) {
                cfg.maxPositions = Math.max(1, v);
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));

        if (!"PAPER".equals(env)) {
            content.addView(text("Stop-loss handling", 13, ACCENT));
            content.addView(toggle("Let Falcon hold the stop if Binance refuses one",
                    cfg.managedStop, new OnToggle() {
                        public void set(boolean on) {
                            if (!on) {
                                cfg.managedStop = false;
                                cfg.save(MainActivity.this);
                                show("MARKET");
                                return;
                            }
                            confirmManagedStop();
                        }
                    }));
            content.addView(text(cfg.managedStop
                    ? "ON — Falcon closes the position itself at the stop level. It can only "
                      + "do that while the app is running with a network connection. If the "
                      + "phone is off or the app is killed, nothing protects the trade."
                    : "OFF — a position that cannot get a real exchange stop is closed "
                      + "immediately. Safest, but nothing will trade while Binance refuses "
                      + "stop orders for your key.", 11, cfg.managedStop ? AMBER : MUTED));
        }

        content.addView(toggle("Auto-open " + env + " trades on READY signals (no tap)",
                cfg.autoTrade, new OnToggle() {
            public void set(boolean on) {
                cfg.autoTrade = on;
                cfg.save(MainActivity.this);
            }
        }));

        content.addView(text("Trading style", 13, ACCENT));
        content.addView(choiceRow(Momentum.STYLES, cfg.tradingStyle, new OnChoice() {
            public void pick(String v) {
                cfg.tradingStyle = v;
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));
        content.addView(text(styleExplanation(cfg.tradingStyle), 11, MUTED));

        content.addView(toggle("Wait for a confirming candle before entering",
                cfg.requireConfirmation, new OnToggle() {
                    public void set(boolean on) {
                        cfg.requireConfirmation = on;
                        cfg.save(MainActivity.this);
                    }
                }));

        content.addView(toggle("Pause scanning while the position limit is reached",
                cfg.pauseAtLimit, new OnToggle() {
                    public void set(boolean on) {
                        cfg.pauseAtLimit = on;
                        cfg.save(MainActivity.this);
                    }
                }));

        content.addView(text("Exits", 13, ACCENT));
        content.addView(toggle("Only SL and TP close positions", cfg.exitOnlyBySlTp,
                new OnToggle() {
                    public void set(boolean on) {
                        cfg.exitOnlyBySlTp = on;
                        cfg.save(MainActivity.this);
                        show("MARKET");
                    }
                }));
        content.addView(text(cfg.exitOnlyBySlTp
                ? "ON — a position runs until its stop or target is hit. Falcon still raises "
                  + "the stop to secure profit, so an exit always happens through an order "
                  + "resting on Binance."
                : "OFF — Falcon may also close a position early when momentum fades or it "
                  + "gives back most of its gain. That cuts losers sooner but turns some "
                  + "recoverable trades into certain small losses.", 11,
                cfg.exitOnlyBySlTp ? MUTED : AMBER));

        content.addView(toggle("Falcon never closes a position", cfg.neverCloseByFalcon,
                new OnToggle() {
                    public void set(boolean on) {
                        if (!on) {
                            cfg.neverCloseByFalcon = false;
                            cfg.save(MainActivity.this);
                            show("MARKET");
                            return;
                        }
                        confirmNeverClose();
                    }
                }));
        content.addView(text(cfg.neverCloseByFalcon
                ? "ON — no close order is ever sent by Falcon. On this account Binance "
                  + "refuses exchange stops, so positions currently run with NO stop at all. "
                  + "Only you can close them."
                : "OFF — if Binance will not hold the stop, Falcon closes at your level "
                  + "instead. That is the only protection available while -4120 persists.",
                11, cfg.neverCloseByFalcon ? 0xFFFF8A90 : MUTED));

        content.addView(text("Safety controls", 13, ACCENT));
        content.addView(text("Daily loss limit: "
                + (cfg.maxDailyLoss <= 0 ? "off" : String.format("%.0f USDT", cfg.maxDailyLoss)),
                12, MUTED));
        content.addView(slider((int) cfg.maxDailyLoss, 200, new OnSlide() {
            public void set(int v) {
                cfg.maxDailyLoss = v;
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));
        content.addView(text("Stop after consecutive losses: "
                + (cfg.maxConsecutiveLosses <= 0 ? "off"
                   : String.valueOf(cfg.maxConsecutiveLosses)), 12, MUTED));
        content.addView(slider(cfg.maxConsecutiveLosses, 10, new OnSlide() {
            public void set(int v) {
                cfg.maxConsecutiveLosses = v;
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));
        content.addView(text("Both off by default. A losing run is where an automated "
                + "system does the most damage.", 11, MUTED));

        content.addView(text("Notifications", 13, ACCENT));
        content.addView(toggle("Notify when a position opens", cfg.notifyOpen, new OnToggle() {
            public void set(boolean on) {
                cfg.notifyOpen = on;
                cfg.save(MainActivity.this);
            }
        }));
        content.addView(toggle("Notify when a position closes", cfg.notifyClose, new OnToggle() {
            public void set(boolean on) {
                cfg.notifyClose = on;
                cfg.save(MainActivity.this);
            }
        }));
        content.addView(text("Errors are never notified — they are recorded in the ERRORS tab.",
                11, MUTED));

        renderBackgroundSection();
    }

    /** Background scanning controls, shown at the bottom of MARKET. */
    private void renderBackgroundSection() {
        content.addView(text("Background scanning", 13, ACCENT));
        content.addView(text("This decides what happens when you leave the app while a scan "
                + "is running. It never starts a scan by itself — use START SCAN for that. "
                + "Android shows a notification for as long as it keeps working.", 12, MUTED));

        content.addView(toggle("Scan in the background", ScanService.isEnabled(this),
                new OnToggle() {
                    public void set(boolean on) {
                        ScanService.setEnabled(MainActivity.this, on);
                        if (on) {
                            requestNotificationPermission();
                            toast(prefs().getBoolean(KEY_AUTOSCAN, false)
                                    ? "Scanning will continue when you leave the app"
                                    : "Enabled. Start a scan for it to take effect.");
                        } else {
                            ScanService.stop(MainActivity.this);
                            toast("Scanning will pause when you leave the app");
                        }
                        show("MARKET");
                    }
                }));

        final int secs = ScanService.intervalSeconds(this);
        content.addView(text("Background scan every " + secs + "s", 13, ACCENT));
        content.addView(slider(secs, 300, new OnSlide() {
            public void set(int v) {
                ScanService.setIntervalSeconds(MainActivity.this, Math.max(5, v));
                show("MARKET");
            }
        }));
        content.addView(text("5s is the floor and the default. A 5s cycle over 12 symbols "
                + "uses about 5% of Binance's rate limit, so the constraint is battery and "
                + "mobile data, not the exchange. Expect heavy drain at 5s.", 11, MUTED));

        if (!"PAPER".equals(env)) {
            content.addView(toggle("Allow unattended orders while minimised",
                    ScanService.backgroundAutoTrade(this), new OnToggle() {
                        public void set(boolean on) {
                            if (!on) {
                                ScanService.setBackgroundAutoTrade(MainActivity.this, false);
                                show("MARKET");
                                return;
                            }
                            confirmBackgroundTrading();
                        }
                    }));
            content.addView(text("Off: the background scanner only alerts you, and you decide. "
                    + "On: it can place " + env + " orders with nobody watching. LIVE also "
                    + "requires arming in the ACCOUNT tab.", 11, AMBER));
        }

        content.addView(text("If scanning stops on its own, exclude Falcon from battery "
                + "optimisation in Android Settings → Apps → Falcon Quantum → Battery.",
                11, MUTED));
    }

    /** Shows what the size percent and leverage produce on this account. */
    private TextView sizePreview() {
        double free = Double.isNaN(liveFree)
                ? ("PAPER".equals(env) ? paper.balance(env) : Double.NaN)
                : liveFree;
        if (Double.isNaN(free)) {
            return text("Load the account in TRADES to preview the order size.", 11, MUTED);
        }
        double margin = free * (cfg.sizePercent / 100.0);
        double notional = margin * cfg.notionalMultiplier();
        String line = String.format("%.2f free × %d%% = %.2f margin × %dx = %.2f USDT position",
                free, cfg.sizePercent, margin, cfg.leverage, notional);
        if (notional < 5.5 && !"PAPER".equals(env)) {
            return text(line + "  —  below Binance's ~5 USDT minimum, this order would be "
                    + "rejected. Raise the percent or the leverage.", 11, AMBER);
        }
        return text(line, 11, MUTED);
    }

    /** Cached list of real, tradeable symbols for the picker. */
    private java.util.List<String> symbolList = null;
    private boolean symbolsLoading = false;

    private void loadSymbolList() {
        if (symbolsLoading) return;
        symbolsLoading = true;
        final boolean futures = cfg.isFutures();
        pool.execute(new Runnable() {
            public void run() {
                java.util.List<String> got = null;
                String err = null;
                try {
                    got = Binance.tradeableSymbols(futures);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final java.util.List<String> result = got;
                final String error = err;
                ui.post(new Runnable() {
                    public void run() {
                        symbolsLoading = false;
                        symbolList = result;
                        if (error != null) statusLine.setText("Could not load coin list: " + error);
                        if ("COINS".equals(tab)) show("COINS");
                    }
                });
            }
        });
    }

    /**
     * Adds a coin only if Binance actually lists it. A typo such as
     * "DODGEUSDT" would otherwise sit in the watchlist and never scan.
     */
    private void addCoin(String raw) {
        String sym = Settings.normalise(raw);
        if (sym == null) {
            toast("Not a valid USDT pair");
            return;
        }
        if (cfg.watchlist.contains(sym)) {
            toast(sym + " is already listed");
            return;
        }
        if (symbolList != null && !symbolList.contains(sym)) {
            StringBuilder near = new StringBuilder();
            String stem = sym.replace("USDT", "");
            for (int i = 0; i < symbolList.size() && near.length() < 60; i++) {
                String candidate = symbolList.get(i);
                String base = candidate.replace("USDT", "");
                if (stem.length() >= 2 && (base.startsWith(stem.substring(0, 2))
                        || stem.startsWith(base))) {
                    if (near.length() > 0) near.append(", ");
                    near.append(candidate);
                }
            }
            showFullError("Not on Binance", sym + " is not a tradeable "
                    + (cfg.isFutures() ? "futures" : "spot") + " symbol."
                    + (near.length() > 0 ? "\n\nDid you mean: " + near : ""));
            return;
        }
        cfg.watchlist.add(sym);
        cfg.save(this);
        show("COINS");
    }

    /**
     * Binance will not hold a stop for this account. Rather than dead-ending,
     * offer the one protection that does work here - a stop Falcon enforces
     * itself - and make its weakness explicit so the choice is informed.
     */
    private void offerManagedStop(final Engine.Row row) {
        String detail = "Binance rejected every stop-order type Falcon knows for this account "
                + "(-4120), so it cannot leave a stop sitting on the exchange.\n\n"
                + "Falcon can hold the stop itself instead: it watches the price every "
                + ScanService.intervalSeconds(this) + "s and closes the position at your stop "
                + "level.\n\n"
                + "The difference matters: an exchange stop fires even if this phone is off. "
                + "A Falcon-held stop only fires while the app is running with a connection.";
        if (!ScanService.isEnabled(this)) {
            detail += "\n\nBackground scanning is currently OFF — turn it on, or the stop stops "
                    + "working the moment you leave the app.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Exchange stop not possible")
                .setMessage(detail)
                .setNeutralButton("Test again", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        testStopOrders();
                    }
                })
                .setNegativeButton("Don't trade", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        refresh();
                    }
                })
                .setPositiveButton("Use Falcon stop", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cfg.managedStop = true;
                        cfg.save(MainActivity.this);
                        if (!ScanService.isEnabled(MainActivity.this)) {
                            ScanService.setEnabled(MainActivity.this, true);
                            requestNotificationPermission();
                        }
                        toast("Falcon-held stop enabled — opening " + row.symbol);
                        sendExchangeOrder(row);
                    }
                }).show();
    }

    private String styleExplanation(String style) {
        if ("SCALPING".equals(style)) {
            return "Only setups already moving hard: momentum 80+, volume 1.6x, target "
                    + "reachable in about 20 minutes at the current pace.";
        }
        if ("FAST_MOVE".equals(style)) {
            return "Ignores slow setups: momentum 75+, volume 1.35x, target within roughly "
                    + "an hour at the current pace.";
        }
        if ("MOMENTUM".equals(style)) {
            return "Prefers setups with real participation behind them: momentum 65+, "
                    + "volume 1.1x.";
        }
        return "No momentum filter. Takes any setup the strategy score approves, including "
                + "ones that may sit for hours.";
    }

    private void confirmNeverClose() {
        new AlertDialog.Builder(this)
                .setTitle("Falcon never closes — confirm")
                .setMessage("Falcon will not send a close order for any reason.\n\n"
                        + "On an account where Binance accepts stop orders this is fine: the "
                        + "stop sits on the exchange and fires without Falcon.\n\n"
                        + "This account returns -4120, so Binance is NOT holding your stops. "
                        + "With this on, a position has no stop anywhere. At 10x leverage an "
                        + "adverse move can take the whole balance before you see it.\n\n"
                        + "Turn this on only if you will watch and close positions yourself.")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        show("MARKET");
                    }
                })
                .setPositiveButton("I will close them myself",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                cfg.neverCloseByFalcon = true;
                                cfg.save(MainActivity.this);
                                show("MARKET");
                            }
                        }).show();
    }

    private void confirmManagedStop() {
        new AlertDialog.Builder(this)
                .setTitle("Falcon-held stop?")
                .setMessage("An exchange stop sits on Binance's servers and fires even if this "
                        + "phone is off. A Falcon-held stop only fires while the app is running "
                        + "with network.\n\nIf the app is killed, the phone dies or signal drops, "
                        + "the position has no protection at all.\n\nKeep background scanning on "
                        + "and the phone charged if you enable this.")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        show("MARKET");
                    }
                })
                .setPositiveButton("I accept the risk", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cfg.managedStop = true;
                        cfg.save(MainActivity.this);
                        if (!ScanService.isEnabled(MainActivity.this)) {
                            toast("Turn on background scanning so the stop works when minimised");
                        }
                        show("MARKET");
                    }
                }).show();
    }

    private void confirmBackgroundTrading() {
        new AlertDialog.Builder(this)
                .setTitle("Unattended " + env + " orders?")
                .setMessage("Falcon will open positions while the app is minimised and you "
                        + "are not watching. Losses can happen with no one to intervene.\n\n"
                        + "Auto-open must also be on, and LIVE must be armed.")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        show("MARKET");
                    }
                })
                .setPositiveButton("Enable", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ScanService.setBackgroundAutoTrade(MainActivity.this, true);
                        show("MARKET");
                    }
                }).show();
    }

    /** Android 13+ needs explicit permission before alerts can be shown. */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 77);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- STRATEGY

    private void renderStrategy() {
        content.addView(text("Strategy preset", 13, ACCENT));
        content.addView(choiceRow(Strategy.MODES, strategy.mode, new OnChoice() {
            public void pick(String v) {
                strategy.applyPreset(v);
                strategy.save(MainActivity.this);
                show("STRATEGY");
            }
        }));

        final int floor = (int) strategy.floorFor(strategy.mode);
        final int ceiling = (int) strategy.ceilingFor(strategy.mode);
        content.addView(text("Minimum entry score: " + Math.round(strategy.entryScore)
                + "%   (" + floor + "–" + ceiling + "% in " + strategy.mode + ")", 13, ACCENT));
        SeekBar sb = new SeekBar(this);
        sb.setMax(ceiling - floor);
        sb.setProgress((int) Math.round(strategy.entryScore) - floor);
        sb.setPadding(dp(8), dp(16), dp(8), dp(16));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                strategy.entryScore = floor + s.getProgress();
                strategy.save(MainActivity.this);
                show("STRATEGY");
            }
        });
        content.addView(sb);

        content.addView(text("Strategy modules", 13, ACCENT));
        for (int i = 0; i < Strategy.MODULES.length; i++) {
            final int idx = i;
            final boolean locked = (idx == 5);
            LinearLayout row = card();
            CheckBox cb = new CheckBox(this);
            cb.setText(Strategy.MODULE_LABELS[idx] + (locked ? "  (always on)" : ""));
            cb.setTextColor(TEXT);
            cb.setTextSize(14);
            cb.setMinHeight(dp(44));
            cb.setChecked(strategy.enabled[idx]);
            cb.setEnabled(!locked);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    strategy.enabled[idx] = on;
                    strategy.save(MainActivity.this);
                    show("STRATEGY");
                }
            });
            row.addView(cb);
            if (strategy.enabled[idx]) {
                row.addView(text("Sensitivity " + Math.round(strategy.sensitivity[idx]), 11, MUTED));
                row.addView(slider((int) strategy.sensitivity[idx], 100, new OnSlide() {
                    public void set(int v) {
                        strategy.sensitivity[idx] = v;
                        strategy.save(MainActivity.this);
                        show("STRATEGY");
                    }
                }));
            }
            content.addView(row);
        }
        content.addView(text("Disabling a module removes it from the score and stops it "
                + "blocking entries. Remaining modules are reweighted to total 100.", 11, MUTED));
    }

    // ---------------------------------------------------------------- COINS

    private void renderCoins() {
        content.addView(toggle("Custom coin scanner (" + env + ")", cfg.customWatchlist,
                new OnToggle() {
                    public void set(boolean on) {
                        cfg.customWatchlist = on;
                        cfg.save(MainActivity.this);
                        show("COINS");
                    }
                }));
        content.addView(text(cfg.customWatchlist
                ? "Scanning only the coins below."
                : "Scanning the top " + cfg.scanLimit + " markets by turnover.", 12, MUTED));

        if (!cfg.customWatchlist) {
            content.addView(text("Markets to scan: " + cfg.scanLimit, 13, ACCENT));
            content.addView(slider(cfg.scanLimit, 30, new OnSlide() {
                public void set(int v) {
                    cfg.scanLimit = Math.max(5, v);
                    cfg.save(MainActivity.this);
                    show("COINS");
                }
            }));
        }

        final AutoCompleteTextView in = new AutoCompleteTextView(this);
        in.setHint(symbolList == null ? "Loading coin list…" : "Search coin, e.g. BTC or SOL");
        in.setHintTextColor(0xFF5D7390);
        in.setTextColor(TEXT);
        in.setBackgroundColor(PANEL);
        in.setPadding(dp(14), dp(14), dp(14), dp(14));
        in.setTextSize(15);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        // Suggest from the first character and always show matches on focus.
        in.setThreshold(1);
        in.setDropDownBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PANEL2));
        if (symbolList != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                    this, R.layout.dropdown_item, symbolList);
            in.setAdapter(adapter);
            in.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    in.showDropDown();
                }
            });
        } else {
            loadSymbolList();
        }
        content.addView(in);

        Button add = bigButton("ADD COIN", 0xFF1D3554);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addCoin(in.getText().toString());
            }
        });
        content.addView(add);

        if (symbolList != null) {
            content.addView(text(symbolList.size() + " tradeable USDT pairs available. "
                    + "Only symbols that exist on Binance can be added.", 11, MUTED));
        }

        content.addView(text("Watchlist (" + cfg.watchlist.size() + ")", 13, ACCENT));
        for (int i = 0; i < cfg.watchlist.size(); i++) {
            final String sym = cfg.watchlist.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(PANEL);
            row.setPadding(dp(12), dp(6), dp(12), dp(6));
            boolean known = symbolList == null || symbolList.contains(sym);
            TextView t = text(sym + (known ? "" : "   ⚠ not on Binance"),
                    14, known ? TEXT : 0xFFFF8A90);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(t);
            Button x = new Button(this);
            x.setText("REMOVE");
            x.setTextSize(11);
            x.setTextColor(0xFFFF8A90);
            x.setBackgroundColor(PANEL2);
            x.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cfg.watchlist.remove(sym);
                    cfg.save(MainActivity.this);
                    show("COINS");
                }
            });
            row.addView(x);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, 0);
            row.setLayoutParams(lp);
            content.addView(row);
        }
    }

    // -------------------------------------------------------------- ACCOUNT

    private void renderAccount() {
        content.addView(text(env + " exchange connection", 13, ACCENT));

        if ("PAPER".equals(env)) {
            content.addView(text("PAPER is fully simulated on this phone. It never contacts "
                    + "an exchange account and needs no API key.", 12, MUTED));
            return;
        }

        content.addView(text("DEMO".equals(env)
                ? "DEMO uses Binance testnet (fake funds). Create testnet keys at "
                + "testnet.binance.vision for spot, or testnet.binancefuture.com for futures."
                : "LIVE uses your real Binance account and real money.", 12,
                "LIVE".equals(env) ? AMBER : MUTED));

        final EditText k = new EditText(this);
        k.setHint("API key");
        k.setText(Trader.key(this, env));
        style(k);
        content.addView(k);

        final EditText s = new EditText(this);
        s.setHint("API secret");
        s.setText(Trader.secret(this, env));
        style(s);
        s.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(s);

        Button save = bigButton("SAVE KEYS", 0xFF1D3554);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Trader.saveKeys(MainActivity.this, env,
                        k.getText().toString(), s.getText().toString());
                toast("Keys saved — loading account…");
                // No second step: read the account straight away.
                loadExchange();
                show("ACCOUNT");
            }
        });
        content.addView(save);

        Button test = bigButton("TEST CONNECTION", PANEL2);
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testConnection();
            }
        });
        content.addView(test);

        Button stopTest = bigButton("TEST STOP ORDERS", PANEL2);
        stopTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testStopOrders();
            }
        });
        content.addView(stopTest);

        Button diag = bigButton("DIAGNOSE KEY PROBLEM", PANEL2);
        diag.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runDiagnostics();
            }
        });
        content.addView(diag);

        if (diagnosticReport != null) {
            LinearLayout box = card();
            box.addView(text("Diagnostics", 13, ACCENT));
            box.addView(text(diagnosticReport, 11, MUTED));
            content.addView(box);
        }

        if ("LIVE".equals(env)) {
            content.addView(text("Live order sending", 13, ACCENT));
            content.addView(text("While this is off, LIVE scans and tracks positions locally but "
                    + "sends nothing to the exchange. Prove your setup on DEMO first: this order "
                    + "path has not been tested against a real account.", 12, AMBER));
            content.addView(toggle("Arm LIVE order sending", Trader.liveArmed(this),
                    new OnToggle() {
                        public void set(boolean on) {
                            if (!on) {
                                Trader.setLiveArmed(MainActivity.this, false);
                                show("ACCOUNT");
                                return;
                            }
                            confirmArmLive();
                        }
                    }));
            content.addView(text("Use an API key with trading permission only. Never enable "
                    + "withdrawals on a key you put in any app.", 11, MUTED));
        }
    }

    private void confirmArmLive() {
        final EditText confirm = new EditText(this);
        confirm.setHint("Type LIVE to confirm");
        style(confirm);
        new AlertDialog.Builder(this)
                .setTitle("Arm real-money trading?")
                .setMessage("Falcon will place real orders on your Binance account. "
                        + "This path is untested against a live account. Type LIVE to confirm.")
                .setView(confirm)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        show("ACCOUNT");
                    }
                })
                .setPositiveButton("Arm", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if ("LIVE".equals(confirm.getText().toString().trim().toUpperCase())) {
                            Trader.setLiveArmed(MainActivity.this, true);
                            toast("LIVE order sending armed");
                        } else {
                            toast("Not armed - confirmation did not match");
                        }
                        show("ACCOUNT");
                    }
                }).show();
    }

    private String diagnosticReport = null;

    /**
     * Ask Binance directly which conditional-order shape it will accept.
     *
     * Each variant is a real request against the live account with a trigger
     * far from the market, cancelled immediately if accepted. Whatever is
     * accepted becomes the shape used for every future stop.
     */
    private void testStopOrders() {
        final Settings snapshot = cfg;
        if (!Trader.hasKeys(this, snapshot.env)) {
            toast("Save API keys first");
            return;
        }
        diagnosticReport = "Testing every stop-order variant against Binance…";
        show("ACCOUNT");
        pool.execute(new Runnable() {
            public void run() {
                String out;
                try {
                    Trader t = Trader.forEnvironment(MainActivity.this, snapshot);
                    if (t == null) {
                        out = "LIVE must be armed in this tab before a real order can be tested.";
                    } else {
                        String symbol = "BTCUSDT";
                        double mark = t.markPrice(symbol);
                        if (mark <= 0) mark = 60000;
                        Trader.ProtectionReport r = t.findWorkingProtection(
                                symbol, true, mark * 0.5, 0.002);
                        StringBuilder sb = new StringBuilder();
                        sb.append("Tested on ").append(symbol)
                          .append(" with a trigger far below the market.\n\n");
                        sb.append(r.log);
                        if (r.anyWorked()) {
                            sb.append("RESULT: protection works using \"")
                              .append(Trader.VARIANT_NAMES[r.workingIndex])
                              .append("\".\nFalcon will use this shape from now on, and live "
                                      + "trading is available again.");
                        } else {
                            sb.append("RESULT: none accepted yet.\n\n"
                                    + "Read the codes above rather than the wording:\n"
                                    + "  -1102 / -1104  a field is missing or misnamed — the "
                                    + "endpoint works and the account is fine, the payload is "
                                    + "wrong. Send me this text and I will correct it.\n"
                                    + "  -4120          that shape was retired on that endpoint.\n"
                                    + "  -2015 / 401    a key or permission problem.\n"
                                    + "  404 HTML       that endpoint does not exist for this "
                                    + "account type.\n\n"
                                    + "Meanwhile you can still trade using the Falcon-held stop "
                                    + "in MARKET.");
                        }
                        out = sb.toString();
                    }
                } catch (Exception e) {
                    out = "Test failed: " + e.getMessage();
                }
                final String result = out;
                ui.post(new Runnable() {
                    public void run() {
                        diagnosticReport = result;
                        show("ACCOUNT");
                        showFullError("Stop order test", result);
                    }
                });
            }
        });
    }
    /** Last order failure, kept verbatim so it can be read and copied. */
    private String lastOrderError = null;

    /**
     * Shows an error in full. A toast truncates after roughly one line,
     * which hid the part of the Binance message that actually said what
     * was wrong.
     */
    private void showFullError(String title, String message) {
        final String full = String.valueOf(message);
        TextView body = new TextView(this);
        body.setText(full);
        body.setTextColor(TEXT);
        body.setTextSize(13);
        body.setPadding(dp(20), dp(14), dp(20), dp(8));
        body.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
                                            getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Falcon", full));
                            toast("Copied");
                        } catch (Exception ignored) {
                        }
                    }
                })
                .show();
    }

    /** Tests spot and futures separately so -2015 can be pinned down. */
    private void runDiagnostics() {
        final Settings snapshot = cfg;
        diagnosticReport = "Running…";
        show("ACCOUNT");
        pool.execute(new Runnable() {
            public void run() {
                String report;
                try {
                    report = Trader.diagnose(MainActivity.this, snapshot);
                } catch (Exception e) {
                    report = "Diagnostics failed: " + e.getMessage();
                }
                final String r = report;
                ui.post(new Runnable() {
                    public void run() {
                        diagnosticReport = r;
                        show("ACCOUNT");
                    }
                });
            }
        });
    }

    private void testConnection() {
        final Settings snapshot = cfg;
        statusLine.setText("Testing " + env + " connection…");
        pool.execute(new Runnable() {
            public void run() {
                String msg;
                try {
                    if (!Trader.hasKeys(MainActivity.this, snapshot.env)) {
                        msg = "No API key saved for " + snapshot.env;
                    } else {
                        // Build directly so an unarmed LIVE key can still be verified.
                        Trader t = Trader.forEnvironment(MainActivity.this, snapshot);
                        if (t == null && "LIVE".equals(snapshot.env)) {
                            msg = "Keys saved. Arm LIVE to enable order sending.";
                        } else if (t == null) {
                            msg = "Could not build client for " + snapshot.env;
                        } else {
                            double bal = t.usdtBalance();
                            msg = t.describe() + " · USDT " + fmt(bal);
                        }
                    }
                } catch (Exception e) {
                    msg = "Connection failed: " + e.getMessage();
                }
                final String m = msg;
                ui.post(new Runnable() {
                    public void run() {
                        statusLine.setText(m);
                        toast(m);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------- PC

    private void renderPc() {
        String saved = prefs().getString(KEY_URL, "");
        if (saved == null || saved.length() == 0) {
            webView.setVisibility(View.GONE);
            content.setVisibility(View.VISIBLE);
            content.addView(text("Connect to the Falcon desktop app using the phone link "
                    + "in the desktop sidebar.", 12, MUTED));
            final EditText in = new EditText(this);
            in.setHint("http://192.168.1.20:8765/m/your-token");
            style(in);
            content.addView(in);
            Button go = bigButton("CONNECT", 0xFF1D3554);
            go.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String u = in.getText().toString().trim();
                    if (u.length() == 0) {
                        toast("Enter the address first");
                        return;
                    }
                    if (!u.startsWith("http")) u = "http://" + u;
                    prefs().edit().putString(KEY_URL, u).apply();
                    show("PC");
                }
            });
            content.addView(go);
            return;
        }
        content.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(saved);
        Button forget = bigButton("CHANGE PC ADDRESS", PANEL);
        forget.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().remove(KEY_URL).apply();
                show("PC");
            }
        });
        content.addView(forget);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setBackgroundColor(BG);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r != null && !r.isForMainFrame()) return;
                statusLine.setText("Cannot reach PC. Check Wi-Fi and the desktop app.");
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // An Activity's timers are frozen once it leaves the foreground, so
        // in-app scanning cannot continue here. If background scanning is
        // enabled, hand the work to the foreground service; otherwise stop
        // cleanly so the button never claims to be running when it is not.
        // Scanning stays ON until the user stops it. The Activity's own
        // timers freeze in the background, so the service carries the work;
        // autoScan is left true so it resumes the moment we come back.
        boolean scanningOn = prefs().getBoolean(KEY_AUTOSCAN, false);
        if (scanningOn && ScanService.isEnabled(this)) {
            requestNotificationPermission();
            ScanService.start(this);
        } else {
            ScanService.stop(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // In the foreground the Activity loop does the scanning, so the
        // service stops. Running both doubled the Binance requests and left
        // a notification up while the app was open.
        ScanService.stop(this);
        if (prefs().getBoolean(KEY_AUTOSCAN, false) && !autoScan) startAutoScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanTask != null) ui.removeCallbacks(scanTask);
        if (tickTask != null) ui.removeCallbacks(tickTask);
        if (pool != null) pool.shutdownNow();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if ("PC".equals(tab) && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            if (!"SCAN".equals(tab)) {
                show("SCAN");
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }

    // -------------------------------------------------------------- widgets

    private interface OnChoice {
        void pick(String value);
    }

    private interface OnToggle {
        void set(boolean on);
    }

    private interface OnSlide {
        void set(int value);
    }

    private View choiceRow(String[] options, String selected, final OnChoice cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            final String o = options[i];
            boolean active = o.equals(selected);
            Button b = new Button(this);
            b.setText(o);
            b.setTextSize(12);
            b.setMinHeight(dp(46));
            b.setTextColor(active ? Color.WHITE : MUTED);
            b.setBackgroundColor(active ? 0xFF1D3554 : PANEL);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(2), dp(4), dp(2), dp(4));
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cb.pick(o);
                }
            });
            row.addView(b);
        }
        return row;
    }

    private View toggle(String labelText, boolean on, final OnToggle cb) {
        CheckBox c = new CheckBox(this);
        c.setText(labelText);
        c.setTextColor(TEXT);
        c.setTextSize(14);
        c.setMinHeight(dp(48));
        c.setPadding(dp(8), dp(8), dp(8), dp(8));
        // The stock box is near-invisible on this dark background; tint it
        // so checked and unchecked are obvious at a glance.
        tintCheckBox(c);
        c.setChecked(on);
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                cb.set(v);
            }
        });
        return c;
    }

    /** Cyan when checked, light grey when not: readable on the dark panel. */
    private void tintCheckBox(CheckBox c) {
        try {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked},
            };
            int[] colours = new int[]{ACCENT, 0xFFB9C9DD};
            android.content.res.ColorStateList list =
                    new android.content.res.ColorStateList(states, colours);
            if (Build.VERSION.SDK_INT >= 21) c.setButtonTintList(list);
        } catch (Exception ignored) {
        }
    }

    private View slider(int value, int max, final OnSlide cb) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(Math.min(value, max));
        sb.setPadding(dp(10), dp(14), dp(10), dp(14));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                cb.set(s.getProgress());
            }
        });
        return sb;
    }

    private Button bigButton(String textValue, int colour) {
        Button b = new Button(this);
        b.setText(textValue);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(colour);
        b.setMinHeight(dp(50));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String value, int size, int colour) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(colour);
        t.setPadding(dp(4), dp(6), dp(4), dp(4));
        return t;
    }

    private void style(EditText e) {
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF5D7390);
        e.setBackgroundColor(PANEL);
        e.setPadding(dp(14), dp(14), dp(14), dp(14));
        e.setTextSize(13);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(PANEL);
        l.setPadding(dp(13), dp(11), dp(13), dp(11));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        l.setLayoutParams(lp);
        return l;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /**
     * Show enough decimals for the value to be meaningful.
     *
     * A fixed 4 decimals printed a 1000PEPEUSDT stop of 0.003663 as
     * "0.0037" - identical to the entry - which made a correct stop look
     * like a broken one. Cheap coins need more places, not fewer.
     */
    private String fmt(double v) {
        double m = Math.abs(v);
        if (m >= 1000) return String.format("%.2f", v);
        if (m >= 1) return String.format("%.4f", v);
        if (m >= 0.01) return String.format("%.5f", v);
        if (m >= 0.0001) return String.format("%.7f", v);
        return String.format("%.9f", v);
    }
}
