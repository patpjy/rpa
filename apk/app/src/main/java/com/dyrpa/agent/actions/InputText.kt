package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

class InputText : Action {
    override val type = "input_text"
    override fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext) {
        val text = step.resolveValue(acx.workflow, acx.drafts)
        if (text.isEmpty()) throw RuntimeException("input_text: empty value")

        // DyrpaIME only receives ADB_INPUT_TEXT while it's the active IME.
        // Save the user's IME, switch, send, restore — keeps the phone usable
        // for manual typing (Huawei LatinIME etc.) between workflow runs.
        val saved = ShellExecutor.run("settings get secure default_input_method").stdout.trim()
        val needsSwitch = saved != DYRPA_IME && saved.isNotEmpty() && saved != "null"
        try {
            if (needsSwitch) {
                ShellExecutor.run("ime set $DYRPA_IME")
                // 500ms was too short on HONOR/MagicOS — the system reports IME set
                // but the focused EditText hasn't re-bound to DyrpaIME's InputConnection
                // yet, so the broadcast lands on a stale connection (commitText no-op).
                // 1500ms tested working with manual `ime set + sleep 1; am broadcast`.
                Thread.sleep(1500)
                val cur = ShellExecutor.run("settings get secure default_input_method").stdout.trim()
                if (cur != DYRPA_IME) {
                    mqtt.publishLog("[input_text] IME switch failed: still '$cur'", level = "warn")
                }
            }
            val r = ShellExecutor.inputText(text)
            if (!r.ok) throw RuntimeException("input_text failed: ${r.stderr}")
            mqtt.publishLog("[input_text] '${text.take(40)}${if (text.length > 40) "…" else ""}'")
        } finally {
            if (needsSwitch) {
                ShellExecutor.run("ime set $saved")
            }
        }
    }

    companion object {
        private const val DYRPA_IME = "com.dyrpa.agent/.input.DyrpaIME"
    }
}
