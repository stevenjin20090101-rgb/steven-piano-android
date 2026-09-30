// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every sheet, menu, dialog and popover of the app stands on glass (DESIGN.md › v1.9): Material's
 * containers are called only inside the glass wrappers ([GlassSheet], [GlassDropdownMenu],
 * [GlassAlertDialog], [GlassDialogSurface], [GlassPopover]), so a screen written beside the glass
 * pass (as the tablet sound's volume popover was, in M25) can't bring back a solid one unnoticed.
 */
class GlassContainersTest {
    private val sources = File("src/main/java/dev/stevenjin/stevenpiano")

    /** The glass wrappers themselves, which alone may call Material's containers. */
    private val wrappers = setOf("GlassMenu.kt", "GlassSheet.kt")

    /** Material's containers, as called. */
    private val containers = Regex("""\b(ModalBottomSheet|DropdownMenu|AlertDialog|BasicAlertDialog|Popup)\(""")

    @Test
    fun `Material's sheets, menus, dialogs and popups are called only inside the glass wrappers`() {
        assertTrue("the app's sources, from the module's folder", sources.isDirectory)
        val found = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in wrappers }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    containers.find(code)?.let { "${file.name}:${i + 1}: ${it.value}" }
                }
            }
            .toList()
        // The time picker's dialog is laid out by hand on BasicAlertDialog, its surface GlassDialogSurface.
        assertEquals(listOf("ScheduleEditorSheet.kt: BasicAlertDialog("), found.map { it.replace(Regex(":\\d+:"), ":") })
        val picker = File(sources, "ui/screens/schedule/ScheduleEditorSheet.kt").readText()
        assertTrue("the time picker's dialog is on the dialogs' glass", "GlassDialogSurface" in picker)
    }

    @Test
    fun `the tablet sound's volume popover is the glass popover`() {
        val controls = File(sources, "ui/components/TabletSoundControls.kt").readText()
        assertTrue(Regex("""\bGlassPopover\(""").containsMatchIn(controls))
        assertTrue("no hand-drawn hairline edge: the glass has its own", "BorderStroke" !in controls)
    }
}
