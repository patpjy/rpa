package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/**
 * V9.1 image_exists — OpenCV multi-scale template match (no tap).
 *
 * Same MVP status as TapImage.kt: not implemented (no OpenCV in build). When
 * called as a sub-check inside skip_if_*, those callers fall back to
 * text/desc anchors. Standalone use raises a loud error unless optional.
 */
class ImageExists : Action {
    override val type = "image_exists"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val template = step.str("template")
        val msg = "[image_exists] '$template' NOT IMPLEMENTED in MVP (no OpenCV) — see ImageExists.kt"
        if (step.optional) {
            mqtt.publishLog(msg + " (optional, skipped)", level = "warn")
            return
        }
        mqtt.publishLog(msg, level = "error")
        throw RuntimeException(msg)
    }
}
