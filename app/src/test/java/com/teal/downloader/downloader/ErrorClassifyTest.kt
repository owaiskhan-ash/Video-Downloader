package com.teal.downloader.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * yt-dlp reports failures as prose on stderr, and this is the mapping from that
 * prose onto something a person can act on. Pure string logic, so it is tested
 * here rather than discovered by a user on a device.
 */
class ErrorClassifyTest {

    @Test
    fun `a stale extractor points at the in-app updater`() {
        val message = DownloadException.classify(
            "ERROR: [youtube] dQw4w9WgXcQ: Unable to extract yt initial data",
            "https://youtu.be/dQw4w9WgXcQ",
        )
        assertTrue(message, message.contains("Update"))
        assertTrue(message, message.contains("Settings"))
    }

    @Test
    fun `nsig extraction failure is treated as a stale extractor`() {
        val message = DownloadException.classify("ERROR: nsig extraction failed", "u")
        assertTrue(message, message.contains("yt-dlp"))
        assertTrue(message, message.contains("Update"))
    }

    @Test
    fun `sign-in prompts offer cookies and updating`() {
        val message = DownloadException.classify(
            "ERROR: [youtube] Sign in to confirm you're not a bot",
            "u",
        )
        assertTrue(message, message.contains("cookies"))
        assertTrue(message, message.contains("updating"))
    }

    @Test
    fun `unsupported urls are named as such`() {
        assertEquals(
            "This site or URL is not supported by yt-dlp",
            DownloadException.classify("ERROR: Unsupported URL: https://example.com/video", "u"),
        )
    }

    @Test
    fun `an unavailable requested format is distinguished from a network failure`() {
        val message = DownloadException.classify(
            "ERROR: Requested format is not available",
            "u",
        )
        assertTrue(message, message.contains("quality"))
    }

    @Test
    fun `network failures name the url they could not reach`() {
        val message = DownloadException.classify(
            "ERROR: Unable to download webpage: timed out",
            "https://a.example/video",
        )
        assertTrue(message, message.contains("https://a.example/video"))
    }

    @Test
    fun `age restriction and privacy are reported distinctly`() {
        assertTrue(
            DownloadException.classify("ERROR: This video is age-restricted", "u").contains("age")
        )
        assertTrue(
            DownloadException.classify("ERROR: Private video", "u").contains("private")
        )
    }

    @Test
    fun `unrecognised text falls back to its first line`() {
        assertEquals(
            "Something odd happened",
            DownloadException.classify("Something odd happened\nand a second line", "u"),
        )
    }

    @Test
    fun `blank text still produces a message`() {
        assertEquals("Download failed", DownloadException.classify("", "u"))
    }
}
