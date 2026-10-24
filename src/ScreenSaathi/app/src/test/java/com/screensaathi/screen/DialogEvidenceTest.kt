package com.screensaathi.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogEvidenceTest {
    @Test
    fun `visible WebView AlertDialog class is explicit popup evidence`() {
        assertTrue(DialogEvidence.isDialogNode("AlertDialog", "", false, emptySet()))
    }

    @Test
    fun `native AppCompat panel needs title and button panel structure`() {
        assertTrue(DialogEvidence.isDialogNode(
            "LinearLayoutCompat", "parentPanel", false,
            setOf("alertTitle", "buttonPanel"),
        ))
        assertFalse(DialogEvidence.isDialogNode(
            "LinearLayoutCompat", "parentPanel", false, setOf("buttonPanel"),
        ))
    }

    @Test
    fun `interactive named dialog is distinct from inert modal container`() {
        assertTrue(DialogEvidence.isDialogNode("LinearLayout", "grant_dialog", true, setOf("dialog_body")))
        assertFalse(DialogEvidence.isDialogNode(
            "FrameLayout", "top_modal_views_container", false, emptySet(),
        ))
    }
}
