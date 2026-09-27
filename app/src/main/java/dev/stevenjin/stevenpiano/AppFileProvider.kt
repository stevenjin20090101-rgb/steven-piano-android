// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * The app's one FileProvider (the manifest's `<provider>`, `res/xml/file_paths.xml`): it can name
 * files in `cacheDir/updates/` (a downloaded update, for Android's installer) and
 * `cacheDir/diagnostics/` (the diagnostics zip, for the share sheet) and nothing else, and each is
 * read only through a one-off grant on the intent that carries it.
 */
object AppFileProvider {
    fun authority(context: Context): String = "${context.packageName}.files"

    /** The content URI for [file]; IllegalArgumentException for a file outside the two folders. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)
}
