"""dyrpa server — multi-device 抖音 RPA control panel.

Two-level UI:
  /              — 设备列表网格
  /devices/{id}  — 单设备控制面板(mirror V9.1 dashboard.py)

JSON API under /api/, all device-scoped routes use /api/devices/{id}/.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .db import SessionLocal, get_session, init_db
from .log_bus import bus
from .models import Device, Task
from .mqtt_router import router as mqtt_router
from .workflow_loader import apply_overrides, list_workflows, load_workflow

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(name)s %(levelname)s %(message)s",
)
logger = logging.getLogger("dyrpa-server")

BASE_DIR = Path(__file__).parent.parent
TEMPLATES_DIR = BASE_DIR / "templates"
STATIC_DIR = BASE_DIR / "static"
SCREENSHOTS_DIR = BASE_DIR / "data" / "screenshots"
SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)
UI_DUMPS_DIR = BASE_DIR / "data" / "ui_dumps"
UI_DUMPS_DIR.mkdir(parents=True, exist_ok=True)


STALE_TASK_TIMEOUT_S = float(os.environ.get("STALE_TASK_TIMEOUT_S", "300"))
STALE_WATCHDOG_INTERVAL_S = float(os.environ.get("STALE_WATCHDOG_INTERVAL_S", "60"))


async def stale_task_watchdog() -> None:
    """Periodically scan running tasks; auto-fail any silent for >STALE_TASK_TIMEOUT_S.

    Catches zombie tasks where the device went silent (network drop, app killed,
    Shizuku binder hang). Does NOT signal the device — phone-side workflow may
    still be alive and finish; we just stop pretending dashboard-side that we're
    waiting for it. Subsequent events from a now-failed task are no-ops because
    _update_task's progress branches don't touch terminal-status tasks.
    """
    while True:
        try:
            await asyncio.sleep(STALE_WATCHDOG_INTERVAL_S)
            now = time.time()
            cutoff = now - STALE_TASK_TIMEOUT_S
            with SessionLocal() as session:
                running = (
                    session.query(Task)
                    .filter(Task.status == "running")
                    .filter(Task.last_event_at > 0)
                    .filter(Task.last_event_at < cutoff)
                    .all()
                )
                for task in running:
                    silent_for = round(now - task.last_event_at, 1)
                    task.status = "failed"
                    task.ended_at = now
                    task.error = f"stalled: no event for {silent_for}s (threshold {STALE_TASK_TIMEOUT_S:.0f}s)"
                    dev = session.get(Device, task.device_id)
                    if dev and dev.current_task_id == task.id:
                        dev.current_task_id = ""
                    bus.append(
                        task.device_id,
                        "warn",
                        f"[watchdog] task {task.id} force-failed: silent for {silent_for}s",
                    )
                if running:
                    session.commit()
                    logger.warning(f"stale watchdog failed {len(running)} task(s)")
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("stale_task_watchdog iteration failed; continuing")


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    mqtt_router.host = os.environ.get("MQTT_HOST", "localhost")
    mqtt_router.port = int(os.environ.get("MQTT_PORT", "1883"))
    mqtt_router.start()
    watchdog_task = asyncio.create_task(stale_task_watchdog())
    logger.info(
        f"dyrpa-server up; MQTT target={mqtt_router.host}:{mqtt_router.port}; "
        f"stale watchdog every {STALE_WATCHDOG_INTERVAL_S:.0f}s, "
        f"timeout {STALE_TASK_TIMEOUT_S:.0f}s"
    )
    yield
    watchdog_task.cancel()
    try:
        await watchdog_task
    except asyncio.CancelledError:
        pass
    mqtt_router.stop()


app = FastAPI(title="dyrpa-server", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))


# ====================== Pydantic schemas ======================


class OverridesIn(BaseModel):
    keyword: Optional[str] = None
    max_items: Optional[int] = None
    dm_template: Optional[str] = None
    workflow_id: Optional[str] = None


class RunIn(BaseModel):
    workflow_id: str
    overrides: OverridesIn = OverridesIn()


# ====================== HTML pages ======================


@app.get("/", response_class=HTMLResponse)
async def page_index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse("devices_index.html", {"request": request})


@app.get("/devices/{device_id}", response_class=HTMLResponse)
async def page_device_detail(device_id: str, request: Request) -> HTMLResponse:
    return templates.TemplateResponse(
        "device_detail.html",
        {"request": request, "device_id": device_id},
    )


# ====================== Helpers ======================


def _device_to_dict(dev: Device, now: float) -> dict:
    return {
        "id": dev.id,
        "name": dev.name or dev.id,
        "manufacturer": dev.manufacturer,
        "model": dev.model,
        "android": dev.android_version,
        "battery": dev.battery,
        "ip": dev.ip,
        "last_seen": dev.last_seen,
        "online": (now - dev.last_seen) < 180 if dev.last_seen else False,
        "current_task_id": dev.current_task_id,
        "last_task_kind": dev.last_task_kind,
        "override_workflow_id": dev.override_workflow_id,
        "override_keyword": dev.override_keyword,
        "override_max_items": dev.override_max_items,
        "override_dm_template": dev.override_dm_template,
        "last_screenshot_path": dev.last_screenshot_path,
    }


# ====================== API: devices ======================


@app.get("/api/devices")
async def api_list_devices(session: Session = Depends(get_session)):
    now = time.time()
    return [_device_to_dict(d, now) for d in session.query(Device).all()]


@app.get("/api/devices/{device_id}")
async def api_get_device(device_id: str, session: Session = Depends(get_session)):
    dev = session.get(Device, device_id)
    if dev is None:
        return {"id": device_id, "online": False, "registered": False}
    out = _device_to_dict(dev, time.time())
    out["registered"] = True
    return out


@app.post("/api/devices/{device_id}/overrides")
async def api_set_overrides(
    device_id: str,
    body: OverridesIn,
    session: Session = Depends(get_session),
):
    """Persist per-device overrides server-side (replaces V9.1 localStorage)."""
    dev = session.get(Device, device_id)
    if dev is None:
        # auto-register placeholder so user can configure ahead of first heartbeat
        dev = Device(id=device_id, name=device_id)
        session.add(dev)
    if body.keyword is not None:
        dev.override_keyword = body.keyword
    if body.max_items is not None:
        dev.override_max_items = max(0, body.max_items)
    if body.dm_template is not None:
        dev.override_dm_template = body.dm_template
    if body.workflow_id is not None:
        dev.override_workflow_id = body.workflow_id
    session.commit()
    return {"ok": True}


# ====================== API: state ======================


@app.get("/api/devices/{device_id}/state")
async def api_device_state(device_id: str, session: Session = Depends(get_session)):
    dev = session.get(Device, device_id)
    if dev is None:
        return {"running": False, "task_kind": "idle"}
    if not dev.current_task_id:
        return {
            "running": False,
            "task_kind": "idle",
            "last_screenshot": dev.last_screenshot_path,
        }
    task = session.get(Task, dev.current_task_id)
    if task is None:
        return {"running": False, "task_kind": "idle"}
    out = {
        "running": task.status == "running",
        "task_id": task.id,
        "task_kind": task.kind,
        "status": task.status,
        "current_index": task.progress_cur,
        "total": task.progress_total,
        "started_at": task.started_at,
        "ended_at": task.ended_at,
        "last_screenshot": dev.last_screenshot_path,
    }
    if task.started_at and not task.ended_at:
        out["elapsed_seconds"] = round(time.time() - task.started_at, 1)
    return out


# ====================== API: SSE per-device logs ======================


@app.get("/api/devices/{device_id}/logs/stream")
async def api_device_logs_stream(device_id: str):
    async def gen():
        async for ts, level, msg in bus.stream(device_id):
            payload = {"ts": ts, "level": level, "msg": msg}
            yield f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"

    return StreamingResponse(gen(), media_type="text/event-stream")


# ====================== API: workflows ======================


@app.get("/api/workflows")
async def api_workflows():
    return list_workflows()


# ====================== API: run / stop ======================


@app.post("/api/devices/{device_id}/run/workflow")
async def api_run_workflow(
    device_id: str, body: RunIn, session: Session = Depends(get_session)
):
    dev = session.get(Device, device_id)
    if dev is None:
        raise HTTPException(404, "device not registered (no heartbeat yet)")
    if dev.current_task_id:
        active = session.get(Task, dev.current_task_id)
        if active and active.status == "running":
            raise HTTPException(409, "device already running a task")

    try:
        wf = load_workflow(body.workflow_id)
    except FileNotFoundError:
        raise HTTPException(400, f"workflow not found: {body.workflow_id}")

    # Dashboard input field is the source of truth at run time — whatever the user
    # typed (and the form auto-saved into Device.override_*) is what gets searched.
    overrides = body.overrides.model_dump(exclude_none=True)
    merged = apply_overrides(wf, overrides)

    task = Task(
        id=uuid.uuid4().hex[:12],
        device_id=device_id,
        workflow_id=body.workflow_id,
        kind="workflow",
        overrides=overrides,
        status="pending",
    )
    session.add(task)
    dev.current_task_id = task.id
    dev.last_task_kind = "workflow"
    session.commit()

    mqtt_router.push_task(
        device_id,
        {
            "task_id": task.id,
            "kind": "workflow",
            "workflow_id": body.workflow_id,
            "workflow_yaml": merged,
            "overrides": overrides,
            "issued_at": time.time(),
        },
    )
    bus.append(device_id, "info", f"[server] task {task.id} dispatched (workflow={body.workflow_id})")
    return {"ok": True, "task_id": task.id}


@app.post("/api/devices/{device_id}/run/inspect")
async def api_run_inspect(
    device_id: str, body: RunIn, session: Session = Depends(get_session)
):
    dev = session.get(Device, device_id)
    if dev is None:
        raise HTTPException(404, "device not registered")
    task = Task(
        id=uuid.uuid4().hex[:12],
        device_id=device_id,
        workflow_id=body.workflow_id,
        kind="inspect",
        status="pending",
    )
    session.add(task)
    dev.current_task_id = task.id
    dev.last_task_kind = "inspect"
    session.commit()
    mqtt_router.push_task(
        device_id,
        {
            "task_id": task.id,
            "kind": "inspect",
            "workflow_id": body.workflow_id,
            "issued_at": time.time(),
        },
    )
    bus.append(device_id, "info", f"[server] inspect dispatched (task={task.id})")
    return {"ok": True, "task_id": task.id}


@app.post("/api/devices/{device_id}/run/stop")
async def api_run_stop(device_id: str, session: Session = Depends(get_session)):
    mqtt_router.push_stop(device_id)
    bus.append(device_id, "warn", "[server] stop requested")
    # Force-fail the current running task locally so dashboard immediately reflects
    # the stop. Without this, if the device is offline / lost MQTT during the run,
    # task.status stays "running" forever and "已运行" keeps growing because
    # ended_at never gets set (no task_failed event ever arrives).
    dev = session.get(Device, device_id)
    if dev and dev.current_task_id:
        task = session.get(Task, dev.current_task_id)
        if task and task.status == "running":
            task.status = "failed"
            task.error = "stopped by user"
            task.ended_at = time.time()
            dev.current_task_id = ""
            session.commit()
            bus.append(device_id, "warn", "[server] task force-failed (stop)")
    return {"ok": True}


# ====================== API: screenshots ======================


@app.post("/api/devices/{device_id}/screenshots")
async def api_upload_screenshot(
    device_id: str,
    file: UploadFile = File(...),
    session: Session = Depends(get_session),
):
    dev = session.get(Device, device_id)
    if dev is None:
        raise HTTPException(404, "device not registered")
    dest_dir = SCREENSHOTS_DIR / device_id
    dest_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{int(time.time() * 1000)}.png"
    path = dest_dir / filename
    content = await file.read()
    path.write_bytes(content)
    dev.last_screenshot_path = str(path)
    session.commit()
    bus.append(device_id, "info", f"[screenshot] {filename}")
    return {"ok": True, "path": str(path)}


@app.get("/api/devices/{device_id}/screenshots/latest")
async def api_latest_screenshot(device_id: str, session: Session = Depends(get_session)):
    dev = session.get(Device, device_id)
    if dev is None or not dev.last_screenshot_path:
        raise HTTPException(404, "no screenshot")
    p = Path(dev.last_screenshot_path)
    if not p.exists():
        raise HTTPException(404, "screenshot file missing")
    return FileResponse(p, media_type="image/png")


@app.get("/api/devices/{device_id}/screenshots")
async def api_list_screenshots(device_id: str, limit: int = 50):
    """List the most recent N screenshots for a device (newest first).
    Used by failure-replay UI to render a per-candidate grid."""
    d = SCREENSHOTS_DIR / device_id
    if not d.exists():
        return []
    files = sorted(d.glob("*.png"), key=lambda p: p.stat().st_mtime, reverse=True)[:limit]
    return [
        {
            "name": p.name,
            "size": p.stat().st_size,
            "mtime": p.stat().st_mtime,
            "url": f"/api/devices/{device_id}/screenshots/{p.name}",
        }
        for p in files
    ]


# ====================== API: UI XML dumps (forensics) ======================


@app.post("/api/devices/{device_id}/ui-dumps")
async def api_upload_uidump(
    device_id: str,
    label: str = "",
    file: UploadFile = File(...),
    session: Session = Depends(get_session),
):
    """Upload UI tree XML captured at the moment of an action failure.
    Stored as data/ui_dumps/{device_id}/{ts_ms}_{label}.xml — lets you compare
    yaml's expected attr=value against the real UI tree抖音 was showing."""
    dev = session.get(Device, device_id)
    if dev is None:
        raise HTTPException(404, "device not registered")
    dest_dir = UI_DUMPS_DIR / device_id
    dest_dir.mkdir(parents=True, exist_ok=True)
    safe_label = "".join(c if c.isalnum() or c in "._-" else "_" for c in label[:80])
    filename = f"{int(time.time() * 1000)}_{safe_label or 'dump'}.xml"
    content = await file.read()
    (dest_dir / filename).write_bytes(content)
    bus.append(device_id, "warn", f"[ui_dump] {filename} ({len(content)}B) label={safe_label}")
    return {"ok": True, "filename": filename, "size": len(content)}


