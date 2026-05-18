package com.example.dyrpa

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQ_CODE = 0xCAFE

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku 已授权 ✓")
        } else {
            log("Shizuku 拒绝授权(去 Stellar app 里手动授权也可以)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Shizuku.addRequestPermissionResultListener(permListener)

        findViewById<Button>(R.id.btnReqShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnTestTap).setOnClickListener { testInputTap() }
        findViewById<Button>(R.id.btnStartHb).setOnClickListener { startHeartbeat() }
        findViewById<Button>(R.id.btnStopHb).setOnClickListener { stopHeartbeat() }

        showStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun requestShizuku() {
        try {
            when {
                Shizuku.isPreV11() ->
                    log("Shizuku 版本太老(<11),请升级 Stellar")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    log("Shizuku 已经授权过 ✓")
                Shizuku.shouldShowRequestPermissionRationale() ->
                    log("用户之前拒绝过,需要在 Stellar app 里手动重新授权")
                else -> {
                    Shizuku.requestPermission(PERMISSION_REQ_CODE)
                    log("授权请求已发,看 Stellar 弹窗")
                }
            }
        } catch (e: IllegalStateException) {
            log("Shizuku/Stellar 没运行: ${e.message}")
        } catch (e: Exception) {
            log("出错: ${e.message}")
        }
    }

    private fun testInputTap() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("先点 [1] 请求 Shizuku 权限")
            return
        }
        Thread {
            try {
                val proc = Shizuku.newProcess(arrayOf("sh", "-c", "input tap 500 500"), null, null)
                val exit = proc.waitFor()
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                runOnUiThread {
                    log(buildString {
                        appendLine("input tap 500 500 完成")
                        appendLine("exitCode = $exit")
                        if (out.isNotBlank()) appendLine("stdout: $out")
                        if (err.isNotBlank()) appendLine("stderr: $err")
                        appendLine()
                        appendLine("→ 开发者选项打开 '显示点按操作反馈',应能看到 (500, 500) 出现白点")
                    })
                }
            } catch (e: Exception) {
                runOnUiThread { log("失败: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }.start()
    }

    private fun startHeartbeat() {
        val url = findViewById<EditText>(R.id.editServerUrl).text.toString().trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            log("服务器 URL 不对,例如: http://your.server:8080/heartbeat")
            return
        }
        val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${(Build.SERIAL ?: "x").take(6)}"
            .replace(" ", "_")
        val intent = Intent(this, HeartbeatService::class.java).apply {
            putExtra(HeartbeatService.EXTRA_URL, url)
            putExtra(HeartbeatService.EXTRA_DEVICE_ID, deviceId)
        }
        startForegroundService(intent)
        log("心跳服务启动\n→ $url\n→ device_id=$deviceId\n锁屏后请保持运行")
    }

    private fun stopHeartbeat() {
        stopService(Intent(this, HeartbeatService::class.java))
        log("心跳服务已停止")
    }

    private fun showStatus() {
        val sb = StringBuilder()
        sb.appendLine("=== 当前状态 ===")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.append("Shizuku 权限: ")
        sb.appendLine(
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "已授权"
                else "未授权"
            } catch (e: Exception) {
                "Stellar/Shizuku 未运行"
            }
        )
        sb.appendLine()
        sb.appendLine("操作步骤:")
        sb.appendLine("1. 请求 Shizuku 权限(确保 Stellar 已启动)")
        sb.appendLine("2. 测试 input tap(看屏幕 (500,500) 是否被点)")
        sb.appendLine("3. 启动心跳(锁屏放 24h 测试保活)")
        log(sb.toString())
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val tv = findViewById<TextView>(R.id.tvStatus)
        tv.text = "[$ts]\n$msg"
    }
}
