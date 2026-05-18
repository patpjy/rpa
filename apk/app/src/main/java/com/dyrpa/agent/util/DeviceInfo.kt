package com.dyrpa.agent.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.dyrpa.agent.shizuku.ShellExecutor

object DeviceInfo {
    fun collect(ctx: Context): Map<String, Any?> {
        val battery = try {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0) (level * 100 / scale) else 0
        } catch (_: Exception) { 0 }

        val ip = try {
            ShellExecutor.run("ip route get 1.1.1.1 2>/dev/null | awk '{print \$7; exit}'").stdout.trim()
        } catch (_: Exception) { "" }

        return mapOf(
            "name" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android" to Build.VERSION.RELEASE,
            "battery" to battery,
            "ip" to ip,
        )
    }
}
