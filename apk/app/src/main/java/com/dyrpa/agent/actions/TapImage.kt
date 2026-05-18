package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/**
 * V9.1 tap_image — OpenCV multi-scale template matching + tap.
 *
 * MVP status: NOT IMPLEMENTED. OpenCV Android adds ~40 MB to APK; current
 * douyin-dm.yaml does not use image actions (all anchoring via text /
 * content-desc / resource-id). If you need image matching, two upgrade paths:
 *   1. Add `org.opencv:opencv:4.10.0` dep + ABI split (arm64 only ~12 MB)
 *      and implement TM_CCOEFF_NORMED multi-scale match (mirror V9.1 device.tap_image)
 *   2. Push image match to server side: APK uploads screencap, server runs cv2,
 *      returns coordinates via MQTT, APK taps. ~1-2s extra latency but no APK bloat.
 *
 * Marked optional in yaml so it gracefully skips; non-optional usage will error loudly.
 */
class TapImage : Action {
    override val type = "tap_image"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val template = step.str("template")
        val msg = "[tap_image] '$template' NOT IMPLEMENTED in MVP (no OpenCV) — see TapImage.kt"
        if (step.optional) {
            mqtt.publishLog(msg + " (optional, skipped)", level = "warn")
            return
        }
        mqtt.publishLog(msg, level = "error")
        throw RuntimeException(msg)
    }
}
