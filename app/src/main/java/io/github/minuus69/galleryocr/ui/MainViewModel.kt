package io.github.minuus69.galleryocr.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.minuus69.galleryocr.app
import io.github.minuus69.galleryocr.data.CollectionEntity
import io.github.minuus69.galleryocr.data.CollectionInfo
import io.github.minuus69.galleryocr.data.CollectionItemEntity
import io.github.minuus69.galleryocr.data.ImageDao
import io.github.minuus69.galleryocr.data.ImageRow
import io.github.minuus69.galleryocr.data.MediaFolder
import io.github.minuus69.galleryocr.data.MediaStoreSource
import io.github.minuus69.galleryocr.data.OcrTextEntity
import io.github.minuus69.galleryocr.data.ScanCounts
import io.github.minuus69.galleryocr.data.SearchMode
import io.github.minuus69.galleryocr.data.SearchText
import io.github.minuus69.galleryocr.ocr.RecModel
import io.github.minuus69.galleryocr.ocr.ScanMode
import io.github.minuus69.galleryocr.scan.Indexer
import io.github.minuus69.galleryocr.scan.ScanScheduler
import io.github.minuus69.galleryocr.smart.SmartIndex
import io.github.minuus69.galleryocr.smart.SmartMode
import io.github.minuus69.galleryocr.util.MediaAccess
import io.github.minuus69.galleryocr.util.Thumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Viewer(val startId: Long) : Screen
    data class Folders(val onboarding: Boolean) : Screen
    data object Settings : Screen
    data object Collections : Screen
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    val settings = application.app.settings
    private val db = application.app.database

    // ---- navigation
    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()

    fun navigate(screen: Screen) {
        backStack += screen
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // Home grid scroll position lives here so it survives opening the viewer and coming back
    // (the home screen leaves composition while another screen is shown).
    val homeGridState = LazyGridState()
    var homeGridKey: Any? = null

    // ---- permission
    var access by mutableStateOf(MediaAccess.NONE)
        private set

    fun onAccessChecked(newAccess: MediaAccess) {
        val before = access
        access = newAccess
        if (newAccess == MediaAccess.NONE) return
        if (before == MediaAccess.NONE && !settings.folderSetupDone.value && backStack.none { it is Screen.Folders }) {
            navigate(Screen.Folders(onboarding = true))
        }
        refresh()
    }

    // ---- search and gallery
    val query = MutableStateFlow("")
    val collectionFilter = MutableStateFlow<Long?>(null)

    val searchTerms: StateFlow<List<String>> = query
        .map { SearchText.terms(it) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Terms to highlight in the viewer; smart search has none. */
    val highlightTerms: StateFlow<List<String>> = combine(searchTerms, settings.searchMode) { t, m ->
        if (m == SearchMode.TEXT) t else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val smartIndex = SmartIndex(context, db.images())

    /** Changes when embeddings are added or removed; throttled while a scan writes many. */
    private val embeddingsVersion: Flow<String> = db.images().observeEmbeddingsVersion()
        .conflate()
        .transform {
            emit(it)
            delay(3000)
        }

    private val textResults: Flow<List<ImageRow>> = combine(searchTerms.debounce(150), collectionFilter) { t, c -> t to c }
        .flatMapLatest { (terms, collection) -> db.images().observeRows(ImageDao.buildRowsQuery(terms, collection)) }

    private data class SmartQuery(val query: String, val collection: Long?, val strictness: Float, val version: String)

    private val smartResults: Flow<List<ImageRow>> = combine(
        query.map { it.trim() }.distinctUntilChanged().debounce(300),
        collectionFilter,
        settings.smartStrictness,
        embeddingsVersion,
    ) { q, c, s, v -> SmartQuery(q, c, s, v) }
        .flatMapLatest { sq ->
            if (sq.query.isEmpty()) {
                db.images().observeRows(ImageDao.buildRowsQuery(emptyList(), sq.collection))
            } else {
                flow { emit(smartSearch(sq)) }
            }
        }

    val results: StateFlow<List<ImageRow>> = settings.searchMode
        .flatMapLatest { mode -> if (mode == SearchMode.TEXT) textResults else smartResults }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun smartSearch(sq: SmartQuery): List<ImageRow> = withContext(Dispatchers.Default) {
        val hits = try {
            smartIndex.search(sq.query, sq.strictness, sq.version)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // E.g. a model that cannot be loaded; search shows no results instead of crashing.
            android.util.Log.e("MainViewModel", "smart search failed", t)
            emptyList()
        }
        val ids = hits.map { it.id }
        val rows = HashMap<Long, ImageRow>(ids.size * 2)
        for (chunk in ids.chunked(900)) {
            val found = if (sq.collection != null) {
                db.images().collectionRows(chunk, sq.collection)
            } else {
                db.images().smartRows(chunk)
            }
            found.forEach { rows[it.id] = it }
        }
        ids.mapNotNull { rows[it] }
    }

    fun setSearchMode(mode: SearchMode) = settings.setSearchMode(mode)

    /** Text search (OCR) progress. */
    val counts: StateFlow<ScanCounts> = db.images().observeCounts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanCounts(0, 0, 0))

    /** Smart search progress. */
    val smartCounts: StateFlow<ScanCounts> = db.images().observeSmartCounts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanCounts(0, 0, 0))

    /** Both kinds of work together, for the scan status card. */
    val scanCounts: StateFlow<ScanCounts> = combine(counts, smartCounts) { a, b ->
        ScanCounts(a.total + b.total, a.done + b.done, a.failed + b.failed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScanCounts(0, 0, 0))

    val scanRunning: StateFlow<Boolean> = ScanScheduler.observeRunning(context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val collections: StateFlow<List<CollectionInfo>> = db.collections().observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Snapshot of the result list the viewer pages through (stable while viewing). */
    val viewerItems = mutableStateListOf<ImageRow>()

    fun openViewer(row: ImageRow) {
        viewerItems.clear()
        viewerItems.addAll(results.value)
        navigate(Screen.Viewer(row.id))
    }

    // ---- selection (long press in the grid)
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleSelection(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        selection.value = results.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // ---- syncing and scanning
    private var refreshJob: Job? = null
    private var lastAutoStart = 0L

    init {
        // Restart the scan if new pending images show up after the worker finished.
        viewModelScope.launch {
            combine(scanCounts, scanRunning, settings.paused) { c, running, paused -> Triple(c, running, paused) }
                .collect { (c, running, paused) ->
                    val now = SystemClock.elapsedRealtime()
                    if (c.pending > 0 && !running && !paused && access != MediaAccess.NONE && now - lastAutoStart > 30_000) {
                        lastAutoStart = now
                        ScanScheduler.ensureRunning(context)
                    }
                }
        }
    }

    /** Re-reads MediaStore and starts scanning new images. */
    fun refresh(delayMs: Long = 0) {
        if (access == MediaAccess.NONE) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            Indexer.sync(context, access == MediaAccess.FULL)
            ScanScheduler.ensureRunning(context)
        }
    }

    fun pauseScan() = ScanScheduler.pause(context)
    fun resumeScan() {
        viewModelScope.launch { ScanScheduler.resume(context) }
    }

    // ---- folders
    suspend fun loadFolders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        MediaStoreSource.folders(MediaStoreSource.queryImages(context))
    }

    fun saveFolders(all: Boolean, folders: Set<String>, smartAll: Boolean, smartFolders: Set<String>) {
        settings.setFolders(all, folders, smartAll, smartFolders)
        refresh()
    }

    // ---- OCR settings
    fun setModels(models: Set<RecModel>, rescan: Boolean) {
        settings.setModels(models)
        applyOcrChange(rescan)
    }

    fun setScanMode(mode: ScanMode, rescan: Boolean) {
        settings.setScanMode(mode)
        applyOcrChange(rescan)
    }

    private fun applyOcrChange(rescan: Boolean) = viewModelScope.launch {
        if (rescan) db.images().rescanOutdated(settings.ocrConfig().key)
        ScanScheduler.restart(context)
    }

    fun retryFailed() = viewModelScope.launch {
        db.images().retryFailed()
        ScanScheduler.ensureRunning(context)
    }

    fun rescanAll() = viewModelScope.launch {
        db.images().rescanAll()
        ScanScheduler.restart(context)
    }

    // ---- smart search settings
    fun setSmartMode(mode: SmartMode, reanalyze: Boolean) = viewModelScope.launch {
        settings.setSmartMode(mode)
        if (reanalyze) db.images().reanalyzeOutdated(mode.key)
        ScanScheduler.restart(context)
    }

    fun setSmartStrictness(value: Float) = settings.setSmartStrictness(value)

    fun retrySmartFailed() = viewModelScope.launch {
        db.images().retrySmartFailed()
        ScanScheduler.ensureRunning(context)
    }

    fun reanalyzeAll() = viewModelScope.launch {
        db.images().reanalyzeAll()
        ScanScheduler.restart(context)
    }

    // ---- images
    suspend fun ocrText(id: Long): OcrTextEntity? = db.images().text(id)

    fun onImagesDeleted(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.forEach { Thumbnails.evict(it) }
        viewerItems.removeAll { it.id in ids }
        selection.value = selection.value - ids.toSet()
        viewModelScope.launch { ids.chunked(500).forEach { db.images().deleteIds(it) } }
    }

    // ---- collections
    fun membership(imageId: Long): Flow<List<Long>> = db.collections().observeMembership(imageId)

    fun createCollection(name: String, addIds: Collection<Long> = emptyList()) = viewModelScope.launch {
        val id = db.collections().insert(CollectionEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
        if (addIds.isNotEmpty()) addToCollection(id, addIds)
    }

    fun addToCollection(collectionId: Long, ids: Collection<Long>) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        db.collections().addItems(ids.map { CollectionItemEntity(collectionId, it, now) })
    }

    fun removeFromCollection(collectionId: Long, ids: Collection<Long>) = viewModelScope.launch {
        ids.chunked(500).forEach { db.collections().removeItems(collectionId, it) }
    }

    fun renameCollection(id: Long, name: String) = viewModelScope.launch { db.collections().rename(id, name.trim()) }

    fun deleteCollection(id: Long) = viewModelScope.launch {
        if (collectionFilter.value == id) collectionFilter.value = null
        db.collections().delete(id)
    }

    override fun onCleared() {
        smartIndex.close()
    }
}
