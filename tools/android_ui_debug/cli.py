"""Command-line entry point: ``python3 -m tools.android_ui_debug``."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .adb import Adb, AdbError
from .apk import ApkError, inspect_apk
from .scenario import ScenarioFailure, load_scenario, run_scenario_file
from .ui import parse_ui_xml, summarize


def _cmd_devices(args: argparse.Namespace) -> int:
    adb = Adb(serial=args.serial, adb_path=args.adb)
    devices = adb.devices()
    if not devices:
        print("no devices")
        return 1
    for device in devices:
        print(f"{device['serial']}\t{device['state']}\t{device['details']}")
    return 0


def _cmd_dump(args: argparse.Namespace) -> int:
    adb = Adb(serial=args.serial, adb_path=args.adb)
    adb.ensure_device()
    xml = adb.dump_ui()
    if args.raw:
        print(xml)
    else:
        nodes = parse_ui_xml(xml)
        print(summarize(nodes, limit=args.limit))
    if args.save:
        Path(args.save).write_text(xml, encoding="utf-8")
        print(f"saved {args.save}", file=sys.stderr)
    return 0


def _cmd_tap(args: argparse.Namespace) -> int:
    adb = Adb(serial=args.serial, adb_path=args.adb)
    adb.ensure_device()
    nodes = parse_ui_xml(adb.dump_ui())
    from .ui import find_nodes

    found = find_nodes(nodes, text=args.text, contains=args.contains, regex=args.regex, desc=args.desc, resource_id=args.id)
    if not found:
        print(f"not found: {args}", file=sys.stderr)
        print(summarize(nodes), file=sys.stderr)
        return 1
    node = found[0]
    x, y = node.center
    adb.tap(x, y)
    print(f"tapped ({x},{y}) {node}")
    return 0


def _cmd_apk(args: argparse.Namespace) -> int:
    try:
        info = inspect_apk(args.apk)
    except ApkError as exc:
        print(f"apk error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(info.__dict__, indent=2))
    return 0


def _cmd_run(args: argparse.Namespace) -> int:
    try:
        artifacts = run_scenario_file(
            args.scenario,
            serial=args.serial,
            adb_path=args.adb,
            apk=args.apk,
            artifacts_root=args.artifacts,
            connect=args.connect,
            clear_logcat=not args.keep_logs,
        )
    except (AdbError, ScenarioFailure) as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        return 1
    report = artifacts / "report.json"
    if report.exists():
        print(json.dumps(json.loads(report.read_text(encoding="utf-8")), indent=2))
    print(f"artifacts: {artifacts}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="ADB/UIAutomator debugging for the Apex APK")
    parser.add_argument("--adb", help="path to adb (default: discover)")
    parser.add_argument("--serial", help="adb device serial")
    sub = parser.add_subparsers(dest="command", required=True)

    devices = sub.add_parser("devices", help="list adb devices")
    devices.set_defaults(func=_cmd_devices)

    dump = sub.add_parser("dump", help="dump the current UI hierarchy")
    dump.add_argument("--raw", action="store_true", help="print raw XML")
    dump.add_argument("--limit", type=int, default=80)
    dump.add_argument("--save", help="write the raw XML here")
    dump.set_defaults(func=_cmd_dump)

    tap = sub.add_parser("tap", help="find a node and tap its center")
    tap.add_argument("--text")
    tap.add_argument("--contains")
    tap.add_argument("--regex")
    tap.add_argument("--desc")
    tap.add_argument("--id")
    tap.set_defaults(func=_cmd_tap)

    apk = sub.add_parser("apk", help="inspect an APK's package and launcher activity")
    apk.add_argument("apk")
    apk.set_defaults(func=_cmd_apk)

    run = sub.add_parser("run", help="run a JSON scenario")
    run.add_argument("scenario")
    run.add_argument("--apk", help="install this APK before running")
    run.add_argument("--connect", help="adb connect host:port first")
    run.add_argument("--artifacts", default="artifacts", help="artifact directory")
    run.add_argument("--keep-logs", action="store_true", help="do not clear logcat before running")
    run.set_defaults(func=_cmd_run)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except AdbError as exc:
        print(f"adb error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
