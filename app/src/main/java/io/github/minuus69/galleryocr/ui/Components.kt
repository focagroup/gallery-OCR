package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.CollectionInfo
import io.github.minuus69.galleryocr.util.Thumbnails

/** Square-ish thumbnail loaded from MediaStore's thumbnail cache. */
@Composable
fun Thumbnail(id: Long, orientation: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 192.dp.roundToPx() }
    var image by remember(id) { mutableStateOf(Thumbnails.cached(id)) }
    LaunchedEffect(id) {
        if (image == null) image = Thumbnails.load(context, id, orientation, sizePx)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {
            // Top-aligned: the top of a screenshot (title, app bar) is the most recognizable part.
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Single-line text input dialog (new collection, rename). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Lets the user pick collections for [count] images. [membership] marks collections that
 * already contain the image (single image only); toggling calls [onToggle].
 */
@Composable
fun CollectionPickerDialog(
    count: Int,
    collections: List<CollectionInfo>,
    membership: Set<Long>,
    onToggle: (CollectionInfo, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_collection),
            confirmLabel = stringResource(R.string.create),
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                onCreate(it)
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.add_to_collection_title, count, count)) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(collections, key = { it.id }) { c ->
                        val checked = c.id in membership
                        ListItem(
                            headlineContent = { Text(c.name) },
                            supportingContent = { Text(pluralStringResource(R.plurals.image_count, c.count, c.count)) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle(c, it) }) },
                            modifier = Modifier.clickable { onToggle(c, !checked) },
                        )
                    }
                }
                if (collections.isNotEmpty()) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.new_collection)) },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 2.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
