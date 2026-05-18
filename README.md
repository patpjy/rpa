# 抖音 RPA — 单机 V9.1 + 群控架构

抖音矩阵号自动化(搜词 → 进视频 → 关注 → 发私信)的两套互补架构,**共用同一份 YAML 工作流**:

* **V9.1 单机**:Mac + USB + ADB + uiautomator2,3-5 台手机,生产可用,源码 818 行
* **群控**:VPS + Android APK(Kotlin)+ Shizuku + MQTT,目标 10-30 台,**装机后无 USB / 无 WiFi,纯蜂窝独立运行**

YAML 不动,改的是执行器位置(PC ↔ 手机自身)。

---

## 当前状态(2026-05-18)

| 阶段 | 状态 | 备注 |
|---|---|---|
| **V9.1 单机** | 🟢 生产 | 1 PC × N 手机(USB 常驻),`rpa_mvp.py` + `dashboard.py` |
| **Phase 1** 公网服务器 | 🟢 已部署 | 腾讯云 VPS 81.69.43.246,FastAPI + Mosquitto + systemd |
| **Phase 2** APK + 17 action | 🟢 完成 | Kotlin,17/17 actions(`tap_image` / `image_exists` 为 stub,等 OpenCV)|
| **Phase 3** 无 USB 持久化 | 🟢 实证 | 2026-05-15 honor50-01 拔 USB + 关 WiFi 后 Shizuku 持续运行,workflow 远程可控 |
| **Phase 4** 工作流真机调优 | 🟢 实证 | 2026-05-18 candidate 3/3 跑通 WiFi + SIM 卡纯蜂窝双场景;MQTT QoS 分级 + `maxInflight=100` 治 SIM 弱网卡死,ShellExecutor watchdog 替换 buggy `waitFor(timeout)`,step_progress 心跳 + 5min stale watchdog 全套韧性上线;**Stop/Start 三层并修**:`Thread.interrupt()` + `ShellExecutor.killCurrent()` 让停止 1-2s 真停(原 15-60s),SharedPreferences 兜底让 service sticky 重启自愈,Paho callback thread 解耦修 subscribe stalled 死锁(详见 `BUGS.md`)|
| **Phase 5** 多机并发 + 风控 + TLS | ⚪ 未启 | 5-10 台并发实测、broker TLS+ACL、包名/痕迹隐藏 |

整体计划草案: `~/.claude/plans/plan-virtual-brooks.md`

---

## 架构对照

```
V9.1 单机                                 群控架构
─────────────                             ─────────────
[Mac]                                     [VPS 81.69.43.246]
 ├─ Flask :8003 (dashboard.py)             ├─ FastAPI :8000 (server/app/main.py)
 ├─ rpa_mvp.py 调度                        ├─ Mosquitto :1883 (MQTT broker)
 └─ uiautomator2 + adb shell               └─ SQLite + UI dumps + 截图存档
       │                                          │
       │ USB ADB(必须常驻)                       │ MQTT/HTTP 出向长连接 → 蜂窝
       ↓                                          ↓
   [Android 手机]                           [Android 手机 × N(各自蜂窝独立)]
                                             ├─ DyrpaAgent APK(前台 Service)
                                             ├─ DyrpaIME(自研 IME,中文 broadcast)
                                             └─ Shizuku(Rikka 原版,shell UID 守护)
                                                   │
                                                   │ Shizuku.newProcess() 本机 binder IPC
                                                   ↓
                                             [shell] input / uiautomator dump / screencap
                                                   ↓
                                             [抖音]
```

**为啥群控这么设计:**

| 约束 | 缓解 |
|---|---|
| 非 root APK 不能跨 App 模拟点击 | Shizuku 提供 shell UID,所有 action 仍是 shell 命令(`input tap` 等),与 V9.1 同源 |
| `AccessibilityService` 易被风控识别 | **完全不用无障碍**,Shizuku 走 shell UID + binder |
| Android 10+ `input text` 多字节 UTF-8 直接 NPE(framework bug) | 内嵌 **DyrpaIME**(InputMethodService),响应 `ADB_INPUT_TEXT` broadcast 提交文字 |
| 蜂窝 NAT 阻断入向 | APK 主动出向连 broker,服务器 publish 到 device topic |
| dyrpa-agent 被 OEM 杀后台 | 前台 Service + 启动管理白名单 + 锁屏卡片锁定(`scripts/keepalive_setup.sh`)|
| **Shizuku 不能跨重启持久化(Android 10 无 root)** | 接受重启后需 USB 重新 bootstrap;运行期(不重启)Shizuku 不死,见下节 |
| Android 9+ 默认禁明文 HTTP | `network_security_config.xml` 仅白名单 VPS IP(Phase 5 上 TLS 后移除) |

