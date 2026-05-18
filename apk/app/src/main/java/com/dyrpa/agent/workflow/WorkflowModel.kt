package com.dyrpa.agent.workflow

import org.json.JSONArray
import org.json.JSONObject

/** Server → device task message (see server/app/main.py:api_run_workflow). */
data class TaskMessage(
    val taskId: String,
    val kind: String,           // "workflow" | "inspect"
    val workflowId: String,
    val workflow: JSONObject,   // full V9.1 workflow YAML dict
    val overrides: JSONObject,
) {
    companion object {
        fun parse(json: String): TaskMessage {
            val o = JSONObject(json)
            return TaskMessage(
                taskId = o.optString("task_id"),
                kind = o.optString("kind", "workflow"),
                workflowId = o.optString("workflow_id"),
                workflow = o.optJSONObject("workflow_yaml") ?: JSONObject(),
                overrides = o.optJSONObject("overrides") ?: JSONObject(),
            )
        }
    }
}

/**
 * A single V9.1 workflow step.
 *
 * V9.1 yaml uses `action:` as the step type name (matches rpa_mvp.execute_steps).
 * Field names per V9.1 schema:
 *   - tap_text / tap_desc / tap_resource_id / input_text → `value:` or `value_from:`
 *   - tap_xy → `x:`, `y:` (screen fraction)
 *   - swipe → `start: [x,y]`, `end: [x,y]`, `duration:`
 *   - wait → `seconds:`
 *   - press → `key:`
 *   - screenshot / collect_visible_text → `name:`
 *   - tap_image / image_exists → `template:`, `threshold:`, `offset_jitter:`
 *   - tap_xy_if_missing → `text:` or `desc:` + `x:`, `y:`
 *   - tap_relative_to_element → `anchor_text:` or `anchor_desc:`, `dx:`, `dy:`
 *   - skip_if_* → `text:`, `desc:`, `template:`, `threshold:`, `reason:`
 */
class Step(val raw: JSONObject) {
    val type: String get() = raw.optString("action", "")
    val optional: Boolean get() = raw.optBoolean("optional", false)
    val clickable: Boolean get() = raw.optBoolean("clickable", false)

    fun str(key: String, default: String = ""): String = raw.optString(key, default)
    fun double(key: String, default: Double = 0.0): Double = raw.optDouble(key, default)
    fun int(key: String, default: Int = 0): Int = raw.optInt(key, default)
    fun bool(key: String, default: Boolean = false): Boolean = raw.optBoolean(key, default)
    fun has(key: String): Boolean = raw.has(key)
    fun array(key: String): JSONArray? = raw.optJSONArray(key)

    /**
     * Resolve a value-or-reference. Mirrors rpa_mvp.resolve_value():
     *   - direct `value:` literal returns as-is
     *   - `value_from: keyword` → workflow.keyword
     *   - `value_from: dm_template` → drafts.dm_template with `{keyword}` substituted
     */
    fun resolveValue(ctxWorkflow: JSONObject, ctxDrafts: JSONObject, default: String = ""): String {
        if (raw.has("value")) return raw.optString("value", default)
        val refKey = raw.optString("value_from", "")
        if (refKey.isEmpty()) return default
        if (refKey == "keyword") return ctxWorkflow.optString("keyword", default)
        if (refKey == "dm_template") {
            val template = ctxDrafts.optString("dm_template", default)
            val keyword = ctxWorkflow.optString("keyword", "")
            return template.replace("{keyword}", keyword)
        }
        return ctxWorkflow.optString(refKey, ctxDrafts.optString(refKey, default))
    }
}

/** Extract step list from a workflow section path (open_search / submit_search / per_item). */
fun JSONObject.stepsAt(path: String): List<Step> {
    val node = optJSONObject("workflow") ?: return emptyList()
    val arr: JSONArray = node.optJSONArray(path) ?: return emptyList()
    return (0 until arr.length()).map { Step(arr.getJSONObject(it)) }
}
