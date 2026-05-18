# BUGS — 排查记录 & 修复说明

> 群控 V9.1 工程踩过的 bug 仓库,每条按"现象 → 关键证据 → 根因 → 修复 → 验证 → 不变量"格式。倒序排列,新的在上。

---

## 2026-05-18 · Stop/Start 失效三层级联 — workflow 不停 / 服务僵尸 / Paho subscribe 停摆

### 现象

群控架构上线后第一次实战时,用户反复点"停止"和"启动 workflow":

1. **停止不停**:dashboard 点停止后 phone 端 workflow 仍跑 15-30 秒(uiautomator dump 跑完才停)
2. **重启没反应**:停止后再点"启动 workflow",dashboard 显示 `[server] task X dispatched` 但**之后任何 phone 端日志都没有**。连点 9 次,server 接受 9 个 task,phone 一个都没接到。
3. **心跳停止 → "在线"假象**:心跳栏停在某个旧时间(180s 内 dashboard 还显示绿点),其实手机端 service 已经死了

### 关键证据

```
Server SQLite (`dyrpa.db`):
b000da11 honor50-02 workflow failed   prog=0/3 err='stopped by user'   ← 原始任务(被 force-fail)
a9a0a615 honor50-02 workflow pending  prog=0/0 err=''                  ← 之后 11 次 dispatch 全部 stuck pending
46279813 honor50-02 workflow pending  prog=0/0 err=''
... (9 more pending)

Mosquitto broker log:
1779075973 (11:46:13)  honor50-02 connected as dyrpa-honor50-02-1779075973417
1779076038 (11:47:18)  closed its connection      ← stop 后 18 秒,phone 端 MQTT 死了
                                                  ← 之后 broker 视角再没有任何 honor50-02 重连事件

Phone-side(第二轮 v1 fix 后的现场):
[control] stop received                ← onControl 触发,publishLog 从 Paho callback 线程发出
[tap_xy] (0.2528, 0.3603) → (273, 843) ← 同一时刻 workflow 线程 step 10 完成,publishLog 也从那边发出
[step_progress] step_index=10 elapsed_ms=5001
[server] task 1086c073ec15 dispatched  ← 再次启动
[彻底没下文]                            ← phone subscribe 死,publish 还活
```

最致命的取证手段:**broker 端 `mosquitto_sub` 订阅 phone 的 event topic 60 秒**,确认 phone 在持续 publish heartbeat(battery 数值在变),但 `mosquitto_pub` 推 control 到 phone 完全无反应 → **phone 的 MQTT 是单向死的**(publish OK,subscribe 死)。

### 根因 — 三层级联

#### 层 1:Stop 是"协作式"的,workflow 主线程有 5 处不响应 stopRequested 标志的阻塞点

| 阻塞点 | 文件:行 | 最坏卡多久 |
|---|---|---|
| `ShellExecutor.run` 的 `waiter.join(timeoutMs+3s)` | `shizuku/ShellExecutor.kt:88` | 18s(uiautomator dump = 15s + 3s grace) |
| `UiTreeFinder.findBy` 内部 while loop | `util/UiTreeFinder.kt:26-32` | 8s 外层(单次 uiDump 可能 15s) |
| `captureUiXmlOnly` 失败后再 uiDump | `workflow/Interpreter.kt:151` | 15s |
| `captureFailure` screencap + 2 次 uiDump + HTTP 上传 | `workflow/Interpreter.kt:170-181` | 20-30s |
| `recover()` back×3 + swipe(都走 shell) | `workflow/Interpreter.kt:127-134` | ~6s |

`stopRequested` 标志只在 `runWorkflow` for-loop 和 `runSection` 的步骤之间被检查,这之间的所有阻塞调用对 stop 信号完全无感。最坏情况 stop 信号被设置后 60-80s workflow 主线程才能爬到下一个 stop 检查点。

#### 层 2:Service lifecycle 漏洞 — START_STICKY 重启拿到 null intent → 僵尸服务

`AgentService.onStartCommand` 老逻辑:
```kotlin
val broker = intent?.getStringExtra(EXTRA_BROKER) ?: return START_NOT_STICKY
val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
```

故障路径:
1. workflow 卡在 step 10 + forensics 上 30+ 秒
2. HONOR/MagicOS 觉得 service 不响应,杀掉(即使 PARTIAL_WAKE_LOCK + 前台 service 也救不了)
3. `onDestroy` → `teardownAgent` → `mqtt.disconnect()`(broker 看到 TCP FIN — 就是上面 11:47:18 那次 close)
4. Android `START_STICKY` 重启 service,但**`Intent` 是 null**(START_STICKY 不重投原 intent,只有 `START_REDELIVER_INTENT` 才会)
5. `onStartCommand` 第一行 `?: return START_NOT_STICKY` → 立刻返回,啥也不干
6. **Service 在跑(`isForeground=true`,wakelock 持有)但 `mqtt`/`interpreter` 都是 null**
7. Heartbeats 停,新 task 全部塞 broker 离线队列里没人取

