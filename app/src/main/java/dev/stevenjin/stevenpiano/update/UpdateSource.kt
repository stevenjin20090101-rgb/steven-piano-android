// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Where updates come from, and every address the updater may reach. Built without Android, so it
 * is unit-tested.
 *
 * [production]: the manifest at [MANIFEST_URL] on `raw.githubusercontent.com` (under the app's own
 * repository); the file a manifest names on `github.com`, only under [DOWNLOAD_PREFIX] (a release
 * asset of this repository); and the two hosts GitHub redirects a release download to,
 * `objects.githubusercontent.com` and `release-assets.githubusercontent.com`. HTTPS only, port 443,
 * no user info, no backslash, hosts compared exactly, as `java.net.URI` reads the address (so as
 * the connection will). Each redirect passes the same test before anything is sent to it.
 *
 * [local]: debug builds on an emulator only (see `UpdateOverride`): one origin (scheme, host, port)
 * for the manifest and its file, plain HTTP allowed, so the updater can be exercised against a
 * server on the Mac. Release builds never make one.
 *
 * [firmware] (v1.6 — M21): the piano's firmware releases, from their own repository
 * ([FIRMWARE_REPOSITORY], `firmware/docs/BLE_OTA.md` › 10 and 15): the manifest at
 * [FIRMWARE_MANIFEST_URL] and nothing else on `raw.githubusercontent.com`, binaries only under
 * [FIRMWARE_DOWNLOAD_PREFIX] on `github.com`, and GitHub's two asset hosts for the redirect. Its own
 * cap ([MAX_FIRMWARE_BYTES]), apart from the APK's. The two allow-lists never overlap: the app's
 * source refuses the firmware's addresses and the firmware's refuses the app's.
 *
 * [models] (v1.7 — M23, Studio): the models' list at [MODELS_MANIFEST_URL] exactly, and the files it
 * names: `.onnx` assets of this repository's release tagged [MODELS_TAG] on `github.com`
 * ([allowsModel]), with GitHub's two asset hosts for the redirect; its own cap ([MAX_MODEL_BYTES]).
 * [localModels] is its emulator stand-in (debug builds only, as [local]).
 */
