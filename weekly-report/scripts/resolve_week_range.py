#!/usr/bin/env python3

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo


def build_range(mode: str, tz_name: str) -> tuple[datetime, datetime]:
    tz = ZoneInfo(tz_name)
    now = datetime.now(tz)

    if mode == "previous_week":
        base = now - timedelta(days=7)
        monday = base - timedelta(days=base.weekday())
        start = monday.replace(hour=0, minute=0, second=0, microsecond=0)
        end = (monday + timedelta(days=6)).replace(hour=23, minute=59, second=59, microsecond=0)
        return start, end

    if mode == "this_week_to_date":
        monday = now - timedelta(days=now.weekday())
        start = monday.replace(hour=0, minute=0, second=0, microsecond=0)
        end = now.replace(hour=23, minute=59, second=59, microsecond=0)
        return start, end

    raise ValueError(f"unsupported mode: {mode}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Resolve weekly report date range.")
    parser.add_argument(
        "--mode",
        choices=["previous_week", "this_week_to_date"],
        required=True,
        help="Range mode to resolve.",
    )
    parser.add_argument(
        "--timezone",
        default="Asia/Shanghai",
        help="IANA timezone name. Default: Asia/Shanghai",
    )
    args = parser.parse_args()

    start, end = build_range(args.mode, args.timezone)
    print(f"start_date={start:%Y-%m-%d}")
    print(f"end_date={end:%Y-%m-%d}")
    print(f"start_sec={int(start.timestamp())}")
    print(f"end_sec={int(end.timestamp())}")
    print(f"start_ms={int(start.timestamp() * 1000)}")
    print(f"end_ms={int(end.timestamp() * 1000)}")


if __name__ == "__main__":
    main()
