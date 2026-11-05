package com.keyframe.studio

import android.content.Context
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes one .txt report per crash into filesDir/crash_reports/. */
object CrashReporter {

    private val logged = mutableListOf<String>()

    fun log(context: Context, module: String, t: Throwable) {
        val logKey = module + "|" + t.javaClass.name
        if (logKey in logged) return   // once per process per module+exception
        logged += logKey
        runCatching {
            val dir = File(context.filesDir, "crash_reports")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "crash_${stamp}.txt").writeText(report(context, module, t))
        }
        android.util.Log.e("KeyframeStudio", "module crash: $module", t)
    }

    fun report(context: Context, module: String, t: Throwable): String {
        val w = StringWriter()
        w.write("Module: $module\n")
        w.write("Version: 1.0.x\n")
        w.write("Android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})\n")
        w.write("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")
        var cur: Throwable? = t
        while (cur != null) {
            w.write("Caused by: $cur\n")
            w.write(android.util.Log.getStackTraceString(cur))
            cur = cur.cause
        }
        return w.toString()
    }

    fun issueUrl(context: Context, module: String, t: Throwable): String {
        val base = "https://github.com/wigmastrrrrrrrrjr/keyframe-studio/issues/new"
        val encTitle = java.net.URLEncoder.encode(
            "Crash in \"$module\" (${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL})", "UTF-8")
        val encBody = java.net.URLEncoder.encode(report(context, module, t), "UTF-8")
        return base + "?title=" + encTitle + "&body=" + encBody
    }
}