#### 层 3:Paho 3.x callback thread re-entrancy — publish 从 callback 线程发,subscribe 死

层 1 修完后 stop 真的快了(1-2s 内 task_failed("stopped") 发出),反而触发了**第三层 bug**:

- `onControl` 在 Paho callback 线程里运行,旧代码直接 `mqtt?.publishLog("[control] stop received")` —— 从 callback 线程 publish
- 同一时刻 workflow 线程被 `Thread.interrupt()` 唤醒,在 `catch (InterruptedException)` 里也 publish `task_failed("stopped")`(QoS 1)
- **两个 publish 几乎同时从不同线程触发,其中一个来自 Paho 自己的 callback 线程** — Paho 3.x 的著名 re-entrancy 雷区
- 加之 workflow 线程的 interrupt flag 还没清,`client.publish(QoS 1)` 内部 `lockInterruptibly` 撞到 flag → 抛 MqttException → task_failed 进 `pendingQueue` → **永远不 drain**(没有 reconnect 触发)
- Paho 客户端进入"publish 还能用、subscribe 流水线烂掉"的奇怪状态。Heartbeat 还能发,但 broker 推下来的 task / control 一律收不到

旧代码不暴露这个 bug 是因为层 1 让 workflow 线程的 task_failed publish 跟 onControl 的 publishLog 错开了 15+ 秒,Paho 来得及"消化"。新代码 stop 太快反而暴露了 Paho 这个底层 bug。

### 修复(2026-05-18 三层并修)

#### 层 1 — Stop 真停

- **`ShellExecutor.kt`**:`currentProc: AtomicReference<Process?>` 暴露当前 in-flight shell + `killCurrent()` SIGTERM→SIGKILL 接口;`run()` 的 `waiter.join` 包 try/catch InterruptedException,中断时立刻 destroy proc + 重抛
- **`UiTreeFinder.kt`**:所有 `findBy*` 方法加可选 `stopCheck: () -> Boolean` 参数,polling loop 每次 uiDump 前后检查,真时抛 InterruptedException
- **7 个 Action 文件**(`TapText/TapDesc/TapResourceId/TapXyIfMissing/TapRelativeToElement/SkipIfExists/SkipIfNotExists`):调 UiTreeFinder 时传 `acx.stopCheck`
- **`Interpreter.kt`**:
  - `runSection` 的 catch 顺序加 `catch (InterruptedException)` 在最前,**跳过 forensics + recover** 直接重抛
  - `runWorkflow` 的 per_item for-loop 内 try 块同样优先抓 InterruptedException
  - for-loop 顶 `if (stopRequested) break` 改成 `if (stopRequested) throw InterruptedException(...)` — 避免 break 跳出后 fall through 到 `task_done` publish

#### 层 2 — Service 自愈

`AgentService.onStartCommand` 改成读 SharedPreferences 兜底:
```kotlin
val broker = intent?.getStringExtra(EXTRA_BROKER)
    ?: prefs.getString("broker", null)
    ?: return START_NOT_STICKY
val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
    ?: prefs.getString("device_id", null)
    ?: return START_NOT_STICKY
prefs.edit().putString("broker", broker).putString("device_id", deviceId).apply()
```

**prefs 名字必须是 `"dyrpa"`**(跟 MainActivity 写入的同名),不能写成 `"dyrpa-agent"` 之类 — 这是踩过的坑(第一版改 `"dyrpa-agent"` 时 sticky 重启读不到、bug 没被掩盖反而曝光了)。

`teardownAgent` 也补上 `workflowThread?.interrupt() + ShellExecutor.killCurrent()`,service 拆掉时 workflow 不会拖延 15s+ 才退出。

#### 层 3 — Paho callback thread 解耦

`AgentService.onTask` 整体移到 dispatcher 线程:
```kotlin
private fun onTask(taskJson: String) {
    // 关键:Paho callback 线程必须立刻返回,不能 publish、不能 join
    Log.i(TAG, "task received (${taskJson.length} bytes)")
    Thread { handleTask(taskJson) }
        .apply { isDaemon = true; name = "task-dispatcher" }
        .start()
}
```

