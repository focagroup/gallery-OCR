package io.github.minuus69.galleryocr.data

import android.content.Context
import io.github.minuus69.galleryocr.ocr.OcrConfig
import io.github.minuus69.galleryocr.ocr.RecModel
import io.github.minuus69.galleryocr.ocr.ScanMode
import io.github.minuus69.galleryocr.smart.SmartMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Which index the search field searches. */
enum class SearchMode(val id: String) { TEXT("text"), SMART("smart") }

/** User settings, stored locally in SharedPreferences and exposed as flows. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _allFolders = MutableStateFlow(prefs.getBoolean(KEY_ALL_FOLDERS, false))
    val allFolders: StateFlow<Boolean> = _allFolders.asStateFlow()

    private val _folders = MutableStateFlow(prefs.getStringSet(KEY_FOLDERS, emptySet())!!.toSet())
    val folders: StateFlow<Set<String>> = _folders.asStateFlow()

    private val _smartAllFolders = MutableStateFlow(prefs.getBoolean(KEY_SMART_ALL_FOLDERS, false))
    val smartAllFolders: StateFlow<Boolean> = _smartAllFolders.asStateFlow()

    private val _smartFolders = MutableStateFlow(prefs.getStringSet(KEY_SMART_FOLDERS, emptySet())!!.toSet())
    val smartFolders: StateFlow<Set<String>> = _smartFolders.asStateFlow()

    private val _smartMode = MutableStateFlow(
        SmartMode.entries.firstOrNull { it.id == prefs.getString(KEY_SMART_MODE, null) } ?: SmartMode.ACCURATE,
    )
    val smartMode: StateFlow<SmartMode> = _smartMode.asStateFlow()

    private val _smartStrictness = MutableStateFlow(prefs.getFloat(KEY_SMART_STRICTNESS, 0.5f))
    val smartStrictness: StateFlow<Float> = _smartStrictness.asStateFlow()

    private val _searchMode = MutableStateFlow(
        SearchMode.entries.firstOrNull { it.id == prefs.getString(KEY_SEARCH_MODE, null) } ?: SearchMode.TEXT,
    )
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _folderSetupDone = MutableStateFlow(prefs.getBoolean(KEY_FOLDER_SETUP, false))
    val folderSetupDone: StateFlow<Boolean> = _folderSetupDone.asStateFlow()

    private val _models = MutableStateFlow(readModels())
    val models: StateFlow<Set<RecModel>> = _models.asStateFlow()

    private val _scanMode = MutableStateFlow(
        ScanMode.entries.firstOrNull { it.id == prefs.getString(KEY_SCAN_MODE, null) } ?: ScanMode.ACCURATE,
    )
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _highlight = MutableStateFlow(prefs.getBoolean(KEY_HIGHLIGHT, true))
    val highlight: StateFlow<Boolean> = _highlight.asStateFlow()

    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    var permissionRequested: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_ASKED, value).apply()

    /** Saves the folder choice for text search (OCR) and smart search. */
    fun setFolders(all: Boolean, folders: Set<String>, smartAll: Boolean, smartFolders: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_ALL_FOLDERS, all)
            .putStringSet(KEY_FOLDERS, folders)
            .putBoolean(KEY_SMART_ALL_FOLDERS, smartAll)
            .putStringSet(KEY_SMART_FOLDERS, smartFolders)
            .putBoolean(KEY_FOLDER_SETUP, true)
            .apply()
        _allFolders.value = all
        _folders.value = folders
        _smartAllFolders.value = smartAll
        _smartFolders.value = smartFolders
        _folderSetupDone.value = true
    }

    fun setSmartMode(mode: SmartMode) {
        prefs.edit().putString(KEY_SMART_MODE, mode.id).apply()
        _smartMode.value = mode
    }

    fun setSmartStrictness(value: Float) {
        val v = value.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_SMART_STRICTNESS, v).apply()
        _smartStrictness.value = v
    }

    fun setSearchMode(mode: SearchMode) {
        prefs.edit().putString(KEY_SEARCH_MODE, mode.id).apply()
        _searchMode.value = mode
    }

    fun setModels(models: Set<RecModel>) {
        if (models.isEmpty()) return
        prefs.edit().putStringSet(KEY_MODELS, models.map { it.id }.toSet()).apply()
        _models.value = models
    }

    fun setScanMode(mode: ScanMode) {
        prefs.edit().putString(KEY_SCAN_MODE, mode.id).apply()
        _scanMode.value = mode
    }

    fun setHighlight(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGHLIGHT, enabled).apply()
        _highlight.value = enabled
    }

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
        _paused.value = paused
    }

    fun isInScope(bucketId: String): Boolean = _allFolders.value || bucketId in _folders.value

    fun isInSmartScope(bucketId: String): Boolean = _smartAllFolders.value || bucketId in _smartFolders.value

    /** True if any folder is chosen for smart search. */
    val hasSmartFolders: Boolean get() = _smartAllFolders.value || _smartFolders.value.isNotEmpty()

    fun ocrConfig(): OcrConfig =
        OcrConfig(_scanMode.value, RecModel.entries.filter { it in _models.value })

    private fun readModels(): Set<RecModel> {
        val stored = prefs.getStringSet(KEY_MODELS, null)
        if (stored != null) {
            val models = RecModel.entries.filter { it.id in stored }.toSet()
            if (models.isNotEmpty()) return models
        }
        // Sensible default from the device language.
        val models = mutableSetOf(RecModel.MAIN)
        when (Locale.getDefault().language) {
            "ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg", "tt", "ba", "cv" -> models += RecModel.CYRILLIC
            "ko" -> models += RecModel.KOREAN
        }
        return models
    }

    private companion object {
        const val KEY_ALL_FOLDERS = "all_folders"
        const val KEY_FOLDERS = "folders"
        const val KEY_FOLDER_SETUP = "folder_setup_done"
        const val KEY_MODELS = "rec_models"
        const val KEY_SCAN_MODE = "scan_mode"
        const val KEY_HIGHLIGHT = "highlight"
        const val KEY_PAUSED = "paused"
        const val KEY_PERMISSION_ASKED = "permission_asked"
        const val KEY_SMART_ALL_FOLDERS = "smart_all_folders"
        const val KEY_SMART_FOLDERS = "smart_folders"
        const val KEY_SMART_MODE = "smart_mode"
        const val KEY_SMART_STRICTNESS = "smart_strictness"
        const val KEY_SEARCH_MODE = "search_mode"
    }
}
