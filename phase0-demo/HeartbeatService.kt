package com.example.dyrpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class HeartbeatService : Service() {

    companion object {
        const val EXTRA_URL = "server_url"
        const val EXTRA_DEVICE_ID = "device_id"
        private const val INTERVAL_MS = 60_000L
        private const val NOTIFY_ID = 0x101
        private const val CHANNEL_ID = "heartbeat"
        private const val TAG = "Heartbeat"
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private var serverUrl: String = ""
    private var deviceId: String = ""
    private var successCount = 0
    private var failCount = 0

    private val tick = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "dyrpa:heartbeat"
        ).apply { setReferenceCounted(false); acquire() }
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra(EXTRA_URL) ?: serverUrl
        deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID) ?: deviceId
        if (serverUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, buildNotification("启动中…"))
        handler.removeCallbacks(tick)
        handler.post(tick)
        Log.i(TAG, "Heartbeat started → $serverUrl, device=$deviceId")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        Log.i(TAG, "Heartbeat stopped (success=$successCount, fail=$failCount)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendHeartbeat() {
        val ts = System.currentTimeMillis()
        val body = """{"device_id":"$deviceId","ts":$ts,"success":$successCount,"fail":$failCount}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(serverUrl).post(body).build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                failCount++
                Log.w(TAG, "heartbeat fail: ${e.message}")
                updateNotification("失败 #$failCount: ${e.javaClass.simpleName}")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { r ->
                    if (r.isSuccessful) {
                        successCount++
                        updateNotification("成功 $successCount 次 (失败 $failCount)")
                    } else {
                        failCount++
                        updateNotification("HTTP ${r.code} (失败 $failCount)")
                    }
                }
            }
        })
    }

    private fun buildNotification(content: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("dyrpa 心跳运行中")
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(content))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "心跳",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }
}
