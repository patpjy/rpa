"""
Phase 0.5 心跳接收服务器 — 单文件 stdlib HTTP,无依赖。

用法:
    python3 heartbeat_server.py [host] [port]

默认监听 0.0.0.0:8080。
每次 POST /heartbeat 都追加一行 JSON 到 hb.log。

24h 后跑:
    python3 heartbeat_report.py hb.log
看丢失率。
"""

from __future__ import annotations

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG_FILE = os.environ.get("HB_LOG", "hb.log")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length > 0 else b""
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except json.JSONDecodeError:
            payload = {"_raw": raw.decode("utf-8", errors="replace")}

        record = {
            "server_ts": time.time(),
            "client_ip": self.client_address[0],
            "path": self.path,
            "payload": payload,
        }
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def do_GET(self) -> None:
        # 简单的存活探活,便于 curl 测试
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"heartbeat server alive\n")

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write(f"[{time.strftime('%H:%M:%S')}] {self.client_address[0]} {fmt % args}\n")


def main() -> None:
    host = sys.argv[1] if len(sys.argv) > 1 else "0.0.0.0"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"心跳服务器监听 {host}:{port},日志 → {LOG_FILE}", flush=True)
    print(f"探活:curl http://{host}:{port}/", flush=True)
    print(f"POST: curl -XPOST http://{host}:{port}/heartbeat -d '{{\"device_id\":\"test\",\"ts\":1}}'", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止", flush=True)


if __name__ == "__main__":
    main()
