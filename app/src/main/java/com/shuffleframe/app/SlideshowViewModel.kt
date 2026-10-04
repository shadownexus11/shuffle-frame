package com.shuffleframe.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Loading : UiState
    data object NeedFolder : UiState
    data class Empty(val folderName: String) : UiState
    data class Failed(val folderName: String) : UiState
    data class Ready(val folderName: String, val count: Int) : UiState
}

/** One photo on screen. [key] is unique per showing, so re-showing a photo still animates. */
data class Slide(val key: Long, val uri: Uri, val forward: Boolean)

class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    var uiState by mutableStateOf<UiState>(UiState.Loading)
        private set
    var settings by mutableStateOf(prefs.loadSettings())
        private set
    var current by mutableStateOf<Slide?>(null)
        private set
    var playing by mutableStateOf(true)
        private set
    var showHint by mutableStateOf(false)
        private set

    private var images: List<Uri> = emptyList()
    private var deck: ShuffleDeck? = null
    private var keyCounter = 0L

    init {
        val saved = prefs.folderUri
        if (saved != null && hasReadPermission(saved)) load(saved) else uiState = UiState.NeedFolder
    }

    private fun hasReadPermission(uri: Uri): Boolean =
        getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }

    fun onFolderPicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Let go of any previously chosen folder.
        resolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach {
                runCatching {
                    resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        prefs.folderUri = uri
        load(uri)
    }

    private fun load(uri: Uri) {
        val name = ImageRepository.folderName(uri)
        uiState = UiState.Loading
        current = null
        viewModelScope.launch {
            val result = runCatching { ImageRepository.loadImages(getApplication<Application>(), uri) }
            val list = result.getOrNull()
            when {
                list == null -> uiState = UiState.Failed(name)
                list.isEmpty() -> uiState = UiState.Empty(name)
                else -> {
                    images = list
                    deck = ShuffleDeck(list.size)
                    playing = true
                    uiState = UiState.Ready(name, list.size)
                    if (!prefs.hintShown) {
                        showHint = true
                        prefs.hintShown = true
                    }
                    next()
                }
            }
        }
    }

    fun next() {
        val d = deck ?: return
        current = Slide(++keyCounter, images[d.next()], forward = true)
    }

    fun previous() {
        val index = deck?.previous() ?: return
        current = Slide(++keyCounter, images[index], forward = false)
    }

    fun upcomingUri(): Uri? = deck?.peekNext()?.let { images.getOrNull(it) }

    fun togglePlaying() {
        playing = !playing
    }

    fun dismissHint() {
        showHint = false
    }

    fun updateSettings(newSettings: Settings) {
        settings = newSettings
        prefs.saveSettings(newSettings)
    }
}
