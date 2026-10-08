package com.ped.mobile.inspection

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Instrumented UI tests run the real Android lifecycle and main-thread recomposer. */
class InspectionHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = requireNotNull(intent.getStringExtra("apiBase"))
        setContent { InspectionScreen("ui-test", { finish() }, base) }
    }
}
