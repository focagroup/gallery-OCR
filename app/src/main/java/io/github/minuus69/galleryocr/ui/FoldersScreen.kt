package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.MediaFolder

/** Choose which folders to scan, or all of them (including folders created later). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(vm: MainViewModel, onboarding: Boolean) {
    val folders by produceState<List<MediaFolder>?>(null) { value = vm.loadFolders() }
    var all by remember { mutableStateOf(vm.settings.allFolders.value) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    val list = folders
    // On first setup, preselect screenshot folders.
    val initial = remember(list) {
        when {
            list == null -> null
            !vm.settings.folderSetupDone.value -> list.filter { it.looksLikeScreenshots }.map { it.bucketId }.toSet()
            else -> vm.settings.folders.value
        }
    }
    val chosen = selected ?: initial ?: emptySet()
    val canSave = all || chosen.isNotEmpty()

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
                        vm.saveFolders(all, chosen)
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
                    headlineContent = { Text(stringResource(R.string.all_folders)) },
                    supportingContent = { Text(stringResource(R.string.all_folders_desc)) },
                    trailingContent = { Switch(checked = all, onCheckedChange = { all = it }) },
                    modifier = Modifier.clickable { all = !all },
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(enabled = !all, onClick = { selected = list.map { it.bucketId }.toSet() }) {
                        Text(stringResource(R.string.select_all))
                    }
                    OutlinedButton(enabled = !all, onClick = { selected = emptySet() }) {
                        Text(stringResource(R.string.select_none))
                    }
                }
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
            }
            items(list, key = { it.bucketId }) { folder ->
                val checked = all || folder.bucketId in chosen
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
                        Checkbox(
                            checked = checked,
                            enabled = !all,
                            onCheckedChange = { on -> selected = if (on) chosen + folder.bucketId else chosen - folder.bucketId },
                        )
                    },
                    modifier = Modifier.clickable(enabled = !all) {
                        selected = if (folder.bucketId in chosen) chosen - folder.bucketId else chosen + folder.bucketId
                    },
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}
