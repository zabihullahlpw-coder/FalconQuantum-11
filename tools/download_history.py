#!/usr/bin/env python3
"""Download public USD-M perpetual data. GET only; no credentials or trading.
Writes completed, contiguous one-minute contract/mark candles and actual funding.
The provenance records source hashes and current (not historical) symbol filters.
"""
import argparse
import csv
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://fapi.binance.com"
MINUTE = 60_000


def get(path, params):
    url = BASE + path + "?" + urllib.parse.urlencode(params)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "FalconQuantum/10.4.1-audit"})
            with urllib.request.urlopen(req, timeout=25) as response:
                value = json.load(response)
            time.sleep(0.3)
            if isinstance(value, dict) and "code" in value:
                raise ValueError(f"Binance response: {value}")
            return value
        except urllib.error.HTTPError as error:
            if error.code in (418, 451):
                raise RuntimeError(f"Binance HTTP {error.code}; download stopped") from error
            if error.code != 429 and error.code < 500:
                raise
            if attempt == 4:
                raise
            retry = error.headers.get("Retry-After", "0")
            delay = max(2 ** attempt, float(retry) if retry.isdigit() else 0)
            if delay > 60:
                raise RuntimeError(f"Retry after {delay}s; rerun later in a new output directory") from error
            time.sleep(delay)
        except (urllib.error.URLError, TimeoutError):
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("Request failed")


def stamp(text):
    value = dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    if value.tzinfo is None:
        value = value.replace(tzinfo=dt.timezone.utc)
    result = int(value.timestamp() * 1000)
    if result % MINUTE:
        raise ValueError("Dates must lie on exact minute boundaries; unzoned dates mean UTC")
    return result


def candles(symbol, start, end, path, output, fetch=get):
    cursor, count = start, 0
    partial = output.with_suffix(".csv.part")
    with partial.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["open_time", "open", "high", "low", "close", "volume"])
        while cursor < end:
            rows = fetch(path, dict(symbol=symbol, interval="1m", startTime=cursor, endTime=end-1, limit=1000))
            if not isinstance(rows, list) or not rows:
                raise ValueError(f"Missing candles at {cursor}; no gap filling allowed")
            for row in rows:
                at = int(row[0])
                if at >= end:
                    break
                if at != cursor or int(row[6]) != at + MINUTE - 1:
                    raise ValueError(f"Missing, duplicate or malformed candle at {cursor}")
                op, high, low, close, volume = map(float, row[1:6])
                if (not all(map(math.isfinite, (op, high, low, close, volume))) or min(op, high, low, close) <= 0
                        or volume < 0 or high < max(op, close) or low > min(op, close)):
                    raise ValueError(f"Invalid OHLCV at {at}")
                writer.writerow([at, *row[1:6]])
                cursor += MINUTE
                count += 1
            if cursor <= int(rows[0][0]):
                raise ValueError("Candle pagination made no progress")
    partial.replace(output)
    return count


def funding(symbol, start, end, output, fetch=get):
    cursor, last, count = start, -1, 0
    partial = output.with_suffix(".csv.part")
    with partial.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["funding_time", "funding_rate", "mark_price"])
        while cursor < end:
            rows = fetch("/fapi/v1/fundingRate", dict(symbol=symbol, startTime=cursor, endTime=end-1, limit=1000))
            if not isinstance(rows, list):
                raise ValueError("Funding response is not an array")
            if not rows:
                break
            for row in rows:
                at, rate, mark = int(row["fundingTime"]), float(row["fundingRate"]), float(row["markPrice"])
                if (row.get("symbol") != symbol or not start <= at < end or at <= last
                        or not math.isfinite(rate) or not math.isfinite(mark) or mark <= 0
                        or row.get("rateType", "Regular") != "Regular"):
                    raise ValueError("Invalid, duplicate or unsupported funding record; inspect source")
                if last >= 0 and at-last > 12*3_600_000:
                    raise ValueError("Funding gap exceeds 12 hours; inspect source before replay")
                writer.writerow([at, row["fundingRate"], row["markPrice"]])
                last, count = at, count+1
            cursor = last+1
            if len(rows) < 1000:
                break
    if count == 0 or last < end-12*3_600_000:
        raise ValueError("Funding does not cover the end of this interval")
    partial.replace(output)
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--start", required=True, help="Inclusive UTC date/time")
    parser.add_argument("--end", required=True, help="Exclusive UTC date/time")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    symbol = args.symbol.upper()
    if not re.fullmatch(r"[A-Z0-9]{2,16}USDT", symbol):
        raise ValueError("Expected a USDT symbol such as BTCUSDT")
    start, end = stamp(args.start), stamp(args.end)
    server = int(get("/fapi/v1/time", {})["serverTime"])
    if end <= start or end > server//MINUTE*MINUTE:
        raise ValueError("Interval must be in the past and contain only completed minutes")
    if end-start < 30*86_400_000:
        raise ValueError("At least 30 days needed; longer windows across regimes are preferable")
    info = get("/fapi/v1/exchangeInfo", {})
    matching = [s for s in info["symbols"] if s["symbol"] == symbol]
    if len(matching) != 1 or matching[0].get("contractType") != "PERPETUAL":
        raise ValueError("This runner requires an available USDT perpetual contract")
    args.out.mkdir(parents=True, exist_ok=True)
    if any(args.out.iterdir()):
        raise ValueError("Use an empty output directory to preserve previous evidence")
    counts = {}
    for name, endpoint in (("contract", "/fapi/v1/klines"), ("mark", "/fapi/v1/markPriceKlines")):
        counts[name] = candles(symbol, start, end, endpoint, args.out / (name+".csv"))
        print(f"{name}: {counts[name]} completed minutes", flush=True)
    counts["funding"] = funding(symbol, start, end, args.out / "funding.csv")
    meta = dict(symbol=symbol, start_inclusive=start, end_exclusive=end, source=BASE,
                downloaded_at=dt.datetime.now(dt.timezone.utc).isoformat(), counts=counts,
                current_symbol_filters=matching[0], filters_are_historical=False,
                funding_order_within_minute="Unknown; replay charges before intrabar exit",
                missing_candles_filled=False, live_approved=False)
    meta["sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in args.out.glob("*.csv")}
    (args.out / "download.json").write_text(json.dumps(meta, indent=2)+"\n", encoding="utf-8")
    print(f"Saved data and provenance to {args.out}. This is data acquisition, not validation.")


if __name__ == "__main__":
    main()
