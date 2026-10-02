// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import android.app.Activity
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Prints the request poster from the tablet (Piano › Guests › Print the request poster):
 * the poster page rendered by a WebView the app makes for it alone, from its assets (no network,
 * no script, nothing that navigates), handed to Android's print dialog on A4. Never a browser, so
 * it works in kiosk mode too (M20). Each print job holds its own WebView until it is done with it,
 * so a second tap never takes the first job's view from under it.
 */
object PosterPrint {
    private val held = mutableSetOf<WebView>()

    /** Opens the print dialog for the poster of [url]; false when the poster cannot be made. */
    fun print(activity: Activity, url: String): Boolean {
        val page = Poster.page(AndroidAssets(activity).read(WebAssets.POSTER.name), url) ?: return false
        val view = WebView(activity)
        view.settings.javaScriptEnabled = false
        view.settings.allowFileAccess = false   // the page's stylesheet comes from android_asset, which this does not affect
        view.settings.allowContentAccess = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                val manager = activity.getSystemService(PrintManager::class.java) ?: return release(view)
                val job = JOB_NAME
                manager.print(job, Releasing(view, view.createPrintDocumentAdapter(job)), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
            }
        }
        held += view
        view.loadDataWithBaseURL(BASE_URL, page.toString(Charsets.UTF_8), "text/html", "utf-8", null)
        return true
    }

    private fun release(view: WebView) {
        held -= view
        view.destroy()
    }

    /** The WebView's own adapter, which lets its WebView go once the print job has finished with it. */
    private class Releasing(private val view: WebView, private val inner: PrintDocumentAdapter) : PrintDocumentAdapter() {
        override fun onStart() = inner.onStart()

        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) =
            inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) =
            inner.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            inner.onFinish()
            release(view)
        }
    }

    private const val JOB_NAME = "Steven Piano request poster"

    /** The poster's stylesheet is a relative link, found beside it in the app's assets. */
    private const val BASE_URL = "file:///android_asset/web/"
}
