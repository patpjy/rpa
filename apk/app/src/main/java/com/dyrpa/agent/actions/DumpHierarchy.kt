package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class DumpHierarchy : Action {
    override val type = "dump_hierarchy"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val r = ShellExecutor.uiDump()
        if (!r.ok) {
            if (step.optional) { mqtt.publishLog("[dump_hierarchy] failed (optional): ${r.stderr}", level = "warn"); return }
            throw RuntimeException("dump_hierarchy failed: ${r.stderr}")
        }
        mqtt.publishLog("[dump_hierarchy] ${r.stdout.length} bytes")
    }
}
