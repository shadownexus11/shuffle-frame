package com.shuffleframe.app

import android.content.Context
import java.io.File

/**
 * Saves which photos have been shown in the current round, per source, so the shuffle
 * carries on after the app is closed. A plain text file: line 1 is the source key,
 * then one photo address per line. Only the most recent source is kept.
 */
class ProgressStore(context: Context) {
    private val file = File(context.filesDir, "shuffle_progress.txt")

    fun load(key: String): MutableSet<String> = runCatching {
        val lines = file.readLines()
        if (lines.firstOrNull() == key) lines.drop(1).filter { it.isNotBlank() }.toHashSet() else HashSet()
    }.getOrDefault(HashSet())

    @Synchronized
    fun save(key: String, seen: Set<String>) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(key); w.newLine()
                seen.forEach { w.write(it); w.newLine() }
            }
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
