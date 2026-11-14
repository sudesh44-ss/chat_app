package com.nexa.app

import com.nexa.app.core.media.AttachmentMediaType
import com.nexa.app.core.media.MediaCompressionOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCompressionOrchestratorUnitTests {

    @Test
    fun classifyMediaTypesFromNameAndMime() {
        assertEquals(AttachmentMediaType.VIDEO, MediaCompressionOrchestrator.classify("clip.mp4", "video/mp4"))
        assertEquals(AttachmentMediaType.PHOTO, MediaCompressionOrchestrator.classify("family.jpg", "image/jpeg"))
        assertEquals(AttachmentMediaType.SCREENSHOT, MediaCompressionOrchestrator.classify("Screenshot_2024.png", "image/png"))
        assertEquals(AttachmentMediaType.VOICE_NOTE, MediaCompressionOrchestrator.classify("voice_note.m4a", "audio/m4a"))
        assertEquals(AttachmentMediaType.PDF, MediaCompressionOrchestrator.classify("invoice.pdf", "application/pdf"))
        assertEquals(AttachmentMediaType.DOCUMENT, MediaCompressionOrchestrator.classify("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(AttachmentMediaType.ARCHIVE, MediaCompressionOrchestrator.classify("bundle.zip", "application/zip"))
    }

    @Test
    fun decideCompressionPolicyForTypicalAttachments() {
        assertTrue(MediaCompressionOrchestrator.shouldCompress(AttachmentMediaType.PHOTO, 6L * 1024L * 1024L, 3000, 2000))
        assertFalse(MediaCompressionOrchestrator.shouldCompress(AttachmentMediaType.PHOTO, 900L * 1024L, 1200, 800))
        assertTrue(MediaCompressionOrchestrator.shouldCompress(AttachmentMediaType.SCREENSHOT, 7L * 1024L * 1024L, 3000, 2500))
        assertFalse(MediaCompressionOrchestrator.shouldCompress(AttachmentMediaType.PDF, 2L * 1024L * 1024L))
        assertFalse(MediaCompressionOrchestrator.shouldCompress(AttachmentMediaType.DOCUMENT, 1L * 1024L * 1024L))
        assertFalse(MediaCompressionOrchestrator.shouldCompress(AttachmentMediaType.ARCHIVE, 25L * 1024L * 1024L))
    }
}