---

## Shizuku 持久化关键(2026-05-15 实证 — 第二轮修正)

**核心结论**:Android 10 + 无 root + HONOR/MagicOS,**启动 shizuku 的时机必须晚于 USB 物理拔除**。USB 在的时候启的 shizuku 拔线必死;USB 拔完让 adbd 进入"无 USB transport"稳态再启的 shizuku 才能持续运行。

```
✗ 错误顺序: USB 连着 → adb tcpip → 连 TCP → 启 shizuku → 拔 USB
  →  拔 USB 触发 adbd 整个进程重启(PID 直接换)
  →  init/HwUsbDeviceManager 钩子清理旧 adbd 名下的所有 shell UID 子进程
  →  即使 shizuku 已 setsid+nohup+PPID=1+tty=0,daemon 化做得再彻底也被清掉
  →  ps 列表里直接消失,无 logcat / dmesg 记录(SIGKILL 不可拦截)

✓ 正确顺序: USB 连着 → adb tcpip → 连 TCP → 先拔 USB → 启 shizuku
  →  拔 USB 时 adbd 重启,但此时还没 shizuku,清理钩子无对象可清
  →  重启后的新 adbd 处在"只有 TCP transport,USB transport 从未在本进程激活"稳态
  →  在这个稳态下启的 shizuku,后续没有 USB 事件可触发清理钩子
  →  Shizuku 持续运行直到手机重启 / 关机
```

**关键观察**:
- HONOR 上 USB 物理拔插 = adbd **整个进程换 PID**,不是只关 USB transport(logcat 实证:`adbd 16859 → 17098`)
- 标准 Unix daemon 化(double-fork、setsid、nohup)在这条 OEM 清理链面前都救不了
- 之前的"USB-adbd vs TCP-adbd 启动"理解不全 —— 真正决定生死的是 **shizuku 出生时刻 adbd 是否还有 USB transport 状态**

**已排除的伪根因**:
- 不是 Huawei HiDecision 杀 — 死亡时刻它只是处理 `FileShareWithUSB` 事件的无辜路人
- 不是 MagicOS 启动管理 / 电池白名单 — 这些管 app UID,管不到 shell UID daemon
- 不是 Shizuku 本身没 daemonize — `ps -A` 显示 PPID=1 已脱钩,/proc/PID/stat 显示 sid=pid tty=0
- 不是 OOM / LMK — dmesg / events buffer 都没记录

**2026-05-15 实证**(写进 [[project_shizuku_state]]):
- USB 在的时候启 shizuku,拔 USB 100% 必死(2 次复现,第二次还加了 nohup setsid 也救不回来)
- 先拔 USB 让 adbd 进 TCP-only 稳态后再启 shizuku → 多次拔插 USB / 关 WiFi / 切蜂窝都不死

**⚠ 2026-05-18 补丁(再次修正)**:决定 shizuku 死活的 trigger 是 **USB transport 状态变化**,**不是** `pm install` 本身。证据:
- **USB 在 + `adb install`** → 杀 shizuku(USB transport 活跃 → adbd 状态变化 → 清理钩子触发)
- **USB 拔 + TCP `adb -s <ip>:5555 install`** → ✅ shizuku 不动(2026-05-18 honor50-02 实证,TCP-only install 后 shizuku_server PID 不变)

所以最佳实践:**蜂窝独立后所有 APK 更新都走 TCP `adb -s <wifi_ip>:5555 install`,不再插 USB,shizuku 永不死**。只有手机重启 / 关机后第一次才需要 USB bootstrap(因为重启后 `adb tcpip 5555` 属性丢失)。

---

## 单机手机 bootstrap SOP

**装机一次,USB 5 分钟搞定,之后蜂窝独立。顺序很关键 — shizuku 必须在 USB 拔完之后才启,见上节说明。**

