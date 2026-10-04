package com.shuffleframe.app

import android.content.Context
import android.net.Uri

enum class TransitionStyle(val label: String) {
    Crossfade("Crossfade"),
    Slide("Slide"),
    Zoom("Zoom"),
    FadeToBlack("Fade to black"),
    PageTurn("Page turn"),
}

/** How far Ken Burns pans and zooms over each photo's time on screen. */
enum class KenBurnsSpeed(val label: String) {
    Subtle("Subtle"),
    Normal("Normal"),
    Dramatic("Dramatic"),
}

data class Settings(
    val intervalSeconds: Int = 8,
    val kenBurns: Boolean = true,
    val kenBurnsSpeed: KenBurnsSpeed = KenBurnsSpeed.Normal,
    val transition: TransitionStyle = TransitionStyle.Crossfade,
    val rememberShuffle: Boolean = true,
    val fairShuffle: Boolean = false,
    val favouritesOnly: Boolean = false,
    val musicEnabled: Boolean = false,
    /** A track id, or [MusicPlayer.MIX] for everything shuffled. */
    val musicSelection: String = MusicPlayer.MIX,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("shuffle_prefs", Context.MODE_PRIVATE)

    /** Where the photos come from: Gallery albums, a chosen folder, or On This Day. */
    var source: Source?
        get() {
            val folder = sp.getString(KEY_FOLDER, null)?.let(Uri::parse)
            return when (sp.getString(KEY_SOURCE_TYPE, null) ?: if (folder != null) "folder" else null) {
                "folder" -> folder?.let { Source.Folder(it) }
                "album" -> {
                    // Older builds stored one album id; null meant All photos.
                    val ids = sp.getString(KEY_ALBUM_IDS, null)
                        ?.let { s -> if (s.isEmpty()) emptyList() else s.split(',') }
                        ?: listOfNotNull(sp.getString(KEY_ALBUM_ID, null))
                    Source.Albums(ids, sp.getString(KEY_ALBUM_NAME, null) ?: "Albums")
                }
                "onthisday" -> Source.OnThisDay
                else -> null
            }
        }
        set(value) {
            val e = sp.edit()
            when (value) {
                is Source.Folder -> e.putString(KEY_SOURCE_TYPE, "folder").putString(KEY_FOLDER, value.uri.toString())
                is Source.Albums -> e.putString(KEY_SOURCE_TYPE, "album")
                    .putString(KEY_ALBUM_IDS, value.ids.joinToString(","))
                    .putString(KEY_ALBUM_NAME, value.name)
                Source.OnThisDay -> e.putString(KEY_SOURCE_TYPE, "onthisday")
                null -> e.remove(KEY_SOURCE_TYPE)
            }
            e.apply()
        }

    var hintShown: Boolean
        get() = sp.getBoolean(KEY_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_HINT, value).apply()

    fun loadSettings(): Settings = Settings(
        intervalSeconds = sp.getInt(KEY_INTERVAL, 8).coerceIn(3, 60),
        kenBurns = sp.getBoolean(KEY_KEN_BURNS, true),
        kenBurnsSpeed = runCatching {
            KenBurnsSpeed.valueOf(sp.getString(KEY_KB_SPEED, null) ?: "Normal")
        }.getOrDefault(KenBurnsSpeed.Normal),
        transition = runCatching {
            TransitionStyle.valueOf(sp.getString(KEY_TRANSITION, null) ?: "Crossfade")
        }.getOrDefault(TransitionStyle.Crossfade),
        rememberShuffle = sp.getBoolean(KEY_REMEMBER, true),
        fairShuffle = sp.getBoolean(KEY_FAIR, false),
        favouritesOnly = sp.getBoolean(KEY_FAV_ONLY, false),
        musicEnabled = sp.getBoolean(KEY_MUSIC_ON, false),
        musicSelection = sp.getString(KEY_MUSIC_SELECTION, null) ?: MusicPlayer.MIX,
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putInt(KEY_INTERVAL, s.intervalSeconds)
            .putBoolean(KEY_KEN_BURNS, s.kenBurns)
            .putString(KEY_KB_SPEED, s.kenBurnsSpeed.name)
            .putString(KEY_TRANSITION, s.transition.name)
            .putBoolean(KEY_REMEMBER, s.rememberShuffle)
            .putBoolean(KEY_FAIR, s.fairShuffle)
            .putBoolean(KEY_FAV_ONLY, s.favouritesOnly)
            .putBoolean(KEY_MUSIC_ON, s.musicEnabled)
            .putString(KEY_MUSIC_SELECTION, s.musicSelection)
            .apply()
    }

    // Favourite and hidden photos, by address. Copied on write so the stored set is never mutated.
    var favourites: Set<String>
        get() = sp.getStringSet(KEY_FAVOURITES, emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet(KEY_FAVOURITES, HashSet(value)).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN, HashSet(value)).apply()

    /** Music the person has added, stored one per line as "address<TAB>name". */
    var userTracks: List<Track>
        get() = (sp.getString(KEY_USER_TRACKS, null) ?: "").lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size < 2 || parts[0].isBlank()) null
            else Track(id = "user:" + parts[0], name = parts[1], uri = Uri.parse(parts[0]), builtIn = false)
        }
        set(value) = sp.edit().putString(
            KEY_USER_TRACKS,
            value.joinToString("\n") { "${it.uri}\t${it.name.replace('\t', ' ').replace('\n', ' ')}" },
        ).apply()

    private companion object {
        const val KEY_FOLDER = "folder_uri"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_ALBUM_IDS = "album_ids"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_HINT = "hint_shown"
        const val KEY_INTERVAL = "interval"
        const val KEY_KEN_BURNS = "ken_burns"
        const val KEY_KB_SPEED = "ken_burns_speed"
        const val KEY_TRANSITION = "transition"
        const val KEY_REMEMBER = "remember_shuffle"
        const val KEY_FAIR = "fair_shuffle"
        const val KEY_FAV_ONLY = "favourites_only"
        const val KEY_MUSIC_ON = "music_on"
        const val KEY_MUSIC_SELECTION = "music_selection"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_USER_TRACKS = "user_tracks"
    }
}
