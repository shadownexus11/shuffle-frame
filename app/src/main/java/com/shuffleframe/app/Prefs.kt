package com.shuffleframe.app

import android.content.Context
import android.net.Uri

enum class TransitionStyle(val label: String) {
    Crossfade("Crossfade"),
    Slide("Slide"),
    Zoom("Zoom"),
}

data class Settings(
    val intervalSeconds: Int = 8,
    val kenBurns: Boolean = true,
    val transition: TransitionStyle = TransitionStyle.Crossfade,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("shuffle_prefs", Context.MODE_PRIVATE)

    var folderUri: Uri?
        get() = sp.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) = sp.edit().putString(KEY_FOLDER, value?.toString()).apply()

    var hintShown: Boolean
        get() = sp.getBoolean(KEY_HINT, false)
        set(value) = sp.edit().putBoolean(KEY_HINT, value).apply()

    fun loadSettings(): Settings = Settings(
        intervalSeconds = sp.getInt(KEY_INTERVAL, 8).coerceIn(3, 60),
        kenBurns = sp.getBoolean(KEY_KEN_BURNS, true),
        transition = runCatching {
            TransitionStyle.valueOf(sp.getString(KEY_TRANSITION, null) ?: "Crossfade")
        }.getOrDefault(TransitionStyle.Crossfade),
    )

    fun saveSettings(s: Settings) {
        sp.edit()
            .putInt(KEY_INTERVAL, s.intervalSeconds)
            .putBoolean(KEY_KEN_BURNS, s.kenBurns)
            .putString(KEY_TRANSITION, s.transition.name)
            .apply()
    }

    private companion object {
        const val KEY_FOLDER = "folder_uri"
        const val KEY_HINT = "hint_shown"
        const val KEY_INTERVAL = "interval"
        const val KEY_KEN_BURNS = "ken_burns"
        const val KEY_TRANSITION = "transition"
    }
}