`AgentService.onControl` 同理,publishLog 移到 daemon 线程:
```kotlin
private fun onControl(payload: String) {
    if (payload.contains("\"stop\"")) {
        interpreter?.stopRequested = true
        workflowThread?.interrupt()
        ShellExecutor.killCurrent()
        Log.i(TAG, "control stop received; signal sent + shell killed")
        Thread { mqtt?.publishLog("[control] stop received", level = "warn") }
            .apply { isDaemon = true; name = "stop-log" }.start()
    }
}
```

`Interpreter.runWorkflow` 的 `catch (InterruptedException)` 第一行加 `Thread.interrupted()` 清 flag,**然后**再 publish task_failed:
```kotlin
} catch (e: InterruptedException) {
    Thread.interrupted()  // 清 flag,避免 Paho lockInterruptibly 撞到
    mqtt.publishEvent("task_failed", task.taskId, mapOf("error" to "stopped"))
}
```

#### Bonus — Start 抢占 + Dashboard lockout

- `onTask` 看到 `activeTaskId != null` 时不再"busy reject",改成 **preempt**:设 stopRequested → interrupt 旧 thread → killCurrent → `join(5_000)` 等旧 workflow 退出 → claim 新 task。语义对齐用户心智模型"点开始 = 重新开始"
- `device_detail.js`:点停止后**禁用启动按钮 6 秒**(`STOP_LOCKOUT_MS`),给 phone 端时间完成 stop 流程,避免用户在 phone 还没清干净时 spam start

### 验证(2026-05-18 v2 实证)

| 测试 | 旧版本 | v1 fix(只修层 1) | v2 fix(三层并修) |
|---|---|---|---|
| stop → task ended 延迟 | 15-60s | **1-2s** ✅ | **1-2s** ✅ |
| stop 后立刻 start | 9 次 dispatch 全 stuck pending | 同样卡 pending(Paho subscribe 死) | **新 task 立刻接管** ✅ |
| Service 被 OS 杀后 | 永久僵尸,需手动开 app | 同 | **sticky 重启自愈,prefs 兜底** ✅ |
| task_failed 错误信息 | `"stopped by user"`(server force-fail,phone 未真停) | `"stopped"`(phone 干净停止) | `"stopped"` |

### 关键不变量(未来改 AgentService / Interpreter 时务必遵守)

1. **Paho callback 线程是神圣的**:`onTask` / `onControl` 等 `MqttCallback` 方法**不准 publish、不准 join、不准任何 IO**。所有工作 offload 到 dispatcher / daemon 线程。Paho 3.x 对 callback 线程的 reentrancy 没有 watchdog,违反约定会进入"subscribe 死、publish 活"的不可恢复状态
2. **Stop 必须三层都打**:`stopRequested` 标志(协作式)+ `Thread.interrupt()`(唤醒 Thread.sleep / Process.waitFor)+ `ShellExecutor.killCurrent()`(SIGKILL in-flight shell)。任何单独一种都救不了
3. **catch InterruptedException 在 publish 之前必须 `Thread.interrupted()` 清 flag**:否则 Paho 内部 `lockInterruptibly` 会撞到 flag → MqttException → 关键事件进 pendingQueue 永远不发出
4. **`START_STICKY` 的 Service 必须能从 null intent 重启自愈**:用 SharedPreferences 持久化关键配置,key 名要跟 MainActivity 写入的对齐(本项目是 `"dyrpa"` + `"broker"`/`"device_id"`)
5. **Stop 路径 SKIP forensics**:catch InterruptedException 直接重抛,不要触发 captureFailure / captureUiXmlOnly / recover —— 这些都是 shell 调用,在中断路径里跑只会拖延 stop + 触发 Paho 写入风暴

### Commits

- `dfb7e30` fix(mqtt): SIM 弱网根治 ← stop 卡 15+s 的根源代码已在此版本
- 后续 commit(本次):fix(workflow): stop 真停 + service 自愈 + Paho callback 解耦

### 引用

- `apk/app/src/main/java/com/dyrpa/agent/service/AgentService.kt`
- `apk/app/src/main/java/com/dyrpa/agent/workflow/Interpreter.kt`
- `apk/app/src/main/java/com/dyrpa/agent/shizuku/ShellExecutor.kt`
- `apk/app/src/main/java/com/dyrpa/agent/util/UiTreeFinder.kt`
- `apk/app/src/main/java/com/dyrpa/agent/actions/{Tap,Skip}*.kt`
- `server/static/device_detail.js`
- Paho callback re-entrancy 讨论:[Paho Java client thread-safety 文档](https://www.eclipse.org/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttCallback.html)

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
