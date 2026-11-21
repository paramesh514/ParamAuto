#!/usr/bin/env python3
"""Desktop tester for ParamAuto's data source.

The app no longer listens for UDP broadcasts. It discovers the comma on the
local network (scan for port 8082, verify /api/device/status) and polls
GET /api/params/all once per second. This script mirrors that from a PC:

    python3 tools/listen.py --host 192.168.68.9

Prints the nav instruction, speed limit and band offset the app would show.
Also polls the comma's battery voltage from /api/system/monitor every 3 s.
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.request

MPH = 2.23694
BANDS = [(25, "Offset1"), (35, "Offset2"), (45, "Offset3"), (55, "Offset4"),
         (65, "Offset5"), (75, "Offset6"), (float("inf"), "Offset7")]


def fetch(host: str, path: str) -> dict:
    with urllib.request.urlopen(f"http://{host}:8082{path}", timeout=5) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch_power(host: str) -> str | None:
    try:
        data = fetch(host, "/api/system/monitor")
        volt = data.get("batteryVoltage")
        if volt is not None:
            return f"power: {float(volt):.2f} V (from /api/system/monitor)"
    except Exception:
        pass
    return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True, help="comma IP on your LAN/hotspot")
    args = ap.parse_args()

    print("device:", fetch(args.host, "/api/device/status"))
    last_power_at = 0.0
    while True:
        params = fetch(args.host, "/api/params/all")
        raw_nav = params.get("NavInstructionState") or ""
        nav = json.loads(raw_nav) if isinstance(raw_nav, str) and raw_nav else {}
        limit_ms = float(params.get("PreviousSpeedLimit") or 0.0)
        limit_mph = limit_ms * MPH
        offset = 0.0
        for cap, key in BANDS:
            if limit_mph < cap:
                offset = float(params.get(key) or 0.0)
                break
        if nav.get("valid"):
            print(f"nav: {nav.get('maneuverPrimaryText')} in "
                  f"{float(nav.get('maneuverDistance') or 0):.0f} m "
                  f"({nav.get('maneuverModifier')}), lane "
                  f"{int(nav.get('activeLaneIndex', -1)) + 1} of {nav.get('laneCount')}")
        else:
            print("nav: (no active instruction)")
        if limit_ms >= 1:
            print(f"limit: {limit_mph:.0f} mph, offset {offset:+.0f}")
        if time.time() - last_power_at >= 3:
            last_power_at = time.time()
            line = fetch_power(args.host)
            if line:
                print(line)
        time.sleep(1)


if __name__ == "__main__":
    main()
