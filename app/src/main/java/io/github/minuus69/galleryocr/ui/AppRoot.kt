package io.github.minuus69.galleryocr.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.util.MediaAccess
import io.github.minuus69.galleryocr.util.Permissions
import io.github.minuus69.galleryocr.util.rememberDeleter

@Composable
fun AppRoot(vm: MainViewModel) {
    val context = LocalContext.current
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val access = Permissions.mediaAccess(context)
        vm.onAccessChecked(access)
        val activity = context as? Activity
        permanentlyDenied = access == MediaAccess.NONE && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Permissions.mainPermission())
    }
    val requestAccess = {
        vm.settings.permissionRequested = true
        permissionLauncher.launch(Permissions.toRequest())
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (vm.access == MediaAccess.NONE) {
            PermissionScreen(
                permanentlyDenied = permanentlyDenied,
                onRequest = requestAccess,
                onOpenSettings = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
        } else {
            val deleter = rememberDeleter { vm.onImagesDeleted(it) }
            BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
            when (val screen = vm.screen) {
                Screen.Home -> HomeScreen(vm, deleter, onRequestFullAccess = requestAccess)
                is Screen.Viewer -> ViewerScreen(vm, screen.startId, deleter)
                is Screen.Folders -> FoldersScreen(vm, screen.onboarding)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Collections -> CollectionsScreen(vm)
            }
        }
    }
}

@Composable
private fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.ImageSearch,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.privacy_statement),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (permanentlyDenied) {
            Text(stringResource(R.string.permission_denied), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_app_settings)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequest) { Text(stringResource(R.string.grant_access)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.grant_access)) }
        }
    }
}
