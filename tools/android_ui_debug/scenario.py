"""JSON-driven ADB/UIAutomator scenarios.

A scenario is a list of steps. Selectors are matched against ``text`` and
``content-desc``; ``id`` matches ``resource-id`` exactly.
"""

from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Optional

from .adb import Adb, AdbError
from .apk import inspect_apk
from .ui import UiNode, find_nodes, parse_ui_xml, summarize


class ScenarioFailure(RuntimeError):
    def __init__(self, message: str, *, step: Optional[dict[str, Any]] = None, artifacts: Optional[Path] = None):
        super().__init__(message)
        self.step = step
        self.artifacts = artifacts


@dataclass
class StepResult:
    index: int
    action: str
    status: str
    detail: str = ""
    started: str = ""
    finished: str = ""


@dataclass
class ScenarioRunner:
    adb: Adb
    scenario: dict[str, Any]
    artifacts_root: Optional[Path] = None
    poll_interval: float = 0.5
    default_timeout: float = 15.0
    settle: float = 0.7
    results: list[StepResult] = field(default_factory=list)
    _last_xml: str = ""
    _artifacts: Optional[Path] = None

    # ------------------------------------------------------------------ setup
    @property
    def package(self) -> str:
        return self.scenario["package"]

    def run(self) -> Path:
        self._prepare_artifacts()
        self.results = []
        started = _now()
        try:
            for index, step in enumerate(self.scenario.get("steps", [])):
                result = StepResult(index=index, action=step.get("action", "?"), status="running", started=_now())
                self.results.append(result)
                try:
                    detail = self._dispatch(step)
                    result.status = "ok"
                    result.detail = detail or ""
                except Exception as exc:  # noqa: BLE001 - report every failure with artifacts
                    result.status = "failed"
                    result.detail = str(exc)
                    self._save_failure(index, step)
                    raise ScenarioFailure(
                        f"step {index} ({step.get('action')}) failed: {exc}",
                        step=step,
                        artifacts=self._artifacts,
                    ) from exc
                finally:
                    result.finished = _now()
        finally:
            self._write_report(started)
        return self._artifacts or Path(".")

    # --------------------------------------------------------------- internals
    def _prepare_artifacts(self) -> None:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        name = re.sub(r"[^a-zA-Z0-9_.-]+", "_", str(self.scenario.get("name", "scenario")))
        root = self.artifacts_root or Path("artifacts")
        self._artifacts = root / f"{name}-{stamp}"
        self._artifacts.mkdir(parents=True, exist_ok=True)

    def _dump(self, save: bool = False, label: str = "ui") -> list[UiNode]:
        self._last_xml = self.adb.dump_ui()
        if save and self._artifacts:
            (self._artifacts / f"{label}.xml").write_text(self._last_xml, encoding="utf-8")
        return parse_ui_xml(self._last_xml)

    def _save_failure(self, index: int, step: dict[str, Any]) -> None:
        if not self._artifacts:
            return
        try:
            self._dump(save=True, label=f"failure_{index:02d}")
        except Exception:  # noqa: BLE001 - keep the original failure
            pass
        try:
            (self._artifacts / f"failure_{index:02d}.txt").write_text(
                f"step={json.dumps(step, indent=2)}\n\n"
                f"visible nodes:\n{summarize(parse_ui_xml(self._last_xml))}\n",
                encoding="utf-8",
            )
        except Exception:  # noqa: BLE001
            pass
        try:
            (self._artifacts / "logcat_crash.txt").write_text(self.adb.crash_log(), encoding="utf-8")
            (self._artifacts / "logcat_app.txt").write_text(self.adb.app_error_log(self.package), encoding="utf-8")
        except Exception:  # noqa: BLE001
            pass

    def _write_report(self, started: str) -> None:
        if not self._artifacts:
            return
        payload = {
            "scenario": self.scenario.get("name"),
            "package": self.package,
            "device": self.adb.serial,
            "started": started,
            "finished": _now(),
            "ok": all(r.status == "ok" for r in self.results),
            "steps": [r.__dict__ for r in self.results],
        }
        (self._artifacts / "report.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def _selectors(self, step: dict[str, Any]) -> dict[str, Any]:
        mapping = {
            "text": "text",
            "contains": "contains",
            "regex": "regex",
            "desc": "desc",
            "id": "resource_id",
            "resource_id": "resource_id",
            "class": "class_name",
            "class_name": "class_name",
        }
        selectors = {mapping[k]: step[k] for k in mapping if k in step}
        if "clickable" in step:
            selectors["clickable"] = bool(step["clickable"])
        return selectors

    def _find(self, nodes: Iterable[UiNode], step: dict[str, Any]) -> Optional[UiNode]:
        selectors = self._selectors(step)
        if not selectors:
            raise ScenarioFailure(f"step {step.get('action')} needs a selector (text/contains/regex/desc/id)")
        found = find_nodes(nodes, **selectors)
        return found[0] if found else None

    def _swipe_vertical(self, direction: str, percent: float = 0.65) -> None:
        width, height = self.adb.screen_size()
        x = width // 2
        distance = int(height * max(0.1, min(0.9, percent)))
        if direction in ("down", "up"):
            # Finger down -> content scrolls up; finger up -> content scrolls down.
            if direction == "down":
                self.adb.swipe(x, int(height * 0.8), x, int(height * 0.8 - distance))
            else:
                self.adb.swipe(x, int(height * 0.2), x, int(height * 0.2 + distance))
        else:
            raise ScenarioFailure(f"unknown swipe direction: {direction}")
        time.sleep(self.settle)

    def _scroll_to(self, step: dict[str, Any], max_swipes: Optional[int] = None) -> UiNode:
        attempts = max_swipes if max_swipes is not None else int(step.get("max_swipes", 12))
        for attempt in range(attempts + 1):
            nodes = self._dump()
            node = self._find(nodes, step)
            if node is not None:
                return node
            if attempt == attempts:
                break
            self._swipe_vertical(str(step.get("scroll_direction", "down")), float(step.get("scroll_percent", 0.65)))
        raise ScenarioFailure(
            f"could not find {self._selectors(step)} after {attempts} swipes; "
            f"visible:\n{summarize(self._dump())}"
        )

    def _tap_node(self, node: UiNode, step: dict[str, Any]) -> str:
        x, y = node.center
        self.adb.tap(x, y)
        time.sleep(float(step.get("settle", self.settle)))
        return f"tapped ({x},{y}) {node}"

    def _wait_for(self, step: dict[str, Any], present: bool = True) -> str:
        timeout = float(step.get("timeout", self.default_timeout))
        deadline = time.time() + timeout
        last_nodes: list[UiNode] = []
        while time.time() < deadline:
            last_nodes = self._dump()
            node = self._find(last_nodes, step)
            if (node is not None) == present:
                return f"{'found' if present else 'gone'}: {node or self._selectors(step)}"
            time.sleep(self.poll_interval)
        raise ScenarioFailure(
            f"timed out after {timeout:.1f}s waiting for {'presence' if present else 'absence'} of "
            f"{self._selectors(step)}; visible:\n{summarize(last_nodes)}"
        )

    def _assert_log(self, step: dict[str, Any], present: bool) -> str:
        needle = str(step["contains"])
        logs = self.adb.crash_log() + "\n" + self.adb.app_error_log(self.package)
        found = needle in logs
        if found != present:
            raise ScenarioFailure(
                f"expected log {'to contain' if present else 'not to contain'} {needle!r}; "
                f"tail:\n{logs[-4000:]}"
            )
        return f"log {'contains' if present else 'does not contain'} {needle!r}"

    def _assert_no_crash(self, step: dict[str, Any]) -> str:
        logs = self.adb.crash_log()
        app_logs = self.adb.app_error_log(self.package)
        combined = logs + "\n" + app_logs
        patterns = step.get("patterns", ["FATAL EXCEPTION", "ANR in ", "Process: " + self.package])
        hits = [p for p in patterns if p in combined]
        if hits:
            raise ScenarioFailure(
                f"crash signatures {hits} found in logcat; tail:\n{combined[-5000:]}"
            )
        return "no crash signatures in logcat"

    def _dispatch(self, step: dict[str, Any]) -> str:
        action = str(step.get("action", "")).strip().lower()
        if action == "launch":
            if step.get("clear_data"):
                self.adb.clear_data(self.package)
            out = self.adb.start(self.package, step.get("activity"))
            time.sleep(float(step.get("settle", 2.0)))
            return out.strip()
        if action == "clear_logcat":
            self.adb.clear_logcat()
            return "logcat cleared"
        if action in ("grant", "grant_permission"):
            permissions = step.get("permissions")
            if permissions is None:
                permissions = [step["permission"]] if "permission" in step else []
            results = [self.adb.grant(self.package, p) for p in permissions]
            return "granted: " + ", ".join(p.strip() or "ok" for p in results)
        if action in ("revoke", "revoke_permission"):
            permissions = step.get("permissions")
            if permissions is None:
                permissions = [step["permission"]] if "permission" in step else []
            results = [self.adb.revoke(self.package, p) for p in permissions]
            return "revoked: " + ", ".join(p.strip() or "ok" for p in results)
        if action == "shell":
            return self.adb.shell(*str(step["command"]).split())
        if action in ("tap_xy", "tap_coordinates"):
            self.adb.tap(int(step["x"]), int(step["y"]))
            time.sleep(float(step.get("settle", self.settle)))
            return f"tapped ({step['x']},{step['y']})"
        if action == "tap":
            if step.get("scroll", True) and not step.get("no_scroll", False):
                node = self._scroll_to(step)
            else:
                node = self._find(self._dump(), step)
                if node is None:
                    raise ScenarioFailure(f"not found: {self._selectors(step)}")
            return self._tap_node(node, step)
        if action == "tap_text":
            step = {**step, "text": step.get("text", step.get("value"))}
            return self._dispatch({**step, "action": "tap"})
        if action == "tap_id":
            step = {**step, "id": step.get("id", step.get("value"))}
            return self._dispatch({**step, "action": "tap", "scroll": step.get("scroll", False)})
        if action in ("wait_text", "wait"):
            return self._wait_for(step, present=True)
        if action in ("wait_gone", "wait_absent"):
            return self._wait_for(step, present=False)
        if action in ("assert_text", "assert_visible"):
            nodes = self._dump()
            node = self._find(nodes, step)
            if node is None and step.get("scroll"):
                self._scroll_to(step)
                nodes = self._dump()
                node = self._find(nodes, step)
            if node is None:
                raise ScenarioFailure(
                    f"assertion failed: {self._selectors(step)} not visible; visible:\n{summarize(nodes)}"
                )
            return f"visible: {node}"
        if action in ("assert_no_text", "assert_absent"):
            nodes = self._dump()
            node = self._find(nodes, step)
            if node is not None:
                raise ScenarioFailure(f"assertion failed: {self._selectors(step)} is visible: {node}")
            return "absent"
        if action == "scroll_to":
            return str(self._scroll_to(step))
        if action == "swipe":
            self._swipe_vertical(str(step.get("direction", "up")), float(step.get("percent", 0.65)))
            return "swiped"
        if action == "back":
            self.adb.keyevent(4)
            time.sleep(float(step.get("settle", self.settle)))
            return "back"
        if action == "input_text":
            if any(k in step for k in ("text_selector", "id", "desc")):
                selector = step.get("text_selector", step)
                node = self._find(self._dump(), selector)
                if node is None:
                    raise ScenarioFailure(f"input field not found: {selector}")
                self._tap_node(node, step)
            self.adb.input_text(str(step.get("value", step.get("text", ""))))
            time.sleep(float(step.get("settle", self.settle)))
            return "text entered"
        if action == "assert_no_crash":
            return self._assert_no_crash(step)
        if action in ("assert_log_contains", "assert_log"):
            return self._assert_log(step, present=True)
        if action == "assert_log_not_contains":
            return self._assert_log(step, present=False)
        if action == "sleep":
            time.sleep(float(step.get("seconds", 1.0)))
            return f"slept {step.get('seconds', 1.0)}s"
        if action == "log":
            return str(step.get("message", ""))
        raise ScenarioFailure(f"unknown action: {action}")


def load_scenario(path: str | Path) -> dict[str, Any]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(data.get("steps"), list):
        raise ValueError("scenario is missing a 'steps' list")
    if not data.get("package") and not data.get("apk"):
        raise ValueError("scenario needs a 'package' or an 'apk' to inspect")
    return data


def run_scenario_file(
    path: str | Path,
    *,
    serial: Optional[str] = None,
    adb_path: Optional[str] = None,
    apk: Optional[str] = None,
    artifacts_root: str | Path = "artifacts",
    connect: Optional[str] = None,
    clear_logcat: bool = True,
) -> Path:
    adb = Adb(serial=serial, adb_path=adb_path)
    if connect:
        adb.connect(connect)
    adb.ensure_device()
    scenario = load_scenario(path)
    apk_path = apk or scenario.get("apk")
    if apk_path:
        info = inspect_apk(apk_path)
        scenario.setdefault("package", info.package)
        if info.activity:
            scenario.setdefault("activity", info.activity)
        adb.install(str(apk_path))
    if clear_logcat:
        adb.clear_logcat()
    runner = ScenarioRunner(adb=adb, scenario=scenario, artifacts_root=Path(artifacts_root))
    return runner.run()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
