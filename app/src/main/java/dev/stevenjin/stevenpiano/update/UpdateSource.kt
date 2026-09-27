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
 */
class UpdateSource private constructor(
    /** The manifest's address. */
    val manifestUrl: String,
    private val origin: Origin?,
) {
    private data class Origin(val scheme: String, val host: String, val port: Int)

    /** Whether a request, or a redirect of one, may go to [url]. */
    fun allowsHop(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (origin != null) return originOf(uri) == origin
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
        val uri = parse(url) ?: return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath.orEmpty()
        if (origin != null) return originOf(uri) == origin && path.endsWith(".apk") && safeSegments(path)
        return secure(uri) && uri.host.lowercase(Locale.ROOT) == DOWNLOAD_HOST && releaseAsset(path) && path.endsWith(".apk")
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
        private fun releaseAsset(path: String): Boolean {
            if (!path.startsWith(DOWNLOAD_PREFIX)) return false
            val rest = path.removePrefix(DOWNLOAD_PREFIX).split('/')
            return rest.size == 2 && rest.all { SEGMENT.matches(it) && it != "." && it != ".." }
        }

        private fun safeSegments(path: String): Boolean = path.split('/').none { it == "." || it == ".." }
    }
}