```bash
# === 第 1 段:USB 在的时候(安装 + 配 TCP 通道)===

# 1. 装 APK
adb install apk/app/build/outputs/apk/debug/app-debug.apk            # dyrpa-agent
adb install shizuku-v13.6.x.apk                                       # Rikka 原版 Shizuku

# 2. 一键保活(脚本自动:加电池白名单 + 后台运行 + 跳 OEM 启动管理页让你勾)
./scripts/keepalive_setup.sh

# 3. 切 adbd 到 TCP 模式 + 建 TCP 通道
adb tcpip 5555
adb connect <phone_wifi_ip>:5555            # Mac 跟手机得在同一 WiFi 子网

# === 第 2 段:★ 物理拔 USB ★ ===
#
# 拔了之后:
# - adbd 进程会重启(PID 换);此时还没 shizuku,清理钩子无目标
# - 新 adbd 处在 TCP-only 稳态(USB transport 从未在它的本次生命周期激活)
# - Mac 端 `adb devices` 还能通过 WiFi 看到 <wifi_ip>:5555 仍 device 状态

# === 第 3 段:在 USB 已拔的状态下启 shizuku(关键)===

# 4. 通过 TCP 启 shizuku — 这是它"出生"时刻,adbd 已经没 USB 状态可清理
adb -s <phone_wifi_ip>:5555 shell /data/app/moe.shizuku.privileged.api-*/lib/arm64/libshizuku.so

# 5. 验证活: 进程 PPID=1 + Shizuku App 主界面显示"运行中,版本 13.x"
adb -s <phone_wifi_ip>:5555 shell ps -A -o PID,PPID,USER,ARGS | grep shizuku_server

# 6. 手机端 UI:
#   a. dyrpa-agent → 请求 Shizuku 权限 → 同意 → 配 broker tcp://81.69.43.246:1883 + device_id → 启动
#   b. 设置 → 应用 → 启动管理 → dyrpa agent → 关自动管理 + 勾 [自启动][关联启动][后台活动]
#   c. 最近任务卡片长按上滑锁定

# 7. 关 WiFi + 插流量卡 + 开蜂窝数据 — Mac 失去 adb 链路,但生产期 Mac 不在链路里
#    Shizuku 是手机本地 daemon,网络切换不影响它
#    dyrpa-agent 走 MQTT isAutomaticReconnect,蜂窝起来自动重连 broker
```

**关键反面教材**:`adb tcpip 5555` 之后**直接**通过 TCP 启 shizuku,然后再拔 USB —— 这条路 HONOR 上必死,加任何 daemon 化技巧都救不回来。原因见上节"Shizuku 持久化关键"。

**这之后的所有维护都不需要再连 USB**,除非:
- 手机重启 — `service.adb.tcp.port=5555` 是运行时属性,重启丢失。要从步骤 3 重跑(~2 分钟)
- 手机断电关机 — 同上

实测预估每月级别需要一次维护,可接受。批量扩 10+ 台时把上面打包成 `scripts/bootstrap_phone.sh`。

---

## 项目结构

```
rpa/
├── README.md                       本文件
├── ARCHITECTURE.md                 跨架构链路拓扑(Mac ↔ broker ↔ 手机 / Shizuku ↔ shell)
├── BUGS.md                         踩过的 bug + 根因 + 修复(2026-05-18 SIM 弱网 etc.)
├── RECONNECT_SOP.md                shizuku_server 死了 30s 救活 runbook
├── requirements.txt                V9.1 Python 依赖
├── rpa_mvp.py                      V9.1 引擎(818 行,uiautomator2 + ADB)
├── dashboard.py                    V9.1 控制台(Flask :8003)
├── templates/index.html            V9.1 控制台模板
├── static/dashboard.{css,js}       V9.1 控制台前端
├── workflows/                      工作流 YAML(跨架构共用)
│   ├── _template.yaml              新工作流脚手架
│   ├── douyin-dm.yaml              抖音私信 v9.1(resource-id 抗漂移)
│   └── README.md                   编写指南
├── outputs/                        V9.1 运行产物(.gitignored)
├── scripts/                        bootstrap / 保活脚本
│   ├── keepalive_setup.sh          OEM 启动管理 + 电池白名单一键(dyrpa-agent)
│   └── KEEPALIVE_README.md         各 OEM 白名单详表
├── phase0-demo/                    早期 Phase 0 验证产物
│
├── server/                         Phase 1 公网服务器(FastAPI)
│   ├── app/main.py                 路由 + ORM + MQTT
│   ├── app/mqtt_router.py          broker connect + topic push/sub
│   ├── app/models.py               Device / Task / Run / Step SQLAlchemy
│   ├── app/workflow_loader.py      yaml 读取 + override 合并
│   ├── templates/                  Jinja2 (顶层网格 + 单设备 mirror V9.1)
│   ├── static/                     dashboard css/js
│   ├── mosquitto/mosquitto.conf    本地 broker 配置示例
│   └── data/{screenshots,ui_dumps} 设备上传的失败现场(.gitignored)
│
└── apk/                            Phase 2 设备端 Kotlin 工程
    ├── build.gradle.kts            + settings.gradle.kts + gradle.properties
    └── app/src/main/
        ├── AndroidManifest.xml     权限 + IME 注册 + cleartext 白名单
        ├── res/xml/                method.xml (IME 元数据) + network_security_config.xml
        └── java/com/dyrpa/agent/
            ├── MainActivity.kt              broker + device_id 配置 UI
            ├── service/AgentService.kt      前台 Service + 心跳 + MQTT 派发
            ├── mqtt/MqttClient.kt           Paho 客户端
            ├── shizuku/ShellExecutor.kt     反射调 Shizuku.newProcess + 并行排空 stdout/stderr
            ├── input/DyrpaIME.kt            自研 IME,接 ADB_INPUT_TEXT broadcast
            ├── workflow/{WorkflowModel,Interpreter}.kt  yaml → 步骤执行 + forensics
            ├── actions/                     17 个 action 实现(2 stub)+ ActionRegistry
            └── util/{DeviceInfo,ScreenSize,UiTreeFinder,ServerUploader}.kt
```

