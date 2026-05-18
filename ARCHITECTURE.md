# 链路与角色

群控架构里每个组件**连谁、不连谁**,以及链路某段断了会怎样。生产期(蜂窝独立)的视角。

> V9.1 单机(Mac + USB + uiautomator2)不在本文范围 — 那条路只有 Mac 直连一台手机,无群控链路。

---

## 完整链路图

```
┌────────────────────────────────────────────────────────────────────────┐
│                          你的浏览器(Mac)                                │
│                          http://localhost:8888/devices/honor50-01      │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │ HTTP / SSE
                                     │ (走 SSH 隧道 localhost:8888 → VPS:8000
                                     │  或直接 http://81.69.43.246:8000)
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  VPS 81.69.43.246(腾讯云)                              │
│  ┌──────────────────────┐         ┌───────────────────────────────┐    │
│  │ FastAPI :8000        │ ──────► │ Mosquitto broker :1883        │    │
│  │ (dashboard / API)    │ ◄────── │ (MQTT 消息中转,谁都不直连)     │    │
│  │ + SQLite             │  MQTT   │                               │    │
│  │ (Device/Task 状态)    │         │ Topics:                       │    │
│  └──────────────────────┘         │  dyrpa/devices/<id>/task      │    │
│                                   │  dyrpa/devices/<id>/control   │    │
│                                   │  dyrpa/devices/<id>/heartbeat │    │
│                                   │  dyrpa/devices/<id>/event     │    │
│                                   └──────────────┬────────────────┘    │
└──────────────────────────────────────────────────┼─────────────────────┘
                                                   │
                                                   │ MQTT(QoS 1)
                                                   │ 走蜂窝 4G/5G(或 WiFi)
                                                   │
                                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        手机(honor50-01)                                 │
│                                                                        │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  dyrpa-agent APK(app UID u0_a206)                              │    │
│  │  ─────────────────────────────────────────                     │    │
│  │  ├─ MainActivity:配 broker + device_id + 启动                  │    │
│  │  ├─ AgentService(前台 Service,长期挂着)                        │    │
│  │  │   ├─ MqttClient(Paho)── 跟 VPS broker 通                   │    │
│  │  │   │      ├─ 订阅 task / control                            │    │
│  │  │   │      └─ 发布 heartbeat / event                          │    │
│  │  │   ├─ Interpreter ── 解析 yaml,挨步骤跑                       │    │
│  │  │   └─ 17 个 Action 实现                                      │    │
│  │  │                                                            │    │
│  │  └─ DyrpaIME(自带 InputMethodService)── input_text 用            │    │
│  │       接 ADB_INPUT_TEXT broadcast,做 commitText                │    │
│  └─────────────────┬──────────────────────────────────────────────┘    │
│                    │ 要 root-级别命令时(input tap 等)                  │
│                    │ 本机 Binder IPC ── Shizuku.newProcess()           │
│                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  Shizuku daemon(shell UID,纯本地,不出门)                       │    │
│  │  PPID=1,自己 session leader,无控制终端                          │    │
│  │  作用:把请求方的指令以 shell 权限执行                            │    │
│  │  ── exec("input tap 500 1000")                                 │    │
│  │  ── exec("uiautomator dump /data/local/tmp/x.xml")             │    │
│  │  ── exec("am broadcast -a ADB_INPUT_TEXT --es msg 'X'")        │    │
│  │  ── exec("ime set com.dyrpa.agent/.input.DyrpaIME")            │    │
│  └─────────────────┬──────────────────────────────────────────────┘    │
│                    │                                                   │
│                    ▼                                                   │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  Android shell(/system/bin/...) → 实际操作系统 / 抖音 App        │    │
│  │  ├─ input tap → InputManager → 触屏事件 → 抖音收                │    │
│  │  ├─ uiautomator dump → AccessibilityNodeInfo 遍历 → XML         │    │
│  │  └─ am broadcast → BroadcastReceiver(DyrpaIME 收到)            │    │
│  └────────────────────────────────────────────────────────────────┘    │
│                                                                        │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │  adbd(只在 bootstrap / Mac 调试时用,生产期跟链路无关)            │    │
│  │  监听 TCP 5555(bootstrap 后稳态)                               │    │
│  └────────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 谁连谁 速查

| 问题 | 答案 |
|---|---|
| dyrpa-agent 连谁? | **Mosquitto broker** (`81.69.43.246:1883`)。不直连 FastAPI。 |
| FastAPI 连谁? | 也连 **Mosquitto broker** (`localhost:1883`,因为同机)。不直连手机。 |
| broker 在哪? | 跟 FastAPI 同一台 VPS,只是另一个端口(1883)。**所有跨设备消息经它中转**。 |
| Shizuku 连谁? | **谁都不连**。本地 daemon,通过 binder IPC 接 dyrpa-agent 的请求。无网络连接。 |
| Mac 连谁? | **生产期谁都不连**。只在装 APK / 看 logcat / bootstrap Shizuku 时通过 `adb` 临时连手机。 |
| adbd 连谁? | 等 Mac 的 `adb` 客户端来连(USB 或 TCP)。生产期没人连它,挂着监听。**跟 workflow 一点关系都没有**。 |
| DyrpaIME 连谁? | 只接 `ADB_INPUT_TEXT` 广播,谁发都行。实际是 dyrpa-agent 通过 Shizuku 发的。**无网络连接**。 |
| 浏览器连谁? | FastAPI (`localhost:8888` 通过 SSH 隧道到 VPS:8000,或直接 `81.69.43.246:8000`)。 |

---

## MQTT topic 详表

所有 topic 前缀 `dyrpa/devices/<device_id>/`(`<device_id>` 例如 `honor50-01`):

| Topic | 方向 | 内容 | QoS | retained |
|---|---|---|---|---|
| `heartbeat` | 手机 → server | 设备元信息 + 电量 + IP(每 ~45s) | 1 | true |
| `event` | 手机 → server | log / task_started / step_complete / candidate_complete / task_done / task_failed | 1 | false |
| `task` | server → 手机 | workflow YAML + overrides + task_id | 1 | false |
| `control` | server → 手机 | `{"cmd":"stop"}` 等控制指令 | 1 | false |

---

## 链路某段断了会怎样

| 断哪里 | 后果 |
|---|---|
| 手机蜂窝抖动 → MQTT 丢包 | dashboard 看不到进度,但 workflow 在手机本地继续跑(它不依赖网络);停止指令到不了手机 |
| Mosquitto 挂了 | 所有手机和 dashboard 互看不见,但 Shizuku 和已下发的 workflow 还在手机上跑 |
| FastAPI 挂了 | dashboard 打不开,broker 还在,手机心跳还在(没人记录);新启动 workflow 不行 |
| Shizuku 死了 | dyrpa-agent 调 shell 命令全失败 → workflow 立刻全错 |
| dyrpa-agent 被系统杀 | 心跳停 → 180s 后 dashboard 显示离线;Shizuku 还活但没人调它 |
| adbd 死了 | **生产期没事**;只有下次想从 Mac 装 APK / 重 bootstrap 时不能用 |
| Mac 关机 | **完全没影响**。生产期 Mac 不在链路里。 |

---

## Bootstrap 期 vs 生产期 链路对比

bootstrap(装机 5 分钟,见 [README 的 bootstrap SOP](README.md#单机手机-bootstrap-sop)):

```
[Mac adb] ──USB──► [手机 adbd] ──shell──► [Shizuku 起来]
[Mac adb] ──TCP(WiFi)──► [手机 adbd] ──shell──► [Shizuku 起来]
        ↑ 这条是关键 — 必须 USB 拔了之后通过这条启 Shizuku 才能跨 USB 拔插存活
