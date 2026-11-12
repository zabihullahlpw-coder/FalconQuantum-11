# Loss attribution audit

Reviewed source: the uploaded FalconQuantum source ZIP. No Binance account access, actual execution export, installed app database or production log was supplied. Findings below are verified source defects and possible loss mechanisms, **not a diagnosis of a particular historical trade**.

## Findings and corrections

| Source finding | Why it matters | Correction |
| --- | --- | --- |
| Protection inspection used `type`/`algoType` while current conditional responses expose `orderType` and `algoStatus` | A valid stop could appear missing, causing redundant repair or a protection-failure close | Parse documented fields and validate working state, exact hedge book, closing side, mark trigger, price and coverage |
| Local ledger completion and some early returns were treated as success | A saved ID or skipped request does not prove an exchange order remains active | Exchange queries are authoritative; failed queries show unknown/stale evidence |
| Multiple speculative protection-order variants and automatic phone-held fallback | Rejections could leave a position inadequately protected; retries could have side effects | One explicit close-position conditional-market shape; no live probe orders or automatic phone-held fallback |
| Blind retry of mutations after transport failures | A request can be accepted even when its response is lost | Persist client IDs, POST once, recover by querying that same ID; unresolved outcomes block new entries |
| Protection could be cancelled before a close/replacement completed | An open position could temporarily lose its stop | Keep protection until flat; replacement verifies the new leg before cancelling the old one |
| Income summed by symbol around entry time; commission/funding not fully attributed | Earlier trades could contaminate P/L; displayed gross gains could hide net losses | Match the entry order and exit fills, deduplicate, reconcile quantities, sum commissions and funding |
| Exit reason inferred from estimated exit price proximity | An early/manual/safety close could be labelled SL/TP incorrectly | Match exit order IDs/types and conditional `actualOrderId`; preserve unknown/external attribution |
| Market order response ignored and brackets re-anchored around a quote | Actual entry and intended invalidation could diverge | Record actual fills; retain original plan levels; reject crossed or stale setups |
| Risk-percent setting did not determine real order size | A margin percentage multiplied by leverage is not a loss budget | Size from stop distance plus costs, then cap by margin and remaining daily/open risk |
| Daily/consecutive loss checks differed between foreground/background | Auto-entry could bypass a foreground-only limit | Both paths use `Execution.open`; daily state is account/environment scoped and latched for the UTC day |
| Forming candles and mutable shared strategy weights | Signals could repaint or change across concurrent scanner workers | Use complete candles and copy strategy state before regime weights are applied |
| Legacy exit manager measured R from a changing stop and could move targets | Exit behavior could differ from a fixed original-risk model | Preserve this as LEGACY for comparison; experimental TRAIL uses immutable entry risk and never loosens a stop |
| PAPER omitted costs and could settle the same trade twice | Simulated results and wallet could overstate performance | Entry/exit fees, slippage, labeled funding estimate, atomic compare-and-set close, crash journal recovery |
| Destructive database upgrades | Evidence could disappear at upgrade | Additive migrations preserving existing records; legacy values remain explicitly unverified |

The original `PositionManager.java` is unchanged. Exchange strategy-driven early exits/trailing changes are not activated by these experiments. DEMO and operator-enabled LIVE use the original brackets plus separately logged protection/risk safety handling. Version 10.4.2 replaces the 10.4.1 hard LIVE lock with the ACCOUNT checkbox, as requested; it does not claim the strategy is validated.

## New evidence chain

`ENTRY_INTENT` is durable before submission. The journal then records the entry response/recovery, protection request and verification, close request/recovery, actual exit orders, executions and income. Entry and close client IDs survive restarts. A timeout is recorded as an unresolved outcome, not a rejected or filled order guessed from price.

Each completed exchange lifecycle must reconcile entry and exit quantities. Gross P/L comes from exchange executions. Net P/L is gross minus commission plus signed funding. Non-USDT fees require historical conversion and therefore remain unverified in this release. Scale-ins, overlapping external round trips, ambiguous hedge funding or unavailable fills also remain unverified; new entries are blocked pending review. This conservatively avoids inventing a precise answer.

The 60-second settlement delay allows late income records to arrive; it is not a guarantee that Binance has finalized every possible adjustment. Reconcile the diagnostics against the account statement when reviewing actual losses. Exchange retention limits may prevent recovery of very old orders.

Old rows are preserved as `LEGACY_UNVERIFIED`, excluded from confirmed totals and exploratory learning. There is no automatic reconstruction of missing historical entry/exit identities.

## Stop verification and operational limits

The current conditional REST shape uses `/fapi/v1/algoOrder`, `algoType=CONDITIONAL`, `STOP_MARKET` / `TAKE_PROFIT_MARKET`, `closePosition=true`, an explicit position side and `workingType=MARK_PRICE`. `priceProtect=false` avoids silently choosing a different trigger condition. Acceptance is queried after submission; an ID alone is insufficient. The monitor rechecks tracked positions at a nominal 15-second interval while the activity or enabled foreground service runs. Network calls can delay this interval.

No code can promise uninterrupted monitoring if Android stops the process or the device loses connectivity. Exchange-hosted stops continue independently once accepted, but acceptance, trigger behavior and account-specific restrictions still require DEMO tests. App startup, restarting after an outage, cancelled stops, hedge/one-way mode and partial fills have not been tested against Binance here.

Uncertain submissions are not retried blindly. A persistently rejected/unknown order can require manual reconciliation on Binance. Inspect and resolve it before allowing more entries. Existing LIVE positions lacking an original account-matched plan are displayed as unverified; the code does not widen stops or invent replacement levels for them.

Protection evidence is scoped to account, environment, symbol, side and entry. Cleanup waits until the symbol is flat and acts on Falcon IDs or exact adopted ledger orders. Paper monitoring continues when scanning is paused. Changes to settings during a PAPER trade retain existing brackets, disable further experimental adjustment and flag the evidence as unsuitable for a clean comparison.

## Risk semantics

Default new-entry risk is 0.5% of equity and the daily loss budget is 2% of the reconstructed UTC-day wallet baseline. Risk includes the entry-to-stop distance plus configured commissions/slippage; DEMO sizing also obtains the account's taker fee. Existing tracked positions reserve additional risk. Unknown external exposure or an unverified stop prevents another entry.

The daily halt accounts for realized costs and adverse unrealized P/L, persists after recovery and resets on the next UTC day. Later deposits cannot enlarge an already recorded baseline; withdrawals can reduce it. It blocks new entries; it does not promise forced liquidation of the portfolio at an exact daily amount. Gaps, rejected exits, funding, liquidation, exchange outages and actual slippage can exceed a modeled stop budget. A baseline first established after an earlier same-day deposit cannot identify that deposit as trading capital from this snapshot alone.

## Sources checked for API/build assumptions

- [Binance Futures trade REST catalog](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/trade): conditional orders, order queries and user executions.
- [Binance Futures general information](https://developers.binance.com/en/docs/products/derivatives-trading-usds-futures/general-info): DEMO host and uncertain execution after transport/server errors.
- [Binance account REST catalog](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/account): income and account commission rate.
- [Binance market-data REST catalog](https://developers.binance.com/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data): contract/mark candles and historical funding.
- [Android Gradle plugin 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes): Gradle 8.7, JDK 17 and SDK/Build Tools 34.

Reviewed on 2026-09-28. These references describe API contracts; they are not evidence that this app's orders were accepted or that its strategy is profitable.
