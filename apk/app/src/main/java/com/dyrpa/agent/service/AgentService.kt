package com.dyrpa.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.util.DeviceInfo
import com.dyrpa.agent.workflow.Interpreter
import org.json.JSONObject

class AgentService : Service() {

    companion object {
        const val EXTRA_BROKER = "broker"
        const val EXTRA_DEVICE_ID = "device_id"
        private const val CHANNEL_ID = "agent"
        private const val NOTIFY_ID = 0x301
        private const val TAG = "AgentService"
        private const val HEARTBEAT_INTERVAL_MS = 45_000L
        private const val MAX_RECENT_TASK_IDS = 16
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private var mqtt: MqttClient? = null
    private var interpreter: Interpreter? = null
    @Volatile private var running = false

    // Dedup state: on flaky cellular, MQTT QoS 1 broker re-delivers task messages
    // when a PUBACK is lost. Without dedup that spawns parallel workflow threads
    // against the same phone (duplicate input_text, doubled step logs, etc.).
    private val recentTaskIds = LinkedHashMap<String, Long>()
    @Volatile private var activeTaskId: String? = null
    private val taskLock = Any()

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "dyrpa:agent"
        ).apply { setReferenceCounted(false); acquire() }
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val broker = intent?.getStringExtra(EXTRA_BROKER) ?: return START_NOT_STICKY
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY

        startForeground(NOTIFY_ID, buildNotification("正在连接 broker…"))

        if (mqtt == null) {
            val client = MqttClient(broker, deviceId, this::onTask, this::onControl, this::onDisconnected)
            mqtt = client
            interpreter = Interpreter(applicationContext, client, broker)
            client.connect {
                updateNotification("已连接 $broker · $deviceId")
                client.publishHeartbeat(DeviceInfo.collect(applicationContext))
            }
            running = true
            startHeartbeatLoop()
        }
        return START_STICKY
    }

    private fun startHeartbeatLoop() {
        Thread {
            while (running) {
                try {
                    mqtt?.publishHeartbeat(DeviceInfo.collect(applicationContext))
                } catch (e: Exception) {
                    Log.w(TAG, "heartbeat error: ${e.message}")
                }
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS) }
                catch (_: InterruptedException) { return@Thread }
            }
        }.start()
    }

    private fun onTask(taskJson: String) {
        Log.i(TAG, "task received (${taskJson.length} bytes)")
        val taskId = peekTaskId(taskJson)
        if (taskId.isEmpty()) {
            Log.w(TAG, "task missing task_id, dropping")
            return
        }
        synchronized(taskLock) {
            if (recentTaskIds.containsKey(taskId)) {
                // MQTT QoS 1 redelivery (broker didn't get our PUBACK in time).
                // Already processed; ignore so we don't run a duplicate workflow.
                mqtt?.publishLog("[task] dedup ignored $taskId (already seen)", level = "warn")
                return
            }
            if (activeTaskId != null) {
                // A different task arrived while one is running. Server's /run/workflow
                // returns 409 for this on the API side, but be defensive in case of
                // races or out-of-order delivery.
                mqtt?.publishLog(
                    "[task] busy with $activeTaskId, rejecting new task $taskId",
                    level = "warn",
                )
                mqtt?.publishEvent("task_failed", taskId, mapOf("error" to "device busy"))
                return
            }
            recentTaskIds[taskId] = System.currentTimeMillis()
            while (recentTaskIds.size > MAX_RECENT_TASK_IDS) {
                val oldest = recentTaskIds.keys.iterator().next()
                recentTaskIds.remove(oldest)
            }
            activeTaskId = taskId
        }
        Thread {
            try {
                interpreter?.execute(taskJson)
            } catch (e: Exception) {
                Log.e(TAG, "task execution failed", e)
                mqtt?.publishEvent("task_failed", taskId, mapOf("error" to (e.message ?: "unknown")))
            } finally {
                synchronized(taskLock) {
                    if (activeTaskId == taskId) activeTaskId = null
                }
            }
        }.start()
    }

    private fun peekTaskId(taskJson: String): String =
        try { JSONObject(taskJson).optString("task_id", "") } catch (_: Exception) { "" }

    private fun onControl(payload: String) {
        // payload format: {"cmd":"stop"}
        if (payload.contains("\"stop\"")) {
            interpreter?.stopRequested = true
            mqtt?.publishLog("[control] stop received", level = "warn")
        }
    }

    private fun onDisconnected(reason: String) {
        updateNotification("断连: $reason · 自动重连中")
    }

    override fun onDestroy() {
        running = false
        mqtt?.disconnect()
        mqtt = null
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(content: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("dyrpa agent")
            .setContentText(content)
            .setOngoing(true)
            .build()

    private fun updateNotification(content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(content))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "agent", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }
}
