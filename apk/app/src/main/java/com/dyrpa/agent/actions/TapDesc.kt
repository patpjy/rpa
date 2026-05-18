package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class TapDesc : Action {
    override val type = "tap_desc"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val desc = step.resolveValue(acx.workflow, acx.drafts)
        if (desc.isEmpty()) throw RuntimeException("tap_desc: empty value")
        // Default 8s — `uiautomator dump` is ~2.5s/call (forks a JVM each time),
        // so 3s only allows ~1 retry. V9.1's uiautomator2 was ~300ms so 3s gave it
        // ~7 retries. Scale up to match the slower shell-fork dump cadence.
        val timeoutMs = (step.double("timeout", 8.0) * 1000).toLong()
        val center = UiTreeFinder.findByDesc(desc, timeoutMs)
            ?: throw RuntimeException("tap_desc: element not found: $desc")
        val r = ShellExecutor.inputTap(center.first, center.second)
        if (!r.ok) throw RuntimeException("tap_desc failed: ${r.stderr}")
        mqtt.publishLog("[tap_desc] '$desc' @ (${center.first}, ${center.second})")
    }
}
