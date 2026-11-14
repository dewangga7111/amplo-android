package com.dewangga.amplo.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.dewangga.amplo.R
import com.dewangga.amplo.audioPermission
import com.dewangga.amplo.ui.theme.Amplo

/** Asks for the music files, and for the playback notification on Android 13 and later. */
@Composable
fun PermissionScreen(onAllowed: () -> Unit) {
    val activity = LocalActivity.current
    // After two refusals Android stops asking, so only the system settings can allow it.
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[audioPermission] == true) {
            onAllowed()
        } else {
            refused = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Amplo.Background)
            .safeDrawingPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(72.dp)
                    .background(Amplo.Accent, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_logo), null, Modifier.size(40.dp), tint = Amplo.OnAccent)
            }
            Spacer(Modifier.height(24.dp))
            Text("amplo", style = MaterialTheme.typography.headlineMedium, color = Amplo.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                if (refused) "Allow Music and audio for amplo in the app settings to see your songs."
                else "amplo plays the music on this device. Allow it to read your songs.",
                style = MaterialTheme.typography.bodyMedium,
                color = Amplo.Soft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (refused) {
                PillButton("Open settings", R.drawable.ic_folder, accent = true) {
                    activity?.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                    )
                }
            } else {
                PillButton("Allow access", R.drawable.ic_check, accent = true) {
                    val permissions = buildList {
                        add(audioPermission)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    ask.launch(permissions.toTypedArray())
                }
            }
        }
    }
}
