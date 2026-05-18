package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class TapResourceId : Action {
    override val type = "tap_resource_id"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val rid = step.resolveValue(acx.workflow, acx.drafts)
        if (rid.isEmpty()) throw RuntimeException("tap_resource_id: empty value")
        val timeoutMs = (step.double("timeout", 8.0) * 1000).toLong()
        val center = UiTreeFinder.findByResourceId(rid, timeoutMs)
            ?: throw RuntimeException("tap_resource_id: element not found: $rid")
        val r = ShellExecutor.inputTap(center.first, center.second)
        if (!r.ok) throw RuntimeException("tap_resource_id failed: ${r.stderr}")
        mqtt.publishLog("[tap_resource_id] '$rid' @ (${center.first}, ${center.second})")
    }
}
