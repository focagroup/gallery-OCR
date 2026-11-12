package io.github.minuus69.galleryocr.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.minuus69.galleryocr.R
import io.github.minuus69.galleryocr.data.ImageRow
import io.github.minuus69.galleryocr.data.ScanCounts
import io.github.minuus69.galleryocr.data.SearchMode
import io.github.minuus69.galleryocr.util.Deleter
import io.github.minuus69.galleryocr.util.Intents
import io.github.minuus69.galleryocr.util.MediaAccess
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, deleter: Deleter, onRequestFullAccess: () -> Unit) {
    val context = LocalContext.current
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val counts by vm.scanCounts.collectAsStateWithLifecycle()
    val textCounts by vm.counts.collectAsStateWithLifecycle()
    val smartCounts by vm.smartCounts.collectAsStateWithLifecycle()
    val running by vm.scanRunning.collectAsStateWithLifecycle()
    val paused by vm.settings.paused.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val filter by vm.collectionFilter.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val folderSetupDone by vm.settings.folderSetupDone.collectAsStateWithLifecycle()
    val allFolders by vm.settings.allFolders.collectAsStateWithLifecycle()
    val folders by vm.settings.folders.collectAsStateWithLifecycle()
    val smartAllFolders by vm.settings.smartAllFolders.collectAsStateWithLifecycle()
    val smartFolders by vm.settings.smartFolders.collectAsStateWithLifecycle()
    val searchMode by vm.settings.searchMode.collectAsStateWithLifecycle()
    val noTextFolders = !allFolders && folders.isEmpty()
    val noSmartFolders = !smartAllFolders && smartFolders.isEmpty()
    val selecting = selection.isNotEmpty()
    var pickCollection by remember { mutableStateOf(false) }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && (query.isNotEmpty() || filter != null)) {
        vm.query.value = ""
        vm.collectionFilter.value = null
    }

    if (pickCollection) {
        val ids = selection
        CollectionPickerDialog(
            count = ids.size,
            collections = collections,
            membership = emptySet(),
            onToggle = { c, add ->
                if (add) vm.addToCollection(c.id, ids) else vm.removeFromCollection(c.id, ids)
                pickCollection = false
                vm.clearSelection()
            },
            onCreate = { name ->
                vm.createCollection(name, ids)
                pickCollection = false
                vm.clearSelection()
            },
            onDismiss = { pickCollection = false },
        )
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(NumberFormat.getIntegerInstance().format(selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = { pickCollection = true }) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.add_to_collection))
                        }
                        val current = filter
                        if (current != null) {
                            IconButton(onClick = {
                                vm.removeFromCollection(current, selection)
                                vm.clearSelection()
                            }) {
                                Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.remove_from_collection))
                            }
                        }
                        IconButton(onClick = { Intents.shareImages(context, selection) }) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(onClick = { deleter.delete(selection.toList()) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                )
            } else {
                SearchRow(
                    query = query,
                    onQueryChange = { vm.query.value = it },
                    mode = searchMode,
                    onModeChange = { vm.setSearchMode(it) },
                    textTotal = textCounts.total,
                    smartTotal = smartCounts.total,
                    onFolders = { vm.navigate(Screen.Folders(onboarding = false)) },
                    onCollections = { vm.navigate(Screen.Collections) },
                    onSettings = { vm.navigate(Screen.Settings) },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            if (vm.access == MediaAccess.PARTIAL) {
                InfoCard(
                    text = stringResource(R.string.partial_access),
                    action = stringResource(R.string.allow_all),
                    onAction = onRequestFullAccess,
                )
            }
            if (!folderSetupDone || (noTextFolders && noSmartFolders)) {
                InfoCard(
                    text = stringResource(R.string.no_folders),
                    action = stringResource(R.string.choose_folders),
                    onAction = { vm.navigate(Screen.Folders(onboarding = false)) },
                )
            } else if (searchMode == SearchMode.SMART && noSmartFolders) {
                InfoCard(
                    text = stringResource(R.string.no_smart_folders),
                    action = stringResource(R.string.choose_folders),
                    onAction = { vm.navigate(Screen.Folders(onboarding = false)) },
                )
            } else if (searchMode == SearchMode.TEXT && noTextFolders) {
                InfoCard(
                    text = stringResource(R.string.no_text_folders),
                    action = stringResource(R.string.choose_folders),
                    onAction = { vm.navigate(Screen.Folders(onboarding = false)) },
                )
            }
            ScanStatus(counts, running, paused, onPause = vm::pauseScan, onResume = vm::resumeScan)
            if (collections.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = filter == null,
                            onClick = { vm.collectionFilter.value = null },
                            label = { Text(stringResource(R.string.all_images)) },
                        )
                    }
                    items(collections, key = { it.id }) { c ->
                        FilterChip(
                            selected = filter == c.id,
                            onClick = { vm.collectionFilter.value = if (filter == c.id) null else c.id },
                            label = { Text(c.name) },
                        )
                    }
                }
            }
            if (query.isNotBlank()) {
                Text(
                    pluralStringResource(R.plurals.match_count, results.size, NumberFormat.getIntegerInstance().format(results.size)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (results.isEmpty()) {
                EmptyState(
                    text = when {
                        query.isNotBlank() && searchMode == SearchMode.SMART -> stringResource(R.string.no_smart_matches)
                        query.isNotBlank() -> stringResource(R.string.no_matches)
                        filter != null -> stringResource(R.string.empty_collection)
                        else -> stringResource(R.string.no_images)
                    },
                )
            } else {
                ImageGrid(
                    rows = results,
                    resetKey = Triple(query, filter, searchMode),
                    selection = selection,
                    onClick = { row -> if (selecting) vm.toggleSelection(row.id) else vm.openViewer(row) },
                    onLongClick = { row -> vm.toggleSelection(row.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    textTotal: Int,
    smartTotal: Int,
    onFolders: () -> Unit,
    onCollections: () -> Unit,
    onSettings: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            placeholder = {
                Text(stringResource(if (mode == SearchMode.SMART) R.string.search_hint_smart else R.string.search_hint))
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                // With text: clear it. Without: the menu (folders, collections, settings).
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                    }
                } else {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.folders)) },
                                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onFolders()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.collections)) },
                                leadingIcon = { Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onCollections()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onSettings()
                                },
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        SearchModeSwitch(
            mode = mode,
            onModeChange = onModeChange,
            totals = mapOf(SearchMode.TEXT to textTotal, SearchMode.SMART to smartTotal),
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/**
 * Text / Smart switch: a bar whose highlight slides to the chosen mode, with the number of
 * images in each index. Same corner radius as the search field and the collection chips.
 */
@Composable
private fun SearchModeSwitch(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    totals: Map<SearchMode, Int>,
    modifier: Modifier = Modifier,
) {
    val modes = SearchMode.entries
    val colors = MaterialTheme.colorScheme
    val format = remember { NumberFormat.getIntegerInstance() }
    val inset = 3.dp
    // Outer corner minus the inset, so both corners share a centre.
    val outer = RoundedCornerShape(8.dp)
    val inner = RoundedCornerShape(8.dp - inset)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(outer)
            .background(colors.surfaceContainerHigh)
            .padding(inset),
    ) {
        val segment = maxWidth / modes.size
        val offset by animateDpAsState(segment * modes.indexOf(mode), label = "searchModeIndicator")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .shadow(1.dp, inner)
                .clip(inner)
                .background(colors.surface),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            for (m in modes) {
                val selected = m == mode
                val tint by animateColorAsState(
                    if (selected) colors.primary else colors.onSurfaceVariant,
                    label = "searchModeTint",
                )
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // No ripple: the sliding highlight is the feedback.
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onModeChange(m) },
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (m == SearchMode.SMART) Icons.Outlined.AutoAwesome else Icons.Outlined.TextFields,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (m == SearchMode.SMART) R.string.search_mode_smart else R.string.search_mode_text) +
                            " · " + format.format(totals[m] ?: 0),
                        style = MaterialTheme.typography.labelLarge,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanStatus(counts: ScanCounts, running: Boolean, paused: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    if (counts.pending <= 0) return
    val format = NumberFormat.getIntegerInstance()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        when {
                            paused -> R.string.scan_paused
                            running -> R.string.scan_running
                            else -> R.string.scan_waiting
                        },
                        format.format(counts.processed),
                        format.format(counts.total),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (paused) {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.resume)) }
                } else {
                    TextButton(onClick = onPause) { Text(stringResource(R.string.pause)) }
                }
            }
            LinearProgressIndicator(
                progress = { if (counts.total == 0) 0f else counts.processed.toFloat() / counts.total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun InfoCard(text: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageGrid(
    rows: List<ImageRow>,
    resetKey: Any,
    selection: Set<Long>,
    onClick: (ImageRow) -> Unit,
    onLongClick: (ImageRow) -> Unit,
) {
    val state = rememberLazyGridState()
    LaunchedEffect(resetKey) { state.scrollToItem(0) }
    // No item keys on purpose: the grid then keeps its position by index, so newly taken
    // screenshots appear at the top instead of pushing the view down.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        state = state,
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows) { row ->
            val selected = row.id in selection
            Box(
                Modifier
                    .aspectRatio(0.62f)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onClick(row) }, onLongClick = { onLongClick(row) }),
            ) {
                Thumbnail(row.id, row.orientation, Modifier.fillMaxSize())
                if (selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                    )
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(24.dp),
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(6.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
