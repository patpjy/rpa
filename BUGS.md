# BUGS — 排查记录 & 修复说明

> 群控 V9.1 工程踩过的 bug 仓库,每条按"现象 → 关键证据 → 根因 → 修复 → 验证 → 不变量"格式。倒序排列,新的在上。

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
