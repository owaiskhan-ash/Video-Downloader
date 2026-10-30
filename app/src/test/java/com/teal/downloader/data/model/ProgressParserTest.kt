package com.teal.downloader.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressParserTest {

    @Test
    fun `parses speed with binary units`() {
        val line = "[download]  42.3% of  ~123.45MiB at    1.50MiB/s ETA 00:41"
        assertEquals(1.5 * 1024 * 1024, ProgressParser.parseSpeed(line)!!, 1.0)
    }

    @Test
    fun `parses speed with kilobytes`() {
        assertEquals(
            640.0 * 1024,
            ProgressParser.parseSpeed("[download] 10% of 5.00MiB at 640.00KiB/s ETA 00:02")!!,
            1.0,
        )
    }

    @Test
    fun `parses total size`() {
        val line = "[download]  42.3% of  ~123.45MiB at    1.50MiB/s ETA 00:41"
        assertEquals((123.45 * 1024 * 1024).toLong(), ProgressParser.parseTotalBytes(line))
    }

    @Test
    fun `returns null for lines without a speed`() {
        assertNull(ProgressParser.parseSpeed("[Merger] Merging formats into \"/sdcard/x.mp4\""))
        assertNull(ProgressParser.parseSpeed(null))
        assertNull(ProgressParser.parseSpeed(""))
    }

    @Test
    fun `fragment progress converts to a percentage`() {
        // The real yt-dlp shape: fragment counters ride along as a suffix on the
        // ordinary progress line, so a byte-based percentage and a fragment
        // count can appear together.
        val line = "[download]  42.3% of ~10.00MiB at  1.23MiB/s ETA 00:41 (frag 5/10)"
        val percent = ProgressParser.parseFragmentProgress(line)
        assertNotNull(percent)
        assertEquals(50f, percent!!, 0.01f)
        assertNull(
            ProgressParser.parseFragmentProgress(
                "[download]  10.0% of 5.00MiB at 1.00MiB/s ETA 00:05"
            )
        )
    }

    @Test
    fun `fragment progress without a total has no percentage`() {
        // yt-dlp emits "(frag 7)" alone while the fragment count is unknown,
        // which says nothing about how far along the download is.
        assertNull(
            ProgressParser.parseFragmentProgress("[download]  1.00MiB at 1.00MiB/s (frag 7)")
        )
    }

    @Test
    fun `fragment progress guards against a zero total`() {
        assertNull(ProgressParser.parseFragmentProgress("fragment 5 / 0"))
    }

    @Test
    fun `recognises the ffmpeg post-processing stages`() {
        assertTrue(ProgressParser.isPostProcessing("[Merger] Merging formats into \"/x.mp4\""))
        assertTrue(ProgressParser.isPostProcessing("[ExtractAudio] Destination: /x.mp3"))
        assertTrue(ProgressParser.isPostProcessing("[EmbedThumbnail] thumb.jpg"))
        assertTrue(ProgressParser.isPostProcessing("[Metadata] Adding metadata to \"/x.mp4\""))
        assertTrue(ProgressParser.isPostProcessing("[SponsorBlock] Found 2 segments"))
        assertTrue(ProgressParser.isPostProcessing("[Fixup] Fixing container"))
        assertFalse(ProgressParser.isPostProcessing("[download]  10% of 5MiB at 1MiB/s"))
        assertFalse(ProgressParser.isPostProcessing(null))
    }

    @Test
    fun `effective percent prefers bytes then fragments`() {
        assertEquals(42f, ProgressUpdate(42f, null, null, null, 90f, false, null).effectivePercent, 0.01f)
        assertEquals(90f, ProgressUpdate(0f, null, null, null, 90f, false, null).effectivePercent, 0.01f)
        assertEquals(0f, ProgressUpdate(0f, null, null, null, null, false, null).effectivePercent, 0.01f)
    }
}
