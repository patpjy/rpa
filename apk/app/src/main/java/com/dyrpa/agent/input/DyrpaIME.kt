package com.dyrpa.agent.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Automation-only IME. No visible keyboard — its sole purpose is to receive
 *   am broadcast -a ADB_INPUT_TEXT --es msg "<text>"
 * and commit the text to whichever EditText currently has focus.
 *
 * One-time activation:
 *   adb shell ime enable com.dyrpa.agent/.input.DyrpaIME
 *   adb shell ime set    com.dyrpa.agent/.input.DyrpaIME
 *
 * Why this exists: Android 10's `input text "<chinese>"` NPE-crashes on multi-byte
 * UTF-8 (framework bug). uiautomator2's setText needs accessibility (hard-banned).
 * Self-hosted IME = both avoided, no third-party APK dependency.
 */
class DyrpaIME : InputMethodService() {

    companion object {
        const val ACTION = "ADB_INPUT_TEXT"
        const val EXTRA_MSG = "msg"
        const val EXTRA_CLEAR = "clear"
        private const val TAG = "DyrpaIME"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val conn = currentInputConnection ?: run {
                Log.w(TAG, "broadcast received but no focused input field")
                return
            }
            if (intent?.getBooleanExtra(EXTRA_CLEAR, false) == true) {
                conn.deleteSurroundingText(Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
            }
            val msg = intent?.getStringExtra(EXTRA_MSG)
            if (!msg.isNullOrEmpty()) {
                conn.commitText(msg, 1)
                Log.i(TAG, "committed ${msg.length} chars")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onCreateInputView(): View? = null
    override fun onEvaluateFullscreenMode(): Boolean = false
}
