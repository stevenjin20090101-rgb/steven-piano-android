// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.net.AppleUrls
import dev.stevenjin.stevenpiano.net.WikipediaUrls

/**
 * "From Wikipedia", for text that is Wikipedia's: opens [url] in a browser. The address is checked
 * again ([WikipediaUrls.pageLink]; anything else shows no link), and it goes out as a browsable
 * VIEW, so only a browser can take it. A tablet with no browser (a kiosk, a managed school device)
 * says so in an [OutlinedBanner] instead of crashing. [modifier] places the button.
 */
@Composable
fun WikipediaLink(url: String?, modifier: Modifier = Modifier) {
    val link = remember(url) { WikipediaUrls.pageLink(url) } ?: return
    BrowserLink("From Wikipedia", link, modifier)
}

/**
 * An album cover's credit (v1.15 — M40), [label] ("Cover: album · artist") in [WikipediaLink]'s style, opening the track
 * on Apple Music at [url]. The address is checked again ([AppleUrls.pageLink]); when it does not pass, the credit stands
 * as plain text, in the eyebrow style and sentence case, as Wikipedia's attribution line does.
 */
@Composable
fun CoverLink(label: String, url: String?, modifier: Modifier = Modifier) {
    val link = remember(url) { AppleUrls.pageLink(url) }
    if (link == null) {
        Eyebrow(label, Modifier.padding(top = 8.dp), uppercase = false)
    } else {
        BrowserLink(label, link, modifier)
    }
}

/** A text button that sends [link] (already checked) to a browser; a tablet with none says so instead. */
@Composable
private fun BrowserLink(label: String, link: String, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(link) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { failed = !openInBrowser(context, link) }, modifier = modifier) {
            Text(label)
        }
        if (failed) OutlinedBanner(NO_BROWSER, Modifier.padding(vertical = 8.dp))
    }
}

/** Sends [link] to a browser; false when none could take it. */
private fun openInBrowser(context: Context, link: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val NO_BROWSER = "No browser is available to open this link."