class UpdateSource private constructor(
    /** The manifest's address. */
    val manifestUrl: String,
    private val origin: Origin?,
    /** Whose releases: the app's own, the piano's firmware ([firmware]) or Studio's models ([models]). */
    private val kind: Kind = Kind.App,
) {
    private data class Origin(val scheme: String, val host: String, val port: Int)

    private enum class Kind { App, Firmware, Models }

    /** Whether a request, or a redirect of one, may go to [url]. */
    fun allowsHop(url: String): Boolean {
        if (kind == Kind.Firmware) return allowsFirmwareManifest(url) || allowsFirmwareBinary(url)
        val uri = parse(url) ?: return false
        if (origin != null) return originOf(uri) == origin
        if (kind == Kind.Models) return allowsModelManifest(url) || allowsModelBinary(url)
        if (!secure(uri)) return false
        val path = uri.rawPath.orEmpty()
        return when (uri.host.lowercase(Locale.ROOT)) {
            MANIFEST_HOST -> path.startsWith(REPOSITORY_PATH) && safeSegments(path)
            DOWNLOAD_HOST -> releaseAsset(path)
            in ASSET_HOSTS -> true
            else -> false
        }
    }

    /**
     * Whether a manifest may name [url] as the file to download: a release asset of this
     * repository on `github.com` (`https://github.com/<owner>/<repo>/releases/download/<tag>/<name>.apk`),
     * with no query or fragment; with a [local] source, an `.apk` on its origin.
     */
    fun allowsApk(url: String): Boolean {
        if (kind != Kind.App) return false
        val uri = parse(url) ?: return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath.orEmpty()
        if (origin != null) return originOf(uri) == origin && path.endsWith(".apk") && safeSegments(path)
        return secure(uri) && uri.host.lowercase(Locale.ROOT) == DOWNLOAD_HOST && releaseAsset(path) && path.endsWith(".apk")
    }

    /**
     * Whether a models' list may name [url] as a model's file: with [models], an `.onnx` asset of this
     * repository's release tagged [MODELS_TAG] on `github.com`, a plain name, no query or fragment
     * ([allowsModelFile]); with [localModels], an `.onnx` file on its origin. Never for the app's own
     * or the firmware's source. [extension] is `.sf2` for a sound (v1.8 — M25); nothing else is allowed.
     */
    fun allowsModel(url: String, extension: String = MODEL_EXTENSION): Boolean {
        if (kind != Kind.Models || extension !in MODEL_EXTENSIONS) return false
        val uri = parse(url) ?: return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath.orEmpty()
        if (origin != null) return originOf(uri) == origin && path.endsWith(extension) && safeSegments(path)
        return allowsModelFile(url, extension)
    }

    /** Whether this is the production source (the only one release builds have). */
    val isProduction: Boolean get() = origin == null

    companion object {
        const val REPOSITORY = "stevenjin20090101-rgb/steven-piano-android"
        const val MANIFEST_HOST = "raw.githubusercontent.com"
        const val DOWNLOAD_HOST = "github.com"
        const val MANIFEST_URL = "https://$MANIFEST_HOST/$REPOSITORY/main/releases/latest.json"

        /** Release assets of this repository: `/<owner>/<repo>/releases/download/<tag>/<file>`. */
        const val DOWNLOAD_PREFIX = "/$REPOSITORY/releases/download/"

        /** Where GitHub redirects a release download (the file itself, behind a signed, expiring URL). */
        val ASSET_HOSTS: Set<String> = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")

        /** Every host the updater may reach in release builds. */
        val HOSTS: Set<String> = setOf(MANIFEST_HOST, DOWNLOAD_HOST) + ASSET_HOSTS

        private const val REPOSITORY_PATH = "/$REPOSITORY/"
        private const val HTTPS_PORT = 443
        private val SEGMENT = Regex("[A-Za-z0-9._-]+")

        /** The app's updates, from its GitHub repository. */
        val production = UpdateSource(MANIFEST_URL, origin = null)

        /**
         * The piano's firmware repository (renamed on GitHub on 2026-09-27; BLE_OTA.md › 15). Exact
         * paths only: a redirect from an old name to this one is never followed.
         */
        const val FIRMWARE_REPOSITORY = "stevenjin20090101-rgb/Steven-Jin-Player-Piano"

        /** The firmware's release manifest: this address exactly. */
        const val FIRMWARE_MANIFEST_URL = "https://$MANIFEST_HOST/$FIRMWARE_REPOSITORY/main/releases/latest.json"

        /** The firmware's binaries: release assets of its repository, `/<owner>/<repo>/releases/download/<tag>/<file>`. */
        const val FIRMWARE_DOWNLOAD_PREFIX = "/$FIRMWARE_REPOSITORY/releases/download/"

        /**
         * The largest firmware image the app downloads: today's is about 0.97 MB and a slot holds
         * 6.25 MB (BLE_OTA.md › 1). Its own cap, apart from the APK's [UpdateManifest.MAX_APK_BYTES].
         */
        const val MAX_FIRMWARE_BYTES = 4L * 1024 * 1024

        /** The piano's firmware releases: their manifest, their binaries and GitHub's asset hosts, nothing else. */
        val firmware = UpdateSource(FIRMWARE_MANIFEST_URL, origin = null, kind = Kind.Firmware)

        /** Studio's models (v1.7 — M23): assets of this repository's release with this tag, never a version tag. */
        const val MODELS_TAG = "models"

        /** The models' list, `releases/models.json` on `main`: this address exactly. */
        const val MODELS_MANIFEST_URL = "https://$MANIFEST_HOST/$REPOSITORY/main/releases/models.json"

        /** The models' files: `/<owner>/<repo>/releases/download/models/<file>.onnx`. */
        const val MODELS_DOWNLOAD_PREFIX = "$DOWNLOAD_PREFIX$MODELS_TAG/"

        /**
         * The largest model the app downloads: today's are 124.5 MB and 173.2 MB. Its own cap, apart from
         * the APK's and the firmware's.
         */
        const val MAX_MODEL_BYTES = 1024L * 1024 * 1024

        /**
         * The largest sound the app downloads (v1.8 — M25): the piano's SoundFont is 57.4 MB. Its own cap,
         * apart from the models'.
         */
        const val MAX_SOUND_BYTES = 200L * 1024 * 1024

        /** A Studio model's file. */
        const val MODEL_EXTENSION = ".onnx"

        /** A sound's file (v1.8 — M25). */
        const val SOUND_EXTENSION = ".sf2"

        /** What the models' list may name: models and sounds, nothing else. */
        private val MODEL_EXTENSIONS = setOf(MODEL_EXTENSION, SOUND_EXTENSION)

        /** Studio's models: their list, their files, and GitHub's asset hosts; nothing else. */
        val models = UpdateSource(MODELS_MANIFEST_URL, origin = null, kind = Kind.Models)

        /** Whether [url] is the models' list: [MODELS_MANIFEST_URL] exactly (HTTPS on 443, no query or fragment). */
        fun allowsModelManifest(url: String): Boolean {
            val uri = parse(url) ?: return false
            if (!secure(uri) || uri.rawQuery != null || uri.rawFragment != null) return false
            return uri.host.lowercase(Locale.ROOT) == MANIFEST_HOST && uri.rawPath == "/$REPOSITORY/main/releases/models.json"
        }

        /**
         * Whether a model's download, or a redirect of one, may go to [url]: an asset of the release
         * tagged [MODELS_TAG] on `github.com`, or one of GitHub's two asset hosts (signed, expiring
         * addresses). HTTPS on port 443 only.
         */
        fun allowsModelBinary(url: String): Boolean {
            val uri = parse(url) ?: return false
            if (!secure(uri)) return false
            return when (uri.host.lowercase(Locale.ROOT)) {
                DOWNLOAD_HOST -> modelAsset(uri.rawPath.orEmpty())
                in ASSET_HOSTS -> true
                else -> false
            }
        }

        /**
         * Whether the models' list may name [url] as a model's file: an `.onnx` asset (or with [extension]
         * `.sf2`, a sound's: v1.8 — M25) of the release tagged [MODELS_TAG] on `github.com`, no query or
         * fragment. An asset host is never named directly (GitHub redirects there by itself).
         */
        fun allowsModelFile(url: String, extension: String = MODEL_EXTENSION): Boolean {
            if (extension !in MODEL_EXTENSIONS) return false
            val uri = parse(url) ?: return false
            if (uri.rawQuery != null || uri.rawFragment != null || !secure(uri)) return false
            val path = uri.rawPath.orEmpty()
            return uri.host.lowercase(Locale.ROOT) == DOWNLOAD_HOST && modelAsset(path) && path.endsWith(extension)
        }

        /**
         * The models' list and files at one origin, for the emulator (debug builds only, as [local]): the
         * list at [manifestUrl], its files `.onnx` on the same scheme, host and port; HTTP allowed.
         * Null when [manifestUrl] is not an absolute http(s) address.
         */
        fun localModels(manifestUrl: String): UpdateSource? {
            val uri = parse(manifestUrl.trim()) ?: return null
            val origin = originOf(uri) ?: return null
            if (origin.scheme != "http" && origin.scheme != "https") return null
            return UpdateSource(manifestUrl.trim(), origin, Kind.Models)
        }

        /** `/<owner>/<repo>/releases/download/models/<file>`, the file a plain name. */
        private fun modelAsset(path: String): Boolean = releaseAsset(path) && path.startsWith(MODELS_DOWNLOAD_PREFIX)

        /** Whether [url] is the firmware's release manifest: [FIRMWARE_MANIFEST_URL] exactly (HTTPS, port 443, no query or fragment). */
        fun allowsFirmwareManifest(url: String): Boolean {
            val uri = parse(url) ?: return false
            if (!secure(uri) || uri.rawQuery != null || uri.rawFragment != null) return false
            return uri.host.lowercase(Locale.ROOT) == MANIFEST_HOST && uri.rawPath == "/$FIRMWARE_REPOSITORY/main/releases/latest.json"
        }

        /**
         * Whether a firmware download, or a redirect of one, may go to [url]: a release asset of the
         * firmware's repository on `github.com` ([FIRMWARE_DOWNLOAD_PREFIX]`<tag>/<file>`, plain names), or
         * one of the two hosts GitHub hands a release's file over from ([ASSET_HOSTS], any path: signed,
         * expiring addresses). HTTPS on port 443 only.
         */
        fun allowsFirmwareBinary(url: String): Boolean {
            val uri = parse(url) ?: return false
            if (!secure(uri)) return false
            return when (uri.host.lowercase(Locale.ROOT)) {
                DOWNLOAD_HOST -> releaseAsset(uri.rawPath.orEmpty(), FIRMWARE_DOWNLOAD_PREFIX)
                in ASSET_HOSTS -> true
                else -> false
            }
        }

        /**
         * Whether a firmware manifest may name [url] as its binary: a `.bin` release asset of the
         * firmware's repository on `github.com`, with no query or fragment. An asset host is never
         * named directly (GitHub redirects there by itself).
         */
        fun allowsFirmwareFile(url: String): Boolean {
            val uri = parse(url) ?: return false
            if (uri.rawQuery != null || uri.rawFragment != null || !secure(uri)) return false
            val path = uri.rawPath.orEmpty()
            return uri.host.lowercase(Locale.ROOT) == DOWNLOAD_HOST && releaseAsset(path, FIRMWARE_DOWNLOAD_PREFIX) && path.endsWith(".bin")
        }

        /**
         * A source at one origin for testing ([manifestUrl] and the files it names on the same
         * scheme, host and port; HTTP allowed), or null when [manifestUrl] is not an absolute
         * http(s) address. Only ever made in debug builds on an emulator.
         */
        fun local(manifestUrl: String): UpdateSource? {
            val uri = parse(manifestUrl.trim()) ?: return null
            val origin = originOf(uri) ?: return null
            if (origin.scheme != "http" && origin.scheme != "https") return null
            return UpdateSource(manifestUrl.trim(), origin)
        }

        private fun parse(url: String): URI? {
            if ('\\' in url) return null
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return null
            }
            if (!uri.isAbsolute || uri.rawUserInfo != null || uri.host.isNullOrEmpty()) return null
            return uri
        }

        private fun originOf(uri: URI): Origin? {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val port = when {
                uri.port != -1 -> uri.port
                scheme == "https" -> HTTPS_PORT
                scheme == "http" -> 80
                else -> return null
            }
            return Origin(scheme, host, port)
        }

        private fun secure(uri: URI): Boolean =
            uri.scheme.equals("https", ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT)

        /** `<prefix><tag>/<file>`, each a plain name (letters, digits, dot, dash, underscore; never `.` or `..`). */
        private fun releaseAsset(path: String, prefix: String = DOWNLOAD_PREFIX): Boolean {
            if (!path.startsWith(prefix)) return false
            val rest = path.removePrefix(prefix).split('/')
            return rest.size == 2 && rest.all { SEGMENT.matches(it) && it != "." && it != ".." }
        }

        private fun safeSegments(path: String): Boolean = path.split('/').none { it == "." || it == ".." }
    }
}
