package io.github.minuus69.galleryocr.data

import android.content.Context
import io.github.minuus69.galleryocr.ocr.OcrConfig
import io.github.minuus69.galleryocr.ocr.RecModel
import io.github.minuus69.galleryocr.ocr.ScanMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** User settings, stored locally in SharedPreferences and exposed as flows. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _allFolders = MutableStateFlow(prefs.getBoolean(KEY_ALL_FOLDERS, false))
    val allFolders: StateFlow<Boolean> = _allFolders.asStateFlow()

    private val _folders = MutableStateFlow(prefs.getStringSet(KEY_FOLDERS, emptySet())!!.toSet())
    val folders: StateFlow<Set<String>> = _folders.asStateFlow()

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

    fun setFolders(all: Boolean, folders: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_ALL_FOLDERS, all)
            .putStringSet(KEY_FOLDERS, folders)
            .putBoolean(KEY_FOLDER_SETUP, true)
            .apply()
        _allFolders.value = all
        _folders.value = folders
        _folderSetupDone.value = true
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
    }
}
