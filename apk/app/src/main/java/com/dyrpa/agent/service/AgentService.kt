package com.dyrpa.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
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
    // Track config so onStartCommand can detect "user changed broker/device_id and tapped
    // 启动 again" — the MainActivity calls startForegroundService with new extras, but the
    // Service is sticky and already has an MqttClient with the old config. Without these
    // sentinels we silently keep using the stale config (see BUGS.md 2026-05-18 entry 1).
    private var currentBroker: String? = null
    private var currentDeviceId: String? = null
    private var heartbeatThread: Thread? = null
    // Reference to the workflow execution thread so onControl/onTask can interrupt
    // it when stop arrives or a new task preempts. Volatile because it's read from
    // the MQTT callback thread and written from the workflow thread on completion.
    @Volatile private var workflowThread: Thread? = null

    // SharedPreferences-backed config: required for START_STICKY recovery.
    // When OS kills the service (e.g. due to long shell wedges) and Android
    // sticky-restarts us, the redelivered Intent is null — without these prefs
    // we'd return START_NOT_STICKY immediately and "zombie" (service alive but
    // no MQTT client + no Interpreter). See BUGS.md 2026-05-18 entry 2.
    //
    // Name "dyrpa" + keys "broker"/"device_id" MUST match MainActivity (it writes
    // the same prefs when the user taps 启动). That way the service can recover
    // from sticky restart even on a fresh install — as long as the user has
    // configured + started once, prefs exist.
    private val prefs by lazy {
        getSharedPreferences("dyrpa", Context.MODE_PRIVATE)
    }

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
        // intent may be null when Android sticky-restarts us after a kill. Fall
        // back to last-known broker/deviceId from prefs so we recover automatically
        // instead of sitting in a zombie state until the user re-opens MainActivity.
        val broker = intent?.getStringExtra(EXTRA_BROKER)
            ?: prefs.getString("broker", null)
            ?: return START_NOT_STICKY
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
            ?: prefs.getString("device_id", null)
            ?: return START_NOT_STICKY

        // Persist current config so the sticky-restart path above can read them
        // next time. Always write (cheap) — covers first run + user reconfig.
        prefs.edit().putString("broker", broker).putString("device_id", deviceId).apply()

        startForeground(NOTIFY_ID, buildNotification("正在连接 broker…"))

        // User reconfigured broker/device_id and re-tapped 启动:
        // MainActivity called startForegroundService with new extras, but our sticky
        // Service is still holding an MqttClient with the OLD config. Detect the change
        // and tear it down so the if-null block below rebuilds with new config.
        // (Pre-fix behavior: silently kept stale MqttClient forever; user had to
        //  `adb shell am force-stop com.dyrpa.agent` to recover.)
        if (mqtt != null && (currentBroker != broker || currentDeviceId != deviceId)) {
            Log.i(TAG, "config changed (broker $currentBroker→$broker, device $currentDeviceId→$deviceId); rebuilding")
            teardownAgent()
        }

        if (mqtt == null) {
            currentBroker = broker
            currentDeviceId = deviceId
            val client = MqttClient(broker, deviceId, this::onTask, this::onControl, this::onDisconnected)
            mqtt = client
            interpreter = Interpreter(applicationContext, client, broker)
            client.connect {
                updateNotification("已连接 $broker · $deviceId")
                client.publishHeartbeat(DeviceInfo.collect(applicationContext))
            }
            running = true
            heartbeatThread = startHeartbeatLoop()
        }
        return START_STICKY
    }

    /** Tear down current MqttClient + interpreter + heartbeat thread. Safe to call
     *  when nothing's running (no-op on null fields). */
    private fun teardownAgent() {
        running = false
        // Tell the workflow to bail and kill any in-flight shell so the worker
        // thread can exit promptly instead of dragging the teardown out 15s+.
        interpreter?.stopRequested = true
        workflowThread?.interrupt()
        ShellExecutor.killCurrent()
        workflowThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try { mqtt?.disconnect() } catch (_: Exception) {}
        mqtt = null
        interpreter = null
        // Don't clear currentBroker/currentDeviceId here — onStartCommand sets them
        // freshly before the next init, and clearing would lose the comparison baseline
        // if onDestroy fires between onStartCommand calls.
    }

    private fun startHeartbeatLoop(): Thread =
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
        }.apply { isDaemon = true; name = "dyrpa-heartbeat"; start() }

    private fun onTask(taskJson: String) {
        // Critical: this is the Paho callback thread. It MUST return ASAP and
        // MUST NOT call client.publish() — both will corrupt Paho 3.x's internal
        // state and cause subscribe-stalled-but-publish-still-works deadlock
        // (the "ghost task" bug, see BUGS.md). Offload everything to a dispatcher.
        Log.i(TAG, "task received (${taskJson.length} bytes)")
        Thread { handleTask(taskJson) }
            .apply { isDaemon = true; name = "task-dispatcher" }
            .start()
    }

    private fun handleTask(taskJson: String) {
        val taskId = peekTaskId(taskJson)
        if (taskId.isEmpty()) {
            Log.w(TAG, "task missing task_id, dropping")
            return
        }

        // Phase 1 (inside lock): dedup check + remember oldThread to preempt.
        // We DON'T claim activeTaskId yet — preempting needs to happen outside
        // the lock (join() may sleep multiple seconds; we don't want to block
        // other dispatcher threads while holding taskLock).
        val toPreempt: Thread?
        val preemptedTaskId: String?
        synchronized(taskLock) {
            if (recentTaskIds.containsKey(taskId)) {
                // MQTT QoS 1 redelivery (broker didn't get our PUBACK in time).
                // Already processed; ignore so we don't run a duplicate workflow.
                mqtt?.publishLog("[task] dedup ignored $taskId (already seen)", level = "warn")
                return
            }
            recentTaskIds[taskId] = System.currentTimeMillis()
            while (recentTaskIds.size > MAX_RECENT_TASK_IDS) {
                val oldest = recentTaskIds.keys.iterator().next()
                recentTaskIds.remove(oldest)
            }
            toPreempt = if (activeTaskId != null) workflowThread else null
            preemptedTaskId = activeTaskId
        }

        // Phase 2 (outside lock): if a previous workflow is still running, signal
        // it to stop and wait briefly for it to unwind. Mirrors the user mental
        // model "点开始 = 重新开始" — old run is replaced, not queued behind.
        if (toPreempt != null) {
            mqtt?.publishLog(
                "[task] preempting previous task $preemptedTaskId for new task $taskId",
                level = "warn",
            )
            interpreter?.stopRequested = true
            toPreempt.interrupt()
            ShellExecutor.killCurrent()
            try { toPreempt.join(5_000) } catch (_: InterruptedException) {}
            if (toPreempt.isAlive) {
                mqtt?.publishLog(
                    "[task] WARN: previous task $preemptedTaskId did not exit within 5s; starting $taskId anyway",
                    level = "warn",
                )
            }
        }

        // Phase 3 (back inside lock): now claim activeTaskId for the new run.
        val newThread = Thread {
            try {
                interpreter?.execute(taskJson)
            } catch (e: Exception) {
                Log.e(TAG, "task execution failed", e)
                mqtt?.publishEvent("task_failed", taskId, mapOf("error" to (e.message ?: "unknown")))
            } finally {
                synchronized(taskLock) {
                    if (activeTaskId == taskId) {
                        activeTaskId = null
                        workflowThread = null
                    }
                }
            }
        }.apply { name = "workflow-$taskId" }
        synchronized(taskLock) {
            activeTaskId = taskId
            workflowThread = newThread
        }
        newThread.start()
    }

    private fun peekTaskId(taskJson: String): String =
        try { JSONObject(taskJson).optString("task_id", "") } catch (_: Exception) { "" }

    private fun onControl(payload: String) {
        // payload format: {"cmd":"stop"}
        // Critical: this runs on Paho's callback thread. We MUST NOT call
        // client.publish() here — concurrent publish from the callback thread
        // while the workflow thread is mid-publish (firing task_failed within
        // ms of our interrupt) corrupts Paho 3.x state and stalls subscribe.
        if (payload.contains("\"stop\"")) {
            interpreter?.stopRequested = true
            workflowThread?.interrupt()
            ShellExecutor.killCurrent()
            Log.i(TAG, "control stop received; signal sent + shell killed")
            // Offload the dashboard log line so the callback thread returns
            // immediately. Daemon thread because it's fire-and-forget.
            Thread {
                mqtt?.publishLog("[control] stop received", level = "warn")
            }.apply { isDaemon = true; name = "stop-log" }.start()
        }
    }

    private fun onDisconnected(reason: String) {
        updateNotification("断连: $reason · 自动重连中")
    }

    override fun onDestroy() {
        teardownAgent()
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
