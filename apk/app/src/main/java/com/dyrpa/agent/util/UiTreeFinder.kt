package com.dyrpa.agent.util

import com.dyrpa.agent.shizuku.ShellExecutor

/**
 * Reads UI tree via `uiautomator dump` (shell, no AccessibilityService needed)
 * and finds the center point of the first node matching attr=value.
 *
 * Used by tap_text / tap_desc / tap_resource_id (Phase 3 adds resource-id).
 */
object UiTreeFinder {

    private val BOUNDS_RE = Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")

    fun findByText(text: String, timeoutMs: Long): Pair<Int, Int>? =
        findBy("text", text, timeoutMs)

    fun findByDesc(desc: String, timeoutMs: Long): Pair<Int, Int>? =
        findBy("content-desc", desc, timeoutMs)

    fun findByResourceId(rid: String, timeoutMs: Long): Pair<Int, Int>? =
        findBy("resource-id", rid, timeoutMs)

    private fun findBy(attr: String, value: String, timeoutMs: Long): Pair<Int, Int>? {
        val start = System.currentTimeMillis()
        while (true) {
            val xml = ShellExecutor.uiDump().takeIf { it.ok }?.stdout.orEmpty()
            val center = parseCenter(xml, attr, value)
            if (center != null) return center
            if (System.currentTimeMillis() - start > timeoutMs) return null
            Thread.sleep(300)
        }
    }

    /** Locate `attr="value"` and parse the enclosing <node ... bounds="[..][..]" />. */
    private fun parseCenter(xml: String, attr: String, value: String): Pair<Int, Int>? {
        if (xml.isEmpty()) return null
        val needle = """$attr="$value""""
        var cursor = 0
        while (true) {
            val hit = xml.indexOf(needle, cursor)
            if (hit < 0) return null
            // Find enclosing <node ... /> tag boundaries around `hit`.
            val nodeStart = xml.lastIndexOf("<node ", hit)
            val nodeEnd = xml.indexOf("/>", hit)
            if (nodeStart >= 0 && nodeEnd > nodeStart) {
                val seg = xml.substring(nodeStart, nodeEnd)
                val m = BOUNDS_RE.find(seg)
                if (m != null) {
                    val (x1, y1, x2, y2) = m.destructured
                    return ((x1.toInt() + x2.toInt()) / 2) to ((y1.toInt() + y2.toInt()) / 2)
                }
            }
            cursor = hit + needle.length
        }
    }
}
