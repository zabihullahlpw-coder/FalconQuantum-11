# Falcon Quantum 11.0.3 — PAPER leverage selector

- PAPER Futures accepts all integer leverages from 1x–150x, with no preset ladder snapping.
- This is *local, virtual-money simulation only*, with no exchange orders.
- DEMO retains the existing 3x cap and all entry-protection checks. LIVE new entries remain disabled.
- Reverse-direction with exact SL/TP swapping remains unchanged.
- The local regression suite checks exact leverage, bounds, and DEMO/LIVE isolation.
- High simulated leverage has no real-money value; results do not establish profitability.
