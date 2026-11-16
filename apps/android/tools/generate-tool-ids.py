#!/usr/bin/env python3
"""Generates Android ToolIds.kt from packages/contract/tools.json.

Run after editing tools.json:
    python3 apps/android/tools/generate-tool-ids.py
"""
import json
import pathlib
import sys

# Script lives at apps/android/tools/, so the repo root is three parents up.
ROOT = pathlib.Path(__file__).resolve().parents[3]
TOOLS_JSON = ROOT / "packages/contract/tools.json"
OUT = ROOT / "apps/android/src/test/java/com/example/contract/ToolIds.kt"


def main() -> int:
    data = json.loads(TOOLS_JSON.read_text())
    caps = data["capabilities"]

    lines = [
        "package com.example.contract",
        "",
        "// Generated from packages/contract/tools.json by tools/generate-tool-ids.py. Do not edit by hand.",
        "object ToolIds {",
        "    val ALL: List<String> = listOf(",
    ]
    for i, cap in enumerate(caps):
        lines.append(f'        "{cap["id"]}"' + ("," if i < len(caps) - 1 else ""))
    lines += ["    )", "", "    val ALIASES: Map<String, List<String>> = mapOf("]
    for i, cap in enumerate(caps):
        aliases = ", ".join(f'"{a}"' for a in cap["aliases"])
        lines.append(f'        "{cap["id"]}" to listOf({aliases})' + ("," if i < len(caps) - 1 else ""))
    lines += ["    )", "}"]

    OUT.write_text("\n".join(lines) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(caps)} capabilities)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
