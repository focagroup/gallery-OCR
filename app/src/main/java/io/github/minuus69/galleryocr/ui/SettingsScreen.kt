package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.minuus69.galleryocr.BuildConfig
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.ocr.RecModel
import io.github.minuus69.galleryocr.ocr.ScanMode
import io.github.minuus69.galleryocr.smart.SmartMode
import java.text.NumberFormat

private sealed interface PendingChange {
    data class Models(val models: Set<RecModel>) : PendingChange
    data class Mode(val mode: ScanMode) : PendingChange
    data class Smart(val mode: SmartMode) : PendingChange
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val models by vm.settings.models.collectAsStateWithLifecycle()
    val mode by vm.settings.scanMode.collectAsStateWithLifecycle()
    val highlight by vm.settings.highlight.collectAsStateWithLifecycle()
    val allFolders by vm.settings.allFolders.collectAsStateWithLifecycle()
    val folders by vm.settings.folders.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val smartAllFolders by vm.settings.smartAllFolders.collectAsStateWithLifecycle()
    val smartFolders by vm.settings.smartFolders.collectAsStateWithLifecycle()
    val smartMode by vm.settings.smartMode.collectAsStateWithLifecycle()
    val strictness by vm.settings.smartStrictness.collectAsStateWithLifecycle()
    val smartCounts by vm.smartCounts.collectAsStateWithLifecycle()
    var confirmReanalyzeAll by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    var confirmRescanAll by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val format = NumberFormat.getIntegerInstance()

