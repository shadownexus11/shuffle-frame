package com.shuffleframe.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Track(val id: String, val name: String, val uri: Uri, val builtIn: Boolean)

object BuiltInTracks {
    private val tracks = listOf(
        "drift" to "Drift",
        "lantern" to "Lantern",
        "tide" to "Tide",
        "morning" to "Morning",
    )

    fun all(context: Context): List<Track> = tracks.mapNotNull { (res, name) ->
        val id = context.resources.getIdentifier(res, "raw", context.packageName)
        if (id == 0) null
        else Track("builtin:$res", name, Uri.parse("android.resource://${context.packageName}/$id"), builtIn = true)
    }
}

/**
 * Plays background music: one track on repeat, or a random mix of all tracks.
 * Fades in and out, and yields to calls and other apps via audio focus.
 */
class MusicPlayer(private val context: Context, private val scope: CoroutineScope) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var player: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var queueIndex = 0
    private var loopSingle = false
    private var wanted = false          // the app would like music playing
    private var lostFocus = false       // a call or another app has taken over
    private var fadeJob: Job? = null
    private var volume = 0f
    private var failures = 0            // consecutive tracks that wouldn't play

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    lostFocus = true
                    fadeTo(0f, 300) { player?.pause() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> fadeTo(0.25f, 300)
                AudioManager.AUDIOFOCUS_GAIN -> {
                    lostFocus = false
                    if (wanted) resume()
                }
            }
        }
        .build()

    /** Sets what to play. [selection] is a track id, or "mix" for everything shuffled. */
    fun setPlaylist(tracks: List<Track>, selection: String) {
        val newQueue: List<Track>
        val single: Boolean
        if (selection == MIX || tracks.none { it.id == selection }) {
            newQueue = tracks.shuffled()
            single = newQueue.size <= 1
        } else {
            newQueue = listOfNotNull(tracks.firstOrNull { it.id == selection })
            single = true
        }
        val sameTrackPlaying = player != null && queue.getOrNull(queueIndex)?.id ==
            newQueue.firstOrNull()?.id && single == loopSingle && newQueue.size == queue.size
        if (sameTrackPlaying) return
        queue = newQueue
        loopSingle = single
        queueIndex = 0
        releasePlayer()
        if (wanted) startCurrent()
    }

    /** True: fade music in (or keep it playing). False: fade out and pause. */
    fun setWanted(play: Boolean) {
        if (play == wanted) return
        wanted = play
        if (play) {
            if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                lostFocus = false
                resume()
            }
        } else {
            fadeTo(0f, 700) {
                player?.pause()
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    private fun resume() {
        if (lostFocus) return
        val p = player
        if (p == null) startCurrent()
        else {
            p.start()
            fadeTo(1f, 1500)
        }
    }

    private fun startCurrent() {
        val track = queue.getOrNull(queueIndex) ?: return
        val p = MediaPlayer()
        runCatching {
            p.setAudioAttributes(attributes)
            p.setDataSource(context, track.uri)
            p.isLooping = loopSingle
            p.setOnCompletionListener { advance() }
            p.setOnErrorListener { _, _, _ -> skipBroken(); true }
            p.setOnPreparedListener {
                failures = 0
                volume = 0f
                it.setVolume(0f, 0f)
                if (wanted && !lostFocus) {
                    it.start()
                    fadeTo(1f, 1500)
                }
            }
            p.prepareAsync()
            player = p
        }.onFailure {
            p.release()
            skipBroken()
        }
    }

    /** A track that can't be played (e.g. a deleted file): skip it, but don't spin forever. */
    private fun skipBroken() {
        failures++
        if (failures < queue.size) advance() else releasePlayer()
    }

    private fun advance() {
        if (queue.isEmpty()) return
        releasePlayer()
        queueIndex = (queueIndex + 1) % queue.size
        if (queueIndex == 0 && queue.size > 2) {
            // New round of the mix: reshuffle, but don't repeat the track just played.
            val last = queue.last()
            queue = queue.shuffled().let { if (it.first() == last) it.drop(1) + it.first() else it }
        }
        if (wanted) startCurrent()
    }

    private fun fadeTo(target: Float, millis: Long, then: (() -> Unit)? = null) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = 20
            val start = volume
            for (i in 1..steps) {
                volume = start + (target - start) * i / steps
                runCatching { player?.setVolume(volume, volume) }
                delay(millis / steps)
            }
            then?.invoke()
        }
    }

    private fun releasePlayer() {
        fadeJob?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        volume = 0f
    }

    fun release() {
        wanted = false
        releasePlayer()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        const val MIX = "mix"
    }
}
