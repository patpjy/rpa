"""ORM models for dyrpa server."""
from __future__ import annotations

import time
import uuid

from sqlalchemy import ForeignKey, Integer, JSON, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


def _now() -> float:
    return time.time()


def _uuid() -> str:
    return uuid.uuid4().hex[:12]


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(128), default="")

    # heartbeat-derived
    first_seen: Mapped[float] = mapped_column(default=_now)
    last_seen: Mapped[float] = mapped_column(default=0.0)
    manufacturer: Mapped[str] = mapped_column(String(64), default="")
    model: Mapped[str] = mapped_column(String(64), default="")
    android_version: Mapped[str] = mapped_column(String(16), default="")
    battery: Mapped[int] = mapped_column(default=0)
    ip: Mapped[str] = mapped_column(String(64), default="")

    # task state
    current_task_id: Mapped[str] = mapped_column(String(32), default="")
    last_task_kind: Mapped[str] = mapped_column(String(32), default="idle")

    # per-device overrides — replaces V9.1 localStorage with server persistence
    override_workflow_id: Mapped[str] = mapped_column(String(64), default="")
    override_keyword: Mapped[str] = mapped_column(String(128), default="")
    override_max_items: Mapped[int] = mapped_column(default=0)
    override_dm_template: Mapped[str] = mapped_column(Text, default="")

    last_screenshot_path: Mapped[str] = mapped_column(String(256), default="")


class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(String(64), ForeignKey("devices.id"), index=True)
    workflow_id: Mapped[str] = mapped_column(String(64))
    kind: Mapped[str] = mapped_column(String(16), default="workflow")  # workflow | inspect
    overrides: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(16), default="pending")  # pending|running|done|failed|stopped
    issued_at: Mapped[float] = mapped_column(default=_now)
    started_at: Mapped[float] = mapped_column(default=0.0)
    ended_at: Mapped[float] = mapped_column(default=0.0)
    progress_cur: Mapped[int] = mapped_column(default=0)
    progress_total: Mapped[int] = mapped_column(default=0)
    error: Mapped[str] = mapped_column(Text, default="")
    result: Mapped[dict] = mapped_column(JSON, default=dict)
    # Refreshed on every task lifecycle event from the device (task_started /
    # step_complete / step_progress / candidate_complete). Stale watchdog checks
    # `now - last_event_at` to detect tasks where the device went silent.
    last_event_at: Mapped[float] = mapped_column(default=0.0)


class LogEntry(Base):
    __tablename__ = "logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    task_id: Mapped[str] = mapped_column(String(32), default="")
    ts: Mapped[float] = mapped_column(default=_now, index=True)
    level: Mapped[str] = mapped_column(String(16), default="info")
    message: Mapped[str] = mapped_column(Text)
