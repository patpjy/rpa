package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class Press : Action {
    override val type = "press"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val key = when (step.str("key").lowercase()) {
            "back" -> "KEYCODE_BACK"
            "home" -> "KEYCODE_HOME"
            "enter" -> "KEYCODE_ENTER"
            "menu" -> "KEYCODE_MENU"
            else -> step.str("key")  // direct keycode passthrough
        }
        if (key.isEmpty()) throw RuntimeException("press: empty key")
        val r = ShellExecutor.keyEvent(key)
        if (!r.ok) throw RuntimeException("press failed: ${r.stderr}")
        mqtt.publishLog("[press] $key")
    }
}
