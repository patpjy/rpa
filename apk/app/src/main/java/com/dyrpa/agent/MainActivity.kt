package com.dyrpa.agent

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.dyrpa.agent.service.AgentService
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val REQ_SHIZUKU = 0xCAFE
    private val REQ_NOTIFY = 0xBEEF
    private lateinit var prefs: SharedPreferences

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("dyrpa", MODE_PRIVATE)

        Shizuku.addRequestPermissionResultListener(permListener)

        val brokerInput = findViewById<EditText>(R.id.editBroker)
        val deviceInput = findViewById<EditText>(R.id.editDevice)
        brokerInput.setText(prefs.getString("broker", "tcp://YOUR.SERVER:1883"))
        deviceInput.setText(prefs.getString("device_id", defaultDeviceId()))

        findViewById<Button>(R.id.btnReqShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val broker = brokerInput.text.toString().trim()
            val deviceId = deviceInput.text.toString().trim()
            prefs.edit().putString("broker", broker).putString("device_id", deviceId).apply()
            startAgent(broker, deviceId)
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            log("agent stopped")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }

        updateStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun defaultDeviceId(): String =
        "${Build.MANUFACTURER}-${Build.MODEL}-${(Build.SERIAL ?: "x").take(6)}".replace(" ", "_")

    private fun requestShizuku() {
        try {
            when {
                Shizuku.isPreV11() -> log("Shizuku 版本过老,请升级 Stellar")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> log("Shizuku 已授权 ✓")
                else -> {
                    Shizuku.requestPermission(REQ_SHIZUKU)
                    log("授权请求已发送,看 Stellar 弹窗")
                }
            }
        } catch (e: IllegalStateException) {
            log("Stellar/Shizuku 未启动: ${e.message}")
        }
    }

    private fun startAgent(broker: String, deviceId: String) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("先点 [1] 请求 Shizuku 权限")
            return
        }
        val intent = Intent(this, AgentService::class.java).apply {
            putExtra(AgentService.EXTRA_BROKER, broker)
            putExtra(AgentService.EXTRA_DEVICE_ID, deviceId)
        }
        startForegroundService(intent)
        log("agent 已启动\nbroker = $broker\ndevice_id = $deviceId\n\n现在到服务器控制台\n/devices/$deviceId\n下发任务即可")
    }

    private fun updateStatus() {
        val shizuku = try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "已授权 ✓"
            else "未授权"
        } catch (_: Exception) {
            "Stellar/Shizuku 未运行"
        }
        log(
            "== dyrpa agent ==\n" +
            "设备: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE}\n" +
            "Shizuku: $shizuku\n\n" +
            "步骤:\n" +
            "1. 请求 Shizuku 权限\n" +
            "2. 填 broker + device_id\n" +
            "3. 启动 agent\n" +
            "4. 服务器控制台下发任务"
        )
    }

    private fun log(msg: String) {
        findViewById<TextView>(R.id.tvStatus).text = msg
    }
}
