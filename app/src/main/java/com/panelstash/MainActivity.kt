package com.panelstash

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.panelstash.data.Settings
import com.panelstash.ui.AppRoot
import com.panelstash.ui.theme.Palette

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Called before the first frame, so the window background and the Compose insets agree on
        // every version from 26 to the current one instead of only where the platform forces it.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Match the launch frame to the saved theme, so a light-theme install does not flash
        // the dark launch drawable before Compose draws its first frame.
        val preferences = Settings(this).current
        window.setBackgroundDrawable(
            ColorDrawable(Palette.canvasColor(preferences.themeMode, preferences.canvas).toArgb()),
        )

        // The theme lives in AppRoot because the accent and canvas are user preferences.
        setContent { AppRoot() }
    }
}
