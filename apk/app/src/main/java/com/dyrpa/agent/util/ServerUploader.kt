package com.dyrpa.agent.util

import android.util.Log
import com.dyrpa.agent.shizuku.ShellExecutor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Uploads screenshots / UI XML from the device to the server's HTTP endpoint.
 * Server URL is derived from broker URL (same host, port 8000 / scheme http).
 *
 * Endpoint contract (see server/app/main.py:api_upload_screenshot):
 *   POST {base}/api/devices/{device_id}/screenshots  multipart file=...
 */
object ServerUploader {
    private const val TAG = "ServerUploader"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Convert `tcp://host:1883` → `http://host:8000` (Phase 1 default colocation). */
    fun httpBaseFromBroker(brokerUrl: String): String {
        val noScheme = brokerUrl.removePrefix("tcp://").removePrefix("ssl://")
        val host = noScheme.substringBefore(":").substringBefore("/")
        return "http://$host:8000"
    }

    /** Read a file from the device via shell (Shizuku) — needed because /sdcard/ is sandboxed for the APK. */
    fun readShellFile(path: String): ByteArray? = ShellExecutor.runBinary("cat $path")

    /** Last failure reason for the most recent screencapAndUpload — surfaced via mqtt log. */
    @Volatile var lastFailureReason: String? = null
        private set

    fun uploadScreenshot(brokerUrl: String, deviceId: String, filename: String, bytes: ByteArray): Boolean {
        val base = httpBaseFromBroker(brokerUrl)
        val url = "$base/api/devices/$deviceId/screenshots"
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    filename,
                    bytes.toRequestBody("image/png".toMediaType()),
                )
                .build()
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastFailureReason = "HTTP ${resp.code} from $url"
                    Log.w(TAG, "upload $url -> HTTP ${resp.code}")
                    return@use false
                }
                true
            }
        } catch (e: Exception) {
            lastFailureReason = "HTTP exception: ${e.javaClass.simpleName} ${e.message}"
            Log.w(TAG, "upload failed", e)
            false
        }
    }

    /**
     * Upload a raw UI XML dump to the server for forensic replay of element-not-found
     * failures. Endpoint: POST /api/devices/{id}/ui-dumps?label=<context>
     */
    fun uploadUiXml(brokerUrl: String, deviceId: String, label: String, xml: String): Boolean {
        if (xml.isEmpty()) return false
        val base = httpBaseFromBroker(brokerUrl)
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val url = "$base/api/devices/$deviceId/ui-dumps?label=$safeLabel"
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "$safeLabel.xml",
                    xml.toByteArray(Charsets.UTF_8).toRequestBody("application/xml".toMediaType()),
                )
                .build()
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "ui-dump upload $url -> HTTP ${resp.code}")
                    return@use false
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadUiXml failed", e)
            false
        }
    }

    fun screencapAndUpload(brokerUrl: String, deviceId: String, label: String): Boolean {
        lastFailureReason = null
        val tmp = "/sdcard/dyrpa_${label}_${System.currentTimeMillis()}.png"
        val cap = ShellExecutor.screencap(tmp)
        if (!cap.ok) {
            lastFailureReason = "screencap exit=${cap.exitCode} stderr='${cap.stderr.take(200)}'"
            Log.w(TAG, "screencap failed: ${cap.stderr}")
            return false
        }
        val bytes = readShellFile(tmp)
        if (bytes == null || bytes.isEmpty()) {
            lastFailureReason = "cat returned ${if (bytes == null) "null" else "empty"} for $tmp"
            Log.w(TAG, lastFailureReason!!)
            ShellExecutor.run("rm -f $tmp")
            return false
        }
        ShellExecutor.run("rm -f $tmp")
        Log.i(TAG, "screencap ok ${bytes.size}B, uploading…")
        val ok = uploadScreenshot(brokerUrl, deviceId, tmp.substringAfterLast('/'), bytes)
        if (ok) lastFailureReason = null
        else if (lastFailureReason == null) lastFailureReason = "upload returned false (no exception)"
        return ok
    }
}
