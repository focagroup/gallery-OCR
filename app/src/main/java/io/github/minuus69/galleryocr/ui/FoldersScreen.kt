package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.MediaFolder

private val COLUMN_WIDTH = 56.dp

/**
 * Choose which folders are used for text search (OCR) and for smart search, or all of them
 * (including folders created later). A folder can be in both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(vm: MainViewModel, onboarding: Boolean) {
    val folders by produceState<List<MediaFolder>?>(null) { value = vm.loadFolders() }
    var allText by remember { mutableStateOf(vm.settings.allFolders.value) }
    var allSmart by remember { mutableStateOf(vm.settings.smartAllFolders.value) }
    var selectedText by remember { mutableStateOf<Set<String>?>(null) }
    var selectedSmart by remember { mutableStateOf<Set<String>?>(null) }
    val list = folders
    // On first setup, preselect screenshot folders for text search.
    val initialText = remember(list) {
        when {
            list == null -> null
            !vm.settings.folderSetupDone.value -> list.filter { it.looksLikeScreenshots }.map { it.bucketId }.toSet()
            else -> vm.settings.folders.value
        }
    }
    val chosenText = selectedText ?: initialText ?: emptySet()
    val chosenSmart = selectedSmart ?: vm.settings.smartFolders.value
    val canSave = allText || allSmart || chosenText.isNotEmpty() || chosenSmart.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                navigationIcon = {
                    if (!onboarding) {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (canSave) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vm.saveFolders(allText, chosenText, allSmart, chosenSmart)
                        vm.back()
                    },
                    icon = { Icon(Icons.Default.Check, contentDescription = null) },
                    text = { Text(stringResource(if (onboarding) R.string.start_scanning else R.string.save)) },
                )
            }
        },
    ) { padding ->
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val ids = list.map { it.bucketId }.toSet()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    stringResource(R.string.folders_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.all_folders_text)) },
                    supportingContent = { Text(stringResource(R.string.all_folders_desc)) },
                    trailingContent = { Switch(checked = allText, onCheckedChange = { allText = it }) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.all_folders_smart)) },
                    supportingContent = { Text(stringResource(R.string.all_folders_desc)) },
                    trailingContent = { Switch(checked = allSmart, onCheckedChange = { allSmart = it }) },
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            if (list.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_image_folders),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    // Column headers; the checkboxes select all or no folders of that column.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.folders),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        ColumnHeader(
                            label = stringResource(R.string.search_mode_text),
                            state = if (allText) ToggleableState.On else stateOf(chosenText, ids),
                            enabled = !allText,
                            onClick = { selectedText = if (chosenText.containsAll(ids)) emptySet() else ids },
                        )
                        ColumnHeader(
                            label = stringResource(R.string.search_mode_smart),
                            state = if (allSmart) ToggleableState.On else stateOf(chosenSmart, ids),
                            enabled = !allSmart,
                            onClick = { selectedSmart = if (chosenSmart.containsAll(ids)) emptySet() else ids },
                        )
                    }
                }
            }
            items(list, key = { it.bucketId }) { folder ->
                ListItem(
                    leadingContent = {
                        Thumbnail(
                            folder.coverId,
                            0,
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    },
                    headlineContent = { Text(folder.name.ifEmpty { "/" }) },
                    supportingContent = {
                        Text("${folder.path} · " + pluralStringResource(R.plurals.image_count, folder.count, folder.count))
                    },
                    trailingContent = {
                        Row {
                            Box(Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                                Checkbox(
                                    checked = allText || folder.bucketId in chosenText,
                                    enabled = !allText,
                                    onCheckedChange = { on ->
                                        selectedText = if (on) chosenText + folder.bucketId else chosenText - folder.bucketId
                                    },
                                )
                            }
                            Box(Modifier.width(COLUMN_WIDTH), contentAlignment = Alignment.Center) {
                                Checkbox(
                                    checked = allSmart || folder.bucketId in chosenSmart,
                                    enabled = !allSmart,
                                    onCheckedChange = { on ->
                                        selectedSmart = if (on) chosenSmart + folder.bucketId else chosenSmart - folder.bucketId
                                    },
                                )
                            }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

private fun stateOf(chosen: Set<String>, ids: Set<String>): ToggleableState {
    val n = ids.count { it in chosen }
    return when (n) {
        0 -> ToggleableState.Off
        ids.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

@Composable
private fun ColumnHeader(label: String, state: ToggleableState, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(COLUMN_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        TriStateCheckbox(state = state, onClick = onClick, enabled = enabled)
    }
}
