package com.imankoppai.mediaanvil

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/** Explicit foreground host for device tests; this activity is absent from release APKs. */
class DebugTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