@app.get("/api/devices/{device_id}/ui-dumps")
async def api_list_uidumps(device_id: str, limit: int = 50):
    d = UI_DUMPS_DIR / device_id
    if not d.exists():
        return []
    files = sorted(d.glob("*.xml"), key=lambda p: p.stat().st_mtime, reverse=True)[:limit]
    return [
        {
            "name": p.name,
            "size": p.stat().st_size,
            "mtime": p.stat().st_mtime,
            "url": f"/api/devices/{device_id}/ui-dumps/{p.name}",
        }
        for p in files
    ]


@app.get("/api/devices/{device_id}/ui-dumps/{filename}")
async def api_get_uidump(device_id: str, filename: str):
    base = Path(filename).name
    p = UI_DUMPS_DIR / device_id / base
    if not p.exists():
        raise HTTPException(404, "ui dump not found")
    return FileResponse(p, media_type="application/xml")


@app.get("/api/devices/{device_id}/screenshots/{filename}")
async def api_get_screenshot(device_id: str, filename: str):
    # path traversal guard: only basename allowed
    if "/" in filename or ".." in filename or not filename.endswith(".png"):
        raise HTTPException(400, "bad filename")
    p = SCREENSHOTS_DIR / device_id / filename
    if not p.exists():
        raise HTTPException(404, "not found")
    return FileResponse(p, media_type="image/png")


