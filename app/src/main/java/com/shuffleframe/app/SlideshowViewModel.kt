package com.shuffleframe.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface Source {
    data class Folder(val uri: Uri) : Source
    /** Empty [ids] means all photos. */
    data class Albums(val ids: List<String>, val name: String) : Source
    data object OnThisDay : Source
}

enum class EmptyKind { Folder, Albums, OnThisDay, AllHidden }

/** What a photo-permission request was for, so we know where to go once it's granted. */
enum class PermissionTarget { AlbumPicker, OnThisDay }

sealed interface UiState {
    data object Loading : UiState
    /** The main menu. [onThisDayCount] is null until known (or if there's no photo access yet). */
    data class ChooseSource(val canGoBack: Boolean, val onThisDayCount: Int?) : UiState
    data class AlbumPicker(
        val albums: List<Album>,
        val limited: Boolean,
        val canGoBack: Boolean,
        val preselected: Set<String>,
    ) : UiState
    data class MediaDenied(val canGoBack: Boolean) : UiState
    data class Empty(val name: String, val kind: EmptyKind, val canGoBack: Boolean) : UiState
    data class Failed(val name: String, val canGoBack: Boolean) : UiState
    data class Ready(val name: String, val count: Int) : UiState
}

/** One photo on screen. [key] is unique per showing, so re-showing a photo still animates. */
data class Slide(val key: Long, val uri: Uri, val forward: Boolean)