---

## V9.1 单机 quick start

```bash
brew install android-platform-tools
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 手机进开发者选项(版本号点 7 次)→ 开 USB 调试 + USB 安装
adb devices                            # 验证识别
.venv/bin/python dashboard.py          # http://127.0.0.1:8003
```

各品牌开发者选项入口见[文末附录](#手机端开发者选项各品牌路径)。完整 yaml 编写 + failed dump 调试见 `workflows/README.md`。

---

## 群控架构 quick start

### A. 本地 server 开发

```bash
cd server
/Users/pat/Desktop/rpa/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
brew install mosquitto && mosquitto -c mosquitto/mosquitto.conf   # 跑真机时需要
```

* 顶层网格:`http://localhost:8000/`
* 单设备:`http://localhost:8000/devices/<device_id>`

### B. 部署 / 同步到 VPS

服务器位置 `/home/ubuntu/dyrpa/{server,workflows,data,.venv}`,systemd 单元 `dyrpa-server.service`,Mosquitto `/etc/mosquitto/conf.d/dyrpa.conf` 听 `0.0.0.0:1883`。

本地 `~/.ssh/config`(必须 `BindInterface=en0` 绕 V2RayN TUN):

```
Host dyrpa
    HostName 81.69.43.246
    User ubuntu
    IdentityFile ~/.ssh/exam_generator_tencent_ed25519
    BindInterface en0
    ServerAliveInterval 60
```

修后端代码:

```bash
rsync -avz server/app/ dyrpa:/home/ubuntu/dyrpa/server/app/
ssh dyrpa "sudo systemctl restart dyrpa-server"
```

修 yaml / static:rsync 即可不用重启(yaml dispatch 时加载,static uvicorn 直接读盘)。

### C. 手机端 bootstrap

见上文 [单机手机 bootstrap SOP](#单机手机-bootstrap-sop)。

### D. dashboard 访问 — V2RayN 全局 TUN 加速

V2RayN TUN 强制全局时直连 VPS 会绕海外节点变慢。开 SSH 隧道走 `BindInterface=en0`:

```bash
ssh -fN -L 8888:127.0.0.1:8000 dyrpa
# 浏览器 http://localhost:8888/devices/<id>
```

杀掉 `pkill -f 'ssh.*8888'`。

---

## V9.1 → 群控 概念映射

| V9.1(Python 单进程) | 群控(server + APK) |
|---|---|
| 全局 `STATE` dict | `Device` 表 per-device 状态 + `Task` per-run |
| localStorage 持久化输入框 | `POST /api/devices/{id}/overrides` → SQLite |
| `LOG_BUFFER` 环形 deque | `log_bus.py` per-device 缓冲 + async SSE |
| `STATE.last_screenshot` | `Device.last_screenshot_path` + `server/data/screenshots/<id>/` |
| 同步 `run_workflow(yaml)` | `mqtt_router.push_task(device_id, payload)` 异步 |
| `execute_steps(steps)` (Python) | `workflow/Interpreter.kt`(Kotlin,设备端) |
| 17 个 action 函数 | `actions/<Name>.kt` + `ActionRegistry` |
| `u2.click_text("X")` | `uiautomator dump` → 正则解析 → `input tap X Y` |
| `device.input_text()`(Unicode OK) | **DyrpaIME** 接 broadcast → `commitText` |
| `outputs/run_*/` 本地落盘 | HTTP POST `/api/devices/{id}/screenshots` + `/ui-dumps` |
| 单设备 1 task | N 设备并发,每设备独立 task slot |

---

## 17 个 Action 实现矩阵

| Action | V9.1 | APK | 备注 |
|---|---|---|---|
| `wait` / `press` / `tap_xy` / `swipe` | ✓ | ✓ | 纯 shell,无 UI tree 依赖 |
| `tap_text` / `tap_desc` / `tap_resource_id` | ✓ | ✓ | `uiautomator dump` → 正则查 `text=` / `content-desc=` / `resource-id=`,**默认 timeout 8s**(适配 shell dump 慢) |
| `input_text` | ✓ | ✓ | APK 自动切到 DyrpaIME → broadcast → 切回原 IME |
| `screenshot` / `dump_hierarchy` / `collect_visible_text` | ✓ | ✓ | 截图/UI XML 失败时自动 HTTP 上传到 forensics |
| `tap_xy_if_missing` / `tap_relative_to_element` | ✓ | ✓ | 组合 dump + tap |
| `skip_if_exists` / `skip_if_not_exists` | ✓ | ✓ | 短路控制,与 `optional:true` 配合 |
| `tap_image` / `image_exists` | ✓ | **stub** | APK 端 OpenCV +40MB,Phase 5 abiSplit 或改服务器侧匹配 |

---

## 关键实现细节

### Forensics 自动上传(bug 定位机制)

任何 action 失败(不分 optional)→ `Interpreter` catch:
1. 标签 `${section}_step${i}_${action}`
2. `ShellExecutor.uiDump()` 抓当前 UI XML,POST `/api/devices/{id}/ui-dumps`
3. 非 optional 再走 `screencapAndUpload` 传 PNG
4. `task_failed` 载荷含 `section / step_index / step_type / forensics_label`,dashboard 直跳关联截图 + UI XML

→ 抖音 UI 漂移时 1 次 run 即拿证据,不靠蒙猜。

### 通信韧性(MQTT,2026-05-18 治 SIM 弱网卡死)

```kotlin
// MqttClient.kt
maxInflight = 100               // Paho 默认 10,SIM 卡 RTT 200-800ms 时 inflight 必爆
private val CRITICAL_EVENT_TYPES = setOf(
    "task_started", "task_done", "task_failed", "candidate_complete",
)
val critical = type in CRITICAL_EVENT_TYPES
val qos = if (critical) 1 else 0   // 高频事件 (log/step_*) 走 QoS 0,fire-and-forget,不占 inflight 槽
```

**没分级前症状**:WiFi 跑 3/3 通,SIM 卡纯蜂窝下 workflow 静默卡 100+ 秒,无报错无心跳,5min 后 server 端 stale watchdog 才标 failed。完整根因分析见 `BUGS.md` 第一条。

**配套韧性栈**(从上至下:server → APK 主流 → 子进程):
- **`server/app/main.py`**:5min `stale_task_watchdog` 60s 轮询扫 zombie task,清 `Device.current_task_id` + bus 写 warn
- **`server/app/mqtt_router.py`**:任何手机端 lifecycle event(含 `step_progress`)刷新 `task.last_event_at`,watchdog 不误杀
- **`apk/.../workflow/Interpreter.kt`**:每个 step 启 daemon Thread 每 5s 发 `step_progress` 心跳,卡 step 时 dashboard 看得到 elapsed_ms
- **`apk/.../mqtt/MqttClient.kt`**:`pendingQueue` 上限 100,`MqttCallbackExtended.connectComplete(reconnect=true)` 时 drain 重发,弱网断点不丢 critical 事件
- **`apk/.../shizuku/ShellExecutor.kt`**:waiter Thread + `join(timeoutMs+3s)` 替换 Shizuku Process 子类下 buggy 的 `proc.waitFor(timeout, TimeUnit)`(那个会假性 return true 然后 `exitValue()` 抛 `IllegalThreadStateException("process hasn't exited")`)

### Stop / Start 三层强制(2026-05-18)

旧版本 stop 只设 `stopRequested` 标志,workflow 主线程卡在 ShellExecutor 的 `waiter.join(15s)` / UiTreeFinder 的 polling / forensics 上传里完全不响应,实测延迟 15-60 秒才停。修后 1-2 秒真停。

```
点停止 / 抢占启动 → 三发齐打:

  ┌──────────────────────────────────────────┐
  │ 1. interpreter.stopRequested = true       │  协作式(原)— Interpreter 主循环间隙看
  │ 2. workflowThread.interrupt()             │  唤醒 Thread.sleep / Process.waitFor
  │ 3. ShellExecutor.killCurrent()            │  SIGTERM in-flight shell → 800ms 后 SIGKILL
  └──────────────────────────────────────────┘
                    │
                    ▼
  Interpreter.runSection / runWorkflow catch InterruptedException
    → Thread.interrupted() 清 flag(避免 Paho lockInterruptibly 卡)
    → publish task_failed("stopped")
    → SKIP forensics / SKIP recover(那些都是 shell 调用,在 stop 路径上跑只拖延)
```

**Paho callback 线程必须神圣**:`AgentService.onTask` 和 `onControl` 全部内容移到 dispatcher / daemon 线程,callback 线程只做无 IO 的标志位 + interrupt + killCurrent。**不准从 callback 线程 publish 或 join** — Paho 3.x 在 callback re-entrancy 下会进入 "publish 还活、subscribe 流水线死" 的不可恢复状态(详见 `BUGS.md` 2026-05-18 三层级联条)。

**Service sticky 重启自愈**:`AgentService.onStartCommand` 读 SharedPreferences `"dyrpa"` 的 `broker` + `device_id` 兜底,OS 杀掉 service 后 START_STICKY 重启拿到 null intent 时不再僵尸,自己重建 MqttClient。

**Start 抢占语义**:`onTask` 看到 `activeTaskId != null` 不再 reject,改成 preempt(stop 旧的 → `join(5s)` → 启新的)— 用户心智模型"点开始 = 重新开始"对齐;`device_detail.js` 配套点停止后 disable 启动按钮 6 秒。

### ShellExecutor 并发排空 stdout/stderr(pipe 死锁修复)

```kotlin
// ❌ 原写法(Java Runtime.exec 经典死锁)
val exit = proc.waitFor()          // 阻塞等
val stdout = proc.inputStream.bufferedReader().readText()   // 死锁:stdout 写 >64KB,子进程 write 阻塞,waitFor 永远不返回

// ✓ 修后(2026-05-15)
val tOut = Thread { stdout = proc.inputStream.bufferedReader().readText() }
val tErr = Thread { stderr = proc.errorStream.bufferedReader().readText() }
tOut.start(); tErr.start()
val exit = proc.waitFor()
tOut.join(); tErr.join()
```

抖音视频详情页 UI XML ~200KB,远超 Linux pipe buffer 默认 64KB。修前 100% 死锁后被 watchdog SIGTERM(exit=143);修后干净通过。

V9.1 副本2 没这问题,因为它用 uiautomator2 的 instrumentation server 通过 socket 流式传 XML,不走 pipe buffer。

### tap_* 默认 timeout 3s → 8s

V9.1 用 uiautomator2 dump ~300ms,3s timeout 允许 7+ 次 retry。我们 APK 用 shell `uiautomator dump` 一次 ~2.5s,3s 只够 1 次 retry,抖音视频页元素晚渲染时必丢。8s 允许 2-3 次 retry,大多数 timing edge case 能 hit。

具体值在 `TapDesc/TapText/TapResourceId.kt:15` 默认参数,yaml 里 `timeout: <seconds>` 可覆盖。

### DyrpaIME(自研输入法 + 自动 IME 切换)

Android 10 内置 `input text "<中文>"` 直接 NPE(framework bug)。我们走 shell broadcast,要求当前 IME 是 DyrpaIME。

```kotlin
// InputText.kt
val saved = ShellExecutor.run("settings get secure default_input_method").stdout.trim()
if (saved != DYRPA_IME) {
    ShellExecutor.run("ime set $DYRPA_IME")
    Thread.sleep(500)          // 等 IME 绑定到焦点
}
ShellExecutor.inputText(text)  // am broadcast -a ADB_INPUT_TEXT --es msg "..."
ShellExecutor.run("ime set $saved")   // 切回原 IME,保证你日常能在手机上手动打字
```

DyrpaIME 自身就一个 `InputMethodService` + 一个 `BroadcastReceiver`,收到广播 `commitText(msg, 1)`,无可见键盘。

### 工作流 YAML(简版)

```yaml
meta: { name: "抖音私信", version: "9.1", description: "..." }
app:  { package: com.ss.android.ugc.aweme }
workflow:
  keyword: "..."                              # dashboard / per-device 可覆盖
  max_items: 3
  open_search:    [ {action: wait, seconds: 5}, {action: tap_xy, x: 0.92, y: 0.06}, ... ]
  submit_search:  [ {action: input_text, value_from: keyword}, ... ]
  per_item:       [ {action: tap_desc, value: "关注", optional: true}, ... ]   # 循环 max_items 次
drafts:
  dm_template: "您好...{keyword}"             # {keyword} 自动替换
```

`value_from` 支持 `keyword` / `dm_template`(后者做 `{keyword}` 替换),`optional:true` 失败不 kill workflow 只 warn。完整字段见 `workflows/README.md`。

---

## 故障排查(2026-05-15 实战版)

| 现象 | 真实根因 | 修法 |
|---|---|---|
| 拔 USB 后 Shizuku UI 显示 "未运行" | **shizuku 在 USB 还插着时启动的** — 拔 USB 触发 adbd 整体重启 + OEM 钩子清理 shell UID 子进程,setsid/nohup 都救不回 | 重做 bootstrap,**严格遵守顺序**:USB 在 → tcpip + connect → **先物理拔 USB** → 在 TCP-only 稳态下启 shizuku(见 bootstrap SOP) |
| `uiautomator dump failed: exit=143` | **Java Runtime.exec pipe 死锁**(stdout >64KB pipe buffer 满,子进程 write 阻塞,waitFor 永远等不到 exit,15s watchdog SIGTERM) | `ShellExecutor.run` 已上并行 stdout/stderr 排空线程 |
| candidate 关注偶尔失败 / element not found | UI 渲染晚 + tap_* 默认 3s timeout 仅 1 次 retry | 默认 timeout 提到 8s(2-3 次 retry) |
| `input_text` broadcast 静默失败 | 当前 IME 不是 DyrpaIME 时 broadcast 无人收 | `InputText.kt` 已加自动切 IME → broadcast → 切回 |
| `adb install` 返回 `User rejected permissions` | 华为应用市场 `captchakit.CaptchaActivity` 风控滑块 | 手动解一次;adb shell `pm disable-user com.huawei.appmarket` 不彻底(系统还会另起一份),实际可接受为装机一次性成本 |
| dashboard 显示 "离线" 但 server `online:true` | 前端心跳显示字段没及时刷新 | 强刷浏览器;后续改 `last_seen` 推送 |
| dashboard 加载极慢 | V2RayN TUN 全局把国内 IP 也代理出去 | 加直连规则,或 SSH 隧道 `localhost:8888` |
| 服务器 `/api/health` 偶发 `HTTP 000` | 旧 uvicorn 被 SIGTERM 卡 connection drain ~90s | `systemctl restart dyrpa-server` |
| 截图轮询拉 2MB PNG 卡 dashboard | 自动刷新 1.5s 一次堵带宽 | JS 节流 10s/次,手动走 "刷新" 按钮 |
| `Shizuku.newProcess` IllegalAccessException | 13.x SDK 标了私有 | 反射调用,见 `shizuku/ShellExecutor.kt` |
| SIM 卡纯蜂窝下 workflow 静默卡 100+ 秒,无报错无心跳 | Paho 默认 `maxInflight=10` + 全部消息 QoS 1,蜂窝 RTT 高 → PUBACK 慢 → inflight 满 → `client.publish()` 同步阻塞死锁 workflow 主线程 + step_progress daemon | `MqttClient.kt` 已抬 `maxInflight=100` + 高频事件降 QoS 0,详见 `BUGS.md` 第一条 |
| `tap_xy/input_text/uiautomator dump` 偶报 `IllegalThreadStateException("process hasn't exited")` | Shizuku 的 `Process` 子类 `waitFor(timeout, TimeUnit)` 继承自基类 polls `exitValue()`,binder IPC 下返回不一致 → 假性 return true 后 `exitValue()` 又说没退出 | `ShellExecutor.run` 改用 waiter Thread + `Thread.join(timeoutMs+3s)` 上限,不走 timed `waitFor` |
| 走 **USB** 装新 APK 后 shizuku_server 没了 | `adb install` 通过 USB transport 触发 adbd 状态变化,清理钩子杀 shell UID 子进程(纯 TCP install 没事,2026-05-18 honor50-02 实证)| 1) 优先选项:**蜂窝独立后所有装 APK 都走 TCP**:`adb -s <wifi_ip>:5555 install -r ...`,shizuku 不死。2) 若已走 USB 死了:按 `RECONNECT_SOP.md` 拔 USB → 重连 TCP → libshizuku.so 重起 |
| 点停止后 workflow 仍跑 15-60 秒才停,期间 dashboard 看到 step_progress 还在累计 | stop 信号只设了 `stopRequested` 标志,workflow 主线程卡在 ShellExecutor 的 `waiter.join(15s)` / UiTreeFinder 的 polling loop / forensics 上传里,这些路径都不响应 flag | `Interpreter` catch InterruptedException 跳过 forensics + recover;`AgentService.onControl` 加 `workflowThread.interrupt()` + `ShellExecutor.killCurrent()`;详见 `BUGS.md` 2026-05-18 三层级联条 |
| 点停止后点开始,server `task dispatched` 但 phone 端没动静,任务永远 pending | Service 被 OS 杀过(workflow 长卡触发 LMK),`START_STICKY` 重启拿到 null intent → `onStartCommand` 第一行 `?: return START_NOT_STICKY` 直接 bail → service 在跑但 `mqtt`/`interpreter` 都 null | `AgentService` 读 SharedPreferences `"dyrpa"` 的 broker/device_id 兜底重建 MqttClient |
| stop 后立刻 start 没反应,phone 还在发心跳但收不到 task / control(单向死) | Paho 3.x callback 线程 re-entrancy bug:`onControl` 从 callback 线程 publish + workflow 线程同时 publish task_failed,两个并发 publish 撞乱 Paho 内部 inflight 状态 → subscribe 流水线卡死(publish 还活) | `onTask` / `onControl` **全部**移到 dispatcher / daemon 线程,callback 线程只做 set flag + interrupt + killCurrent;`Interpreter` catch InterruptedException 在 publish 前先 `Thread.interrupted()` 清 flag |

