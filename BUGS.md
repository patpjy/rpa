# BUGS — 排查记录 & 修复说明

> 群控 V9.1 工程踩过的 bug 仓库,每条按"现象 → 关键证据 → 根因 → 修复 → 验证 → 不变量"格式。倒序排列,新的在上。

---

## 2026-05-18 · AgentService 一次性 mqtt init 守卫:改 broker / device_id 不生效

### 现象

新机首次装机时,broker URL **填错(或保留 hint 占位符)**点了启动 → 前台通知显示"正在连接 broker…"卡死,dashboard 看不到设备。回主界面改对 broker URL → 再点启动 → 通知**仍然**卡在"正在连接 broker…"。

dyrpa-agent UI **没有任何错误提示**,以为已经连上;但 server 端 broker log **完全没有这个 device_id 的连接记录**(超过 12 分钟)。

### 关键证据

通过 TCP adb 拉新机现场:

```bash
# 1. SharedPrefs 里 broker URL 已经是对的(字符级无误)
$ adb shell 'run-as com.dyrpa.agent cat /data/data/com.dyrpa.agent/shared_prefs/dyrpa.xml'
<string name="broker">tcp://81.69.43.246:1883</string>     # ← 对的

# 2. 但 dyrpa 进程 socket fd 数 = 0(根本没建 TCP 连接)
$ adb shell 'ls -la /proc/<PID>/fd | grep socket | wc -l'
0

# 3. 前台通知文本仍是初始值
$ adb shell 'dumpsys notification ... | grep dyrpa | grep android.text'
android.text=String (正在连接 broker…)

# 4. MQTT 连接线程 Thread-4 state=S 等了 18 分钟没动
$ adb shell 'cat /proc/<PID>/task/<MQTT_TID>/status | grep State'
State: S (sleeping)

# 5. Service 是 foreground,18 分钟前 create,4 分钟前还有 activity
$ adb shell 'dumpsys activity services com.dyrpa.agent'
ServiceRecord{... AgentService}
  isForeground=true
  createTime=-18m51s
  lastActivity=-4m54s
```

总结:Service 在跑、配置存对了、socket 没建、连接线程 hang 着等 socket 一直没回来。

### 根因

`apk/.../service/AgentService.kt:54-64` 是经典的"一次性 lazy init"模式:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val broker = intent?.getStringExtra(EXTRA_BROKER) ?: return START_NOT_STICKY
    val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
    startForeground(NOTIFY_ID, buildNotification("正在连接 broker…"))
    if (mqtt == null) {                                  // ← 致命守卫
        val client = MqttClient(broker, deviceId, ...)
        mqtt = client
        client.connect { updateNotification("已连接 ...") }
        ...
    }
    return START_STICKY
}
```

事件链(用户 reproducible):
1. 用户**第一次点启动**:broker 填了错的 URL(或残留的 `tcp://YOUR.SERVER:1883` 占位符)
2. MqttClient 用错的 URL 创建,`client.connect()` 卡 SYN_SENT 阶段无限等(Paho 的 `connectionTimeout=10` **不会**让 Socket connect 真在 10s 内放弃 — 详见下文配套问题)
3. 用户发现没连上,回 MainActivity 改 broker → SharedPrefs 存对了
4. 用户**再次点启动**按钮 → MainActivity 触发 `startForegroundService(intent)` → AgentService.onStartCommand 被第二次调用
5. 走到 `if (mqtt == null)` —— `mqtt` **不为 null**(上一个错 URL 的 client 还挂在那)→ **整个 if 块跳过** → 新 broker URL 永远不传到 MqttClient → 通知卡在初始 "正在连接 broker…" 文本不变
6. Foreground Service `START_STICKY` 永久保留,直到 `am force-stop` 或手动停止才重置

### 配套问题:Paho `client.connect()` 不严格遵守 connectionTimeout

我们 connect opts 里设了 `connectionTimeout = 10`,理论上 Paho `connect()` 应该 10s 内抛 MqttException 进 catch 块 → 触发 `onDisconnected` → 通知变 "断连: ... · 自动重连中"。

实际上 Paho 在 Java Socket connect 阶段(TCP SYN_SENT)对错 endpoint 不会及时放弃,可能 hang 几十分钟。这是 Paho 的二级 bug,暂时被 AgentService 守卫掩盖,未来若改了 AgentService 但 Paho 这条仍在,需要单独治理(可能要包装 Socket factory 强制 connectTimeout)。

### 临时绕过(已验证 2026-05-18)

