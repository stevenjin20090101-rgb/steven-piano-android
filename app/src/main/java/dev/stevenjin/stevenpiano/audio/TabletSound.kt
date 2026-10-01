// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiSink
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.ModelEntry
import dev.stevenjin.stevenpiano.studio.ModelInstaller
import dev.stevenjin.stevenpiano.studio.ModelStore
import dev.stevenjin.stevenpiano.studio.StudioFailure
import dev.stevenjin.stevenpiano.studio.StudioFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The player's sink with the tablet beside the link (v1.8 — M25): each batch goes to [link] first, then
 * to [tablet], on the player's thread at the same moment, so the piano is never kept waiting by the
 * tablet (whose sink only queues, [PianoVoice]).
 */
class TeeSink(private val link: MidiSink, private val tablet: MidiSink) : MidiSink {
    override fun send(batch: MidiBatch, dropPending: Boolean) {
        link.send(batch, dropPending)
        tablet.send(batch, dropPending)
    }

    /** Live keys (v1.11 — M29): the link's front lane first, then the tablet, as [send]. */
    override fun sendLive(batch: MidiBatch) {
        link.sendLive(batch)
        tablet.sendLive(batch)
    }
}

/**
 * When the tablet plays the piano sound (Piano › Playback › TABLET SOUND, v1.8 — M25): never; only while
 * the piano isn't connected (the default, so it never doubles the real piano out of step); or always,
 * with the piano too (Steven's choice: it may sound slightly early or late against the piano).
 */
enum class TabletSoundMode {
    OFF,
    WHEN_NOT_CONNECTED,
    ALWAYS,
    ;

    /** Whether the mode has the tablet sound now, the piano [connected] or not. */
    fun sounds(connected: Boolean): Boolean = when (this) {
        OFF -> false
        WHEN_NOT_CONNECTED -> !connected
        ALWAYS -> true
    }
}

/** The piano sound's download: under way ([Running], [bytes] of [total]) or failed ([Failed], its line). */
sealed interface SoundDownload {
    data class Running(val bytes: Long, val total: Long) : SoundDownload

    data class Failed(val line: String) : SoundDownload
}

/**
 * The tablet's sound as the Playback page, Now playing, the Keys screen and the web panel show it: the
 * [mode] and [volume] as set, whether the piano is [connected], whether the SoundFont is [installed],
 * and its [download].
 */
data class TabletSoundState(
    val mode: TabletSoundMode = TabletSoundMode.WHEN_NOT_CONNECTED,
    val volume: Int = Sampler.DEFAULT_VOLUME,
    val connected: Boolean = false,
    val installed: Boolean = false,
    val download: SoundDownload? = null,
) {
    /** The mode would have the tablet sound now. */
    val wanted: Boolean get() = mode.sounds(connected)

    /** The tablet sounds now: the mode wants it and the SoundFont is here. */
    val active: Boolean get() = wanted && installed

    /** The mode wants sound but the SoundFont isn't here: the app offers its download. */
    val needsDownload: Boolean get() = wanted && !installed

    val downloading: Boolean get() = download is SoundDownload.Running
}

/**
 * The tablet's piano sound (v1.8 — M25), what the rest of the app holds: its [state], the [voice] it plays
 * with, and the [sink] the player sends through beside the link. [follow] hands it the mode, the volume
 * and whether the piano is connected as they change; the sink passes what the player sends (the same
 * notes, pedal and stop sequence as the piano, at the same moments) only while the sound is active, and
 * the moment it stops being active (the piano connects in "When the piano isn't connected", the mode
 * changes, the SoundFont goes) everything sounding fades out. The SoundFont is read (its SHA-256 checked
 * once per process, then memory-mapped) whenever the mode isn't Off and it is installed, and let go when
 * the mode is Off. [download] brings it from the release `models` through Studio's verified download path
 * ([ModelInstaller]), with its progress, [cancelDownload] and failures in words; [remove] deletes it.
 * [playing] tells it whether the player plays, so [keepOpen] can hold the audio output open between the
 * notes of a piece; [focusLost] silences it.
 */
