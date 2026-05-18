package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class Wait : Action {
    override val type = "wait"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val seconds = step.double("seconds", step.double("duration", 0.5))
        val totalMs = (seconds * 1000).toLong()
        val chunk = 200L
        var slept = 0L
        while (slept < totalMs) {
            // Without this poll the workflow ignores stop for the full sleep
            // (e.g. up to 3.5s for the inter-step warmups in douyin-dm.yaml).
            if (acx.stopCheck()) throw InterruptedException("stopped")
            val left = (totalMs - slept).coerceAtMost(chunk)
            Thread.sleep(left)
            slept += left
        }
        mqtt.publishLog("[wait] ${seconds}s")
    }
}
