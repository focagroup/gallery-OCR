package io.github.minuus69.galleryocr.util

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.MediaStoreSource

/** Deletes images through the system's consent flow (moves them to the trash on Android 11+). */
class Deleter internal constructor(private val start: (List<Long>) -> Unit) {
    fun delete(ids: List<Long>) {
        if (ids.isNotEmpty()) start(ids)
    }
}

@Composable
fun rememberDeleter(onDeleted: (List<Long>) -> Unit): Deleter {
    val context = LocalContext.current
    val notify by rememberUpdatedState(onDeleted)
    // Continuation for Android 10, which asks for consent one file at a time.
    val continueAndroid10 = remember { arrayOfNulls<(List<Long>) -> Unit>(1) }
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    var confirm by remember { mutableStateOf<List<Long>>(emptyList()) }

    fun deleteDirectly(ids: List<Long>, ctx: Context): List<Long> {
        val done = ArrayList<Long>()
        for (id in ids) {
            try {
                if (ctx.contentResolver.delete(MediaStoreSource.imageUri(id), null, null) > 0) done += id
            } catch (e: SecurityException) {
                // handled by caller on Android 10
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) throw e
            }
        }
        return done
    }

    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ids = pending
        pending = emptyList()
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= 30) {
            notify(ids)
        } else {
            // Android 10: permission for the first file was granted; delete it and continue.
            val done = runCatching { deleteDirectly(ids.take(1), context) }.getOrDefault(emptyList())
            if (done.isNotEmpty()) notify(done)
            continueAndroid10[0]?.invoke(ids.drop(1))
        }
    }

    fun launchAndroid10(ids: List<Long>) {
        val remaining = ids.toMutableList()
        val deleted = ArrayList<Long>()
        while (remaining.isNotEmpty()) {
            val id = remaining.first()
            try {
                deleted += deleteDirectly(listOf(id), context)
                remaining.removeAt(0)
            } catch (e: RecoverableSecurityException) {
                pending = remaining.toList()
                sender.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                break
            }
        }
        if (deleted.isNotEmpty()) notify(deleted)
    }
    continueAndroid10[0] = { ids -> if (ids.isNotEmpty()) launchAndroid10(ids) }

    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ids = pending
        pending = emptyList()
        if (granted) notify(deleteDirectly(ids, context))
    }

    if (confirm.isNotEmpty()) {
        val ids = confirm
        AlertDialog(
            onDismissRequest = { confirm = emptyList() },
            title = { Text(pluralStringResource(R.plurals.delete_title, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.delete_permanent_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = emptyList()
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        notify(deleteDirectly(ids, context))
                    } else {
                        pending = ids
                        writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = emptyList() }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    return remember {
        Deleter { ids ->
            when {
                Build.VERSION.SDK_INT >= 30 -> {
                    val uris = ids.map { MediaStoreSource.imageUri(it) }
                    try {
                        val request = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                        pending = ids
                        sender.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                Build.VERSION.SDK_INT == 29 -> launchAndroid10(ids)
                else -> confirm = ids
            }
        }
    }
}