class TabletSound(
    private val scope: CoroutineScope,
    private val store: ModelStore,
    private val installer: ModelInstaller,
    private val online: () -> Boolean,
    val voice: PianoVoice,
    private val model: ModelEntry = ModelCatalogue.pianoSound,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val read: (File) -> SoundFont = Sf2Reader::read,
    private val log: (String) -> Unit = {},
    /** Called when [keepOpen] may have changed (the audio output looks again). */
    private val poke: () -> Unit = {},
) {
    private val _state = MutableStateFlow(TabletSoundState())
    val state: StateFlow<TabletSoundState> = _state.asStateFlow()

    /** Whether the tablet sounds now; read by [sink] on the player's thread. */
    @Volatile
    var active = false
        private set

    /** What the player sends beside the link. */
    val sink: MidiSink = voice.sink { active }

    @Volatile
    private var playing = false

    /** Whether the audio output stays open though nothing sounds: a piece is playing and the tablet sounds. */
    val keepOpen: Boolean get() = active && playing

    private var downloadJob: Job? = null
    private var loadJob: Job? = null

    /** The file the voice plays, or null. */
    private var loadedFrom: File? = null

    /** Reads what is installed, and loads the SoundFont if the mode wants it. */
    fun start() {
        scope.launch {
            val installed = withContext(io) { runCatching { model.name in store.refresh() }.getOrDefault(false) }
            _state.update { it.copy(installed = installed) }
            settle()
        }
    }

    /** The mode, the volume and the piano's link, as they are now. */
    fun follow(mode: TabletSoundMode, volume: Int, connected: Boolean) {
        voice.volume(volume)
        _state.update { it.copy(mode = mode, volume = volume.coerceIn(0, 100), connected = connected) }
        settle()
    }

    /** Whether the player is playing (for [keepOpen]). */
    fun playing(on: Boolean) {
        if (playing == on) return
        playing = on
        poke()
    }

    /** Audio focus went elsewhere: everything sounding has gone (the output did it); nothing to hold open. */
    fun focusLost() {
        log("Tablet sound: stopped for another app's sound")
    }

    /** Downloads the SoundFont, unless it is here or on its way. */
    fun download() {
        if (downloadJob?.isActive == true || _state.value.installed) return
        _state.update { it.copy(download = SoundDownload.Running(0, model.sizeBytes)) }
        downloadJob = scope.launch {
            try {
                if (!online()) throw StudioFailure(StudioFailures.OFFLINE)
                withContext(io) {
                    installer.install(model) { bytes, total -> _state.update { it.copy(download = SoundDownload.Running(bytes, total)) } }
                }
                log("Tablet sound: ${model.file} downloaded and verified")
                _state.update { it.copy(download = null, installed = true) }
                settle()
            } catch (e: CancellationException) {
                _state.update { it.copy(download = null) }
                throw e
            } catch (e: StudioFailure) {
                _state.update { it.copy(download = SoundDownload.Failed(failureLine(e.message))) }
            } catch (e: Exception) {
                log("Tablet sound: the download failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(download = SoundDownload.Failed(failureLine(StudioFailures.UNREACHABLE))) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Deletes the SoundFont (the voice lets go of it first). */
    fun remove() {
        cancelDownload()
        active = false
        voice.load(null)
        loadedFrom = null
        loadJob?.cancel()
        _state.update { it.copy(installed = false, download = null) }
        scope.launch {
            withContext(io) { runCatching { store.remove(model) } }
            settle()
        }
    }

    /** Brings [active] and the loaded SoundFont in line with the state. */
    private fun settle() {
        val s = _state.value
        val nowActive = s.active && voice.loaded
        if (active && !nowActive) voice.silence()
        if (active != nowActive) {
            val why = when {
                nowActive -> "sounding (${s.mode.name.lowercase()}, ${if (s.connected) "the piano connected" else "no piano"})"
                s.mode == TabletSoundMode.OFF -> "silent (off)"
                s.connected && s.mode == TabletSoundMode.WHEN_NOT_CONNECTED -> "silent (the piano is connected)"
                else -> "silent (no SoundFont)"
            }
            log("Tablet sound: $why after ${voice.notesPosted.get()} notes")
        }
        active = nowActive
        poke()
        when {
            s.mode == TabletSoundMode.OFF || !s.installed -> unload()
            loadedFrom == null && loadJob?.isActive != true -> load()
        }
    }

    private fun load() {
        loadJob = scope.launch {
            val font = withContext(io) {
                try {
                    val started = System.nanoTime()
                    val file = store.open(model) ?: return@withContext null   // the hash, once per process
                    val checked = System.nanoTime()
                    read(file).also {
                        val ms = { from: Long, to: Long -> (to - from) / 1_000_000 }
                        log("Tablet sound: ${it.name} checked in ${ms(started, checked)} ms, read in ${ms(checked, System.nanoTime())} ms, ${it.regions.size} regions")
                    } to file
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Tablet sound: the SoundFont couldn't be read: ${e.message}")
                    null
                }
            }
            if (font == null) {
                // Missing, damaged (and deleted by the store) or unreadable: it reads as not installed.
                _state.update { it.copy(installed = model.name in store.installed.value) }
                return@launch
            }
            val s = _state.value
            if (s.mode == TabletSoundMode.OFF || !s.installed) return@launch
            voice.load(font.first)
            loadedFrom = font.second
            settle()
        }
    }

    private fun unload() {
        loadJob?.cancel()
        if (loadedFrom == null && !voice.loaded) return
        active = false
        voice.load(null)
        loadedFrom = null
    }

    companion object {
        /** A failure's line, with the model's words made the piano sound's. */
        fun failureLine(message: String?): String = when (message) {
            StudioFailures.OFFLINE -> "Downloading the piano sound needs an internet connection."
            StudioFailures.NOT_OFFERED -> "The piano sound isn't offered right now."
            StudioFailures.MISMATCH -> "The download didn't match the piano sound; try again."
            StudioFailures.NO_ROOM -> "There isn't enough free space for the piano sound."
            StudioFailures.UNREADABLE -> "The list of downloads couldn't be read."
            null -> StudioFailures.UNREACHABLE
            else -> message
        }
    }
}
