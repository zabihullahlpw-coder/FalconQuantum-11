import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("download_history", Path(__file__).resolve().parents[1]/"tools/download_history.py")
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)


class DownloaderTests(unittest.TestCase):
    def test_candle_pages(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/"c.csv"
            calls = []
            def fetch(endpoint, params):
                calls.append(params["startTime"])
                t = params["startTime"]
                return [[t, "100", "102", "99", "101", "12", t+59999]]
            self.assertEqual(d.candles("BTCUSDT", 0, 120000, "/test", path, fetch), 2)
            self.assertEqual(calls, [0, 60000])
            self.assertEqual(len(path.read_text().splitlines()), 3)

    def test_gap_is_not_filled(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/"c.csv"
            with self.assertRaisesRegex(ValueError, "Missing"):
                d.candles("BTCUSDT", 0, 120000, "/test", path,
                          lambda *_: [[60000, 100, 102, 99, 101, 12, 119999]])
            self.assertFalse(path.exists())

    def test_invalid_ohlc_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "Invalid OHLCV"):
                d.candles("BTCUSDT", 0, 60000, "/test", Path(tmp)/"c.csv",
                          lambda *_: [[0, 100, 90, 99, 101, 12, 59999]])

    def test_funding_preserves_negative_rate_and_milliseconds(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/"f.csv"
            rows = [{"symbol":"BTCUSDT", "fundingTime":1, "fundingRate":"-0.0001", "markPrice":"100"}]
            self.assertEqual(d.funding("BTCUSDT", 0, 60000, path, lambda *_: rows), 1)
            with path.open() as f:
                self.assertEqual(next(csv.DictReader(f))["funding_time"], "1")

    def test_duplicate_funding_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            row = {"symbol":"BTCUSDT", "fundingTime":1, "fundingRate":"0", "markPrice":"100"}
            with self.assertRaisesRegex(ValueError, "duplicate"):
                d.funding("BTCUSDT", 0, 60000, Path(tmp)/"f.csv", lambda *_:[row,row])

    def test_time_boundaries(self):
        self.assertEqual(d.stamp("1970-01-01T01:00:00+01:00"), 0)
        with self.assertRaisesRegex(ValueError, "minute"):
            d.stamp("1970-01-01T00:00:01Z")


if __name__ == "__main__":
    unittest.main()
