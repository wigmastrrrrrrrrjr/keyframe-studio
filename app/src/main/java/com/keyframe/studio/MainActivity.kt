package com.keyframe.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var rootView: StudioRootView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (rootView == null) rootView = StudioRootView(this).also { setContentView(it) }
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
