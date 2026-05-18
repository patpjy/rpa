package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/**
 * V9.1 tap_relative_to_element: find a UI anchor (text/content-desc), then
 * tap at offset (dx, dy) from its center. Used on screens that are almost
 * entirely SurfaceView (Douyin video page) where only one anchor element
 * is reliably present in the UI tree.
 */
class TapRelativeToElement : Action {
    override val type = "tap_relative_to_element"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val anchorText = step.str("anchor_text")
        val anchorDesc = step.str("anchor_desc")
        val dx = step.int("dx", 0)
        val dy = step.int("dy", 0)
        val timeoutMs = (step.double("timeout", 2.0) * 1000).toLong()

        val center = when {
            anchorText.isNotEmpty() -> UiTreeFinder.findByText(anchorText, timeoutMs, acx.stopCheck)
            anchorDesc.isNotEmpty() -> UiTreeFinder.findByDesc(anchorDesc, timeoutMs, acx.stopCheck)
            else -> throw RuntimeException("tap_relative_to_element: anchor_text or anchor_desc required")
        } ?: throw RuntimeException("tap_relative_to_element: anchor not found: ${anchorText.ifEmpty { anchorDesc }}")

        val tx = center.first + dx
        val ty = center.second + dy
        val r = ShellExecutor.inputTap(tx, ty)
        if (!r.ok) throw RuntimeException("tap_relative_to_element tap failed: ${r.stderr}")
        mqtt.publishLog("[tap_relative_to_element] anchor (${center.first},${center.second}) + ($dx,$dy) → ($tx,$ty)")
    }
}
