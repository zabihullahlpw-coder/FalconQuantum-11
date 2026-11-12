import importlib.util
import csv
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

spec = importlib.util.spec_from_file_location('performance_audit', Path(__file__).parents[1] / 'tools' / 'performance_audit.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class PerformanceAuditTests(unittest.TestCase):
    def rows(self, net, n=150, notional=1000):
        t0 = datetime(2025, 1, 1, tzinfo=timezone.utc)
        return [(t0 + timedelta(days=i), net(i), notional) for i in range(n)]

    def test_losing_system_rejected(self):
        report = mod.evaluate(self.rows(lambda i: -1))
        self.assertFalse(report['worth_further_paper_testing'])
        self.assertFalse(report['live_authorized'])

    def test_small_sample_rejected(self):
        report = mod.evaluate(self.rows(lambda i: 100, 10))
        self.assertFalse(report['worth_further_paper_testing'])

    def test_stress_can_reverse_apparent_profit(self):
        rows = self.rows(lambda i: 0.5)
        plain = mod.evaluate(rows, extra_slippage_bps=0)
        stressed = mod.evaluate(rows, extra_slippage_bps=10)
        self.assertGreater(plain['periods']['holdout']['net_usdt'], 0)
        self.assertLess(stressed['periods']['holdout']['net_usdt'], 0)

    def test_drawdown_identified(self):
        rows = self.rows(lambda i: -20 if i >= 125 and i < 140 else 2)
        result = mod.evaluate(rows, starting_equity=1000, extra_slippage_bps=0)
        self.assertGreater(result['periods']['holdout']['max_drawdown_pct'], 5)

    def test_out_of_sample_overfitting_rejected(self):
        rows = self.rows(lambda i: 30 if i < 120 else -30)
        result = mod.evaluate(rows, extra_slippage_bps=0)
        self.assertFalse(result['worth_further_paper_testing'])
        self.assertLess(result['periods']['holdout']['net_usdt'], 0)

    def test_positive_simulation_remains_simulation_only(self):
        rows = self.rows(lambda i: 30 if i % 3 != 0 else -10)
        result = mod.evaluate(rows)
        self.assertTrue(result['worth_further_paper_testing'])
        self.assertFalse(result['live_authorized'])

    def test_csv_rejects_live_and_invalid_numbers(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / 'trades.csv'
            for env, val in [('LIVE', '1'), ('PAPER', 'nan')]:
                with p.open('w', newline='') as f:
                    w = csv.writer(f)
                    w.writerow(['closed_at','environment','net_pnl_usdt','entry_notional_usdt'])
                    w.writerow(['2025-01-01T00:00:00Z', env, val, '100'])
                with self.assertRaises(ValueError):
                    mod.read_trades(p)

if __name__ == '__main__':
    unittest.main()
