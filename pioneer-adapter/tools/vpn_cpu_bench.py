#!/usr/bin/env python3
"""Reproduce the same wired CarPlay interaction and report process/thread CPU."""

import argparse
import importlib.util
import json
import statistics
import time
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[2]
DEBUG_CLIENT = Path(__file__).with_name("diplay_debug.py")


def load_debug_client():
    spec = importlib.util.spec_from_file_location("diplay_debug", DEBUG_CLIENT)
    if spec is None or spec.loader is None:
        raise RuntimeError("Could not load diplay_debug.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def cpu_window(before, after, duration_seconds):
    duration_seconds = max(duration_seconds, 0.001)
    process_percent = (
        after["processCpuMs"] - before["processCpuMs"]
    ) / 10.0 / duration_seconds
    before_threads = {
        row["tid"]: row["userTicks"] + row["systemTicks"]
        for row in before["threads"]
    }
    threads = []
    for row in after["threads"]:
        ticks = row["userTicks"] + row["systemTicks"]
        delta = ticks - before_threads.get(row["tid"], ticks)
        threads.append({
            "tid": row["tid"],
            "name": row["name"],
            "corePercent": delta / duration_seconds,
        })
    return process_percent, sorted(
        threads, key=lambda item: item["corePercent"], reverse=True
    )


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((len(ordered) - 1) * percent)))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser(
        description="Run a repeatable VPN CPU benchmark over DiPlay TCP debugging"
    )
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=8721)
    parser.add_argument("--timeout", type=float, default=45.0)
    parser.add_argument("--token")
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--seconds", type=float, default=15.0)
    parser.add_argument("--gesture", choices=["SWIPE", "PINCH"], default="SWIPE")
    parser.add_argument("--expect-backend", default="VPN")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error("--rounds must be at least 1")
    if args.seconds < 15:
        parser.error("--seconds must be at least 15")

    debug = load_debug_client()
    client = SimpleNamespace(
        host=args.host,
        port=args.port,
        timeout=args.timeout,
        token=args.token,
        apk=None,
        output=None,
    )

    def request(command, **fields):
        reply, body = debug.request(client, command, **fields)
        if body:
            return json.loads(body)
        return reply

    args.output.mkdir(parents=True, exist_ok=True)
    rounds = []
    for round_number in range(1, args.rounds + 1):
        status_before = request("status", compact=True)
        backend = status_before.get("pioneerWiredSession", {}).get("backend")
        if args.expect_backend and backend != args.expect_backend:
            raise RuntimeError(
                f"Expected {args.expect_backend}, found {backend or 'NONE'}"
            )

        request("host-ui", action="FOREGROUND")
        request("host-ui", action="MEDIA_PAUSE")
        time.sleep(1)
        before = request("runtime", compact=True)
        request("video-gesture", kind=args.gesture)
        started = time.monotonic()
        samples = 0
        while time.monotonic() - started < args.seconds:
            time.sleep(min(2.0, max(0.0, args.seconds - (time.monotonic() - started))))
            samples += 1
        after = request("runtime", compact=True)
        status_after = request("status", compact=True)
        duration = (after["atNs"] - before["atNs"]) / 1_000_000_000
        process_percent, threads = cpu_window(before, after, duration)
        rendered_before = status_before.get("host", {}).get("videoRendered")
        rendered_after = status_after.get("host", {}).get("videoRendered")
        received_before = status_before.get("host", {}).get("videoReceived")
        received_after = status_after.get("host", {}).get("videoReceived")
        result = {
            "round": round_number,
            "durationSeconds": duration,
            "sampleCount": samples,
            "samePid": before["pid"] == after["pid"],
            "pid": before["pid"],
            "backend": backend,
            "cpuPercentOneCore": process_percent,
            "threads": threads[:24],
            "videoReceivedBefore": received_before,
            "videoReceivedAfter": received_after,
            "videoReceivedDelta": (
                received_after - received_before
                if isinstance(received_before, int) and isinstance(received_after, int)
                else None
            ),
            "renderedBefore": rendered_before,
            "renderedAfter": rendered_after,
            "renderedDelta": (
                rendered_after - rendered_before
                if isinstance(rendered_before, int) and isinstance(rendered_after, int)
                else None
            ),
            "networkBefore": status_before.get("networkCounters"),
            "networkAfter": status_after.get("networkCounters"),
        }
        (args.output / f"round-{round_number}.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2)
        )
        print(json.dumps({
            "round": round_number,
            "durationSeconds": round(duration, 3),
            "cpuPercentOneCore": round(process_percent, 3),
            "renderedDelta": result["renderedDelta"],
        }, ensure_ascii=False), flush=True)
        rounds.append(result)

    cpu_values = [row["cpuPercentOneCore"] for row in rounds]
    rendered_values = [
        row["renderedDelta"] for row in rounds if row["renderedDelta"] is not None
    ]
    summary = {
        "rounds": len(rounds),
        "samePidAcrossRounds": len({row["pid"] for row in rounds}) == 1,
        "backend": rounds[0]["backend"],
        "gesture": args.gesture,
        "durationSeconds": args.seconds,
        "cpuPercentOneCore": {
            "median": statistics.median(cpu_values),
            "max": max(cpu_values),
            "min": min(cpu_values),
        },
        "renderedDelta": {
            "median": statistics.median(rendered_values) if rendered_values else None,
            "min": min(rendered_values) if rendered_values else None,
        },
        "threadCpuP95": {
            thread["name"]: percentile([
                next(
                    candidate["corePercent"]
                    for candidate in row["threads"]
                    if candidate["tid"] == thread["tid"]
                )
                for row in rounds
                if any(candidate["tid"] == thread["tid"] for candidate in row["threads"])
            ], 0.95)
            for thread in rounds[0]["threads"]
        },
        "p95P99Unavailable": "DiPlay runtime exposes bounded histograms separately",
        "roundsData": rounds,
    }
    (args.output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2)
    )
    print(json.dumps(summary["cpuPercentOneCore"], ensure_ascii=False))
    return 0 if summary["samePidAcrossRounds"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
