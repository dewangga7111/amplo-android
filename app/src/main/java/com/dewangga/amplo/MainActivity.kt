package com.dewangga.amplo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dewangga.amplo.ui.LibraryScreen
import com.dewangga.amplo.ui.PermissionScreen
import com.dewangga.amplo.ui.theme.AmploTheme

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            AmploTheme { App(model) }
        }
    }
}

/** The permission that lets the app read music files. */
val audioPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun Context.mayReadMusic() =
    ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App(model: AppViewModel) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(context.mayReadMusic()) }
    // The permission can be given or taken in the system settings while the app is away.
    LifecycleResumeEffect(Unit) {
        allowed = context.mayReadMusic()
        onPauseOrDispose {}
    }
    LaunchedEffect(allowed) {
        if (allowed) model.open()
    }
    if (allowed) LibraryScreen(model) else PermissionScreen(onAllowed = { allowed = true })
}
