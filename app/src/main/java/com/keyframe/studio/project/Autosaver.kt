package com.keyframe.studio.project

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Reliable autosave with one rolling backup generation:
 *   active:  <parent>/<name>/
 *   backup:  <parent>/<name>_backup/
 * Write goes to a staging dir, then directories are swapped - the staging
 * directory always holds a complete, loadable snapshot.
 */
class Autosaver(
    private val targetDir: File,
    private val collect: () -> com.keyframe.studio.engine.Document?,
    private val onSaved: ((File) -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val staging = File(targetDir.parentFile, targetDir.name + "_staging")
    private val backup = File(targetDir.parentFile, targetDir.name + "_backup")

    val projectFile: File get() = File(targetDir, "project.json")

    fun saveNow(): Boolean {
        val doc = collect() ?: return false
        return try {
            ProjectIO.save(doc, staging)
            if (backup.exists()) backup.deleteRecursively()
            if (targetDir.exists()) {
                if (!targetDir.renameTo(backup)) targetDir.deleteRecursively()
            }
            if (!staging.renameTo(targetDir)) {
                targetDir.mkdirs()
                staging.copyRecursively(targetDir, overwrite = true)
                staging.deleteRecursively()
            }
            onSaved?.invoke(projectFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Last complete snapshot location for crash recovery. */
    fun bestProjectDir(): File = if (projectFile.exists()) targetDir
    else File(targetDir.parentFile, targetDir.name + "_backup")

    private val tick = object : Runnable {
        override fun run() {
            saveNow()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        saveNow()
    }

    companion object { const val INTERVAL_MS = 12_000L }
}
