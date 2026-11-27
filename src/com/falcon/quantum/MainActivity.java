package com.falcon.quantum;

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
 * Falcon Quantum mobile - self-contained trading and analysis app.
 *
 * PAPER / DEMO / LIVE are fully separate environments, each with its own
 * market type (Spot or Futures), leverage, margin mode, direction
 * permissions, risk settings, watchlist and ledger.
 *
 * PAPER is simulated locally. DEMO trades Binance testnet with fake funds.
 * LIVE entries require explicit operator permission.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "falcon_prefs";
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
    private ScrollView pageScroll;
    private TextView header, statusLine;

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
    private Runnable protectionTask;
    private volatile boolean protectionBusy=false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        paper = new Paper(this);
        journal = new Journal(this);
        paper.reconcileJournal();
        pool = Executors.newFixedThreadPool(4);
        env = prefs().getString(KEY_ENV, "PAPER");
        strategy = Strategy.load(this);
        cfg = Settings.load(this, env);
        SecureKeys.purgeRetiredDesktopLink(this);

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

        pageScroll = new ScrollView(this);
        pageScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(28));
        pageScroll.addView(content);
        root.addView(pageScroll);

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
                "MARKET", "STRATEGY", "COINS", "ACCOUNT"};
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

        pageScroll.setVisibility(View.VISIBLE);
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
            row.addView(text(r.symbol + "   " + r.side
                    + (r.directionReversed ? " (from " + r.signalSide + ")" : "")
                    + "   " + r.score + "%   " + r.status
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
            row.addView(chartButton(r.symbol,r.entry,r.stop,r.target,r.reason+" | "+r.reasons+" | "+r.warnings));
            if (r.ready && "LIVE".equals(env) && !Trader.liveArmed(this)) {
                row.addView(text("Research signal only — LIVE entry disabled", 11, AMBER));
            } else if (r.ready) {
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
        if (protectionTask != null) ui.removeCallbacks(protectionTask);
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
        if (protectionTask != null) ui.removeCallbacks(protectionTask);
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
                            Engine.Row r = EntryTiming.analyse(sym, k, qv, strategy, cfg);
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
                        lastRows.addAll(found);
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
     * PAPER writes to the local ledger. DEMO uses testnet order execution;
     * Only one exchange entry is started per
     * scan cycle: firing several at once would race on the same balance and
     * could blow past the position limit. With a 5s cycle the limit still
     * fills within seconds.
     */
    private void autoTrade() {
        if (!cfg.autoTrade || autoOpening) return;

        if ("PAPER".equals(env)) {
            if(autoOpening)return;autoOpening=true;
            final Settings snapshot=Settings.load(this,env);final List<Engine.Row> rows=new ArrayList<Engine.Row>(lastRows);
            pool.execute(new Runnable(){public void run(){
                int count=0;for(Engine.Row r:rows)if(r.ready&&paper.open(snapshot,r,strategy)==null)count++;
                final int opened=count;ui.post(new Runnable(){public void run(){autoOpening=false;if(opened>0)toast("Opened "+opened+" PAPER trades");}});
            }});return;
        }

        if ("LIVE".equals(env) && !Trader.liveArmed(this)) {
            statusLine.setText("Enable Allow LIVE trading in Account.");
            return;
        }
        if (!Trader.hasKeys(this, env)) return;

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
        if ("LIVE".equals(env) && !Trader.liveArmed(this)) {
            toast("Enable Allow LIVE trading in Account");
            return;
        }
        // Auto-open is an explicit standing instruction; asking every time
        // would defeat it.
        if (cfg.autoTrade) {
            openNow(r);
            return;
        }
        String detail = r.symbol + "  " + r.side
                + (r.directionReversed ? " (reversed from " + r.signalSide + ")" : "") + "\n"
                + cfg.describe() + "\n"
                + "entry " + fmt(r.entry) + "\nstop " + fmt(r.stop) + "\ntarget " + fmt(r.target);
        if ("LIVE".equals(env)) {
            detail += "\n\nLIVE uses real funds.";
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
        if ("PAPER".equals(env)) {
            final Settings snapshot=Settings.load(this,env);
            pool.execute(new Runnable(){public void run(){
                final String refusal=paper.open(snapshot,r,strategy);
                ui.post(new Runnable(){public void run(){toast(refusal==null?"Opened "+r.symbol:"Refused: "+refusal);refresh();}});
            }});return;
        }
        // DEMO and LIVE have no local ledger: the exchange is the single
        // source of truth. If the order cannot be sent, nothing is recorded.
        if ("LIVE".equals(env) && !Trader.liveArmed(this)) {
            toast("Enable Allow LIVE trading in Account");
            return;
        }
        if (!Trader.hasKeys(this, env)) {
            toast("No API key saved for " + env + " - see the ACCOUNT tab");
            return;
        }
        sendExchangeOrder(r);
    }

    /**
     * Send an order for the selected exchange environment. Position size is derived
     * from the exchange's own available balance - never from a simulated
     * one - and is refused if the account cannot cover it.
     */
    private void sendExchangeOrder(final Engine.Row r) {
        final Settings snapshot=Settings.load(this,env);
        statusLine.setText("Checking entry, account risk and protection…");
        pool.execute(new Runnable() { public void run() {
            final String result=Execution.open(MainActivity.this,snapshot,r,strategy);
            ui.post(new Runnable() { public void run() {
                autoOpening=false;statusLine.setText(result);toast(result);loadExchange();
            }});
        }});
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
        head.addView(text("Modeled fills, fees and funding. Liquidation is not simulated.", 11, MUTED));
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

            row.addView(chartButton(t.symbol,t.entry,t.stop,t.target,t.policy+" | modeled PAPER position"));
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
                        .setMessage("Restores the starting balance after all positions close. Trade evidence and today's loss halt are retained.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                try{paper.resetAccount(env);}catch(Exception e){toast(e.getMessage());}
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
                    double p = Binance.price(t.symbol,"FUTURES".equals(t.market));
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
                        double p = Binance.price(t.symbol,"FUTURES".equals(t.market));
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
            Journal.Trade tracked=journal.active(env,pos.symbol,pos.side);
            double[] plan=tracked==null?new double[]{0,0}:new double[]{tracked.sl,tracked.tp};
            Boolean onExchange = exchangeProtected.get(pos.symbol+"|"+pos.side);
            if (plan[0] > 0 || plan[1] > 0) {
                boolean live = Boolean.TRUE.equals(onExchange);
                String levels = "SL " + (plan[0] > 0 ? fmt(plan[0]) : "—")
                        + (live && plan[0] > 0 ? " verified" : "")
                        + "   ·   TP " + (plan[1] > 0 ? fmt(plan[1]) : "—")
                        + (live && plan[1] > 0 ? " verified" : "");
                row.addView(text(levels, 14,
                        Boolean.TRUE.equals(onExchange) ? 0xFF3EE7AB : AMBER));
                if (Boolean.TRUE.equals(onExchange)) {
                    row.addView(text("Last verified on Binance: " + Journal.when(lastExchangeLoadAt) + ". Refresh to check again.", 11, MUTED));
                } else {
                    row.addView(text("Protection missing or UNKNOWN. New entries blocked until resolved.", 11, AMBER));
                    String why = protectionError.get(pos.symbol+"|"+pos.side);
                    if (why != null) {
                        TextView w = text("Protection detail: " + why, 10, 0xFFFF8A90);
                        w.setTextIsSelectable(true);
                        row.addView(w);
                    }
                }
            } else {
                row.addView(text("No verified trade plan. Inspect protection directly on Binance.", 14, 0xFFFF8A90));
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
     * Finish any journal row whose position no longer exists, using
     * Binance's own realised-profit record as the source of truth.
     */
    private void reconcileHistory(Trader trader,String environment,java.util.List<Trader.Position> live) {
        Execution.reconcile(this,trader,Settings.load(this,environment),live);
    }

    private void loadExchange() {
        if(liveLoading)return;
        final Settings snapshot = cfg;
        final java.util.Map<String,Boolean> checked=new java.util.HashMap<String,Boolean>();
        final java.util.Map<String,String> failures=new java.util.HashMap<String,String>();
        liveLoading = true;
        liveError = null;
        exchangeProtected.clear();
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
                        Trader writer = Trader.forManagement(MainActivity.this, snapshot);
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
                                Journal.Trade tracked=journal.active(snapshot.env,held.symbol,held.side);
                                double[] plan=tracked==null?new double[]{0,0}:new double[]{tracked.sl,tracked.tp};
                                String problem = tracked==null||!writer.accountKey().equals(tracked.accountKey)
                                        ? "Untracked/legacy position: review existing orders manually"
                                        : writer.ensureProtection(held, plan[0], plan[1]);
                                checked.put(held.symbol+"|"+held.side, Boolean.valueOf(problem == null));
                                if (problem == null) {
                                    // Protection is in place: drop any earlier
                                    // failure so a stale message cannot linger.
                                    failures.remove(held.symbol+"|"+held.side);
                                    if (lastOrderError != null
                                            && lastOrderError.contains(held.symbol)) {
                                        lastOrderError = null;
                                    }
                                    writer.tidyProtection(held.symbol);
                                } else {
                                    failures.put(held.symbol+"|"+held.side, problem);
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
                                err = "Protection unverified: " + bare + ". Review the recorded API response. Check protection "
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
                        if(!snapshot.env.equals(env))return;
                        exchangeProtected.clear();exchangeProtected.putAll(checked);
                        protectionError.clear();protectionError.putAll(failures);
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
                                    Trader t = Trader.forManagement(MainActivity.this, snapshot);
                                    if (t == null) msg = "Order sending is not armed for " + snapshot.env;
                                    else {
                                        Execution.close(MainActivity.this,t,snapshot,pos,"MANUAL","User pressed CLOSE NOW");
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
            head.addView(text("Use TRADES for PAPER positions and costed history. Exit experiments run in PAPER.",12,MUTED));
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
            Journal.Trade tracked=journal.active(env,pos.symbol,pos.side);
            double[] plan=tracked==null?new double[]{0,0}:new double[]{tracked.sl,tracked.tp};
            double mark = pos.mark > 0 ? pos.mark : pos.entry;
            double stop = plan[0];
            double target = plan[1];

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
            row.addView(text("Shadow only: "+v.reason, 11, v.closes() ? 0xFFFF8A90 : MUTED));

            row.addView(chartButton(pos.symbol,pos.entry,stop,target,"Position plan | "+cfg.exitPolicy+" | "+v.reason));
            Button close = bigButton("CLOSE NOW", PANEL2);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v2) {
                    confirmCloseExchange(pos);
                }
            });
            row.addView(close);
            content.addView(row);
        }

        content.addView(text("Displayed manager suggestions are a shadow comparison only. Exchange positions retain their original brackets; safety closures are logged separately.",11,MUTED));
    }

    /** Best favourable move seen per symbol, for give-back detection. */
    private final java.util.HashMap<String, Double> peakMove =
            new java.util.HashMap<String, Double>();

    /** Every position this environment has taken, with its result. */
    private void renderHistory() {
        double[] sum = journal.summary(env);
        LinearLayout head = card();
        head.addView(text(env + " history", 16, TEXT));
        head.addView(text("Totals include only reconciled or simulated costs. Legacy records remain unverified.",11,MUTED));
        Button export=bigButton("EXPORT TRADE DIAGNOSTICS (JSON)",PANEL2);
        export.setOnClickListener(new View.OnClickListener(){public void onClick(View v){exportDiagnostics();}});
        head.addView(export);
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
            final Journal.Trade t = rows.get(i);
            LinearLayout row = card();
            boolean open = t.closedAt == 0;
            row.addView(text(t.symbol + "  " + t.side + "  " + t.leverage + "x "
                    + (t.marginMode == null ? "" : t.marginMode)
                    + "   " + t.status, 14, open ? ACCENT : TEXT));
            if (!open && ("CONFIRMED".equals(t.costState)||"SIMULATED".equals(t.costState))) {
                row.addView(text("Net P/L " + String.format("%.4f", t.pnl) + " USDT", 15,
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
            row.addView(text("Reason: "+t.closeReason+" | costs: "+t.costState,12,AMBER));
            row.addView(text("Gross "+fmt(t.grossPnl)+" − fees "+fmt(t.commission)+" + funding "+fmt(t.funding)
                    +"\nFee assets "+t.feeAssets+"\n"+t.closeDetail,11,MUTED));
            row.addView(text("Entry order "+t.entryOrderId+" | Exit orders "+t.exitOrderIds
                    +"\nSL algo "+t.stopOrderId+" / TP algo "+t.targetOrderId,10,MUTED));
            Button evidence=bigButton("WHY THIS TRADE CLOSED / EVENTS",PANEL2);
            evidence.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
                showFullError(t.symbol+" evidence",t.closeReason+"\n"+t.closeDetail+"\n\n"+journal.events(t.id));
            }});row.addView(evidence);
            if(t.signalPrice>0)row.addView(text("Signal "+fmt(t.signalPrice)+" | actual entry difference "+fmt(t.entry-t.signalPrice)
                +" (already included in fill P/L)",10,MUTED));
            row.addView(chartButton(t.symbol,t.entry,t.sl,t.tp,t.note+" | "+t.closeReason+" | "+t.costState));
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
        LearningEngine.Review r = LearningEngine.review(journal, env, Readiness.key(cfg,strategy));

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
                    + LearningEngine.MINIMUM_CONFIDENT_SAMPLE + ", no exploratory changes are proposed. "
                    + "Meeting this count alone does not establish a profitable edge.",
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
        if(!"PAPER".equals(env)){showFullError("Research only","Test changes in PAPER/replay first");return;}
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

    private void chooseFuturesLeverage() {
        if (!cfg.isFutures()) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(cfg.leverage));
        input.setSelectAllOnFocus(true);
        input.setTextColor(TEXT);
        new AlertDialog.Builder(this)
            .setTitle(env + " Futures leverage · 1x–150x")
            .setMessage("Enter a whole number. Exchange orders require leverage accepted for the symbol and account.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    int chosen;
                    try { chosen = Integer.parseInt(input.getText().toString().trim()); }
                    catch (Exception e) { toast("Enter a whole number from 1 to 150"); return; }
                    if (chosen < 1 || chosen > Settings.MAX_FUTURES_LEVERAGE) { toast("Choose 1x–150x"); return; }
                    cfg.leverage = chosen;
                    cfg.save(MainActivity.this);
                    lastRows.clear();
                    show("MARKET");
                }
            }).show();
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
            content.addView(text("Leverage (" + cfg.leverage + "x)", 13, ACCENT));
            Button choose = bigButton("SELECT LEVERAGE · " + cfg.leverage + "x", 0xFF1D3554);
            choose.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { chooseFuturesLeverage(); }
            });
            content.addView(choose);

            content.addView(text("Margin mode", 13, ACCENT));
            content.addView(choiceRow(Settings.MARGIN_MODES, cfg.marginMode, new OnChoice() {
                public void pick(String v) {
                    cfg.marginMode = v;
                    cfg.save(MainActivity.this);
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

        content.addView(text("Reverse Trade Direction (experimental)", 13, ACCENT));
        content.addView(toggle("Reverse Trade Direction — LONG to SHORT / SHORT to LONG",
                cfg.reverseTradeDirection, new OnToggle() {
                    public void set(boolean on) {
                        cfg.reverseTradeDirection = on;
                        cfg.save(MainActivity.this);
                        lastRows.clear(); // old signals must never be entered with changed settings
                        show("MARKET");
                    }
                }));
        content.addView(text("OFF by default. Applies ONLY to newly finalized FUTURES entries "
                + "in " + env + "; swaps the EXACT existing SL and TP prices "
                + "(old SL becomes TP; old TP becomes SL). Effective risk/reward "
                + "changes. SPOT entries are blocked while "
                + "reverse is enabled. Does not reverse exits or existing positions. "
                + "This may increase losses; test in PAPER first.", 11, AMBER));
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
        content.addView(slider(cfg.maxPositions, Settings.MAX_POSITIONS, new OnSlide() {
            public void set(int v) {
                cfg.maxPositions = Math.max(1, v);
                cfg.save(MainActivity.this);
                show("MARKET");
            }
        }));

        content.addView(text("DEMO testnet entries use risk limits and exchange-stop checks. LIVE entries require account permission. No automatic phone-stop fallback.",12,AMBER));
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
        content.addView(toggle("LEGACY paper experiment: suppress momentum closes", cfg.exitOnlyBySlTp,
                new OnToggle() {
                    public void set(boolean on) {
                        cfg.exitOnlyBySlTp = on;
                        cfg.save(MainActivity.this);
                        show("MARKET");
                    }
                }));
        content.addView(text("Exit variants run in PAPER/replay. DEMO and LIVE use exchange brackets and protection/risk safety closures. An unknown protection status is never treated as success.",11,MUTED));

        content.addView(text("Safety controls", 13, ACCENT));
        content.addView(text("Additional daily USDT cap: "
                + (cfg.maxDailyLoss <= 0 ? "percentage limit applies" : String.format("%.0f USDT", cfg.maxDailyLoss)),
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
        content.addView(text("Daily loss percentage is always enforced; USDT cap and consecutive-loss cap are additional limits. UTC reset. Gap losses can exceed estimates.", 11, MUTED));

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

        content.addView(text("Maximum planned loss per trade: "+cfg.riskPercent+"% of equity",13,ACCENT));
        content.addView(choiceRow(new String[]{"0.25","0.5","1.0","2.0"},String.valueOf(cfg.riskPercent),new OnChoice(){public void pick(String x){cfg.riskPercent=Double.parseDouble(x);cfg.save(MainActivity.this);show("MARKET");}}));
        content.addView(text("Daily loss budget: "+cfg.dailyLossPercent+"% (UTC)",13,ACCENT));
        content.addView(choiceRow(new String[]{"1.0","2.0","3.0","5.0"},String.valueOf(cfg.dailyLossPercent),new OnChoice(){public void pick(String x){cfg.dailyLossPercent=Double.parseDouble(x);cfg.save(MainActivity.this);show("MARKET");}}));
        content.addView(text("Cost assumptions per side: "+cfg.feeBps+" bps fee / "+cfg.slippageBps+" bps slippage",12,MUTED));
        content.addView(choiceRow(new String[]{"5.0","7.5","10.0"},String.valueOf(cfg.feeBps),new OnChoice(){public void pick(String x){cfg.feeBps=Double.parseDouble(x);cfg.save(MainActivity.this);show("MARKET");}}));
        content.addView(choiceRow(new String[]{"2.0","5.0","10.0"},String.valueOf(cfg.slippageBps),new OnChoice(){public void pick(String x){cfg.slippageBps=Double.parseDouble(x);cfg.save(MainActivity.this);show("MARKET");}}));
        content.addView(text("Research variants: select before a PAPER/DEMO session; no claim of improvement",13,ACCENT));
        content.addView(choiceRow(new String[]{"BASELINE","PULLBACK"},cfg.entryPolicy,new OnChoice(){public void pick(String x){cfg.entryPolicy=x;cfg.save(MainActivity.this);show("MARKET");}}));
        content.addView(choiceRow(new String[]{"LEGACY","FIXED","EARLY","TRAIL"},cfg.exitPolicy,new OnChoice(){public void pick(String x){cfg.exitPolicy=x;cfg.save(MainActivity.this);show("MARKET");}}));
        content.addView(text("PULLBACK tests closed 1h/4h trend with a 15m EMA21 pullback. LEGACY retains old exit logic for comparison. FIXED/EARLY/TRAIL are separate experiments.",11,MUTED));
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
        content.addView(text("Fast scanning increases API load and battery use. Respect exchange throttling; cached scan prices never authorise a stale entry.", 11, MUTED));

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
                    + "On: Falcon can place orders while minimised with Auto-open enabled. LIVE also requires account permission.", 11, AMBER));
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
        String line=String.format("Margin ceiling %.2f USDT; notional ceiling %.2f. Actual size is reduced to keep planned stop loss within %.2f%% of equity and remaining daily risk.",margin,notional,cfg.riskPercent);
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
                + "DEMO Futures credentials from Binance Demo Trading. SPOT is PAPER-only in this build."
                : "LIVE uses your real Binance account and real money.", 12,
                "LIVE".equals(env) ? AMBER : MUTED));

        final EditText k = new EditText(this);
        k.setHint("New API key (not displayed after saving)");
        style(k);
        content.addView(k);

        final EditText s = new EditText(this);
        s.setHint("New API secret (not displayed after saving)");
        style(s);
        s.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(s);

        Button save = bigButton("SAVE KEYS", 0xFF1D3554);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Trader.saveKeys(MainActivity.this, env,
                            k.getText().toString(), s.getText().toString());
                    k.setText("");
                    s.setText("");
                    toast("Keys stored in Android Keystore protection");
                    loadExchange();
                    show("ACCOUNT");
                } catch (RuntimeException error) {
                    toast("Could not securely store credentials: " + error.getMessage());
                }
            }
        });
        content.addView(save);
        content.addView(text("API credentials are stored encrypted. Enter both fields to replace them; "
                + "clearing requires the CLEAR KEYS button.", 11, MUTED));
        Button clearKeys = bigButton("CLEAR KEYS", PANEL2);
        clearKeys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Remove saved credentials?")
                    .setMessage("This removes this environment's API keys from the phone. "
                        + "Existing exchange positions will NOT be closed or cancelled.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("REMOVE", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            SecureKeys.clear(MainActivity.this, env);
                            show("ACCOUNT");
                        }
                    }).show();
            }
        });
        content.addView(clearKeys);

        Button test = bigButton("TEST CONNECTION", PANEL2);
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                testConnection();
            }
        });
        content.addView(test);

        Button stopTest = bigButton("INSPECT STOP ORDERS", PANEL2);
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
            content.addView(toggle("Allow LIVE trading (real funds)", Trader.liveArmed(this), new OnToggle() {
                public void set(boolean on) {
                    Trader.setLiveArmed(MainActivity.this, on);
                    show("ACCOUNT");
                }
            }));
            content.addView(text(Readiness.liveOrderStatus(Trader.liveArmed(this)), 12, AMBER));
            content.addView(text("Use an API key with trading permission only. Never enable "
                    + "withdrawals on a key you put in any app.", 11, MUTED));
        }
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
        final Settings snapshot=Settings.load(this,env);
        pool.execute(new Runnable(){public void run(){
            String out;
            try {
                Trader t=Trader.forReading(MainActivity.this,snapshot);
                if(t==null) out="Save keys for the selected environment first";
                else {
                    StringBuilder b=new StringBuilder("Read-only inspection. No probe orders sent.\n");
                    for(Trader.Position p:t.positions()) b.append(p.symbol).append(' ').append(p.side).append("\n")
                        .append(t.openAlgoOrders(p.symbol).toString(2)).append("\n");
                    out=b.toString();
                }
            }catch(Exception e){out="UNKNOWN: "+e.getMessage();}
            final String result=out;ui.post(new Runnable(){public void run(){diagnosticReport=result;show("ACCOUNT");showFullError("Protection evidence",result);}});
        }});
    }
    private boolean hasTrackedPositions() {
        for(String e:new String[]{"PAPER","DEMO","LIVE"}) for(Journal.Trade t:journal.history(e,10000)) if(t.closedAt==0 && !"ENTRY_REJECTED".equals(t.status))return true;
        return false;
    }
    private Button chartButton(final String symbol,final double entry,final double stop,final double target,final String analysis) {
        Button b=bigButton("CHART / ENTRY / SL / TP",PANEL2);
        final String timeframe=cfg.timeframe;
        final boolean chartFutures=cfg.isFutures();
        b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            pool.execute(new Runnable(){public void run(){
                try{
                    final Binance.Candles candles=Binance.klines(symbol,timeframe,120,chartFutures);
                    ui.post(new Runnable(){public void run(){
                        LinearLayout box=new LinearLayout(MainActivity.this);box.setOrientation(LinearLayout.VERTICAL);
                        TradeChart chart=new TradeChart(MainActivity.this,candles,entry,stop,target);
                        box.addView(chart,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(310)));
                        TextView detail=text("Current "+timeframe+" closed candles with stored trade levels | "+analysis,12,MUTED);detail.setPadding(dp(12),dp(8),dp(12),dp(12));box.addView(detail);
                        new AlertDialog.Builder(MainActivity.this).setTitle(symbol+" trade plan").setView(box).setPositiveButton("Close",null).show();
                    }});
                }catch(final Exception e){ui.post(new Runnable(){public void run(){toast("Chart unavailable: "+e.getMessage());}});}
            }});
        }});return b;
    }
    private String pendingExport;
    private void exportDiagnostics() {
        try {
            pendingExport=journal.exportEvidence(env);
            android.content.Intent i=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);i.setType("application/json");
            i.putExtra(android.content.Intent.EXTRA_TITLE,"Falcon-"+env+"-diagnostics.json");startActivityForResult(i,1041);
        }catch(Exception e){showFullError("Export failed",e.toString());}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=1041||result!=RESULT_OK||data==null||pendingExport==null)return;
        try {
            java.io.OutputStream out=getContentResolver().openOutputStream(data.getData());
            if(out==null)throw new java.io.IOException("No output stream");
            try{out.write(pendingExport.getBytes("UTF-8"));}finally{out.close();}
            pendingExport=null;toast("Diagnostics exported");
        }catch(Exception e){showFullError("Export failed",e.toString());}
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
                        // Use management-only client to inspect even with LIVE entries blocked.
                        Trader t = Trader.forManagement(MainActivity.this, snapshot);
                        if (t == null && "LIVE".equals(snapshot.env)) {
                            msg = "LIVE keys available only for inspection and management.";
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

    @Override
    protected void onStop() {
        super.onStop();
        if(protectionTask!=null)ui.removeCallbacks(protectionTask);
        // An Activity's timers are frozen once it leaves the foreground, so
        // in-app scanning cannot continue here. If background scanning is
        // enabled, hand the work to the foreground service; otherwise stop
        // cleanly so the button never claims to be running when it is not.
        // Scanning stays ON until the user stops it. The Activity's own
        // timers freeze in the background, so the service carries the work;
        // autoScan is left true so it resumes the moment we come back.
        boolean scanningOn = prefs().getBoolean(KEY_AUTOSCAN, false);
        if ((scanningOn || hasTrackedPositions()) && ScanService.isEnabled(this)) {
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
        if(protectionTask==null) protectionTask=new Runnable(){public void run(){
            if(!protectionBusy) {
                protectionBusy=true; final String monitorEnv=env;
                pool.execute(new Runnable(){public void run(){try{ProtectionMonitor.runAll(MainActivity.this);}finally{protectionBusy=false;}}});
            }
            ui.postDelayed(protectionTask,15000);
        }};
        ui.removeCallbacks(protectionTask);ui.post(protectionTask);
        if (prefs().getBoolean(KEY_AUTOSCAN, false) && !autoScan) startAutoScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanTask != null) ui.removeCallbacks(scanTask);
        if (tickTask != null) ui.removeCallbacks(tickTask);
        if (protectionTask != null) ui.removeCallbacks(protectionTask);
        if (pool != null) pool.shutdownNow();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
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
        if(Double.isNaN(v)||Double.isInfinite(v))return "unknown";
        double m = Math.abs(v);
        if (m >= 1000) return String.format("%.2f", v);
        if (m >= 1) return String.format("%.4f", v);
        if (m >= 0.01) return String.format("%.5f", v);
        if (m >= 0.0001) return String.format("%.7f", v);
        return String.format("%.9f", v);
    }
}