---

## 配置 / 端口

| Key | 默认 | 作用 |
|---|---|---|
| `DASHBOARD_PORT` | 8003 | V9.1 `dashboard.py` |
| dyrpa-server 监听 | 8000 | FastAPI(systemd `dyrpa-server.service`) |
| Mosquitto 监听 | 0.0.0.0:1883 | broker(`/etc/mosquitto/conf.d/dyrpa.conf`) |
| adbd TCP 模式 | 5555 | bootstrap 必须切到这里(`adb tcpip 5555`) |
| VPS SSH | 22 | `~/.ssh/config` alias `dyrpa`,`BindInterface=en0` |

---

## 安全 / 合规

* V9.1 默认**全自动发送**,执行到 `tap "发送"` 即真发,无二次确认;节奏 ~22-24 秒/条;抖音对未回复陌生人私信硬限 3 条
* MVP 阶段服务器明文(HTTP + 无 TLS broker + 匿名 MQTT),只接受信任 IP;Phase 5 上 TLS + ACL
* `outputs/` / `server/data/` / `*.db` / 凭证 均在 `.gitignore`
* 自动化操作有账号封禁风险;Phase 1-3 风险面与 V9.1 相同(开发者模式 + shell UID),Phase 5 评估改 Shizuku 包名 + LSPosed 隐藏
* 仅用于学习与受授权场景

---

## 手机端开发者选项各品牌路径

| 品牌 | 入口 |
|---|---|
| 华为 / 荣耀 | 设置 → 关于手机 → 连续点版本号 7 次 |
| 小米 / Redmi | 设置 → 我的设备 → 连续点 MIUI/HyperOS 版本 |
| OPPO / realme | 设置 → 关于本机 → 版本信息 → 连续点版本号 |
| vivo / iQOO | 设置 → 我的设备 → 连续点软件版本号 |
| 三星 | 设置 → 关于手机 → 软件信息 → 连续点版本号 |
| 一加 | 设置 → 关于设备 → 连续点版本号 |
| 原生 Android(Pixel) | 设置 → 关于手机 → 连续点版本号 |

进开发者选项后打开:**USB 调试**(V9.1 必需 + 群控初次配 Shizuku 用)+ **USB 安装**(部分品牌默认关闭)。
