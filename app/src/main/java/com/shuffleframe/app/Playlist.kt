package com.shuffleframe.app

import android.net.Uri
import kotlin.random.Random

/**
 * Decides which photo comes next.
 *
 * Normal mode: every photo once per round in random order (a shuffled deck), then reshuffle.
 *
 * Fair mode: photos are kept in groups (albums, or years for On This Day). Groups take turns
 * in a shuffled rotation, each drawing from its own shuffled deck. A 200-photo album then gets
 * the same screen time as an 8,000-photo one, which means its photos come round far more often.
 *
 * [seen] holds the photos already shown this round. Passing in a saved set lets the shuffle
 * carry on from where it left off; new photos simply join the not-yet-seen pool.
 */
class Playlist(
    groups: List<List<Uri>>,
    fair: Boolean,
    private val seen: MutableSet<String>,
    private val random: Random = Random.Default,
) {
    private val groups: List<MutableList<Uri>> =
        (if (fair) groups.filter { it.isNotEmpty() } else listOf(groups.flatten().distinct()))
            .map { it.toMutableList() }

    private val decks = Array(this.groups.size) { ArrayDeque<Uri>() }
    private val groupBag = ArrayDeque<Int>()
    private var lastGroup = -1
    private var lastShown: Uri? = null

    private val history = ArrayDeque<Uri>()
    private var cursor = -1
    private var pending: Uri? = null

    init {
        require(this.groups.isNotEmpty() && this.groups.any { it.isNotEmpty() }) { "No photos" }
        // Forget photos that have since been deleted.
        val existing = HashSet<String>()
        this.groups.forEach { g -> g.forEach { existing.add(it.toString()) } }
        seen.retainAll(existing)
        for (g in this.groups.indices) refill(g)
    }

    /** Fill a group's deck with its unseen photos, starting a fresh round if all were seen. */
    private fun refill(g: Int) {
        val items = groups[g]
        var pool = items.filter { it.toString() !in seen }
        if (pool.isEmpty()) {
            items.forEach { seen.remove(it.toString()) }
            pool = items
        }
        val shuffled = pool.shuffled(random).toMutableList()
        // Don't open a new round with the photo that's just been on screen.
        if (shuffled.size > 1 && shuffled[0] == lastShown) {
            val j = 1 + random.nextInt(shuffled.size - 1)
            shuffled[0] = shuffled[j].also { shuffled[j] = shuffled[0] }
        }
        decks[g].clear()
        decks[g].addAll(shuffled)
    }

    private fun pickGroup(): Int {
        if (groups.size == 1) return 0
        if (groupBag.isEmpty()) {
            val order = groups.indices.shuffled(random).toMutableList()
            if (order.size > 1 && order[0] == lastGroup) {
                order[0] = order[1].also { order[1] = order[0] }
            }
            groupBag.addAll(order)
        }
        return groupBag.removeFirst().also { lastGroup = it }
    }

    private fun draw(): Uri {
        // A group can be emptied by hiding photos, so try each group before giving up.
        repeat(groups.size + 1) {
            val g = pickGroup()
            if (decks[g].isEmpty()) refill(g)
            decks[g].removeFirstOrNull()?.let { return it }
        }
        throw NoSuchElementException("No photos left")
    }

    val isEmpty: Boolean get() = groups.all { it.isEmpty() }

    /**
     * Takes a photo out of the shuffle (when it's hidden), keeping the rest of the round and
     * the back/forward history intact. Returns its group, so [restore] can put it back.
     */
    fun remove(uri: Uri): Int {
        var group = -1
        groups.forEachIndexed { i, g -> if (g.remove(uri)) group = i }
        decks.forEach { it.remove(uri) }
        if (pending == uri) pending = null
        var i = 0
        while (i < history.size) {
            if (history[i] == uri) {
                history.removeAt(i)
                if (i <= cursor) cursor--
            } else i++
        }
        seen.remove(uri.toString())
        return group
    }

    /** Undo for [remove]: the photo rejoins its group and will come round again this round. */
    fun restore(uri: Uri, group: Int) {
        if (group !in groups.indices || uri in groups[group]) return
        groups[group].add(uri)
        val deck = decks[group]
        deck.add(if (deck.isEmpty()) 0 else random.nextInt(deck.size + 1), uri)
    }

    fun next(): Uri {
        if (cursor < history.size - 1) {
            cursor++
            return history[cursor]
        }
        val uri = pending ?: draw()
        pending = null
        seen.add(uri.toString())
        lastShown = uri
        history.addLast(uri)
        cursor++
        if (history.size > MAX_HISTORY) {
            history.removeFirst()
            cursor--
        }
        return uri
    }

    fun previous(): Uri? {
        if (cursor <= 0) return null
        cursor--
        return history[cursor]
    }

    /** What [next] will return, so it can be loaded in advance. */
    fun peekNext(): Uri =
        if (cursor < history.size - 1) history[cursor + 1]
        else pending ?: draw().also { pending = it }

    private companion object {
        const val MAX_HISTORY = 500
    }
}
