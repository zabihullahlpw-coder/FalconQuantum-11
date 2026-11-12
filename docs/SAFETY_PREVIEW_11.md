# Falcon Quantum 11.0 — Safety Preview

**Status:** Updated source only. Java core tests and static checks passed locally; full Android compile, APK generation, installation, Android Keystore migration and any real exchange operation were **not tested** because this environment has no Android SDK/platform/build tools and no phone/exchange access.

**Not a profit guarantee.** The existing trading rules have not been demonstrated profitable with trustworthy unseen data. Simulated PAPER/DEMO operation can still lose synthetic funds. The security changes and lower initial limits are intended to reduce modeled risk, not eliminate it.

## Implemented

- Retired the prior LIVE entry checkbox. New LIVE orders are blocked in `Trader.entryBlockReason`, the common `Execution.open` entry path, every `Trader.requireEntryPermission` call, and immediately before entry transport. Existing LIVE position inspection, protective order management and position closing remain possible as in the earlier build. These management routes were tested **with local HTTPS interceptors only** and should be verified on-device before relying on them.
- Set conservative new defaults: 1x isolated margin, 0.25% equity at modeled stop, 1% UTC-day loss budget, one position, 10% margin share, three consecutive losses then halt, 30s scan cycle. DEMO leverage max 3x, margin isolated, no more than 0.5% modeled risk per trade, 2% daily loss, 25% margin share, 2 concurrent positions. Persisted settings are clamped at load/save (PAPER is allowed broader settings for experiments).
- Added `SecureKeys.java` using Android Keystore AES-256-GCM with authenticated environment-specific records. API keys, API secrets, and desktop URLs containing access tokens are encrypted at rest. Old plaintext preferences are migrated only after a successful encrypted commit; an unreadable secure record fails closed. Removed credentials from UI prefill and added explicit key clearing.
- Disabled Android app backup, full-backup content, and device-transfer extraction. Disallowed cleartext traffic at manifest and network-security config levels. Desktop connections and embedded WebView navigation now require HTTPS; mixed content and file/content access are disallowed.
- Added `tools/performance_audit.py`. It analyzes a CSV of closed PAPER/DEMO trades (`closed_at,environment,net_pnl_usdt,entry_notional_usdt`), deducts an additional user-selectable slippage stress, and reports separately on development/validation/untouched holdout periods. It never returns a LIVE authorization. Reports of historical success cannot predict future profitability.
- New regression/safety/performance checks and static configuration tests.

## Validation completed in this environment

`bash tests/run.sh` performs parser checks of all 27 Java source files; compiles most core files against local JVM testing stubs; and runs 83 financial control assertions, 29 simulation/LIVE-permission mock HTTPS checks, 7 SQLite migration/idempotency checks, 6 downloader tests, 7 performance-audit tests, and 4 static security-configuration tests. The `SecureKeys.java` Android implementation is excluded from JVM compilation and is replaced with a test-only in-memory stub: **none of these tests proves Android Keystore runtime behavior**.

## Important compatibility and operational limits

- **APK not supplied.** To build, install Android SDK platform 34, Build Tools 34.0.0 and JDK 17; use `python3 tools/build_apk_sdk.py --sdk "$ANDROID_HOME" --keystore /private/path/to/retained-audit-debug.jks` from `source/`, or the project Gradle instructions. Do not publish the old signing key or include it in distributable source. Use the retained **same** key if you need an in-place update of the separate `.audit` application. An APK signed by a different key cannot update it in place.
- Test Android SDK compile, installation/update on a disposable test device first. In particular verify secure preferences, legacy credential migration, removal of insecure HTTP desktop URLs, background service behavior, any old stored settings and all read-only/manage-old-position routes.
- A LIVE position on an exchange still exists if new entry is disabled, if keys are removed or if the phone is offline. Risk-reducing actions still depend on exchange/network availability. Do not infer a trade is closed until exchange execution is verified.
- HTTPS is now mandatory for the desktop viewer. A desktop web server offering only `http://` will not connect without an independently secured HTTPS endpoint. This is an intentional breaking change.
- Initial risk controls do not prevent losses caused by gaps, liquidity issues, failed stops or exchange outages. No historical market dataset or forward-tested profitability evidence was provided.

## Evaluate simulated results (optional)

Export only **completed PAPER/DEMO** positions to UTF-8 CSV with columns:

```csv
closed_at,environment,net_pnl_usdt,entry_notional_usdt
2026-11-10T10:00:00Z,PAPER,-2.50,100
2026-11-10T11:00:00Z,PAPER,0.75,85
```

Then run: `python3 tools/performance_audit.py paper_trades.csv --starting-equity 1000 --extra-slippage-bps 10`

The included two-row example is formatting guidance, not evidence of returns. An output flagged *worth further PAPER testing* is not authorization for real-money deployment.

## Next verification stages

Full Android SDK compile and debug build; Android 24/34/35+ smoke tests; confirm encryption and migration without credential loss; inspect the app's background work; verify all modes with offline mocked exchange scenarios, stop failures, partial fills, unknown response recovery and reconnection. Only after that, inspect credible forward PAPER/DEMO track records with costs and adverse conditions.
