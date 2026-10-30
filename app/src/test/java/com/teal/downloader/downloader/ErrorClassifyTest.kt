package com.teal.downloader.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * yt-dlp reports failures as prose, and this is the mapping from that prose onto
 * something a person can act on. Pure string logic on the path every failure
 * takes, so it is tested here rather than discovered by a user on a device.
 *
 * [Diagnosis.staleEngine] matters as much as the wording: it is what triggers an
 * automatic engine refresh and retry, so a false positive wastes a network
 * round-trip and a false negative leaves a fixable failure unfixed.
 */
class ErrorClassifyTest {

    // -- the failure this mapping exists for ------------------------------

    @Test
    fun `the youtube bot wall is treated as a stale engine, not an account problem`() {
        val diagnosis = DownloadException.classify(
            "ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm you're not a bot. " +
                "Use --cookies-from-browser or --cookies",
            "https://youtu.be/dQw4w9WgXcQ",
        )
        assertTrue(diagnosis.staleEngine)
        // It must not tell the user to go and supply credentials: Teal retries.
        assertFalse(diagnosis.message, diagnosis.message.contains("cookies"))
    }

    @Test
    fun `a private video that mentions signing in is still reported as private`() {
        val diagnosis = DownloadException.classify(
            "ERROR: Private video. Sign in if you've been granted access to this video",
            "u",
        )
        assertTrue(diagnosis.message, diagnosis.message.contains("private"))
        assertFalse(diagnosis.staleEngine)
    }

    @Test
    fun `members-only content is not mistaken for a stale engine`() {
        val diagnosis = DownloadException.classify(
            "ERROR: Join this channel to get access to members-only content",
            "u",
        )
        assertTrue(diagnosis.message, diagnosis.message.contains("members"))
        assertFalse(diagnosis.staleEngine)
    }

    // -- stale extractors --------------------------------------------------

    @Test
    fun `an extraction failure is treated as a stale engine`() {
        val diagnosis = DownloadException.classify(
            "ERROR: [youtube] dQw: Unable to extract yt initial data",
            "https://youtu.be/dQw",
        )
        assertTrue(diagnosis.staleEngine)
    }

    @Test
    fun `nsig extraction failure is treated as a stale engine`() {
        assertTrue(
            DownloadException.classify("ERROR: nsig extraction failed", "u").staleEngine
        )
    }

    @Test
    fun `a genuine sign-in requirement offers cookies but no automatic retry`() {
        val diagnosis = DownloadException.classify(
            "ERROR: This video is only available to logged-in users, login required",
            "u",
        )
        assertTrue(diagnosis.message, diagnosis.message.contains("cookies"))
        assertTrue(diagnosis.message, diagnosis.message.contains("Settings"))
        assertFalse(diagnosis.staleEngine)
    }

    // -- failures a refresh cannot help -----------------------------------

    @Test
    fun `unsupported urls are named as such`() {
        val diagnosis = DownloadException.classify(
            "ERROR: Unsupported URL: https://example.com/video",
            "u",
        )
        assertEquals("That link is not a video Teal can download", diagnosis.message)
        assertFalse(diagnosis.staleEngine)
    }

    @Test
    fun `an unavailable format is distinguished from a network failure`() {
        val diagnosis = DownloadException.classify(
            "ERROR: Requested format is not available",
            "u",
        )
        assertTrue(diagnosis.message, diagnosis.message.contains("quality"))
        assertFalse(diagnosis.staleEngine)
    }

    @Test
    fun `no video formats is read as a quality problem`() {
        assertTrue(
            DownloadException.classify("ERROR: No video formats found", "u").message
                .contains("quality")
        )
    }

    @Test
    fun `network failures name the url they could not reach`() {
        val diagnosis = DownloadException.classify(
            "ERROR: Unable to download webpage: timed out",
            "https://a.example/video",
        )
        assertTrue(diagnosis.message, diagnosis.message.contains("https://a.example/video"))
        assertFalse(diagnosis.staleEngine)
    }

    @Test
    fun `age restriction is reported distinctly`() {
        val diagnosis = DownloadException.classify("ERROR: This video is age-restricted", "u")
        assertTrue(diagnosis.message, diagnosis.message.contains("age"))
        assertFalse(diagnosis.staleEngine)
    }

    // -- fallbacks ---------------------------------------------------------

    @Test
    fun `unrecognised text falls back to its first line`() {
        assertEquals(
            "Something odd happened",
            DownloadException.classify("Something odd happened\nand a second line", "u").message,
        )
    }

    @Test
    fun `blank text still produces a message`() {
        assertEquals("Download failed", DownloadException.classify("", "u").message)
    }

    // -- the non-zero exit path -------------------------------------------
    //
    // yt-dlp reports most real failures by exiting non-zero with the reason on
    // stderr instead of throwing. That path used to hand the raw line straight
    // to the user, bypassing this mapping entirely.

    @Test
    fun `a non-zero exit is translated the same way as a thrown error`() {
        val exception = DownloadException.forStderr(
            """
            [debug] Loading ytdlp
            WARNING: unable to obtain file
            ERROR: [youtube] dQw: Sign in to confirm you're not a bot
            """.trimIndent(),
            "u",
        )
        assertTrue(exception.staleEngine)
        assertFalse(exception.message!!, exception.message!!.contains("Sign in"))
    }

    @Test
    fun `stderr with no error line still yields its last line`() {
        val exception = DownloadException.forStderr("just some noise\nand more noise", "u")
        assertEquals("and more noise", exception.message)
        assertFalse(exception.staleEngine)
    }

    @Test
    fun `empty stderr falls back to a generic message`() {
        assertEquals("Download failed", DownloadException.forStderr("", "u").message)
    }

    @Test
    fun `the last error line wins over earlier noise`() {
        assertEquals(
            "ERROR: Private video",
            firstMeaningfulLine("ERROR: first problem\nsome output\nERROR: Private video"),
        )
    }
}
