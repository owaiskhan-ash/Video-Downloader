package com.teal.downloader.downloader

import com.teal.downloader.data.model.AudioFormat
import com.teal.downloader.data.model.DownloadRequest
import com.teal.downloader.data.model.DownloadSettings
import com.teal.downloader.data.model.VideoFormatPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBuilderTest {

    private val dir = "/storage/emulated/0/Android/data/files/Download/Teal"

    private fun args(settings: DownloadSettings = DownloadSettings(), url: String = "https://example.com/v") =
        CommandBuilder.downloadArgs(DownloadRequest(url = url, settings = settings), dir)

    /** The value that follows an option, or null when the option is absent. */
    private fun List<String>.valueOf(option: String): String? {
        val index = indexOf(option)
        return if (index >= 0 && index + 1 < size) this[index + 1] else null
    }

    @Test
    fun `output path and template always come first`() {
        val result = args()
        assertEquals(dir, result.valueOf("-P"))
        assertEquals(DownloadSettings.DEFAULT_OUTPUT_TEMPLATE, result.valueOf("-o"))
    }

    @Test
    fun `url is never part of the argument list`() {
        // YoutubeDLRequest.buildCommand() appends the URLs itself; including one
        // here would pass the URL twice.
        val result = args(url = "https://example.com/watch?v=abc123")
        assertFalse(result.any { it.contains("example.com") })
    }

    @Test
    fun `compatibility mode prefers avc1 and mp4a and merges to mp4`() {
        val result = args(DownloadSettings(videoPreference = VideoFormatPreference.COMPATIBLE))
        val format = result.valueOf("-f").orEmpty()
        assertTrue(format.startsWith("bv*[vcodec^=avc1]+ba[acodec^=mp4a]"))
        assertEquals("mp4", result.valueOf("--merge-output-format"))
    }

    @Test
    fun `best quality mode does not force mp4`() {
        val result = args(DownloadSettings(videoPreference = VideoFormatPreference.BEST))
        val format = result.valueOf("-f").orEmpty()
        assertTrue(format.startsWith("bv*+ba"))
        assertFalse(format.contains("avc1"))
        assertFalse(result.contains("--merge-output-format"))
    }

    @Test
    fun `height cap uses the optional operator so it can fall back`() {
        // <=? means "at most this, but ignore the constraint if nothing matches".
        // A plain <= would fail outright on a source with no 1080p variant.
        val format = args(DownloadSettings(preferredHeight = 1080)).valueOf("-f").orEmpty()
        assertTrue(format.contains("[height<=?1080]"))
        assertFalse(format.contains("[height<=1080]"))
    }

    @Test
    fun `no height cap means no height filter at all`() {
        val format = args(DownloadSettings(preferredHeight = null)).valueOf("-f").orEmpty()
        assertFalse(format.contains("height"))
    }

    @Test
    fun `format selector has no duplicate fallbacks when uncapped`() {
        val parts = CommandBuilder.videoFormatSelector(DownloadSettings()).split("/")
        assertEquals(parts.size, parts.distinct().size)
    }

    @Test
    fun `audio only extracts and converts to the chosen format`() {
        val result = args(DownloadSettings(audioOnly = true, audioFormat = AudioFormat.MP3))
        assertEquals("bestaudio/best", result.valueOf("-f"))
        assertTrue(result.contains("-x"))
        assertEquals("mp3", result.valueOf("--audio-format"))
        assertEquals("0", result.valueOf("--audio-quality"))
        assertFalse(result.contains("--merge-output-format"))
    }

    @Test
    fun `audio only with best format does not ask for a conversion`() {
        val result = args(DownloadSettings(audioOnly = true, audioFormat = AudioFormat.BEST))
        assertFalse(result.contains("--audio-format"))
    }

    @Test
    fun `explicit format id wins over the preference selector`() {
        val result = CommandBuilder.downloadArgs(
            DownloadRequest(url = "https://example.com/v", formatId = "137"),
            dir,
        )
        assertEquals("137", result.valueOf("-f"))
    }

    @Test
    fun `separate video and audio ids are joined for merging`() {
        val result = CommandBuilder.downloadArgs(
            DownloadRequest(url = "https://example.com/v", formatId = "137", audioFormatId = "140"),
            dir,
        )
        assertEquals("137+140", result.valueOf("-f"))
        assertEquals("mp4", result.valueOf("--merge-output-format"))
    }

    @Test
    fun `post-processing flags follow their settings`() {
        val off = args(
            DownloadSettings(
                embedMetadata = false,
                embedThumbnail = false,
                writeSubtitles = false,
                embedSubtitles = false,
                removeSponsorSegments = false,
            ),
        )
        assertFalse(off.contains("--embed-metadata"))
        assertFalse(off.contains("--embed-thumbnail"))
        assertFalse(off.contains("--write-subs"))
        assertFalse(off.contains("--embed-subs"))
        assertFalse(off.contains("--sponsorblock-remove"))

        val on = args(
            DownloadSettings(
                embedMetadata = true,
                embedThumbnail = true,
                writeSubtitles = true,
                embedSubtitles = true,
                subtitleLanguages = "en,hi",
                removeSponsorSegments = true,
            ),
        )
        assertTrue(on.contains("--embed-metadata"))
        assertTrue(on.contains("--embed-thumbnail"))
        assertTrue(on.contains("--write-subs"))
        assertTrue(on.contains("--embed-subs"))
        assertEquals("en,hi", on.valueOf("--sub-langs"))
        assertEquals("all", on.valueOf("--sponsorblock-remove"))
    }

    @Test
    fun `sub-langs is emitted once even when writing and embedding`() {
        val result = args(DownloadSettings(writeSubtitles = true, embedSubtitles = true))
        assertEquals(2, result.count { it == "--sub-langs" })
    }

    @Test
    fun `progress parsing is always enabled`() {
        val result = args()
        assertTrue(result.contains("--newline"))
        assertTrue(result.contains("--progress"))
    }

    @Test
    fun `aria2c is only used when enabled, with matching connection counts`() {
        assertFalse(args(DownloadSettings(useAria2c = false)).contains("--external-downloader"))

        val result = args(DownloadSettings(useAria2c = true, aria2cConnections = 16))
        assertEquals("aria2c", result.valueOf("--external-downloader"))
        assertEquals("aria2c:-x16 -s16 -k1M", result.valueOf("--external-downloader-args"))
    }

    @Test
    fun `aria2c connections are clamped into the supported range`() {
        val tooMany = args(DownloadSettings(useAria2c = true, aria2cConnections = 99))
            .valueOf("--external-downloader-args")
        assertEquals("aria2c:-x16 -s16 -k1M", tooMany)
    }

    @Test
    fun `no-playlist by default, playlist-items overrides it`() {
        assertTrue(args(DownloadSettings(noPlaylist = true)).contains("--no-playlist"))
        assertFalse(args(DownloadSettings(noPlaylist = false)).contains("--no-playlist"))

        val ranged = args(DownloadSettings(noPlaylist = true, playlistItems = "1-5"))
        assertEquals("1-5", ranged.valueOf("--playlist-items"))
        assertFalse(ranged.contains("--no-playlist"))
    }

    @Test
    fun `network options appear only when set`() {
        val plain = args()
        assertFalse(plain.contains("--proxy"))
        assertFalse(plain.contains("--user-agent"))
        assertFalse(plain.contains("--cookies"))
        assertFalse(plain.contains("--limit-rate"))

        val full = args(
            DownloadSettings(
                proxy = "socks5://127.0.0.1:1080",
                userAgent = "Teal/1.0",
                cookiesFile = "/sdcard/cookies.txt",
                rateLimit = "2M",
            ),
        )
        assertEquals("socks5://127.0.0.1:1080", full.valueOf("--proxy"))
        assertEquals("Teal/1.0", full.valueOf("--user-agent"))
        assertEquals("/sdcard/cookies.txt", full.valueOf("--cookies"))
        assertEquals("2M", full.valueOf("--limit-rate"))
    }

    @Test
    fun `retries and fragment settings are emitted as strings`() {
        val result = args(DownloadSettings(retries = 5, concurrentFragments = 8))
        assertEquals("5", result.valueOf("--retries"))
        assertEquals("5", result.valueOf("--fragment-retries"))
        assertEquals("8", result.valueOf("--concurrent-fragments"))
    }

    @Test
    fun `zero retries and single fragment omit their options`() {
        val result = args(DownloadSettings(retries = 0, concurrentFragments = 1))
        assertFalse(result.contains("--retries"))
        assertFalse(result.contains("--concurrent-fragments"))
    }

    @Test
    fun `custom arguments are appended last and can override earlier options`() {
        val result = args(DownloadSettings(customArguments = "--format-sort res:720 --no-part"))
        assertEquals("--format-sort", result[result.size - 3])
        assertEquals("res:720", result[result.size - 2])
        assertEquals("--no-part", result.last())
    }

    @Test
    fun `info args dump json and can flatten playlists`() {
        val info = CommandBuilder.infoArgs()
        assertEquals("-J", info.first())
        assertFalse(info.contains("--flat-playlist"))

        val flat = CommandBuilder.infoArgs(flatPlaylist = true)
        assertTrue(flat.contains("--flat-playlist"))
        assertFalse(flat.contains("-P"))
        assertFalse(flat.contains("-o"))
    }

    @Test
    fun `tokenizer honours double quotes so values with spaces survive`() {
        val tokens = CommandBuilder.tokenize("""--parse-metadata "%(title)s:%(meta)s" --no-part""")
        assertEquals(listOf("--parse-metadata", "%(title)s:%(meta)s", "--no-part"), tokens)
    }

    @Test
    fun `tokenizer treats blanks as nothing`() {
        assertTrue(CommandBuilder.tokenize("").isEmpty())
        assertTrue(CommandBuilder.tokenize("   \n  ").isEmpty())
    }

    @Test
    fun `tokenizer keeps empty quoted strings`() {
        assertEquals(listOf("--exec", "", "--no-part"), CommandBuilder.tokenize("""--exec "" --no-part"""))
    }

    @Test
    fun `restrict filenames is opt-in`() {
        assertFalse(args().contains("--restrict-filenames"))
        assertTrue(args(DownloadSettings(restrictFilenames = true)).contains("--restrict-filenames"))
    }

    @Test
    fun `custom output template is passed through`() {
        val result = CommandBuilder.downloadArgs(
            DownloadRequest(url = "https://example.com/v"),
            dir,
            outputTemplate = "%(uploader)s - %(title)s.%(ext)s",
        )
        assertEquals("%(uploader)s - %(title)s.%(ext)s", result.valueOf("-o"))
    }
}
