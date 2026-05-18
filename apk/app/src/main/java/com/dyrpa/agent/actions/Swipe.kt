package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.ScreenSize
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class Swipe : Action {
    override val type = "swipe"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val start = step.array("start") ?: throw RuntimeException("swipe: missing start")
        val end = step.array("end") ?: throw RuntimeException("swipe: missing end")
        if (start.length() < 2 || end.length() < 2) throw RuntimeException("swipe: malformed start/end")
        val (w, h) = ScreenSize.get(ctx)
        val x1 = (start.getDouble(0) * w).toInt()
        val y1 = (start.getDouble(1) * h).toInt()
        val x2 = (end.getDouble(0) * w).toInt()
        val y2 = (end.getDouble(1) * h).toInt()
        val ms = (step.double("duration", 0.4) * 1000).toInt()
        val r = ShellExecutor.inputSwipe(x1, y1, x2, y2, ms)
        if (!r.ok) throw RuntimeException("swipe failed: ${r.stderr}")
        mqtt.publishLog("[swipe] ($x1,$y1) → ($x2,$y2) ${ms}ms")
    }
}
