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

    private data class Pending(val topic: String, val payload: String, val retained: Boolean)

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
        // Heartbeat is retained, so a single fresh one supersedes anything
        // queued from before — no point buffering stale heartbeats.
        publish(topicHeartbeat(), JSONObject(info).toString(), retained = true, buffer = false)
    }

    fun publishEvent(type: String, taskId: String?, data: Map<String, Any?>) {
        val obj = JSONObject()
            .put("type", type)
            .put("task_id", taskId ?: "")
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("data", JSONObject(data))
        // step_progress is a transient "still-alive" beacon; if it didn't go
        // through when fresh, replaying it later just clutters dashboard.
        // Everything else (task_started / step_complete / candidate_complete /
        // task_done / task_failed / logs) is buffered so dashboard reconstructs
        // history on reconnect.
        val skipBuffer = type == "step_progress"
        publish(topicEvent(), obj.toString(), retained = false, buffer = !skipBuffer)
    }

    fun publishLog(msg: String, level: String = "info", taskId: String? = null) {
        publishEvent("log", taskId, mapOf("level" to level, "msg" to msg))
    }

    private fun publish(topic: String, payload: String, retained: Boolean, buffer: Boolean = true) {
        // Happy path: client connected, publish goes directly. Paho handles
        // QoS 1 in-flight tracking internally (cleanSession=false), so a brief
        // network hiccup AFTER this call still gets retried by Paho.
        try {
            val m = MqttMessage(payload.toByteArray()).apply {
                qos = 1
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
            pendingQueue.addLast(Pending(topic, payload, retained))
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
                    qos = 1
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
