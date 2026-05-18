package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.util.ServerUploader
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/**
 * Takes a screenshot via `screencap -p`, reads it back via Shizuku cat,
 * and HTTP-uploads to the server's POST /api/devices/{id}/screenshots.
 *
 * V9.1 saves PNG to local outputs/run_TS folder; here it's pushed to server so
 * the Web UI can render the latest frame + history of failure shots.
 */
class Screenshot : Action {
    override val type = "screenshot"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        // Screenshot is auxiliary evidence — failure must never kill the workflow.
        // We always log + continue; the real workflow state is the action chain (taps/inputs).
        val name = step.str("name", "screen")
        val ok = ServerUploader.screencapAndUpload(acx.brokerUrl, acx.deviceId, name)
        if (ok) {
            mqtt.publishLog("[screenshot] uploaded: $name")
        } else {
            val reason = ServerUploader.lastFailureReason ?: "unknown"
            mqtt.publishLog("[screenshot] failed (continuing): $name | $reason", level = "warn")
        }
    }
}
