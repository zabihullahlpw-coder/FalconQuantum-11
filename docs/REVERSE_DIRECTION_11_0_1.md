# Reverse Trade Direction (v11.0.1 safety preview)

In MARKET settings, the checkbox **Reverse Trade Direction — LONG to SHORT / SHORT to LONG** is OFF by default and saved independently for PAPER, DEMO, and LIVE.

When enabled, the strategy still makes and scores its ORIGINAL decision first. Only after signal confirmation and entry-policy checks does the app invert the finalized **new-entry** plan:

- Original LONG entry at 100, SL 98, TP 104 → effective SHORT at 100, SL 102, TP 96.
- Original SHORT entry at 100, SL 102, TP 96 → effective LONG at 100, SL 98, TP 104.

Original risk and reward distances are maintained; quantities and net reward/risk are checked against the inverted plan. The effective order direction and protective levels are consistent in preview, entry, PAPER/DEMO journal, and background scan. The original signal remains available in the UI for audit. No changes are made to already open positions, exit decisions, or previously placed stop/target orders.

Safety: SPOT cannot short; new SPOT entries are blocked while reverse is selected. Disallowed effective directions are blocked. Invalid mirrored prices are rejected. Previously scanned rows are invalidated when toggled and setting fingerprints include reverse mode. LIVE new entries remain disabled regardless of this checkbox.

This option is an experiment, **not a performance enhancement**. Reversing a signal can increase losses; use PAPER mode and independent performance analysis before drawing conclusions.
