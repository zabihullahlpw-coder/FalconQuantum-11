# Validation status and workflow

## Checks performed in this workspace

| Check | Result | Boundary |
| --- | --- | --- |
| Java syntax parsing | 26 production files pass | Full Android compilation is checked separately |
| Core compilation | Pass with Java 8 source/API target on JDK 17 | Minimal JVM Android/JSON adapters; not an APK build |
| Financial/control regression suite | 76 assertions pass | Fixtures; no exchange requests |
| LIVE checkbox and permission controls | 31 checks pass | Local mocked HTTPS; no Binance requests or orders |
| SQLite migration/idempotency checks | 7 assertions pass | Actual SQLite executes extracted CREATE/ALTER/SELECT statements; not Android instrumentation |
| Public downloader tests | 6 tests pass | Injected responses test pagination, gaps, malformed candles, funding and timestamps; no real download performed |
| Replay CLI smoke check | 10 grouped checks pass | 30 days of generated synthetic data; 32 development/validation and 2 selected holdout reports |
| Android build and signature | See delivered build record | Direct Android SDK build; no Gradle build claim |
| Device installation and UI rendering | NOT RUN | Requires an Android device |
| Binance DEMO order acceptance and triggering | NOT RUN | No credentials or account access supplied |
| Real historical backtest | NOT RUN | No complete historical dataset supplied/downloaded here |
| Forward paper trading | NOT RUN | Requires elapsed market time and a running device |
| LIVE operator permission | Opt-in checkbox, off by default | Version 10.4.2 supports LIVE when checked; this is not proof of profitability or exchange acceptance |

Run `bash tests/run.sh` and then `python3 tests/smoke_replay.py`. The financial checks cover cost sign changes, sizing, daily latches, withdrawal/deposit behavior, identity isolation, conditional-order shape, incomplete/foreign fills, candle availability, stale/chasing entries, stop-first ambiguity, gap fills and independent exit variants. The smoke check verifies wallet reconciliation and report separation; **it is not a market-performance report**.

## Acquire historical data

From a computer with Binance public API access, use an empty output directory. The script sends unsigned GET requests only. It never reads keys or sends an order.

```bash
python3 tools/download_history.py --symbol BTCUSDT \
  --start 2025-01-01 --end 2026-01-01 --out data/btc-2025
```

Dates are examples of a retrospective research window, not a recommended asset or period. Use multiple preselected symbols and market regimes. The end is exclusive; only completed UTC minutes are accepted. The first 10 days are reserved for higher-timeframe warm-up. At least 30 contiguous days are required; that minimum is not sufficient evidence by itself.

Outputs: `contract.csv`, `mark.csv`, `funding.csv` and `download.json`. Contract and mark timestamps must match. Missing/duplicate minutes fail validation; nothing is forward-filled. Funding preserves signed rates and original millisecond times. Downloader errors leave `.part` files; a directory without completed provenance is not a validated dataset.

`download.json` records the current symbol filters and data hashes. Current filters are not historical filters. Obtain the applicable historical quantity step/minimum quantity/minimum notional and retain that source when testing older periods. The downloader rejects unsupported/duplicate funding records and gaps over 12 hours; inspect shorter missing intervals too. Symbols delisted before download and historical universe changes require separate data sources and survivorship review.

## Development and validation first

Run the audited baseline and all isolated candidates on the first two chronological windows. After warm-up, the evaluation period is split 60% development, 20% validation and 20% holdout. Positions and wallet are reset between windows; open trades are closed explicitly as `END_OF_WINDOW`.

```bash
bash tools/backtest.sh \
  --contract data/btc-2025/contract.csv \
  --mark data/btc-2025/mark.csv \
  --funding data/btc-2025/funding.csv \
  --symbol BTCUSDT --out results/btc-development \
  --stage development --step 0.001 --min-qty 0.001 --min-notional 100 \
  --leverage 3 --fee-bps 5 --slippage-bps 2 \
  --risk-pct 0.5 --daily-pct 2 --initial-equity 1000
```

The filter/cost values above are **examples**, not verified Binance settings for that period. Replace them with supported values for the chosen account, symbol and dates. Commission and slippage are basis points per side. Scenario 2 doubles both assumptions; real funding is unchanged. The CLI rejects misspelled options and output-directory reuse.

There are 32 reports: 2 windows × 2 entries × 4 exits × 2 cost scenarios. `comparison.csv` records trade count, win rate, net P/L, net profit factor, minute-close equity drawdown, fees, funding, ambiguity and skipped entries. Each comparison has an execution ledger. Zero-loss profit factor is `undefined`, not an invented high number. `provenance.json` identifies input hashes, engine version and arguments.

Entries:

- `BASELINE`: existing Falcon signal engine using closed candles, plus the common freshness/chasing guards. This is an **audited baseline**, not a bit-for-bit reconstruction of the old APK's unsafe execution.
- `PULLBACK`: the same eligibility plus aligned closed 1h/4h trends and a 15m EMA21 pullback-hold candle. It is an experimental candidate and can reduce or miss trades; no improvement has been demonstrated.

