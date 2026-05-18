package com.dyrpa.agent.mqtt

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * MQTT wrapper.
 * Topics (server contract — see server/app/mqtt_router.py):
 *   <- dyrpa/devices/{id}/task     (incoming task assignments)
 *   <- dyrpa/devices/{id}/control  (stop commands etc.)
 *   -> dyrpa/devices/{id}/heartbeat (retained, every ~45s)
 *   -> dyrpa/devices/{id}/event    (logs + lifecycle events)
 */
class MqttClient(
    private val brokerUrl: String,
    val deviceId: String,
    private val onTask: (String) -> Unit,
    private val onControl: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {
    companion object {
        private const val TAG = "MqttClient"
        private const val MAX_PENDING = 100

        // task/state-machine 事件:server 端进度条 / 状态机依赖,丢一条就脏 → QoS 1 + buffer
        // 其余 (log / step_complete / step_progress / 默认) → QoS 0 + 不 buffer,纯
        // fire-and-forget,弱网下不堵 Paho inflight 队列。
        private val CRITICAL_EVENT_TYPES = setOf(
            "task_started", "task_done", "task_failed", "candidate_complete",
        )
    }

    private val client = org.eclipse.paho.client.mqttv3.MqttClient(
        brokerUrl,
        "dyrpa-$deviceId-${System.currentTimeMillis()}",
        MemoryPersistence()
    )

    // Fallback queue for publishes that fail because the client wasn't connected
    // (cellular blip). Paho's QoS 1 in-flight queue covers the normal case where
    // a publish was accepted into the client but not yet PUBACK'd; this queue is
    // a second layer for "client.publish threw because isConnected==false".
    // Bounded so a long outage doesn't OOM.
    private val pendingMutex = Any()
    private val pendingQueue = ArrayDeque<Pending>(MAX_PENDING)

    private data class Pending(val topic: String, val payload: String, val retained: Boolean, val qos: Int)

    private fun topicTask() = "dyrpa/devices/$deviceId/task"
    private fun topicControl() = "dyrpa/devices/$deviceId/control"
    private fun topicHeartbeat() = "dyrpa/devices/$deviceId/heartbeat"
    private fun topicEvent() = "dyrpa/devices/$deviceId/event"

    fun connect(onConnected: () -> Unit) {
        val opts = MqttConnectOptions().apply {
            isCleanSession = false
            isAutomaticReconnect = true
            keepAliveInterval = 60
            connectionTimeout = 10
            // Paho 默认 maxInflight = 10。SIM 卡蜂窝 RTT 200-800ms 时,QoS 1 的 PUBACK
            // 慢回 → 10 条未确认 QoS 1 消息一旦堆满,第 11 条 client.publish() 同步
            // 阻塞等 PUBACK,直接卡死 workflow 主线程 + step_progress 心跳 Thread。
            // 抬到 100 给慢网络兜底;搭配下面 publishEvent 的 QoS 分级 (高频事件 QoS 0
            // 根本不进 inflight 队列),双保险。
            maxInflight = 100
        }
        // MqttCallbackExtended adds `connectComplete` over plain MqttCallback —
        // gives us a hook to drain the pending queue on every successful
        // reconnect, not just the first connect.
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "connectComplete reconnect=$reconnect uri=$serverURI")
                if (reconnect) drainPending()
            }
            override fun connectionLost(cause: Throwable?) {
                onDisconnected(cause?.message ?: "unknown")
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload)
                Log.i(TAG, "<- $topic: ${payload.take(200)}")
                when (topic) {
                    topicTask() -> onTask(payload)
                    topicControl() -> onControl(payload)
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        Thread {
            try {
                client.connect(opts)
                client.subscribe(topicTask(), 1)
                client.subscribe(topicControl(), 1)
                onConnected()
                Log.i(TAG, "connected; subscribed task+control")
            } catch (e: MqttException) {
                Log.e(TAG, "connect failed: ${e.message}", e)
                onDisconnected(e.message ?: "connect failed")
            }
        }.start()
    }

    fun publishHeartbeat(info: Map<String, Any?>) {
        // Heartbeat is retained — a single fresh one supersedes whatever's
        // queued. QoS 1 keeps it reliable; no buffer (a stale heartbeat is
        // worse than no heartbeat).
        publish(topicHeartbeat(), JSONObject(info).toString(), retained = true, qos = 1, buffer = false)
    }

    fun publishEvent(type: String, taskId: String?, data: Map<String, Any?>) {
        val obj = JSONObject()
            .put("type", type)
            .put("task_id", taskId ?: "")
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("data", JSONObject(data))
        // Critical lifecycle events (task_started/done/failed, candidate_complete)
        // drive server-side state machine + dashboard progress bar — must arrive.
        // QoS 1 + buffer on reconnect. Worst case: 4 events per task, never fills inflight queue.
        //
        // Everything else (log, step_complete, step_progress) is high-frequency
        // observability noise. QoS 0 + no buffer:
        //   - QoS 0 = fire-and-forget, no PUBACK, no inflight slot consumed → can't deadlock publish()
        //   - no buffer = if connection's down, just drop; server's stale watchdog still triggers via missing critical events
        // This is THE fix for SIM-card weak-network freezing: high-frequency
        // events no longer occupy Paho's inflight queue, so critical events
        // never queue behind them.
        val critical = type in CRITICAL_EVENT_TYPES
        val qos = if (critical) 1 else 0
        publish(topicEvent(), obj.toString(), retained = false, qos = qos, buffer = critical)
    }

    fun publishLog(msg: String, level: String = "info", taskId: String? = null) {
        publishEvent("log", taskId, mapOf("level" to level, "msg" to msg))
    }

    private fun publish(topic: String, payload: String, retained: Boolean, qos: Int = 1, buffer: Boolean = true) {
        // Happy path: client connected, publish goes directly. Paho handles
        // QoS 1 in-flight tracking internally (cleanSession=false), so a brief
        // network hiccup AFTER this call still gets retried by Paho.
        //
        // Note: qos = 0 returns immediately (no PUBACK wait); qos = 1 may
        // synchronously block here if Paho's inflight queue is at maxInflight
        // (we raised that to 100 in connect() opts) — but critical events are
        // only ~4 per task, so realistically never fills.
        try {
            val mqos = qos
            val m = MqttMessage(payload.toByteArray()).apply {
                this.qos = mqos
                isRetained = retained
            }
            client.publish(topic, m)
            return
        } catch (e: MqttException) {
            // Client disconnected or publish queue full. Fall through to our
            // own fallback buffer below.
            Log.w(TAG, "publish failed on $topic (${e.reasonCode}): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "publish unexpected error on $topic: ${e.message}")
            return
        }
        if (!buffer) return
        synchronized(pendingMutex) {
            while (pendingQueue.size >= MAX_PENDING) {
                pendingQueue.pollFirst()  // evict oldest
            }
            pendingQueue.addLast(Pending(topic, payload, retained, qos))
        }
    }

    /** Called from MqttCallbackExtended.connectComplete on reconnect. */
    private fun drainPending() {
        val snapshot: List<Pending> = synchronized(pendingMutex) {
            val s = pendingQueue.toList()
            pendingQueue.clear()
            s
        }
        if (snapshot.isEmpty()) return
        Log.i(TAG, "draining ${snapshot.size} buffered publishes")
        for (p in snapshot) {
            try {
                val m = MqttMessage(p.payload.toByteArray()).apply {
                    qos = p.qos
                    isRetained = p.retained
                }
                client.publish(p.topic, m)
            } catch (e: Exception) {
                // Re-queue on the front; remaining will retry next reconnect.
                Log.w(TAG, "drain failed on ${p.topic}; re-queueing: ${e.message}")
                synchronized(pendingMutex) { pendingQueue.addFirst(p) }
                break
            }
        }
    }

    fun disconnect() {
        try { client.disconnect() } catch (_: Exception) {}
    }
}