```bash
# 通过 TCP adb 强杀 dyrpa-agent
adb -s <phone_ip>:5555 shell 'am force-stop com.dyrpa.agent'
# 然后手机端打开 dyrpa-agent → 点 [1] 请求 Shizuku 权限 → 点 [2] 启动 agent
```

force-stop 杀进程 → JVM 退出 → `mqtt` 引用清零 → 下次 onStartCommand 走 if 块 → 用 SharedPrefs 里的新 broker 重建。秒连。

### 修复(2026-05-18 已做)

`AgentService.kt onStartCommand` 检测配置变化时主动重建:

```kotlin
private var currentBroker: String? = null
private var currentDeviceId: String? = null

override fun onStartCommand(intent: Intent?, ...): Int {
    val broker = intent?.getStringExtra(EXTRA_BROKER) ?: return START_NOT_STICKY
    val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
    startForeground(...)
    // 配置变了 → 拆掉旧的
    if (mqtt != null && (currentBroker != broker || currentDeviceId != deviceId)) {
        Log.i(TAG, "config changed, recreating mqtt: $currentBroker → $broker, $currentDeviceId → $deviceId")
        mqtt?.disconnect()
        mqtt = null
    }
    if (mqtt == null) {
        currentBroker = broker
        currentDeviceId = deviceId
        // ... 原 MqttClient 创建逻辑
    }
    return START_STICKY
}
```

附加增强(可选):MainActivity 启动按钮的 onClick 里**主动 disconnect 旧 service 后再 startForegroundService**,避免 race。

### 关键不变量(未来改 AgentService 时遵守)

1. **Foreground Service 是 sticky 的,onStartCommand 多次调用必须支持配置变化**。任何"一次性 lazy init"守卫遇到新 intent extras 时必须重新评估
2. **UI 状态(通知 / status 文本)必须反映真实连接状态**,不能只反映 startForegroundService 调用成功(那只代表 Service 拉起来,不代表 MQTT 通)
3. **Paho `client.connect()` 不可信任 connectionTimeout** — 真在不可达 endpoint 上会 hang;未来要么换异步 connect API,要么自己包 Socket factory

### Commits

- `dfb7e30` fix(mqtt): SIM 弱网根治 ← 引入了这个守卫的代码已在 master
- 后续 commit:fix(service): onStartCommand 配置变化时重建 MqttClient(未做)

### 引用

- `apk/app/src/main/java/com/dyrpa/agent/service/AgentService.kt:48-66`
- `apk/app/src/main/java/com/dyrpa/agent/MainActivity.kt:81-92`(调用方)

---

## 2026-05-18 · SIM 卡蜂窝下 workflow 静默卡死 (Paho QoS 1 同步阻塞)

### 现象

- WiFi 下 `max_items=3` 完整跑通 3/3,稳定
- 手机切到 **SIM 卡纯蜂窝**(WiFi 关掉)后,workflow 卡在某一步**完全不动**
- dashboard:"已运行 116.7 秒"但进度 0/3 始终没动
- **没有报错** / 没有 `step_failed` / 没有 `step_progress` 心跳 / 没有任何新事件
- 5 分钟后才被 server 端 `stale_task_watchdog` 强标 failed(只是清账,手机端 workflow 主线程仍冻在那)

### 关键证据

任务最后几行日志:
```
[task_started] {"total": 3, "max_items": 3}
[step_progress] {"step_index": 1, "step_type": "wait", "elapsed_ms": 5004}
[wait] 5.0s
[step_complete] {"current": 1, "total": 69, "type": "wait"}
[tap_xy] (0.9204, 0.0556) → (994, 130)
[step_complete] {"current": 2, "total": 69, "type": "tap_xy"}
[wait] 1.0s
                ← 之后 116s+ 完全静默,无任何事件
```

`wait` action 是纯 `Thread.sleep(1000)`,本地 sleep 不可能卡。它返回后 Interpreter 调 `mqtt.publishEvent("step_complete", ...)` —— **publish 本身阻塞**。

### 根因

**Paho MQTT 客户端默认 `maxInflight = 10`,我们代码里全部消息走 QoS 1 → SIM 卡蜂窝 RTT 高时 PUBACK 慢回 → inflight 队列填满 → `client.publish()` 同步阻塞等 PUBACK → workflow 主线程 + `step_progress` daemon thread 同时卡死。**

#### 为什么 WiFi 永远复现不出来

- 家用 WiFi → MQTT broker RTT 5-20ms,PUBACK 秒回
- inflight 队列基本永远 < 10
- `client.publish()` 立即返回,workflow 流畅

#### 为什么 SIM 卡触发

