// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.LibraryCopy
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.UpdateCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.ShareDiagnosticsRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.library.LibraryLicenceSheet
import dev.stevenjin.stevenpiano.ui.screens.piano.AboutRow
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import dev.stevenjin.stevenpiano.ui.screens.piano.UpdateRow
import dev.stevenjin.stevenpiano.update.UpdateState

// THIS TABLET's pages that came out of the hub's APP group and the About area in v1.13 (M31b).

/**
 * Updates: Check for updates automatically, Check for app updates ("Check now" until v1.13, with what the
 * last check found under it), then the UPDATE block while a release is known or was just installed
 * ([UpdateRow]: it sat on the hub until v1.13; the hub's row now names it, "1.14 available"). In kiosk mode
 * the page waits for the PIN as every page does.
 */
@Composable
fun UpdatesPage(settings: PianoSettings, vm: PianoViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(vm.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstall = vm.canInstall()   // the person may come back from the Install unknown apps setting
        onPauseOrDispose { }
    }
    SectionRule()
    Anchored(PageRows.CHECK_AUTOMATICALLY.anchor) {
        SwitchRow(PageRows.CHECK_AUTOMATICALLY.label, settings.checkForUpdates, vm::setCheckForUpdates, note = SettingNotes.CHECK_AUTOMATICALLY)
    }
    Anchored(PageRows.CHECK_FOR_APP_UPDATES.anchor) {
        ActionRow(note = UpdateCopy.checkLine(update) ?: SettingNotes.CHECK_FOR_APP_UPDATES) {
            ActionButton(PageRows.CHECK_FOR_APP_UPDATES.label, onClick = vm::checkNow, enabled = update != UpdateState.Checking && !update.busy)
        }
    }
    UpdateRow(
        update,
        canInstall,
        onUpdate = { vm.update(context) },
        onRestart = { vm.restart(context) },
        onAllowInstalls = { runCatching { context.startActivity(vm.installPermissionSettings()) } },
    )
}

/**
 * Library and artwork: Fetch artwork automatically (from Display, with what it sends), Fetch artwork for
 * every composer and Steven's library (Load, or Update while a newer pack is on offer, the licence sheet
 * before the first load). Both actions stay in the Library's + sheet too.
 */
@Composable
fun ArtworkPage(settings: PianoSettings, vm: PianoViewModel) {
    val context = LocalContext.current
    val pack = context.graph.libraryPack
    val packState by pack.state.collectAsStateWithLifecycle()
    val packOffer by pack.offer.collectAsStateWithLifecycle()
    val newerPack by pack.newerAvailable.collectAsStateWithLifecycle()
    var licence by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { pack.check(maxAgeMs = LIBRARY_CHECK_AGE_MS) }   // the row shows the pack's numbers
    val firstLoad = settings.libraryPackVersion == 0

    SectionRule()
    Anchored(PageRows.FETCH_AUTOMATICALLY.anchor) {
        SwitchRow(PageRows.FETCH_AUTOMATICALLY.label, settings.fetchArtworkAutomatically, vm::setFetchArtworkAutomatically, note = ArtworkCopy.TRANSPARENCY)
    }
    Anchored(PageRows.FETCH_EVERY_COMPOSER.anchor) {
        ActionRow(note = SettingNotes.FETCH_EVERY_COMPOSER) {
            ActionButton(PageRows.FETCH_EVERY_COMPOSER.label, onClick = { ArtworkService.start(context, force = true) })
        }
    }
    Anchored(PageRows.STEVENS_LIBRARY.anchor) {
        ActionRow(note = LibraryCopy.line(packState, packOffer)) {
            ActionButton(
                if (firstLoad) LibraryCopy.LOAD else LibraryCopy.updateLabel(packOffer?.newPieces),
                onClick = { if (firstLoad) licence = true else pack.load(false) },
                enabled = !packState.busy && (firstLoad || newerPack),
            )
        }
    }
    if (licence) {
        LibraryLicenceSheet(
            packOffer,
            onLoad = {
                licence = false
                pack.load(false)
            },
            onDismiss = { licence = false },
        )
    }
}

/** Help and about: Share diagnostics (from the hub's APP group), then the About lines (from the hub's foot). */
@Composable
fun HelpPage() {
    SectionRule()
    Anchored(PageRows.DIAGNOSTICS.anchor) { ShareDiagnosticsRow() }
    Anchored(PageRows.ABOUT.anchor) { AboutRow(Modifier.padding(16.dp)) }
}

/** How old the library pack's manifest may be before the page asks again (the + sheet's rule). */
private const val LIBRARY_CHECK_AGE_MS = 10 * 60_000L
