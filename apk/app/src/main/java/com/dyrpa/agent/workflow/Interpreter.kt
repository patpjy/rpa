package com.dyrpa.agent.workflow

import android.content.Context
import android.util.Log
import com.dyrpa.agent.actions.ActionRegistry
import com.dyrpa.agent.mqtt.MqttClient
import com.dyrpa.agent.shizuku.ShellExecutor
import com.dyrpa.agent.util.ServerUploader
import org.json.JSONObject

/**
 * Executes a V9.1-shaped workflow on the device side. Mirrors rpa_mvp.execute_steps
 * + run_workflow loop (including per-candidate failure capture + back×3 + swipe recovery).
 */
class Interpreter(
    private val ctx: Context,
    val mqtt: MqttClient,
    private val brokerUrl: String,
) {
    companion object { private const val TAG = "Interpreter" }

    @Volatile var stopRequested: Boolean = false

    fun execute(taskJson: String) {
        stopRequested = false
        val task = TaskMessage.parse(taskJson)
        mqtt.publishLog("[task] id=${task.taskId} workflow=${task.workflowId}", taskId = task.taskId)

        if (task.kind == "inspect") {
            runInspect(task)
            return
        }
        runWorkflow(task)
    }

    private fun runInspect(task: TaskMessage) {
        val dump = ShellExecutor.uiDump()
        if (dump.ok) {
            mqtt.publishLog("[inspect] UI dump ${dump.stdout.length} bytes", taskId = task.taskId)
            ServerUploader.screencapAndUpload(brokerUrl, mqtt.deviceId, "inspect")
            mqtt.publishEvent("task_done", task.taskId, mapOf("ui_size" to dump.stdout.length))
        } else {
            mqtt.publishEvent("task_failed", task.taskId, mapOf("error" to dump.stderr))
        }
    }

    private fun runWorkflow(task: TaskMessage) {
        val wf = task.workflow
        val workflow = wf.optJSONObject("workflow") ?: run {
            mqtt.publishEvent("task_failed", task.taskId, mapOf("error" to "no workflow{} in payload"))
            return
        }
        val drafts = wf.optJSONObject("drafts") ?: JSONObject()
        val pkg = wf.optJSONObject("app")?.optString("package").orEmpty()
        val maxItems = workflow.optInt("max_items", 1).coerceAtLeast(1)
        val openSteps = wf.stepsAt("open_search")
        val submitSteps = wf.stepsAt("submit_search")
        val perItemSteps = wf.stepsAt("per_item")
        val total = openSteps.size + submitSteps.size + perItemSteps.size * maxItems

        // Progress bar in dashboard is candidate-based (X / max_items), not step-based.
        // step_complete events still flow through the log panel for debugging granularity,
        // but `total` here drives the progress denominator → must be max_items.
        mqtt.publishEvent("task_started", task.taskId, mapOf("total" to maxItems, "max_items" to maxItems))

        if (pkg.isNotEmpty()) {
            ShellExecutor.amForceStop(pkg)
            interruptibleSleep(800)
            ShellExecutor.amStart(pkg)
            interruptibleSleep(3500)
        }

        val acx = ActionContext(workflow, drafts, brokerUrl, mqtt.deviceId) { stopRequested }
        var idx = 0
        try {
            idx = runSection(task.taskId, openSteps, idx, total, acx, "open_search")
            idx = runSection(task.taskId, submitSteps, idx, total, acx, "submit_search")
            var completed = 0
            for (i in 1..maxItems) {
                if (stopRequested) break
                mqtt.publishLog("[per_item] iteration $i/$maxItems", taskId = task.taskId)
                try {
                    idx = runSection(task.taskId, perItemSteps, idx, total, acx, "per_item_$i")
                    completed++
                    // Drives the dashboard progress bar (numerator).
                    mqtt.publishEvent(
                        "candidate_complete",
                        task.taskId,
                        mapOf("completed" to completed, "total" to maxItems),
                    )
                } catch (e: SkipCandidate) {
                    mqtt.publishLog("[per_item] skip: ${e.message}", level = "warn", taskId = task.taskId)
                    recover()
                    idx += perItemSteps.size
                } catch (e: Exception) {
                    // V9.1 behaviour: capture failure scene (screen + UI tree) then recover & continue
                    mqtt.publishLog("[per_item] candidate $i failed: ${e.message}", level = "error", taskId = task.taskId)
                    captureFailure("per_item_$i")
                    recover()
                    idx += perItemSteps.size
                }
            }
            mqtt.publishEvent("task_done", task.taskId, mapOf("completed" to completed, "attempted" to maxItems))
        } catch (e: InterruptedException) {
            mqtt.publishEvent("task_failed", task.taskId, mapOf("error" to "stopped"))
        } catch (e: WorkflowStepException) {
            // Rich failure: forensics already captured inside runSection. Surface step context to server.
            Log.e(TAG, "workflow failed at ${e.section}/${e.stepType}#${e.stepIndex}", e)
            mqtt.publishEvent(
                "task_failed",
                task.taskId,
                mapOf(
                    "error" to (e.cause?.message ?: e.message ?: "unknown"),
                    "section" to e.section,
                    "step_index" to e.stepIndex,
                    "step_type" to e.stepType,
                    "forensics" to e.forensicsLabel,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "workflow failed", e)
            mqtt.publishEvent("task_failed", task.taskId, mapOf("error" to (e.message ?: "unknown")))
        }
    }

    private fun recover() {
        repeat(3) {
            ShellExecutor.keyEvent("KEYCODE_BACK")
            interruptibleSleep(300)
        }
        // Swipe up to next video (mirror V9.1 0.5,0.78 → 0.5,0.22)
        val (w, h) = com.dyrpa.agent.util.ScreenSize.get(ctx)
        ShellExecutor.inputSwipe((w * 0.5).toInt(), (h * 0.78).toInt(), (w * 0.5).toInt(), (h * 0.22).toInt(), 400)
        interruptibleSleep(1500)
    }

    /** Sleep that bails out within ~200ms if stopRequested is set. */
    private fun interruptibleSleep(totalMs: Long) {
        var slept = 0L
        while (slept < totalMs) {
            if (stopRequested) throw InterruptedException("stopped")
            val left = (totalMs - slept).coerceAtMost(200L)
            Thread.sleep(left)
            slept += left
        }
    }

    /** UI tree only (cheap). Used on every action fail so we can diagnose element-not-found root cause. */
    private fun captureUiXmlOnly(label: String) {
        try {
            val dump = ShellExecutor.uiDump()
            if (dump.ok && dump.stdout.isNotEmpty()) {
                val ok = ServerUploader.uploadUiXml(brokerUrl, mqtt.deviceId, label, dump.stdout)
                if (ok) mqtt.publishLog("[ui_dump] uploaded ${dump.stdout.length}B as $label")
                else mqtt.publishLog("[ui_dump] upload failed for $label", level = "warn")
            } else {
                // dump.stderr is now visible (uiDump no longer suppresses it)
                mqtt.publishLog(
                    "[ui_dump] uiautomator dump failed: exit=${dump.exitCode} stderr='${dump.stderr.take(300)}'",
                    level = "warn",
                )
            }
        } catch (e: Exception) {
            mqtt.publishLog("[ui_dump] $label exception: ${e.message}", level = "warn")
        }
    }

    /** Mirror V9.1 _fail_*_screen.png + _fail_*_ui.xml — upload screenshot + UI tree to server. */
    private fun captureFailure(label: String) {
        try {
            mqtt.publishLog("[forensics] capturing $label …", level = "warn")
            val shotOk = ServerUploader.screencapAndUpload(brokerUrl, mqtt.deviceId, label)
            if (!shotOk) {
                val reason = ServerUploader.lastFailureReason ?: "unknown"
                mqtt.publishLog("[forensics] screencap upload failed: $reason", level = "warn")
            }
            captureUiXmlOnly(label)  // unified UI XML path
        } catch (e: Exception) {
            mqtt.publishLog("[forensics] $label failed: ${e.message}", level = "warn")
        }
    }

    private fun runSection(
        taskId: String,
        steps: List<Step>,
        startIdx: Int,
        total: Int,
        acx: ActionContext,
        sectionName: String,
    ): Int {
        var i = startIdx
        for (step in steps) {
            if (stopRequested) throw InterruptedException("stopped")
            i++
            // Heartbeat: if a step runs longer than 5s (e.g. a hung shell call
            // or slow uiautomator dump), emit step_progress so dashboard can
            // tell "still working on step N" vs "actually frozen". Cancelled
            // immediately when the step completes or throws.
            val stepStartMs = System.currentTimeMillis()
            val stepIndex = i
            val stepType = step.type
            val progressTimer = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(5_000)
                        if (Thread.currentThread().isInterrupted) return@Thread
                        val elapsed = System.currentTimeMillis() - stepStartMs
                        mqtt.publishEvent(
                            "step_progress",
                            taskId,
                            mapOf(
                                "step_index" to stepIndex,
                                "step_type" to stepType,
                                "section" to sectionName,
                                "elapsed_ms" to elapsed,
                            ),
                        )
                    }
                } catch (_: InterruptedException) { /* expected on step completion */ }
            }.apply { isDaemon = true; name = "step_progress-$stepIndex" }
            progressTimer.start()
            try {
                val action = ActionRegistry.find(step.type)
                if (action == null) {
                    val msg = "unknown action: ${step.type}"
                    if (step.optional) mqtt.publishLog("[skip optional] $msg", level = "warn", taskId = taskId)
                    else throw IllegalStateException(msg)
                } else {
                    action.execute(step, ctx, mqtt, acx)
                }
                mqtt.publishEvent(
                    "step_complete",
                    taskId,
                    mapOf("current" to i, "total" to total, "type" to step.type),
                )
            } catch (e: SkipCandidate) {
                progressTimer.interrupt()
                throw e
            } catch (e: Exception) {
                progressTimer.interrupt()
                // Always capture UI XML on action failure (cheap, ~50KB) so we can
                // confirm whether the element actually existed at this moment.
                // Cheaper than screenshot — fires even for `optional: true` actions.
                val baseLabel = "${sectionName}_step${i}_${step.type}"
                captureUiXmlOnly("uixml_$baseLabel")
                if (step.optional) {
                    mqtt.publishLog("[optional fail] ${step.type}: ${e.message}", level = "warn", taskId = taskId)
                } else {
                    // Non-optional fail also gets full forensics (screenshot + UI XML again with screencap context).
                    val label = "fail_$baseLabel"
                    captureFailure(label)
                    throw WorkflowStepException(sectionName, i, step.type, label, e)
                }
            }
            // Success or `optional fail` fall-through: stop the heartbeat now
            // that we're moving to the next step.
            progressTimer.interrupt()
        }
        return i
    }
}

class SkipCandidate(msg: String) : RuntimeException(msg)

/** Carries section/step context so server-side task_failed event can pinpoint the bug location. */
class WorkflowStepException(
    val section: String,
    val stepIndex: Int,
    val stepType: String,
    val forensicsLabel: String,
    cause: Throwable,
) : RuntimeException("[$section] step $stepIndex ($stepType) failed: ${cause.message}", cause)

/**
 * Bag of context each action needs:
 *  - workflow / drafts: V9.1 workflow.* and drafts.* dicts for value_from resolution
 *  - brokerUrl: derive server HTTP URL for screenshot upload
 *  - deviceId: identify this device in the upload URL path
 */
data class ActionContext(
    val workflow: JSONObject,
    val drafts: JSONObject,
    val brokerUrl: String,
    val deviceId: String,
    // Lets long-running actions (notably wait) poll for stop without
    // holding a reference to the Interpreter. Defaults to never-stop for
    // legacy/test callers.
    val stopCheck: () -> Boolean = { false },
)
