package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/**
 * Mirrors V9.1 extract_visible_text(): scans uiautomator dump for text /
 * content-desc / resource-id attribute values and reports the distinct list
 * via MQTT log. Useful for debugging "why didn't tap_text find X" remotely.
 */
class CollectVisibleText : Action {
    override val type = "collect_visible_text"
    private val ATTR_RE = Regex("""(text|content-desc|resource-id)="([^"]*)"""")

    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val r = ShellExecutor.uiDump()
        if (!r.ok) {
            if (step.optional) { mqtt.publishLog("[collect_visible_text] dump failed (optional)", level = "warn"); return }
            throw RuntimeException("collect_visible_text: dump failed: ${r.stderr}")
        }
        val seen = LinkedHashSet<String>()
        for (m in ATTR_RE.findAll(r.stdout)) {
            val v = m.groupValues[2].trim()
            if (v.isNotEmpty()) seen.add(v)
        }
        val preview = seen.take(40).joinToString(" | ")
        mqtt.publishLog("[collect_visible_text] ${seen.size} items: $preview")
    }
}
