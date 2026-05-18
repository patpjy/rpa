package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.util.UiTreeFinder
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.SkipCandidate
import com.dyrpa.agent.workflow.Step

/**
 * V9.1 skip_if_not_exists: if neither text nor desc anchor is currently on screen,
 * throw SkipCandidate (Interpreter recovers with back×3 + swipe to next video).
 *
 * Template-based check (image existence) is NOT supported in MVP — `template:` field
 * is accepted but logged as "not yet implemented", falls through to text/desc check.
 */
class SkipIfNotExists : Action {
    override val type = "skip_if_not_exists"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val targetText = step.str("text")
        val targetDesc = step.str("desc")
        val template = step.str("template")
        val reason = step.str("reason", "required element missing")
        val timeoutMs = (step.double("timeout", 1.5) * 1000).toLong()

        if (template.isNotEmpty()) {
            mqtt.publishLog("[skip_if_not_exists] template '$template' check not supported in MVP, falling back to text/desc", level = "warn")
        }

        val found = (targetText.isNotEmpty() && UiTreeFinder.findByText(targetText, timeoutMs) != null) ||
                (targetDesc.isNotEmpty() && UiTreeFinder.findByDesc(targetDesc, timeoutMs) != null)
        if (!found) {
            mqtt.publishLog("[skip_if_not_exists] $reason", level = "warn")
            throw SkipCandidate(reason)
        }
        mqtt.publishLog("[skip_if_not_exists] check ok: ${targetText.ifEmpty { targetDesc }} present")
    }
}
