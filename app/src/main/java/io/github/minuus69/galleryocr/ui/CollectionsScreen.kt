package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.CollectionInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(vm: MainViewModel) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionInfo?>(null) }
    var deleting by remember { mutableStateOf<CollectionInfo?>(null) }

    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_collection),
            confirmLabel = stringResource(R.string.create),
            onDismiss = { creating = false },
            onConfirm = {
                vm.createCollection(it)
                creating = false
            },
        )
    }
    renaming?.let { c ->
        TextInputDialog(
            title = stringResource(R.string.rename),
            initial = c.name,
            confirmLabel = stringResource(R.string.save),
            onDismiss = { renaming = null },
            onConfirm = {
                vm.renameCollection(c.id, it)
                renaming = null
            },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_collection_title, c.name)) },
            text = { Text(stringResource(R.string.delete_collection_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCollection(c.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collections)) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_collection))
            }
        },
    ) { padding ->
        if (collections.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.no_collections),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(collections, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    leadingContent = {
                        val cover = c.coverId
                        if (cover != null) {
                            Thumbnail(cover, 0, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                        } else {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null)
                            }
                        }
                    },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text(pluralStringResource(R.plurals.image_count, c.count, c.count)) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                                    menu = false
                                    renaming = c
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                    menu = false
                                    deleting = c
                                })
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        vm.collectionFilter.value = c.id
                        vm.back()
                    },
                )
            }
        }
    }
}
