"""Flask dashboard for the Android RPA project.

Browser-facing control panel that wraps `rpa_mvp.py`:
- shows live ADB device status (model / Android / battery / foreground app)
- streams the RPA log buffer to the browser via Server-Sent Events
- starts/stops `inspect` and `workflow` runs in a background thread
- serves the latest screenshot

Listens on 127.0.0.1:8003 by default. Override with DASHBOARD_PORT=xxxx.
"""

from __future__ import annotations

import json
import os
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml
from flask import Flask, Response, abort, jsonify, render_template, request, send_file

import rpa_mvp
from rpa_mvp import LOG_BUFFER, ROOT, STATE, inspect_device, logger, run_workflow


HOST = "127.0.0.1"
PORT = int(os.environ.get("DASHBOARD_PORT", "8003"))

WORKFLOWS_DIR = ROOT / "workflows"


def list_workflows() -> List[Dict[str, Any]]:
    """Scan workflows/*.yaml (skipping _-prefixed templates) and return their
    metadata for the dashboard dropdown."""
    items: List[Dict[str, Any]] = []
    if not WORKFLOWS_DIR.is_dir():
        return items
    for path in sorted(WORKFLOWS_DIR.glob("*.yaml")):
        if path.name.startswith("_"):
            continue
        wf_id = path.stem
        try:
            with path.open("r", encoding="utf-8") as f:
                data = yaml.safe_load(f) or {}
            meta = data.get("meta") or {}
            items.append({
                "id": wf_id,
                "name": meta.get("name") or wf_id,
                "description": meta.get("description") or "",
                "default_keyword": (data.get("workflow") or {}).get("keyword") or "",
                "default_max_items": int((data.get("workflow") or {}).get("max_items") or 1),
                "default_dm_template": (data.get("drafts") or {}).get("dm_template") or "",
            })
        except Exception as exc:
            logger.warning(f"failed to read workflow {path.name}: {exc}")
    return items


def workflow_config_path(workflow_id: Optional[str] = None) -> Path:
    """Resolve workflow id → yaml path. Unknown/None → first workflow alphabetically."""
    if workflow_id:
        candidate = WORKFLOWS_DIR / f"{workflow_id}.yaml"
        if candidate.exists():
            return candidate
    items = list_workflows()
    if not items:
        raise RuntimeError(f"no workflows defined in {WORKFLOWS_DIR}")
    return WORKFLOWS_DIR / f"{items[0]['id']}.yaml"


_TASK_LOCK = threading.Lock()