```

生产期(装机后,蜂窝独立):

```
[手机 dyrpa-agent] ──蜂窝/4G/5G──► [VPS Mosquitto] ◄──同机──► [VPS FastAPI] ◄──HTTP──► [浏览器]
       │
       └──binder IPC──► [Shizuku daemon] ──exec──► [抖音]
```

注意:**生产期 Mac、USB、WiFi 都不在链路里**,只有手机自己 + 蜂窝 + VPS。

---

## 故障排查时该看哪里

| 症状 | 第一现场 | 第二现场 |
|---|---|---|
| dashboard 显示离线 | 手机端 dyrpa-agent 通知栏是否还在?Shizuku UI 是否运行中? | broker 是否 reachable(VPS 端 `sudo systemctl status mosquitto`) |
| 进度卡在某步不动 | 手机屏幕实际状态(可能 workflow 卡在 shell 命令上) | logcat 看 MqttClient + AgentService 行为 |
| 停止按钮没反应 | 服务端是否记录 `[server] stop requested`?如有,broker → 手机段在丢包 | 强制走服务端 `/api/devices/<id>/run/stop`(已经会自动标 fail) |
| input_text 没输入文字 | Shizuku UI 是否运行中?DyrpaIME 是否在 `ime list -s` 里? | logcat 找 `DyrpaIME: committed N chars` 是否打 |
| `[step_complete]` 只有 current=1 | broker → 手机 publish 路径丢包(弱网典型) | 换稳定网络重测,确认是网络问题 |







vps上也有一个ui前端，但是和我们本机通过ssh连的是一样的，都是这个程序在服务器上对应的后台服务，只不过他在服务器上。



手机上的dyrpa agent是通过mqtt和broker通信的，如果手机通过dyrpa agent连上了broke，我们的ssh监听是能立刻察觉到的，也就是localhost:8888的页面上会立刻显示的。
