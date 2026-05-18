package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.ScreenSize
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class TapXy : Action {
    override val type = "tap_xy"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val rx = step.double("x", 0.5)
        val ry = step.double("y", 0.5)
        val (w, h) = ScreenSize.get(ctx)
        val x = (rx * w).toInt()
        val y = (ry * h).toInt()
        val r = ShellExecutor.inputTap(x, y)
        if (!r.ok) throw RuntimeException("tap_xy failed: ${r.stderr}")
        mqtt.publishLog("[tap_xy] ($rx, $ry) → ($x, $y)")
    }
}