- 运营商蜂窝 RTT 200-800ms,抖动时 2-3s
- 单台 workflow ~1s 内发 5-10 条事件(`step_complete` + `log` + `step_progress`)
- 网络一抖 → PUBACK 队列堆积 → 10 个 inflight 槽抢空 → 第 11 条 `publish()` 同步卡死
- workflow 主线程死锁;`step_progress` daemon thread 调同一个 `publish()` 也卡 → 心跳停发 → dashboard 看上去就是"什么都没发生"

#### 为什么之前的韧性 fix(`cfd9226`)救不了

| 之前的 fix | 救不了的原因 |
|---|---|
| ShellExecutor 真硬 kill | 修的是 shell 子进程 D-state — 这次主线程根本没进到 shell 调用 |
| 5 min stale watchdog | 只清 server 端 zombie task — 手机端 Java thread 死锁动不了 |
| step_progress 5s 心跳 | 心跳本身调同一个被卡的 `publish()` → 它也卡了 |
| MQTT pendingQueue 重连重放 | `publish()` 在卡死前根本没抛 `MqttException` → 进不到 buffer 路径 |

**所有韧性都假设 publish() 会快速返回(成功或抛异常),但 Paho QoS 1 在 inflight 满时是直接阻塞等 PUBACK。**

### 修复

`apk/app/src/main/java/com/dyrpa/agent/mqtt/MqttClient.kt` 两处改动:

#### (A) 抬 maxInflight

```kotlin
val opts = MqttConnectOptions().apply {
    ...
    maxInflight = 100   // Paho 默认 10,SIM 卡场景必爆
}
```

#### (B) QoS 分级 + buffer 语义对齐

```kotlin
private val CRITICAL_EVENT_TYPES = setOf(
    "task_started", "task_done", "task_failed", "candidate_complete",
)

fun publishEvent(type: String, taskId: String?, data: Map<String, Any?>) {
    ...
    val critical = type in CRITICAL_EVENT_TYPES
    val qos = if (critical) 1 else 0
    publish(topicEvent(), obj.toString(), retained = false, qos = qos, buffer = critical)
}
```

分级理由:

| 事件类型 | QoS | Buffer | 理由 |
|---|---|---|---|
| `task_started` / `task_done` / `task_failed` / `candidate_complete` | 1 | yes | server 端状态机依赖,丢一条 dashboard 进度条就脏。每任务最多 ~4 条,根本填不满 inflight |
| `step_complete` / `step_progress` / `log` | 0 | no | 高频可观测噪声,fire-and-forget,不占 inflight 槽 |
| `heartbeat` (retained) | 1 | no | retained 单条;新心跳会盖旧的,不必 buffer |

**双保险**:即使未来某个 event 漏分类被错走 QoS 1,`maxInflight = 100` 也能撑住 SIM 卡常态 RTT 下 ~100 条消息累积,不会再卡死。

### 验证

2026-05-18:
- **WiFi regression**:`max_items=3` 完整 3/3,无回归(等价于 `cfd9226` 之前的成功 baseline)
- **SIM 卡纯蜂窝**:`max_items=3` 完整 3/3 跑通 — 同一台机同一份 workflow,改前卡 116s+ 不动,改后正常完成

### 关键不变量(未来改 MqttClient 时务必遵守)

1. **任何高频事件不能走 QoS 1**(`log` / `step_*` / 任何 inner-loop publish)。新加 event type 时默认 QoS 0,除非它是状态机 critical
2. **新增状态机 critical event 必须加进 `CRITICAL_EVENT_TYPES` set**,否则 dashboard 进度会丢
3. `maxInflight = 100` 是兜底不是治本 — 真正治本的是 QoS 分级。两者都要,缺一不可
4. `client.publish()` 在弱网 + QoS 1 + inflight 满时**会同步阻塞**,任何调它的线程都可能死锁 — 不要在不能阻塞的路径上调 QoS 1 publish

### Commits

- `cfd9226` feat: 群控架构入库 + 通信韧性 v1 ← bug 在这一版被发现
- 后续 commit:fix(mqtt): SIM 弱网根治 QoS 分级 + maxInflight ← 本次修复

### 引用

- 代码:`apk/app/src/main/java/com/dyrpa/agent/mqtt/MqttClient.kt`
- Paho 文档:[`MqttConnectOptions.setMaxInflight`](https://www.eclipse.org/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttConnectOptions.html)
- 截图:dashboard 116.7s 卡死现场(2026-05-18 早 10:08)

---

## (历史 bug 占位)

后续遇到的 bug 加在这上面,保持倒序时间线。
