package com.airos.pos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.airos.pos.core.ui.AirosPosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirosPosTheme {
                AirosPosApp((application as AirosPosApplication).appContainer)
            }
        }
    }
}
