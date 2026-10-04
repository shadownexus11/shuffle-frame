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

sealed interface Source {
    data class Folder(val uri: Uri) : Source
    data class Album(val id: String?, val name: String) : Source
}

sealed interface UiState {
    data object Loading : UiState
    data class ChooseSource(val canGoBack: Boolean) : UiState
    data class AlbumPicker(val albums: List<Album>, val limited: Boolean, val canGoBack: Boolean) : UiState
    data class MediaDenied(val canGoBack: Boolean) : UiState
    data class Empty(val name: String, val isFolder: Boolean, val canGoBack: Boolean) : UiState
    data class Failed(val name: String, val canGoBack: Boolean) : UiState
    data class Ready(val name: String, val count: Int) : UiState
}

/** One photo on screen. [key] is unique per showing, so re-showing a photo still animates. */
data class Slide(val key: Long, val uri: Uri, val forward: Boolean)

class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val ctx get() = getApplication<Application>()

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
    private var lastReady: UiState.Ready? = null

    /** True when there's a slideshow running underneath that "back" can return to. */
    private val canGoBack get() = lastReady != null && deck != null

    init {
        when (val s = prefs.source) {
            is Source.Folder -> if (hasFolderPermission(s.uri)) load(s) else uiState = UiState.ChooseSource(false)
            is Source.Album -> if (MediaAccess.has(ctx)) load(s) else uiState = UiState.ChooseSource(false)
            null -> uiState = UiState.ChooseSource(false)
        }
    }

    private fun hasFolderPermission(uri: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    // ---------- choosing where photos come from ----------

    fun chooseSource() {
        uiState = UiState.ChooseSource(canGoBack)
    }

    val canHandleBack: Boolean
        get() = when (uiState) {
            is UiState.ChooseSource -> canGoBack
            is UiState.AlbumPicker, is UiState.MediaDenied, is UiState.Empty, is UiState.Failed -> true
            else -> false
        }

    fun back() {
        val ready = lastReady
        uiState = when {
            ready != null && deck != null -> ready
            uiState is UiState.ChooseSource -> uiState
            else -> UiState.ChooseSource(false)
        }
    }

    fun onMediaPermissionResult() {
        if (MediaAccess.has(ctx)) openAlbumPicker() else uiState = UiState.MediaDenied(canGoBack)
    }

    /** Called when the app comes back to the front, e.g. after visiting Settings. */
    fun onResume() {
        if (uiState is UiState.MediaDenied && MediaAccess.has(ctx)) openAlbumPicker()
    }

    fun openAlbumPicker() {
        val back = canGoBack
        uiState = UiState.Loading
        viewModelScope.launch {
            val albums = runCatching { MediaLibrary.loadAlbums(ctx) }.getOrDefault(emptyList())
            uiState = UiState.AlbumPicker(albums, MediaAccess.isLimited(ctx), back)
        }
    }

    fun onAlbumPicked(album: Album) {
        val source = Source.Album(album.id, album.name)
        prefs.source = source
        load(source)
    }

    fun onFolderPicked(uri: Uri) {
        val resolver = ctx.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // Let go of any previously chosen folder.
        resolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach {
                runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        val source = Source.Folder(uri)
        prefs.source = source
        load(source)
    }

    private fun load(source: Source) {
        val name = when (source) {
            is Source.Folder -> ImageRepository.folderName(source.uri)
            is Source.Album -> source.name
        }
        val back = canGoBack
        uiState = UiState.Loading
        viewModelScope.launch {
            val list = runCatching {
                when (source) {
                    is Source.Folder -> ImageRepository.loadImages(ctx, source.uri)
                    is Source.Album -> MediaLibrary.loadAlbumImages(ctx, source.id)
                }
            }.getOrNull()
            when {
                list == null -> uiState = UiState.Failed(name, back)
                list.isEmpty() -> uiState = UiState.Empty(name, source is Source.Folder, back)
                else -> {
                    images = list
                    deck = ShuffleDeck(list.size)
                    playing = true
                    val ready = UiState.Ready(name, list.size)
                    lastReady = ready
                    uiState = ready
                    if (!prefs.hintShown) {
                        showHint = true
                        prefs.hintShown = true
                    }
                    next()
                }
            }
        }
    }

    // ---------- playback ----------

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
