"""MQTT client + topic routing.

Topics (all under dyrpa/devices/{id}/):
  Device → Server:
    heartbeat — registration + liveness (every 30-60s)
    event     — state changes, logs, screenshots, etc.
  Server → Device:
    task      — task assignment payload
    control   — control commands (stop, etc.)
"""
from __future__ import annotations

import json
import logging
import threading
import time
from typing import Optional

import paho.mqtt.client as mqtt

from .db import SessionLocal
from .log_bus import bus
from .models import Device, Task

logger = logging.getLogger("mqtt")

_PREFIX = "dyrpa/devices"


class MqttRouter:
    def __init__(self, host: str = "localhost", port: int = 1883) -> None:
        self.host = host
        self.port = port
        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"dyrpa-server-{int(time.time())}",
        )
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.on_disconnect = self._on_disconnect
        self._thread: Optional[threading.Thread] = None
        self._connected = False

    def start(self) -> None:
        try:
            self.client.connect(self.host, self.port, keepalive=60)
        except Exception as exc:
            logger.warning(
                f"MQTT broker unreachable at {self.host}:{self.port} — {exc}. "
                "Server will run without MQTT (no real device control)."
            )
            return
        self._thread = threading.Thread(target=self.client.loop_forever, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        try:
            self.client.disconnect()
        except Exception:
            pass

    @property
    def connected(self) -> bool:
        return self._connected

    # ---------- subscriptions ----------

    def _on_connect(self, client, userdata, flags, reason_code, properties) -> None:
        self._connected = True
        client.subscribe(f"{_PREFIX}/+/heartbeat")
        client.subscribe(f"{_PREFIX}/+/event")
        logger.info(f"MQTT connected to {self.host}:{self.port}; subscribed.")

    def _on_disconnect(self, client, userdata, flags, reason_code, properties) -> None:
        self._connected = False
        logger.warning(f"MQTT disconnected: {reason_code}")

    def _on_message(self, client, userdata, msg: mqtt.MQTTMessage) -> None:
        parts = msg.topic.split("/")
        if len(parts) != 4 or parts[0] != "dyrpa" or parts[1] != "devices":
            return
        device_id, kind = parts[2], parts[3]
        try:
            payload = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            logger.warning(f"bad payload on {msg.topic}: {msg.payload[:100]!r}")
            return

        try:
            if kind == "heartbeat":
                self._handle_heartbeat(device_id, payload)
            elif kind == "event":
                self._handle_event(device_id, payload)
        except Exception:
            logger.exception(f"error handling {msg.topic}")

    def _handle_heartbeat(self, device_id: str, payload: dict) -> None:
        with SessionLocal() as session:
            dev = session.get(Device, device_id)
            if dev is None:
                dev = Device(id=device_id, name=payload.get("name") or device_id)
                session.add(dev)
            dev.last_seen = time.time()
            for k_in, k_db in (
                ("name", "name"),
                ("manufacturer", "manufacturer"),
                ("model", "model"),
                ("android", "android_version"),
                ("ip", "ip"),
            ):
                v = payload.get(k_in)
                if v:
                    setattr(dev, k_db, str(v)[:64])
            try:
                dev.battery = int(payload.get("battery") or dev.battery)
            except (TypeError, ValueError):
                pass
            session.commit()

    def _handle_event(self, device_id: str, payload: dict) -> None:
        evt = payload.get("type") or "unknown"
        data = payload.get("data") or {}
        task_id = payload.get("task_id") or ""

        if evt == "log":
            level = data.get("level") or "info"
            msg = data.get("msg") or ""
            bus.append(device_id, level, msg)
            return

        # task lifecycle events also produce a log line for visibility
        snippet = json.dumps(data, ensure_ascii=False)[:200]
        bus.append(device_id, "info", f"[{evt}] {snippet}")

        if task_id and evt in {
            "task_started", "step_complete", "step_progress",
            "candidate_complete", "task_done", "task_failed",
        }:
            self._update_task(device_id, task_id, evt, data)

    def _update_task(self, device_id: str, task_id: str, evt: str, data: dict) -> None:
        with SessionLocal() as session:
            task = session.get(Task, task_id)
            if task is None:
                return
            # Any device-originated event keeps the stale watchdog at bay.
            # Even step_progress (5s in-flight heartbeat) and step_complete
            # (no progress side-effect) count as proof of life.
            if evt in {"task_started", "step_complete", "step_progress",
                       "candidate_complete", "task_done", "task_failed"}:
                task.last_event_at = time.time()
            if evt == "task_started":
                task.status = "running"
                task.started_at = time.time()
                # APK now sends total=max_items here (candidate-based progress).
                # step_complete still fires for every step but only updates logs, not progress.
                task.progress_total = int(data.get("total") or 0)
            elif evt == "step_complete":
                # Logs only — progress is driven by candidate_complete below.
                pass
            elif evt == "step_progress":
                # In-flight step heartbeat — pure observability, no state change
                # beyond the last_event_at refresh above. Drops through to commit.
                pass
            elif evt == "candidate_complete":
                task.progress_cur = int(data.get("completed") or task.progress_cur)
                if data.get("total"):
                    task.progress_total = int(data["total"])
            elif evt == "task_done":
                task.status = "done"
                task.ended_at = time.time()
                task.result = data
                dev = session.get(Device, device_id)
                if dev and dev.current_task_id == task_id:
                    dev.current_task_id = ""
            elif evt == "task_failed":
                task.status = "failed"
                task.ended_at = time.time()
                task.error = str(data.get("error") or "")[:1000]
                dev = session.get(Device, device_id)
                if dev and dev.current_task_id == task_id:
                    dev.current_task_id = ""
            session.commit()

    # ---------- publishing ----------

    def push_task(self, device_id: str, payload: dict) -> None:
        topic = f"{_PREFIX}/{device_id}/task"
        self.client.publish(topic, json.dumps(payload, ensure_ascii=False), qos=1)

    def push_stop(self, device_id: str) -> None:
        topic = f"{_PREFIX}/{device_id}/control"
        self.client.publish(topic, json.dumps({"cmd": "stop"}), qos=1)


router = MqttRouter()
