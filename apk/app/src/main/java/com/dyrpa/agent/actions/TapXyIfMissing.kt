package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.ScreenSize
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/**
 * V9.1 tap_xy_if_missing: if neither `text` nor `desc` element is currently visible,
 * tap the given (x, y) — used as a fallback path (e.g., "if no 发私信 button, tap
 * the avatar to go to profile first").
 */
class TapXyIfMissing : Action {
    override val type = "tap_xy_if_missing"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val targetText = step.str("text")
        val targetDesc = step.str("desc")
        val timeoutMs = (step.double("timeout", 1.0) * 1000).toLong()
        val found = (targetText.isNotEmpty() && UiTreeFinder.findByText(targetText, timeoutMs, acx.stopCheck) != null) ||
                (targetDesc.isNotEmpty() && UiTreeFinder.findByDesc(targetDesc, timeoutMs, acx.stopCheck) != null)
        if (found) {
            mqtt.publishLog("[tap_xy_if_missing] '${targetText.ifEmpty { targetDesc }}' present → skip fallback")
            return
        }
        val rx = step.double("x", 0.5)
        val ry = step.double("y", 0.5)
        val (w, h) = ScreenSize.get(ctx)
        val x = (rx * w).toInt()
        val y = (ry * h).toInt()
        val r = ShellExecutor.inputTap(x, y)
        if (!r.ok) throw RuntimeException("tap_xy_if_missing tap failed: ${r.stderr}")
        mqtt.publishLog("[tap_xy_if_missing] target missing → tap ($x, $y)")
    }
}