    pending?.let { change ->
        // Ask whether already scanned images should be scanned again with the new settings.
        val apply = { rescan: Boolean ->
            when (change) {
                is PendingChange.Models -> vm.setModels(change.models, rescan)
                is PendingChange.Mode -> vm.setScanMode(change.mode, rescan)
                is PendingChange.Smart -> vm.setSmartMode(change.mode, rescan)
            }
            pending = null
        }
        val smart = change is PendingChange.Smart
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(if (smart) R.string.reanalyze_question_title else R.string.rescan_question_title)) },
            text = { Text(stringResource(if (smart) R.string.reanalyze_question_body else R.string.rescan_question_body)) },
            confirmButton = {
                TextButton(onClick = { apply(true) }) {
                    Text(stringResource(if (smart) R.string.reanalyze_all else R.string.rescan_all))
                }
            },
            dismissButton = { TextButton(onClick = { apply(false) }) { Text(stringResource(R.string.only_new_images)) } },
        )
    }
    if (confirmRescanAll) {
        AlertDialog(
            onDismissRequest = { confirmRescanAll = false },
            title = { Text(stringResource(R.string.rescan_everything)) },
            text = { Text(stringResource(R.string.rescan_everything_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRescanAll = false
                    vm.rescanAll()
                }) { Text(stringResource(R.string.rescan_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmRescanAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmReanalyzeAll) {
        AlertDialog(
            onDismissRequest = { confirmReanalyzeAll = false },
            title = { Text(stringResource(R.string.reanalyze_everything)) },
            text = { Text(stringResource(R.string.reanalyze_everything_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReanalyzeAll = false
                    vm.reanalyzeAll()
                }) { Text(stringResource(R.string.reanalyze_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmReanalyzeAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.licenses)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.licenses_body))
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.done)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { SectionHeader(stringResource(R.string.section_folders)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folders_title)) },
                    supportingContent = {
                        val text = if (allFolders) {
                            stringResource(R.string.all_folders)
                        } else {
                            pluralStringResource(R.plurals.folder_count, folders.size, folders.size)
                        }
                        val smart = if (smartAllFolders) {
                            stringResource(R.string.all_folders)
                        } else {
                            pluralStringResource(R.plurals.folder_count, smartFolders.size, smartFolders.size)
                        }
                        Text(stringResource(R.string.folders_summary, text, smart))
                    },
                    modifier = Modifier.clickable { vm.navigate(Screen.Folders(onboarding = false)) },
                )
            }

            // ---- Text search (OCR)
            item { SectionHeader(stringResource(R.string.section_ocr)) }
            item { SubHeader(stringResource(R.string.section_languages)) }
            item {
                Text(
                    stringResource(R.string.languages_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val modelLabels = listOf(
                Triple(RecModel.MAIN, R.string.model_main, R.string.model_main_desc),
                Triple(RecModel.CYRILLIC, R.string.model_cyrillic, R.string.model_cyrillic_desc),
                Triple(RecModel.KOREAN, R.string.model_korean, R.string.model_korean_desc),
            )
            for ((model, title, desc) in modelLabels) {
                item(key = model.id) {
                    val checked = model in models
                    val canToggle = !checked || models.size > 1
                    val toggle = { if (canToggle) pending = PendingChange.Models(if (checked) models - model else models + model) }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(desc)) },
                        trailingContent = { Checkbox(checked = checked, enabled = canToggle, onCheckedChange = { toggle() }) },
                        modifier = Modifier.clickable(enabled = canToggle) { toggle() },
                    )
                }
            }
            item { SubHeader(stringResource(R.string.section_scan_mode)) }
            for ((m, title, desc) in listOf(
                Triple(ScanMode.ACCURATE, R.string.mode_accurate, R.string.mode_accurate_desc),
                Triple(ScanMode.FAST, R.string.mode_fast, R.string.mode_fast_desc),
            )) {
                item(key = m.id) {
                    val select = { if (mode != m) pending = PendingChange.Mode(m) }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(desc)) },
                        leadingContent = { RadioButton(selected = mode == m, onClick = { select() }) },
                        modifier = Modifier.clickable { select() },
                    )
                }
            }
            item { SubHeader(stringResource(R.string.section_more)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.highlight_matches)) },
                    supportingContent = { Text(stringResource(R.string.highlight_matches_desc)) },
                    trailingContent = { Switch(checked = highlight, onCheckedChange = { vm.settings.setHighlight(it) }) },
                    modifier = Modifier.clickable { vm.settings.setHighlight(!highlight) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rescan_everything)) },
                    supportingContent = { Text(stringResource(R.string.rescan_everything_desc)) },
                    modifier = Modifier.clickable { confirmRescanAll = true },
                )
            }
            item {
                IndexItem(
                    stringResource(
                        R.string.index_stats,
                        format.format(counts.total),
                        format.format(counts.done),
                        format.format(counts.pending),
                        format.format(counts.failed),
                    ),
                )
            }
            if (counts.failed > 0) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.retry_failed)) },
                        supportingContent = { Text(stringResource(R.string.retry_failed_desc)) },
                        modifier = Modifier.clickable { vm.retryFailed() },
                    )
                }
            }

            // ---- Smart search
            item { SectionHeader(stringResource(R.string.section_smart)) }
            item {
                Text(
                    stringResource(R.string.smart_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { SubHeader(stringResource(R.string.section_analyze_mode)) }
            for ((m, title, desc) in listOf(
                Triple(SmartMode.ACCURATE, R.string.mode_accurate, R.string.smart_accurate_desc),
                Triple(SmartMode.FAST, R.string.mode_fast, R.string.smart_fast_desc),
            )) {
                item(key = "smart-" + m.id) {
                    val select = { if (smartMode != m) pending = PendingChange.Smart(m) }
                    ListItem(
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(desc)) },
                        leadingContent = { RadioButton(selected = smartMode == m, onClick = { select() }) },
                        modifier = Modifier.clickable { select() },
                    )
                }
            }
            item { SubHeader(stringResource(R.string.section_more)) }
            item {
                var value by remember(strictness) { mutableStateOf(strictness) }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.strictness)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.strictness_desc))
                            Slider(
                                value = value,
                                onValueChange = { value = it },
                                onValueChangeFinished = { vm.setSmartStrictness(value) },
                                steps = 9,
                            )
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.reanalyze_everything)) },
                    supportingContent = { Text(stringResource(R.string.reanalyze_everything_desc)) },
                    modifier = Modifier.clickable { confirmReanalyzeAll = true },
                )
            }
            item {
                IndexItem(
                    stringResource(
                        R.string.smart_stats,
                        format.format(smartCounts.total),
                        format.format(smartCounts.done),
                        format.format(smartCounts.pending),
                        format.format(smartCounts.failed),
                    ),
                )
            }
            if (smartCounts.failed > 0) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.retry_failed)) },
                        supportingContent = { Text(stringResource(R.string.retry_smart_failed_desc)) },
                        modifier = Modifier.clickable { vm.retrySmartFailed() },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_privacy)) }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.WifiOff, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.offline_title)) },
                    supportingContent = { Text(stringResource(R.string.privacy_statement)) },
                )
            }

            item { SectionHeader(stringResource(R.string.section_about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.licenses)) },
                    modifier = Modifier.clickable { showLicenses = true },
                )
            }
        }
    }
}

/** Small heading for a group of settings inside a section. */
@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Progress of one index: images, done, waiting, failed. */
@Composable
private fun IndexItem(stats: String) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.section_index)) },
        supportingContent = { Text(stats) },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}
