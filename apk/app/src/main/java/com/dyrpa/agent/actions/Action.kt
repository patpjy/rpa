package com.dyrpa.agent.actions

import android.content.Context
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.workflow.ActionContext
import com.dyrpa.agent.workflow.Step

/** Each V9.1 action type has one implementation. */
interface Action {
    val type: String
    fun execute(step: Step, ctx: Context, mqtt: MqttClient, acx: ActionContext)
}

/**
 * Static action registry — covers all 17 V9.1 action types.
 *
 * Tier 1 (5): tap_xy, tap_text, tap_desc, press, wait
 * Tier 2 (6): tap_resource_id, input_text, swipe, screenshot, dump_hierarchy, collect_visible_text
 * Tier 3 (4): tap_image*, image_exists*, tap_xy_if_missing, tap_relative_to_element
 * Tier 4 (2): skip_if_not_exists, skip_if_exists
 *
 * * tap_image / image_exists are stub-only in MVP (no OpenCV). They throw loudly
 *   unless step.optional=true. Current douyin-dm.yaml uses neither.
 */
object ActionRegistry {
    private val actions: Map<String, Action> = listOf<Action>(
        // Tier 1
        TapXy(), TapText(), TapDesc(), Press(), Wait(),
        // Tier 2
        TapResourceId(), InputText(), Swipe(), Screenshot(), DumpHierarchy(), CollectVisibleText(),
        // Tier 3
        TapImage(), ImageExists(), TapXyIfMissing(), TapRelativeToElement(),
        // Tier 4
        SkipIfNotExists(), SkipIfExists(),
    ).associateBy { it.type }

    fun find(type: String): Action? = actions[type]

    fun knownTypes(): Set<String> = actions.keys
}