class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val progress = ProgressStore(app)
    private val ctx get() = getApplication<Application>()

    /** Saving outlives the ViewModel, so a save started while closing still finishes. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Favourite and hidden photos (shared across every album and folder).
    var favourites by mutableStateOf(prefs.favourites)
        private set
    private var hidden: Set<String> = prefs.hidden
    var hiddenCount by mutableStateOf(hidden.size)
        private set
    /** How many favourites are in the current source, so "Favourites only" can't empty the screen. */
    var favouritesHere by mutableStateOf(0)
        private set
    /** The photo just hidden, while its Undo is on offer. */
    var undoable by mutableStateOf<Uri?>(null)
        private set
    private var undoGroup = -1

    // Music.
    private val music = MusicPlayer(app, viewModelScope)
    private val builtInTracks = BuiltInTracks.all(app)
    var userTracks by mutableStateOf(prefs.userTracks)
        private set
    val tracks: List<Track> get() = builtInTracks + userTracks

    private var currentSource: Source? = null
    private var groups: List<List<Uri>> = emptyList()
    private var playlist: Playlist? = null
    private var seen: MutableSet<String> = HashSet()
    private var sourceKey: String? = null
    private var sinceSave = 0
    private var keyCounter = 0L
    private var lastReady: UiState.Ready? = null

    /** True when there's a slideshow running underneath that "back" can return to. */
    private val canGoBack get() = lastReady != null && playlist != null

    init {
        music.setPlaylist(tracks, settings.musicSelection)
        when (val s = prefs.source) {
            is Source.Folder -> if (hasFolderPermission(s.uri)) load(s) else goHome()
            is Source.Albums, Source.OnThisDay -> if (MediaAccess.has(ctx)) load(s!!) else goHome()
            null -> goHome()
        }
    }

    private fun hasFolderPermission(uri: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    // ---------- main menu and navigation ----------

    private fun goHome() {
        uiState = UiState.ChooseSource(canGoBack, null)
        refreshOnThisDayCount()
    }

    fun chooseSource() = goHome()

    private fun refreshOnThisDayCount() {
        if (!MediaAccess.has(ctx)) return
        viewModelScope.launch {
            val count = runCatching { MediaLibrary.loadOnThisDay(ctx, LocalDate.now()).sumOf { it.size } }.getOrNull()
            val s = uiState
            if (s is UiState.ChooseSource) uiState = s.copy(onThisDayCount = count)
        }
    }

    val canHandleBack: Boolean
        get() = when (uiState) {
            is UiState.ChooseSource -> canGoBack
            is UiState.AlbumPicker, is UiState.MediaDenied, is UiState.Empty, is UiState.Failed -> true
            else -> false
        }

    fun back() {
        val ready = lastReady
        when {
            ready != null && playlist != null -> uiState = ready
            uiState !is UiState.ChooseSource -> goHome()
        }
    }

    fun onMediaPermissionResult(target: PermissionTarget) {
        if (!MediaAccess.has(ctx)) {
            uiState = UiState.MediaDenied(canGoBack)
            return
        }
        when (target) {
            PermissionTarget.AlbumPicker -> openAlbumPicker()
            PermissionTarget.OnThisDay -> playOnThisDay()
        }
    }

    /** Called when the app comes back to the front, e.g. after visiting Settings. */
    fun onResume() {
        val s = uiState
        if (s is UiState.MediaDenied && MediaAccess.has(ctx)) openAlbumPicker()
        if (s is UiState.ChooseSource && s.onThisDayCount == null) refreshOnThisDayCount()
    }

    fun openAlbumPicker() {
        val back = canGoBack
        val preselected = when (val src = currentSource) {
            is Source.Albums -> if (src.ids.isEmpty()) setOf(ALL_PHOTOS_KEY) else src.ids.toSet()
            else -> emptySet()
        }
        uiState = UiState.Loading
        viewModelScope.launch {
            val albums = runCatching { MediaLibrary.loadAlbums(ctx) }.getOrDefault(emptyList())
            uiState = UiState.AlbumPicker(albums, MediaAccess.isLimited(ctx), back, preselected)
        }
    }

    fun onAlbumsPicked(selected: List<Album>) {
        if (selected.isEmpty()) return
        val all = selected.any { it.id == null }
        val name = when {
            all -> "All photos"
            selected.size == 1 -> selected[0].name
            selected.size == 2 -> "${selected[0].name} & ${selected[1].name}"
            else -> "${selected[0].name} + ${selected.size - 1} more"
        }
        val source = Source.Albums(if (all) emptyList() else selected.mapNotNull { it.id }, name)
        prefs.source = source
        load(source)
    }

    fun playOnThisDay() {
        prefs.source = Source.OnThisDay
        load(Source.OnThisDay)
    }

    fun onFolderPicked(uri: Uri) {
        val resolver = ctx.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // Let go of any previously chosen folder (but not access to music the person added).
        val musicUris = userTracks.map { it.uri }.toSet()
        resolver.persistedUriPermissions
            .filter { it.uri != uri && it.uri !in musicUris }
            .forEach {
                runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        val source = Source.Folder(uri)
        prefs.source = source
        load(source)
    }

    // ---------- loading ----------

    private fun keyFor(source: Source, today: LocalDate): String = when (source) {
        is Source.Folder -> "folder:${source.uri}"
        is Source.Albums -> "albums:" + source.ids.sorted().joinToString(",")
        Source.OnThisDay -> "onthisday:${today.monthValue}-${today.dayOfMonth}"
    }

    private fun load(source: Source) {
        val back = canGoBack
        uiState = UiState.Loading
        viewModelScope.launch {
            val today = LocalDate.now()
            val name = when (source) {
                is Source.Folder -> ImageRepository.folderName(source.uri)
                is Source.Albums -> source.name
                Source.OnThisDay -> "On this day · " +
                    today.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
            }
            val loaded = runCatching {
                when (source) {
                    is Source.Folder -> listOf(ImageRepository.loadImages(ctx, source.uri))
                    is Source.Albums -> MediaLibrary.loadAlbumGroups(ctx, source.ids)
                    Source.OnThisDay -> MediaLibrary.loadOnThisDay(ctx, today)
                }
            }.getOrNull()
            val total = loaded?.sumOf { it.size } ?: 0
            when {
                loaded == null -> uiState = UiState.Failed(name, back)
                total == 0 -> uiState = UiState.Empty(
                    name,
                    when (source) {
                        is Source.Folder -> EmptyKind.Folder
                        is Source.Albums -> EmptyKind.Albums
                        Source.OnThisDay -> EmptyKind.OnThisDay
                    },
                    back,
                )
                else -> {
                    saveProgress() // finish the old source's bookkeeping before switching
                    val key = keyFor(source, today)
                    currentSource = source
                    groups = loaded
                    sourceKey = key
                    seen = if (settings.rememberShuffle) progress.load(key) else HashSet()
                    countFavouritesHere()
                    if (settings.favouritesOnly && favouritesHere == 0) updateSettings(settings.copy(favouritesOnly = false))
                    if (!rebuildPlaylist()) {
                        uiState = UiState.Empty(name, EmptyKind.AllHidden, back)
                        return@launch
                    }
                    playing = true
                    val ready = UiState.Ready(name, activeCount())
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

    // ---------- which photos are in play ----------

    private fun isActive(u: Uri): Boolean {
        val k = u.toString()
        return k !in hidden && (!settings.favouritesOnly || k in favourites)
    }

    private fun activeGroups(): List<List<Uri>> = groups.map { g -> g.filter(::isActive) }

    private fun activeCount(): Int = groups.sumOf { g -> g.count(::isActive) }

    private fun countFavouritesHere() {
        favouritesHere = groups.sumOf { g -> g.count { val k = it.toString(); k in favourites && k !in hidden } }
    }

    /**
     * Rebuilds the shuffle from the photos currently in play. In Favourites-only mode the round
     * is tracked separately, so dipping into favourites doesn't wipe progress through everything.
     * Returns false if nothing is left to show.
     */
    private fun rebuildPlaylist(): Boolean {
        val active = activeGroups()
        if (active.all { it.isEmpty() }) {
            playlist = null
            return false
        }
        playlist = Playlist(active, settings.fairShuffle, if (settings.favouritesOnly) HashSet() else seen)
        return true
    }

    private fun refreshCount() {
        val r = lastReady ?: return
        val updated = r.copy(count = activeCount())
        lastReady = updated
        if (uiState is UiState.Ready) uiState = updated
    }

    // ---------- favourites and hiding ----------

    fun isFavourite(uri: Uri): Boolean = uri.toString() in favourites

    fun toggleFavourite() {
        val uri = current?.uri ?: return
        val k = uri.toString()
        favourites = if (k in favourites) favourites - k else favourites + k
        prefs.favourites = favourites
        countFavouritesHere()
        if (settings.favouritesOnly && k !in favourites) {
            // Un-starred in Favourites-only mode: it no longer belongs in this slideshow.
            playlist?.remove(uri)
            refreshCount()
            if (favouritesHere == 0) updateSettings(settings.copy(favouritesOnly = false))
        }
    }

    fun hideCurrent() {
        val uri = current?.uri ?: return
        val p = playlist ?: return
        hidden = hidden + uri.toString()
        prefs.hidden = hidden
        hiddenCount = hidden.size
        undoGroup = p.remove(uri)
        undoable = uri
        countFavouritesHere()
        refreshCount()
        if (p.isEmpty) {
            if (settings.favouritesOnly && favouritesHere == 0 && activeCountIgnoringFavourites() > 0) {
                updateSettings(settings.copy(favouritesOnly = false)) // rebuilds and moves on
            } else {
                val r = lastReady
                playlist = null
                lastReady = null
                uiState = UiState.Empty(r?.name ?: "", EmptyKind.AllHidden, false)
            }
        } else next()
    }

    private fun activeCountIgnoringFavourites() = groups.sumOf { g -> g.count { it.toString() !in hidden } }

    fun undoHide() {
        val uri = undoable ?: return
        undoable = null
        hidden = hidden - uri.toString()
        prefs.hidden = hidden
        hiddenCount = hidden.size
        countFavouritesHere()
        playlist?.restore(uri, undoGroup)
        refreshCount()
        current = Slide(++keyCounter, uri, forward = false)
    }

    fun dismissUndo() {
        undoable = null
    }

    fun unhideAll() {
        hidden = emptySet()
        prefs.hidden = hidden
        hiddenCount = 0
        undoable = null
        countFavouritesHere()
        if (groups.isNotEmpty()) {
            rebuildPlaylist()
            refreshCount()
        }
    }

    /** From the "everything's hidden" screen: bring them all back and start again. */
    fun unhideAllAndReload() {
        unhideAll()
        currentSource?.let { load(it) }
    }

    // ---------- music ----------

    /** The slideshow screen says whether music should be audible right now. */
    fun setMusicWanted(wanted: Boolean) = music.setWanted(wanted)

    fun addTracks(uris: List<Uri>) {
        val resolver = ctx.contentResolver
        val existing = userTracks.map { it.uri }.toSet()
        val added = uris.filter { it !in existing }.map { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()?.substringBeforeLast('.') ?: "Track"
            Track("user:$uri", name, uri, builtIn = false)
        }
        if (added.isEmpty()) return
        userTracks = userTracks + added
        prefs.userTracks = userTracks
        // Adding one track: assume they want to hear it.
        val selection = if (added.size == 1) added[0].id else settings.musicSelection
        updateSettings(settings.copy(musicEnabled = true, musicSelection = selection))
        music.setPlaylist(tracks, settings.musicSelection)
    }

    fun removeTrack(track: Track) {
        if (track.builtIn) return
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(track.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        userTracks = userTracks.filter { it.id != track.id }
        prefs.userTracks = userTracks
        if (settings.musicSelection == track.id) updateSettings(settings.copy(musicSelection = MusicPlayer.MIX))
        music.setPlaylist(tracks, settings.musicSelection)
    }

    // ---------- remembering progress ----------

    fun saveProgress() {
        if (!settings.rememberShuffle) return
        val key = sourceKey ?: return
        val snapshot = seen.toSet()
        ioScope.launch { progress.save(key, snapshot) }
    }

    override fun onCleared() {
        saveProgress()
        music.release()
        super.onCleared()
    }

    // ---------- playback ----------

    fun next() {
        val p = playlist ?: return
        if (p.isEmpty) return
        current = Slide(++keyCounter, p.next(), forward = true)
        if (++sinceSave >= 15) {
            sinceSave = 0
            saveProgress()
        }
    }

    fun previous() {
        val uri = playlist?.previous() ?: return
        current = Slide(++keyCounter, uri, forward = false)
    }

    fun upcomingUri(): Uri? = playlist?.peekNext()

    fun togglePlaying() {
        playing = !playing
    }

    fun dismissHint() {
        showHint = false
    }

    fun updateSettings(newSettings: Settings) {
        val old = settings
        settings = newSettings
        prefs.saveSettings(newSettings)

        if ((old.fairShuffle != newSettings.fairShuffle || old.favouritesOnly != newSettings.favouritesOnly) &&
            groups.isNotEmpty()
        ) {
            rebuildPlaylist()
            refreshCount()
            if (old.favouritesOnly != newSettings.favouritesOnly && uiState is UiState.Ready) next()
        }
        if (old.musicSelection != newSettings.musicSelection) {
            music.setPlaylist(tracks, newSettings.musicSelection)
        }
        if (old.rememberShuffle && !newSettings.rememberShuffle) {
            ioScope.launch { progress.clear() }
        } else if (!old.rememberShuffle && newSettings.rememberShuffle) {
            saveProgress()
        }
    }
}
