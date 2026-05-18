package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class TapText : Action {
    override val type = "tap_text"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val text = step.resolveValue(acx.workflow, acx.drafts)
        if (text.isEmpty()) throw RuntimeException("tap_text: empty value")
        val timeoutMs = (step.double("timeout", 8.0) * 1000).toLong()
        val center = UiTreeFinder.findByText(text, timeoutMs, acx.stopCheck)
            ?: throw RuntimeException("tap_text: element not found: $text")
        val r = ShellExecutor.inputTap(center.first, center.second)
        if (!r.ok) throw RuntimeException("tap_text failed: ${r.stderr}")
        mqtt.publishLog("[tap_text] '$text' @ (${center.first}, ${center.second})")
    }
}