def _adb(*args: str, timeout: float = 4.0) -> str:
    try:
        result = subprocess.run(
            ["adb", *args],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return (result.stdout or result.stderr).strip()
    except FileNotFoundError:
        return ""
    except subprocess.TimeoutExpired:
        return ""


def _adb_shell(serial: str, command: str) -> str:
    return _adb("-s", serial, "shell", *command.split())


def get_device_status() -> Dict[str, Any]:
    raw = _adb("devices")
    if not raw:
        return {"connected": False, "reason": "adb not available or no daemon"}

    rows = [
        line.strip()
        for line in raw.splitlines()
        if line.strip() and not line.lower().startswith("list of devices")
    ]
    if not rows:
        return {"connected": False, "reason": "no device plugged in"}

    serial, _, status = rows[0].partition("\t")
    serial = serial.strip()
    status = status.strip() or "unknown"
    if status != "device":
        return {"connected": False, "serial": serial, "status": status}

    info: Dict[str, Any] = {"connected": True, "serial": serial, "status": status}
    info["model"] = _adb_shell(serial, "getprop ro.product.model") or "unknown"
    info["android"] = _adb_shell(serial, "getprop ro.build.version.release") or "unknown"
    info["manufacturer"] = _adb_shell(serial, "getprop ro.product.manufacturer") or ""

    battery_out = _adb_shell(serial, "dumpsys battery")
    for line in battery_out.splitlines():
        line = line.strip()
        if line.startswith("level:"):
            try:
                info["battery"] = int(line.split(":", 1)[1].strip())
            except ValueError:
                pass
            break

    activity_out = _adb_shell(serial, "dumpsys activity activities")
    for line in activity_out.splitlines():
        if "ResumedActivity" in line:
            for token in line.split():
                if "/" in token and "." in token:
                    info["foreground_pkg"] = token.split("/")[0]
                    break
            if "foreground_pkg" in info:
                break

    size_out = _adb_shell(serial, "wm size")
    if "Physical size:" in size_out:
        info["resolution"] = size_out.split("Physical size:")[-1].strip().splitlines()[0]

    power_out = _adb_shell(serial, "dumpsys power")
    info["screen_on"] = "mWakefulness=Awake" in power_out

    # IP: 解 `ip route` 找 wlan0 那行的 src
    route_out = _adb_shell(serial, "ip route")
    info["ip"] = ""
    for line in route_out.splitlines():
        if "wlan0" in line and " src " in line:
            try:
                info["ip"] = line.split(" src ", 1)[1].split()[0]
            except IndexError:
                pass
            break

    # MAC: Android 10+ 拿真实 MAC 需要 root,SIM 卡和 sysfs 都受限
    # 设置里看到的 MAC 是 randomized 的,这里读 init.svc.macaddr 也只能拿到状态
    mac = _adb_shell(serial, "cat /sys/class/net/wlan0/address")
    if mac and ":" in mac and "denied" not in mac.lower():
        info["mac"] = mac.strip()
    else:
        info["mac"] = "受限 (Android 10+)"

    return info


app = Flask(__name__, template_folder=str(ROOT / "templates"), static_folder=str(ROOT / "static"))


@app.route("/")
def index() -> str:
    return render_template("index.html", port=PORT)


@app.route("/api/device")
def api_device() -> Response:
    return jsonify(get_device_status())


@app.route("/api/state")
def api_state() -> Response:
    snapshot = dict(STATE)
    if snapshot.get("started_at") and not snapshot.get("ended_at"):
        snapshot["elapsed_seconds"] = round(time.time() - snapshot["started_at"], 1)
    return jsonify(snapshot)


@app.route("/api/logs/stream")
def api_logs_stream() -> Response:
    def generate():
        snapshot = list(LOG_BUFFER)
        for ts, msg in snapshot:
            yield f"data: {json.dumps({'ts': ts, 'msg': msg})}\n\n"
        seen = len(snapshot)

        while True:
            current = list(LOG_BUFFER)
            if len(current) > seen:
                # buffer may have rotated; if so, replay everything
                if seen > len(current):
                    seen = 0
                for ts, msg in current[seen:]:
                    yield f"data: {json.dumps({'ts': ts, 'msg': msg})}\n\n"
                seen = len(current)
            else:
                yield ": heartbeat\n\n"
            time.sleep(0.6)

    return Response(generate(), mimetype="text/event-stream")


@app.route("/api/logs/inject", methods=["POST"])
def api_logs_inject() -> Response:
    payload = request.get_json(silent=True) or {}
    msg = str(payload.get("msg") or "ping from /api/logs/inject")
    logger.info(f"[inject] {msg}")
    return jsonify({"ok": True})


@app.route("/api/screenshot/latest")
def api_screenshot_latest() -> Response:
    path = STATE.get("last_screenshot")
    if not path or not Path(path).exists():
        abort(404)
    return send_file(path, mimetype="image/png")


def _start_task(target, *args) -> tuple[bool, str]:
    if not _TASK_LOCK.acquire(blocking=False):
        return False, "another task is already running"

    def runner():
        try:
            target(*args)
        except Exception as exc:
            logger.error(f"task failed: {exc}")
        finally:
            _TASK_LOCK.release()

    thread = threading.Thread(target=runner, daemon=True)
    thread.start()
    return True, "started"


@app.route("/api/workflows")
def api_workflows() -> Response:
    return jsonify(list_workflows())


@app.route("/api/run/inspect", methods=["POST"])
def api_run_inspect() -> Response:
    if STATE.get("running"):
        return jsonify({"ok": False, "msg": "already running"}), 409
    payload = request.get_json(silent=True) or {}
    workflow_id = payload.get("workflow")
    ok, msg = _start_task(inspect_device, workflow_config_path(workflow_id), False)
    return jsonify({"ok": ok, "msg": msg}), (200 if ok else 409)


@app.route("/api/run/workflow", methods=["POST"])
def api_run_workflow() -> Response:
    if STATE.get("running"):
        return jsonify({"ok": False, "msg": "already running"}), 409

    payload = request.get_json(silent=True) or {}
    workflow_id = payload.get("workflow")
    keyword = payload.get("keyword")
    max_items = payload.get("max_items")
    dm_template = payload.get("dm_template")

    if keyword:
        STATE["override_keyword"] = str(keyword).strip()
    if max_items is not None:
        try:
            STATE["override_max_items"] = max(1, int(max_items))
        except (TypeError, ValueError):
            STATE["override_max_items"] = None
    # 空字符串视为不覆盖,走 yaml 默认;非空就覆盖
    if dm_template is not None and str(dm_template).strip():
        STATE["override_dm_template"] = str(dm_template)

    ok, msg = _start_task(run_workflow, workflow_config_path(workflow_id), False)
    return jsonify({"ok": ok, "msg": msg}), (200 if ok else 409)


@app.route("/api/run/stop", methods=["POST"])
def api_run_stop() -> Response:
    STATE["stop_requested"] = True
    logger.warning("stop requested via dashboard")
    return jsonify({"ok": True})


def main() -> None:
    print(f"Dashboard starting on http://{HOST}:{PORT}")
    workflows = list_workflows()
    print(f"Found {len(workflows)} workflow(s) in {WORKFLOWS_DIR}:")
    for w in workflows:
        print(f"  - {w['id']}: {w['name']}")
    app.run(host=HOST, port=PORT, debug=False, threaded=True)


if __name__ == "__main__":
    main()
