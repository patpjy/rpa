package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.SkipCandidate
import com.dyrpa.agent.workflow.Step

/**
 * V9.1 skip_if_exists: if a forbidden element is on screen (e.g., "已关注" tag,
 * indicating this account was already DMed), throw SkipCandidate.
 */
class SkipIfExists : Action {
    override val type = "skip_if_exists"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val targetText = step.str("text")
        val targetDesc = step.str("desc")
        val template = step.str("template")
        val reason = step.str("reason", "forbidden element present")
        val timeoutMs = (step.double("timeout", 1.0) * 1000).toLong()

        if (template.isNotEmpty()) {
            mqtt.publishLog("[skip_if_exists] template '$template' check not supported in MVP, falling back to text/desc", level = "warn")
        }

        val found = (targetText.isNotEmpty() && UiTreeFinder.findByText(targetText, timeoutMs) != null) ||
                (targetDesc.isNotEmpty() && UiTreeFinder.findByDesc(targetDesc, timeoutMs) != null)
        if (found) {
            mqtt.publishLog("[skip_if_exists] $reason", level = "warn")
            throw SkipCandidate(reason)
        }
    }
}
