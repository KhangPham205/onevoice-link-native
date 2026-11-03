package com.onevoice.link.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private const val TAG = "OneVoiceSession"

/**
 * Append-only JSONL record of a live demo session: one header row, then one
 * row per bounded turn (the routing row plus transcript and translation).
 *
 * This is the offline benchmark evidence the Phase 3 brief asks for, captured
 * without a cable attached to logcat. Every row is flushed so a crash or a
 * force-stop mid-demo still leaves every completed turn on disk.
 *
 * Files live under internal `<filesDir>/sessions/`; [exportToDownloads]
 * copies one to the public Download folder so it can leave the device
 * without `run-as`.
 */
internal class SessionRecorder(
    private val directory: File,
    private val header: () -> JSONObject,
) {
    private var writer: BufferedWriter? = null

    /** The file currently being appended to, else the last one closed. */
    @Volatile
    var currentFile: File? = null
        private set

    @Synchronized
    fun append(row: JSONObject) {
        runCatching {
            val out = writer ?: open()
            out.write(row.toString())
            out.newLine()
            out.flush()
        }.onFailure { Log.w(TAG, "session row dropped: ${it.message}") }
    }

    /** Close the current file; the next [append] starts a new session. */
    @Synchronized
    fun rotate() {
        runCatching { writer?.close() }
        writer = null
    }

    private fun open(): BufferedWriter {
        check(directory.isDirectory || directory.mkdirs()) {
            "cannot create ${directory.absolutePath}"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(directory, "session_$stamp.jsonl")
        val out = file.bufferedWriter()
        out.write(header().put("type", "header").toString())
        out.newLine()
        out.flush()
        writer = out
        currentFile = file
        Log.i(TAG, "session log ${file.absolutePath}")
        return out
    }

    companion object {
        /**
         * Copy [file] to `Download/OneVoice/` via MediaStore, which needs no
         * storage permission on API 29+. Returns the public display path.
         */
        fun exportToDownloads(context: Context, file: File): String {
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/OneVoice"
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val target = File(Environment.getExternalStoragePublicDirectory(relative), file.name)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
                return target.absolutePath
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-ndjson")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            ) { "MediaStore refused ${file.name}" }
            try {
                checkNotNull(resolver.openOutputStream(uri)) { "cannot open $uri" }.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (failure: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw failure
            }
            return "$relative/${file.name}"
        }
    }
}
