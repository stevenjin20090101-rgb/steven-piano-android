// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.quiet.QuietCopy
import dev.stevenjin.stevenpiano.quiet.QuietSection
import dev.stevenjin.stevenpiano.quiet.QuietTimes
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Card
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.quiet.LocalPlayAnyway
import dev.stevenjin.stevenpiano.ui.screens.quiet.QuietEditorSheet
import dev.stevenjin.stevenpiano.ui.screens.quiet.QuietWeek
import dev.stevenjin.stevenpiano.ui.screens.quiet.rememberQuiet
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import java.time.ZonedDateTime

/** With no section yet. */
const val NO_SECTIONS = "No quiet times yet: the piano plays whenever someone asks it to."

/** Before the sections have been read (v1.25 — M59). */
const val READING_SECTIONS = "Reading the quiet times…"

/**
 * Quiet times (DESIGN.md › v1.20 — M54, v1.25 — M59), in the PLAYING group where Schedule was, as wide as the page: at
 * the top the quiet now beside the moon, "Quiet now · until 9:30" with Play anyway, or "Next quiet time 9:40"; the week
 * as a calendar ([QuietWeek]), whose blocks open their section's editor; under SECTIONS a card a section (its name over
 * its days, Edit, its blocks as chips), "Reading the quiet times…" until they are read; Add section, with what quiet
 * times do. The editor is [QuietEditorSheet]. Behind the kiosk PIN as the other pages are (the page shows locked until
 * it is given).
 */
@Composable
fun QuietTimesPage() {
    val context = LocalContext.current
    val quietStore = context.graph.quietStore
    val rows by quietStore.quietRows.collectAsStateWithLifecycle()
    val quiet = rememberQuiet()
    val playAnyway = LocalPlayAnyway.current
    val sections = remember(rows) { QuietTimes.sections(rows.orEmpty()) }
    // The section being edited: its place, NEW for a new one, NONE while the editor is closed.
    var editing by rememberSaveable { mutableIntStateOf(NONE) }

    Anchored(PageRows.PLAY_ANYWAY.anchor) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_g_moon), contentDescription = null, modifier = Modifier.size(24.dp), tint = LocalTertiary.current)
            Spacer(Modifier.width(12.dp))
            Text(
                QuietCopy.status(quiet, ZonedDateTime.now()),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (quiet.holds) ActionButton(PageRows.PLAY_ANYWAY.label, onClick = playAnyway)
        }
    }
    QuietWeek(sections, onEdit = { editing = it }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    when {
        rows == null -> {
            SectionRule()
            NoteLine(READING_SECTIONS)
        }
        sections.isEmpty() -> {
            SectionRule()
            NoteLine(NO_SECTIONS)
        }
        else -> {
            SectionEyebrow("Sections", rule = false)
            sections.forEachIndexed { i, section -> SectionCard(section, onEdit = { editing = i }) }
        }
    }
    Anchored(PageRows.ADD_SECTION.anchor) {
        ActionRow(note = QuietCopy.NOTE) {
            ActionButton(PageRows.ADD_SECTION.label, onClick = { editing = NEW }, enabled = sections.size < QuietTimes.MAX_SECTIONS)
        }
    }

    if (editing != NONE) {
        val index = editing.takeIf { it >= 0 && it < sections.size }
        if (editing == NEW || index != null) QuietEditorSheet(index, sections, onDismiss = { editing = NONE })
    }
}

/**
 * A section on its [Card] (v1.25 — M59), 12 dp from the next: its name (medium) over its days, Edit at the end, and its
 * blocks as chips ("8:40–9:30") 6 dp under them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionCard(section: QuietSection, onEdit: () -> Unit) {
    Card(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(section.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                Eyebrow(QuietCopy.days(section.days), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
            }
            Spacer(Modifier.width(16.dp))
            ActionButton("Edit", onClick = onEdit, description = "Edit ${section.name}")
        }
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in section.blocks) {
                Text(
                    QuietCopy.range(block),
                    Modifier
                        .border(Hairline, LocalHairline.current, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private const val NONE = -1
private const val NEW = -2
