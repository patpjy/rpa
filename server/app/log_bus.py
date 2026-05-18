"""Per-device log buffer + async SSE fan-out.

In-memory ring per device for the SSE replay, plus subscriber queues for live
push. Persistent storage of logs is separate (see models.LogEntry).
"""
from __future__ import annotations

import asyncio
import time
from collections import deque
from typing import AsyncIterator


class LogBus:
    def __init__(self, max_per_device: int = 500) -> None:
        self._buffers: dict[str, deque[tuple[float, str, str]]] = {}
        self._subscribers: dict[str, set[asyncio.Queue]] = {}
        self._max = max_per_device

    def append(self, device_id: str, level: str, msg: str) -> None:
        ts = time.time()
        buf = self._buffers.setdefault(device_id, deque(maxlen=self._max))
        buf.append((ts, level, msg))
        for q in self._subscribers.get(device_id, ()):
            try:
                q.put_nowait((ts, level, msg))
            except asyncio.QueueFull:
                pass

    def snapshot(self, device_id: str) -> list[tuple[float, str, str]]:
        return list(self._buffers.get(device_id, ()))

    async def stream(self, device_id: str) -> AsyncIterator[tuple[float, str, str]]:
        # replay backlog
        for entry in self.snapshot(device_id):
            yield entry
        # live tail
        queue: asyncio.Queue = asyncio.Queue(maxsize=200)
        subs = self._subscribers.setdefault(device_id, set())
        subs.add(queue)
        try:
            while True:
                entry = await queue.get()
                yield entry
        finally:
            subs.discard(queue)


bus = LogBus()
