package com.keyframe.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var rootView: StudioRootView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val f = java.io.File(getExternalFilesDir(null), "runtime-crash.log")
                val sb = StringBuilder("thread: $thread\n")
                var cur = e
                while (cur != null) {
                    sb.append("Caused by: ").append(cur).append('\n')
                    sb.append(android.util.Log.getStackTraceString(cur))
                    cur = cur.cause
                }
                f.writeText(sb.toString())
                android.util.Log.e("KeyframeStudio", "runtime crash log: ${f.absolutePath}", e)
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, e)
        }
        try {
            if (rootView == null) rootView = StudioRootView(this).also { setContentView(it) }
        } catch (t: Throwable) {
            showFatalStartupError(t)
        }
    }

    private fun showFatalStartupError(t: Throwable) {
        val text = TextView(this).apply {
            val sb = StringBuilder("FATAL ERROR — APP CANNOT START\n\n")
            var cur: Throwable? = t
            while (cur != null) {
                sb.append("Caused by: ").append(cur).append('\n')
                for (e in cur.stackTrace) sb.append("    at ").append(e).append('\n')
                cur = cur.cause
            }
            setText(sb)
            textSize = 12f
            setPadding(24, 48, 24, 24)
            setBackgroundColor(0xFF1B1B1D.toInt())
            setTextColor(0xFFFF7676.toInt())
            try {
                val f = java.io.File(getExternalFilesDir(null), "startup-crash.log")
                f.writeText(sb.toString())
                android.util.Log.e("KeyframeStudio", "startup crash log: ${f.absolutePath}")
            } catch (_: Exception) { }
        }
        val scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        rootView?.onConfigChanged()
    }

    override fun onPause() {
        super.onPause()
        rootView?.onAppPause()
    }

    override fun onResume() {
        super.onResume()
        rootView?.onAppResume()
    }

    override fun onBackPressed() {
        // long-running creative app: avoid accidental kill of the autosave state
        moveTaskToBack(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri -> rootView?.onFilePicked(uri) }
        }
    }

    companion object { const val REQ_FILE = 2002 }
}
