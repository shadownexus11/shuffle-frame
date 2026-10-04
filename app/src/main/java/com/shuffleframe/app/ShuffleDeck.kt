package com.shuffleframe.app

import kotlin.random.Random

/**
 * A shuffled deck of indices 0 until [size].
 *
 * Every item is shown once per round (Fisher-Yates shuffle), then the deck is
 * reshuffled, making sure the new round doesn't open with the photo just shown.
 * A history lets "previous" walk back through what was actually shown.
 */
class ShuffleDeck(private val size: Int, private val random: Random = Random.Default) {

    private var deck = IntArray(0)
    private var deckPos = 0
    private val history = ArrayDeque<Int>()
    private var cursor = -1

    init {
        require(size > 0) { "Deck needs at least one item" }
        reshuffle(avoidFirst = -1)
    }

    private fun reshuffle(avoidFirst: Int) {
        deck = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            swap(i, j)
        }
        if (size > 1 && deck[0] == avoidFirst) {
            swap(0, 1 + random.nextInt(size - 1))
        }
        deckPos = 0
    }

    private fun swap(a: Int, b: Int) {
        val t = deck[a]; deck[a] = deck[b]; deck[b] = t
    }

    /** The next index to show, moving forward through history first if we went back. */
    fun next(): Int {
        if (cursor < history.size - 1) {
            cursor++
            return history[cursor]
        }
        if (deckPos >= size) reshuffle(avoidFirst = history.lastOrNull() ?: -1)
        val value = deck[deckPos++]
        history.addLast(value)
        cursor++
        if (history.size > MAX_HISTORY) {
            history.removeFirst()
            cursor--
        }
        return value
    }

    /** The previously shown index, or null if we're at the start of history. */
    fun previous(): Int? {
        if (cursor <= 0) return null
        cursor--
        return history[cursor]
    }

    /** What [next] will return, if known without reshuffling (used for preloading). */
    fun peekNext(): Int? = when {
        cursor < history.size - 1 -> history[cursor + 1]
        deckPos < size -> deck[deckPos]
        else -> null
    }

    private companion object {
        const val MAX_HISTORY = 500
    }
}