# ====================== API: batch dispatch ======================


class BatchRunIn(BaseModel):
    device_ids: list[str]
    workflow_id: str
    overrides: OverridesIn = OverridesIn()


@app.post("/api/batch/run")
async def api_batch_run(body: BatchRunIn, session: Session = Depends(get_session)):
    """Fan out the same workflow to multiple devices at once.

    Per-device overrides on the Device row (override_keyword etc.) take precedence
    over the BatchRunIn overrides — so each device keeps its independent keyword /
    话术 even when running the same workflow simultaneously.
    """
    if not body.device_ids:
        raise HTTPException(400, "device_ids must be non-empty")
    try:
        wf = load_workflow(body.workflow_id)
    except FileNotFoundError:
        raise HTTPException(400, f"workflow not found: {body.workflow_id}")

    batch_overrides = body.overrides.model_dump(exclude_none=True)
    dispatched: list[dict] = []
    skipped: list[dict] = []
    for dev_id in body.device_ids:
        dev = session.get(Device, dev_id)
        if dev is None:
            skipped.append({"device_id": dev_id, "reason": "not registered"})
            continue
        if dev.current_task_id:
            active = session.get(Task, dev.current_task_id)
            if active and active.status == "running":
                skipped.append({"device_id": dev_id, "reason": "busy"})
                continue

        # Merge: batch overrides first, then per-device persisted overrides win
        per_device = {
            "keyword": dev.override_keyword,
            "max_items": dev.override_max_items,
            "dm_template": dev.override_dm_template,
        }
        merged_overrides = {**batch_overrides}
        for k, v in per_device.items():
            if v not in (None, "", 0):
                merged_overrides[k] = v

        merged_wf = apply_overrides(wf, merged_overrides)
        task = Task(
            id=uuid.uuid4().hex[:12],
            device_id=dev_id,
            workflow_id=body.workflow_id,
            kind="workflow",
            overrides=merged_overrides,
            status="pending",
        )
        session.add(task)
        dev.current_task_id = task.id
        dev.last_task_kind = "workflow"
        session.commit()
        mqtt_router.push_task(
            dev_id,
            {
                "task_id": task.id,
                "kind": "workflow",
                "workflow_id": body.workflow_id,
                "workflow_yaml": merged_wf,
                "overrides": merged_overrides,
                "issued_at": time.time(),
            },
        )
        bus.append(dev_id, "info", f"[batch] task {task.id} dispatched (workflow={body.workflow_id})")
        dispatched.append({"device_id": dev_id, "task_id": task.id})

    return {"ok": True, "dispatched": dispatched, "skipped": skipped}


@app.post("/api/batch/stop")
async def api_batch_stop(body: BatchRunIn):
    """Broadcast stop to a list of devices."""
    for dev_id in body.device_ids:
        mqtt_router.push_stop(dev_id)
        bus.append(dev_id, "warn", "[batch] stop requested")
    return {"ok": True, "stopped": body.device_ids}


# ====================== Dev helper: inject log entries ======================


@app.post("/api/devices/{device_id}/logs/inject")
async def api_logs_inject(device_id: str, request: Request):
    body = await request.json()
    msg = str(body.get("msg") or "ping from /api/logs/inject")
    level = body.get("level") or "info"
    bus.append(device_id, level, msg)
    return {"ok": True}


# ====================== Health ======================


@app.get("/api/health")
async def api_health():
    return {
        "ok": True,
        "mqtt_connected": mqtt_router.connected,
        "mqtt_host": mqtt_router.host,
        "mqtt_port": mqtt_router.port,
    }
