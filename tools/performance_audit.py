#!/usr/bin/env python3
"""Evaluate PAPER/DEMO trade history, never authorize real-money trading.

Input CSV: closed_at,environment,net_pnl_usdt,entry_notional_usdt
  closed_at: ISO-8601 date/time, optionally with timezone; records sorted by close.
  net_pnl_usdt: realised net P/L *after* all known commissions/funding.
  entry_notional_usdt: initial contract value for extra slippage stress.

Walk-forward slices: first 60% (development), next 20% (validation),
last 20% (untouched test). This is diagnostics, not proof of profitability.
"""
import argparse
import csv
from datetime import datetime
import json
import math
from pathlib import Path

REQUIRED = {'closed_at', 'environment', 'net_pnl_usdt', 'entry_notional_usdt'}


def read_trades(path):
    with open(path, newline='', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        if not REQUIRED.issubset(set(reader.fieldnames or [])):
            raise ValueError('Required CSV columns: ' + ', '.join(sorted(REQUIRED)))
        trades = []
        for line, row in enumerate(reader, 2):
            if row['environment'].upper().strip() not in ('PAPER', 'DEMO'):
                raise ValueError(f'Line {line}: only PAPER/DEMO data can be audited here')
            date = datetime.fromisoformat(row['closed_at'].strip().replace('Z', '+00:00'))
            if date.tzinfo is None:
                raise ValueError(f'Line {line}: closed_at must specify timezone')
            net = float(row['net_pnl_usdt'])
            notional = float(row['entry_notional_usdt'])
            if not (math.isfinite(net) and math.isfinite(notional) and notional > 0):
                raise ValueError(f'Line {line}: finite P/L and positive notional required')
            trades.append((date.astimezone(__import__('datetime').timezone.utc), net, notional))
    trades.sort(key=lambda t: t[0])
    return trades


def metrics(trades, starting_equity, extra_slippage_bps):
    if not math.isfinite(starting_equity) or starting_equity <= 0:
        raise ValueError('starting_equity must be positive')
    if not math.isfinite(extra_slippage_bps) or extra_slippage_bps < 0:
        raise ValueError('extra_slippage_bps must be nonnegative')
    net = [pnl - notional * extra_slippage_bps / 10000 for _, pnl, notional in trades]
    equity = peak = starting_equity
    maximum_dd = 0.0
    ruined = False
    for value in net:
        equity += value
        if equity <= 0:
            ruined = True
        peak = max(peak, equity)
        maximum_dd = max(maximum_dd, 100 * (peak - equity) / peak)
    gains = sum(v for v in net if v > 0)
    losses = -sum(v for v in net if v < 0)
    return {
        'trades': len(net),
        'net_usdt': round(sum(net), 4),
        'expectancy_usdt': round(sum(net) / len(net), 4) if net else None,
        'win_rate_pct': round(100 * sum(x > 0 for x in net) / len(net), 2) if net else None,
        'profit_factor': round(gains / losses, 4) if losses > 0 else None,
        'max_drawdown_pct': round(maximum_dd, 3),
        'equity_ruined': ruined,
    }


def evaluate(trades, starting_equity=1000, extra_slippage_bps=10):
    n = len(trades)
    training_end = int(n * .6)
    validation_end = int(n * .8)
    partitions = {'development': trades[:training_end],
                  'validation': trades[training_end:validation_end],
                  'holdout': trades[validation_end:]}
    result = {name: metrics(rows, starting_equity, extra_slippage_bps)
              for name, rows in partitions.items()}
    notes = []
    if n < 150:
        notes.append('Fewer than 150 completed trades: insufficient sample')
    for name in ('validation', 'holdout'):
        item = result[name]
        if item['trades'] < 30:
            notes.append(f'{name}: fewer than 30 trades')
        if item['net_usdt'] <= 0:
            notes.append(f'{name}: stressed net loss/breakeven')
        if item['profit_factor'] is None or item['profit_factor'] < 1.25:
            notes.append(f'{name}: insufficient loss-adjusted evidence')
        if item['max_drawdown_pct'] > 5.0 or item['equity_ruined']:
            notes.append(f'{name}: excessive drawdown')
    return {
        'mode': 'SIMULATION RESEARCH ONLY',
        'live_authorized': False,
        'assumed_extra_slippage_bps': extra_slippage_bps,
        'total_trade_count': n,
        'periods': result,
        'worth_further_paper_testing': not notes,
        'warnings': notes or ['Promising historical sample; no future profit guarantee. Continue PAPER testing.']
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('csv', type=Path)
    ap.add_argument('--starting-equity', type=float, default=1000)
    ap.add_argument('--extra-slippage-bps', type=float, default=10)
    args = ap.parse_args()
    print(json.dumps(evaluate(read_trades(args.csv), args.starting_equity,
                              args.extra_slippage_bps), indent=2))


if __name__ == '__main__':
    main()