Exits:

- `LEGACY`: unchanged PositionManager logic, sampled at closed 15m signals in replay.
- `FIXED`: original stop and target.
- `EARLY`: close a nonwinning position when the closed-candle directional thesis reverses.
- `TRAIL`: original-R profit ladder, including a modeled cost allowance, without loosening the stop.

The CLI currently uses the default BALANCED strategy/modules, NORMAL style, both directions, confirmation off, and the legacy SL/TP-only switch on. It supports 15m signals, one symbol and one position. It does not reproduce arbitrary saved app strategy presets or multi-symbol selection. EARLY is an explicit experiment independent of the legacy switch.

## Freeze a candidate before the holdout

Write down the chosen entry/exit, account/risk assumptions, benchmark, data hashes and acceptance criteria before reading holdout results. Compare costs, drawdown, tail losses, stability across periods and the number of independent trades; do not select on win rate alone.

Run the **preselected** combination once on the untouched final window:

```bash
bash tools/backtest.sh \
  --contract data/btc-2025/contract.csv \
  --mark data/btc-2025/mark.csv \
  --funding data/btc-2025/funding.csv \
  --symbol BTCUSDT --out results/btc-holdout \
  --stage holdout --entry PULLBACK --exit FIXED \
  --step 0.001 --min-qty 0.001 --min-notional 100 \
  --leverage 3 --fee-bps 5 --slippage-bps 2 \
  --risk-pct 0.5 --daily-pct 2 --initial-equity 1000
```

This produces two cost scenarios. `PULLBACK/FIXED` is an example selection, not a recommendation or winner. Evaluate the already specified `BASELINE/LEGACY` benchmark separately on the same holdout. If you change the candidate after seeing that result, the window is no longer unseen; reserve new data. The code does not enforce research honesty across separate CLI invocations or treat a positive result as LIVE approval.

## Execution-model boundaries

Signals use only completed bars; fills occur at the following one-minute contract open with adverse slippage. Mark-price highs/lows trigger exchange-style brackets, translated to an estimated contract fill using the opening basis. If stop and target both appear in one minute, the stop wins. Adverse gaps fill at the opening price, not a fictional guaranteed stop price.

Historical funding is applied to held positions. Events inside a minute are charged before modeled intrabar exits; exact order within that minute is unknown, so this is an approximation, especially for funding credits. Simultaneous boundary events also require tick data for exact ordering. Minute-close drawdown can understate intraminute drawdown. The model has no order-book queue, maintenance-margin/liquidation tiers, ADL, network latency, rate-limit failures or exchange rejection simulation. Leverage above 5 is refused by the runner because liquidation is unmodeled; even lower leverage is not an execution guarantee.

PAPER is a separate forward approximation using sampled prices and completed one-minute contract candles. It includes both commissions and slippage; Futures funding is explicitly estimated as 1bp paid per 8 hours, prorated. SPOT funding is zero. It does not reproduce mark-trigger/basis execution or liquidation. The entry minute is sampled to avoid using pre-entry highs/lows. Missed observations over two minutes, unavailable bars and changed settings flag a data/config gap. Such trades are excluded from same-settings exploratory learning. These differences must be reconciled before comparing PAPER with historical replay or exchange statements.

## Forward PAPER and DEMO acceptance

1. Build/install the audit app, verify chart/history/export screens and additive upgrades against a backed-up old database. Keep original evidence.
2. Use a frozen PAPER configuration matching the selected replay as closely as supported: one preselected symbol, 15m, max positions 1, NORMAL, both directions, BALANCED defaults, leverage 3 in the example, and identical risk/cost assumptions. Record differences in polling, funding and trigger prices.
3. Keep monitoring enabled across foreground/background use. Export diagnostics regularly. Review gaps, runtime errors, all loss causes, modeled cost totals and settings fingerprints. Observe enough elapsed time and independent trades across relevant conditions; a fixed trade count alone does not prove an edge.
4. With an explicitly configured DEMO account, test entry fill/partial-fill handling, accepted SL/TP queries, actual stop and target triggers, cancellation detection, hedge/one-way mode, lost responses, app restart and manual closes. Confirm quantities, mark trigger, actual exit IDs and commissions against Binance's own statement.
5. For every safety close, demonstrate the rejection/error that requested it and the actual order that closed the position. For every gross win/net loss, reconcile commissions/funding. Unknown outcomes must remain labeled and block new entries.
6. Review aggregate risk, daily UTC halt, account switching and concurrent scanner/UI requests on device. Confirm no more than one entry is created from a duplicated signal.

None of these account/device/forward steps were completed in this workspace. Version 10.4.2 allows operator-enabled LIVE entries. Local permission tests intercept all HTTPS traffic to test enabling, disabling, stale clients, last-moment disarming, read-only clients and preserved position-management transport. They do not send real orders or prove Binance acceptance. Passing tests does not check the LIVE box or establish profitability.
