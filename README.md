# Falcon Quantum 11.0 Safety Preview — source package (not an APK)

This is an updated source code candidate based on the uploaded Falcon Quantum 10.4.2 audit build. It is **not** a profitable system, a production-ready release, or a prebuilt/installable APK. The source was modified locally; Android SDK compilation, installation, Keystore migration and exchange connectivity were **not** tested here.

**Safety preview behavior:** New LIVE entries are refused at the UI, shared execution and low-level transport permission checks, irrespective of pre-existing preferences. Existing LIVE positions can still be inspected and managed using connected credentials. PAPER simulations and DEMO testnet remain available. Do not interpret simulated gains as real financial returns.

**Security improvements:** Android Keystore AES-GCM encryption for API credentials and desktop connection tokens, one-time migration from legacy plaintext preferences, no Android backups/transfer of app data, no HTTP cleartext or mixed WebView content, no prefilled API secrets. The PC link now requires HTTPS and will refuse the original HTTP-only desktop link; an HTTPS endpoint is a prerequisite.

**Safer initial settings:** 0.25% modeled risk per simulated trade, 1% daily loss budget, 1x isolated margin, one open position, 10% maximum margin allocation by default, three-loss pause, and 30-second scan cadence. DEMO testnet caps leverage at 3x, risk budget at 0.5% per trade, daily loss budget at 2%, and margin allocation at 25%. Persisted settings are clamped when loaded; these controls are estimates, not guarantees against stop slippage or trading losses.

**Performance diagnostics:** `tools/performance_audit.py` checks net P/L with additional slippage stress and chronologically separated development, validation, and holdout results. It only accepts PAPER/DEMO input, and never authorizes LIVE trading.

**Package/version:** Debug application ID `com.falcon.quantum.audit`, source version `11.0.0-safety-preview`, version code `11000`. Installing over the 10.4.2 debug app would require signing with the SAME existing audit signing certificate and device testing. No signing key is included in this source archive.

See [docs/SAFETY_PREVIEW_11.md](docs/SAFETY_PREVIEW_11.md) for limitations, build prerequisites, tests and next steps. The older audit and validation docs below are preserved as historical context for 10.4.2; any references they contain to an enabled LIVE checkbox are obsolete in 11.0.

## What changed

| Request | Delivered | Evidence still needed |
| --- | --- | --- |
| Explain every close, fees and errors | Durable intent/event journal; actual order/fill attribution; gross, commissions, funding and net; diagnostics export | Your Binance fills/income and app logs are needed to identify the exact causes of historical losses |
| Verify Binance stops | Query accepted conditional orders, verify exact side/quantity/trigger/state; periodic recheck; unknown remains unknown | DEMO acceptance, trigger, outage and restart tests on a device |
| Avoid late entries | Closed candles, freshness and price-distance checks; optional 1h/4h trend plus 15m pullback candidate | Unseen market comparisons |
| Test early exits/trailing first | LEGACY, FIXED, EARLY and TRAIL isolated in PAPER/replay; original PositionManager retained | Forward results for the chosen settings |
| Bound risk | Stop-distance sizing including costs, 0.25% default risk, 1% default daily limit, aggregate open-risk reservation | Exchange fills can exceed modeled risk during gaps; limits block entries, not guarantee a maximum loss |
| Put the plan on a chart | Candle chart with entry, SL, TP and analysis from scanner/history/positions | Device rendering check; history overlays use current candles and stored levels |
| Validate performance | Separate development/holdout runner, data downloader, cost stress tests and paper evidence; LIVE new entries disabled in this preview | Real historical evaluation and forward paper trading have not been performed; a checked box is not performance approval |

Read [docs/AUDIT.md](docs/AUDIT.md) first for the loss investigation. [docs/VALIDATION.md](docs/VALIDATION.md) contains the checks performed and the remaining acceptance work.

## Build the Android app

A manual GitHub Actions build is also prepared. See [docs/APK_BUILD.md](docs/APK_BUILD.md) for the build, signature verification and APK download steps. It has not been run yet.

Use JDK 17, Android SDK platform 34 and Build Tools 34.0.0. The source has no external runtime libraries. Android's `org.json` is used in the app; test adapters are excluded from its source sets. A direct SDK build is available without Gradle:

```bash
export ANDROID_HOME=/absolute/path/to/Android/Sdk
python3 tools/build_apk_sdk.py --sdk "$ANDROID_HOME" --keystore /path/to/retained-audit-debug.jks
```

The direct SDK build generates and verifies its signed APK under `build/sdk-apk/`. Its package is `com.falcon.quantum.audit`. Preserve the signing key for future updates. Phone installation/UI and Binance acceptance still require device testing. With Gradle 8.7 installed, `bash tools/build_android.sh` is the alternative build route and writes under `build/outputs/apk/debug/`.

There is no bundled Gradle-wrapper JAR. With Gradle 8.7 installed, run `gradle wrapper` to generate the official wrapper, then use `./gradlew assembleDebug`. Android Studio can also open this project with the same tool versions. This pins the toolchain; it does not claim a verified reproducible APK.

An in-place upgrade of the original 10.4.0 app needs its original owner's signing key and application ID. The delivered update instead targets the separately installed 10.4.1 audit app. Do not uninstall either app to work around a signature mismatch: that can erase local history. The source tree contains no API credentials or signing keys; a private build-record bundle may include the retained audit-only signing key separately.

## Read why a trade closed

1. Open HISTORY in the appropriate environment.
2. Read the close reason and cost state. `CONFIRMED` means exchange executions and available costs reconciled; `SIMULATED` means a model; `UNVERIFIED` / `LEGACY_UNVERIFIED` means an exact claim cannot be made.
3. Open **WHY THIS TRADE CLOSED / EVENTS** for the entry, protection requests, exchange responses, close request, order IDs and fills.
4. Select **EXPORT TRADE DIAGNOSTICS (JSON)** to save the evidence. It includes trade/account identifiers but not API secrets.

The display separates the requested action from the eventual exchange outcome. For example, a protection failure may request a safety close, while an exchange stop fills first. A small positive gross result can become a net loss after commissions and funding. Slippage is already reflected in actual fill prices and is not subtracted twice.

For losses in the old APK, also supply Binance trade/fill, order and income exports covering entry through exit, including commission assets and funding. The old source did not persist enough evidence to reconstruct every reason from its local P/L alone. Never send API secrets or signing keys.

## Run local checks

```bash
bash tests/run.sh
python3 tests/smoke_replay.py
```

The first command parses all production Java and compiles/tests core classes against narrow JVM adapters. It is **not** an Android SDK build. The second uses generated synthetic prices only, to verify CLI splits, ledger accounting and report output. Synthetic data are temporary and are not supplied as trading results.

## Historical and paper workflow

See [docs/VALIDATION.md](docs/VALIDATION.md) for exact commands and assumptions. In brief: download public contract/mark/funding history, compare candidates on development/validation windows, freeze one candidate, evaluate it once on the held-out window, then collect forward PAPER and DEMO execution evidence. LIVE entry remains hard-disabled in 11.0 even if an older version saved an enabled checkbox.

DEMO entries are Futures only; LIVE new entries are disabled. SPOT is PAPER-only. Existing exchange positions can still be inspected, protected when an original tracked plan is available, or manually closed, including while LIVE entry permission is off. Untracked/legacy positions are not assigned invented stops; inspect their protection directly on Binance.
