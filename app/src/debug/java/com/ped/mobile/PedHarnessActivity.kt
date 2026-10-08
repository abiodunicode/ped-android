package com.ped.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ped.mobile.ui.PedTheme

/** Launch the complete app against a test-only API without changing production configuration. */
class PedHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = requireNotNull(intent.getStringExtra("apiBase"))
        setContent { PedTheme { PedMobileApp(base) } }
    }
}
