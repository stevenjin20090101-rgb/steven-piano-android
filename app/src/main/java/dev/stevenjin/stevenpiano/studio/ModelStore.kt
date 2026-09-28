// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Studio's models on the device: `filesDir/models/<name>-v<n>.onnx` ([file]), private to the app and
 * kept out of backups with the rest of `files/`. A model counts as [installed] when its file is there
 * at its pinned size; before a model is used ([open]) its SHA-256 is checked against the pin, once per
 * process (a file that no longer matches is deleted, and the model reads as not installed). A file
 * that has just been downloaded and verified ([downloaded]) needs no second check. [installed] is a
 * flow of the installed models' names, which the Studio page, the hub and the web panel follow.
 * Blocking I/O: call it off the main thread.
 */
class ModelStore(
    val dir: File,
    private val models: List<ModelEntry> = ModelCatalogue.all,
    private val sha256: (File) -> String? = VerifiedDownloader::sha256Of,
) {
    private val verified = ConcurrentHashMap.newKeySet<String>()
    private val state = MutableStateFlow<Set<String>>(emptySet())

    /** The names of the models whose files are here at their pinned size. Up to date after [refresh]. */
    val installed: StateFlow<Set<String>> = state.asStateFlow()

    /** Where [model]'s file lives (whether or not it is there). */
    fun file(model: ModelEntry): File = File(dir, model.file)

    /** Whether [model]'s file is here, at its pinned size (its hash is checked when it is opened). */
    fun isInstalled(model: ModelEntry): Boolean = file(model).let { it.isFile && it.length() == model.sizeBytes }

    /** Reads the folder again. */
    fun refresh(): Set<String> = models.filter(::isInstalled).map { it.name }.toSet().also { state.value = it }

    /**
     * [model]'s file, ready to load: its SHA-256 checked against the pin the first time in this process.
     * Null when it is missing, the wrong size, or no longer hashes to the pin (then it is deleted).
     */
    fun open(model: ModelEntry): File? {
        val file = file(model)
        if (!isInstalled(model)) return null
        if (model.name in verified) return file
        if (sha256(file) != model.sha256) {
            file.delete()
            refresh()
            return null
        }
        verified += model.name
        return file
    }

    /** [model]'s file has just arrived and matched its pin ([VerifiedDownloader]): no second check before its first use. */
    fun downloaded(model: ModelEntry) {
        verified += model.name
        refresh()
    }

    /** Removes [model]'s file (and a download of it left half-way). */
    fun remove(model: ModelEntry) {
        verified -= model.name
        file(model).delete()
        File(dir, model.file + VerifiedDownloader.PART).delete()
        refresh()
    }

    /** The bytes the installed models take. */
    fun sizeOnDisk(): Long = models.filter(::isInstalled).sumOf { it.sizeBytes }

    /**
     * At the app's start: downloads a stopped process left half-way (`*.part`), and files no model of
     * this build names (an older version's), are removed; then the folder is read.
     */
    fun sweep() {
        val names = models.map { it.file }.toSet()
        dir.listFiles()?.forEach { if (it.isFile && it.name !in names) it.delete() }
        refresh()
    }
}
