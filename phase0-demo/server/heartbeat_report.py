"""
Phase 0.5 心跳报告 — 24h 后跑,看是否通过保活判定。

用法:
    python3 heartbeat_report.py hb.log [--interval 60]

判定:丢失率 < 1% 且最大间隔 < 5 min
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime, timezone


def load_records(path: str) -> list[dict]:
    rows: list[dict] = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return rows


def analyze(rows: list[dict], interval_s: int) -> int:
    if not rows:
        print("hb.log 为空,APK 一条心跳都没发到 — 检查 URL / 防火墙 / Stellar 是否在跑")
        return 2

    # 按 device_id 分组
    by_device: dict[str, list[float]] = defaultdict(list)
    for r in rows:
        dev = (r.get("payload") or {}).get("device_id") or r.get("client_ip", "unknown")
        ts = r.get("server_ts")
        if isinstance(ts, (int, float)):
            by_device[dev].append(float(ts))

    overall_pass = True
    for dev, timestamps in sorted(by_device.items()):
        timestamps.sort()
        first = timestamps[0]
        last = timestamps[-1]
        duration_s = last - first
        if duration_s < interval_s:
            print(f"\n=== {dev} === 数据太少 ({len(timestamps)} 条 / {duration_s:.0f}s),跳过分析")
            continue

        expected = int(duration_s / interval_s) + 1
        actual = len(timestamps)
        lost = max(0, expected - actual)
        loss_rate = lost / expected if expected > 0 else 0.0

        gaps = [timestamps[i + 1] - timestamps[i] for i in range(len(timestamps) - 1)]
        max_gap = max(gaps) if gaps else 0.0
        median_gap = sorted(gaps)[len(gaps) // 2] if gaps else 0.0

        loss_ok = loss_rate < 0.01
        gap_ok = max_gap < 300  # 5 min
        passed = loss_ok and gap_ok
        overall_pass = overall_pass and passed

        first_iso = datetime.fromtimestamp(first, tz=timezone.utc).astimezone().isoformat(timespec="seconds")
        last_iso = datetime.fromtimestamp(last, tz=timezone.utc).astimezone().isoformat(timespec="seconds")

        print(f"\n=== {dev} ===")
        print(f"  时间窗口    : {first_iso}  →  {last_iso}")
        print(f"  持续        : {duration_s/3600:.1f} h")
        print(f"  收到 / 期望 : {actual} / {expected}")
        print(f"  丢失        : {lost} ({loss_rate*100:.2f}%)  {'✓' if loss_ok else '✗(>1%)'}")
        print(f"  中位间隔    : {median_gap:.1f}s (期望 {interval_s}s)")
        print(f"  最大间隔    : {max_gap:.1f}s  {'✓' if gap_ok else '✗(>5min)'}")
        print(f"  判定        : {'PASS ✓' if passed else 'FAIL ✗'}")

    print()
    print("=" * 50)
    print(f"总体判定: {'Phase 0.5 通过 ✓ 可以进 Phase 1' if overall_pass else 'Phase 0.5 失败 ✗ 不要进 Phase 1'}")
    return 0 if overall_pass else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("log_file", help="heartbeat_server.py 产生的 hb.log")
    parser.add_argument("--interval", type=int, default=60, help="心跳间隔秒数(默认 60)")
    args = parser.parse_args()

    rows = load_records(args.log_file)
    return analyze(rows, args.interval)


if __name__ == "__main__":
    sys.exit(main())
