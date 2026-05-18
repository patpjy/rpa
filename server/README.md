# dyrpa server — multi-device 抖音 RPA 控制台

Phase 1 skeleton(参见 `~/.claude/plans/plan-virtual-brooks.md`)。

## 启动(本地开发)

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 启动 MQTT broker(另开终端)
docker run -d --name dyrpa-mqtt -p 1883:1883 -v $(pwd)/mosquitto:/mosquitto/config eclipse-mosquitto
# 不想用 docker 的话:brew install mosquitto && mosquitto -c mosquitto/mosquitto.conf

# 启动 server
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- 顶层(设备列表): http://localhost:8000
- 单设备(控制面板): http://localhost:8000/devices/{id}
- API 健康检查: http://localhost:8000/api/health

## 部署到公网 VPS(用户给地址后做)

1. 上传 server/ 整个目录到 VPS
2. 配置 EMQX(比 Mosquitto 更适合 30+ 并发设备)+ TLS
3. systemd 单元跑 uvicorn(`--workers 1`,因为 SQLite 单写)
4. Nginx 反代 :8000 → :443,SSE 路径关闭 buffering
5. 数据库后期改 PostgreSQL

## 测试(没有真机时也能玩)

在终端模拟一台设备发心跳:

```bash
# 心跳 (一次性)
mosquitto_pub -h localhost -p 1883 -t 'dyrpa/devices/test01/heartbeat' \
  -m '{"name":"测试机01","manufacturer":"HONOR","model":"ANY-NX9","android":"13","battery":87,"ip":"10.0.0.1"}'

# 然后刷新 http://localhost:8000 会看到 test01 卡片出现
```

打开 http://localhost:8000/devices/test01 进单设备控制台,改话术,服务器持久化下来。

## 当前 Phase 1 状态

- ✅ FastAPI app + SQLAlchemy + SQLite
- ✅ MQTT 接入(Paho client,topic 路由)
- ✅ 设备注册 / 列表 / 详情 / per-device overrides 持久化
- ✅ 任务下发 + stop 命令
- ✅ 截图上传 / 拉取
- ✅ SSE per-device 日志流
- ✅ workflow YAML 加载 + override 合并(复用 V9.1 schema)
- ✅ 两级 Web 控制台(顶层 grid + 单设备 mirror V9.1)
- ⏳ EMQX 替代 Mosquitto(下一步,等真机数量上来)
- ⏳ 失败现场捕获/上传 (Phase 4)
- ⏳ 批量任务下发 UI (Phase 4)

## 与 V9.1 dashboard.py 的对应

| V9.1 | 新 server |
|---|---|
| `GET /` (Flask `index.html`) | `GET /devices/{id}` (Jinja `device_detail.html`) |
| `GET /api/device` | `GET /api/devices/{id}` |
| `GET /api/state` | `GET /api/devices/{id}/state` |
| `GET /api/logs/stream` (SSE) | `GET /api/devices/{id}/logs/stream` |
| `GET /api/workflows` | `GET /api/workflows`(workflow 全局共享) |
| `POST /api/run/inspect` | `POST /api/devices/{id}/run/inspect` |
| `POST /api/run/workflow` | `POST /api/devices/{id}/run/workflow` |
| `POST /api/run/stop` | `POST /api/devices/{id}/run/stop` |
| `GET /api/screenshot/latest` | `GET /api/devices/{id}/screenshots/latest` |
| localStorage 持久化参数 | `POST /api/devices/{id}/overrides`(服务器持久化) |
| `STATE` 全局 dict | `Device` 行 + `Task` 行(per-device) |
| Python in-process `run_workflow` | MQTT publish 给手机端 APK 执行 |

## MQTT 协议

参见 [../phase0-demo/README.md](../phase0-demo/README.md) 之后补到这里。简版:

**Device → Server:**
- `dyrpa/devices/{id}/heartbeat` — `{name, manufacturer, model, android, battery, ip}`
- `dyrpa/devices/{id}/event` — `{type, task_id, ts, data}` 其中 type ∈ {log, task_started, step_complete, task_done, task_failed}

**Server → Device:**
- `dyrpa/devices/{id}/task` — `{task_id, kind, workflow_id, workflow_yaml, overrides, issued_at}`
- `dyrpa/devices/{id}/control` — `{cmd: "stop"}`
