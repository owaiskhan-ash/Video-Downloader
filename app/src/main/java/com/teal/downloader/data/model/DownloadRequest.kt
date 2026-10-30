package com.teal.downloader.data.model

/** What the user asked for, independent of how yt-dlp is invoked. */
data class DownloadRequest(
    val url: String,
    /** Explicit format id chosen in the picker; null means "apply my defaults". */
    val formatId: String? = null,
    /** Set when the user picked separate video+audio formats to be merged. */
    val audioFormatId: String? = null,
    val title: String? = null,
    val settings: DownloadSettings = DownloadSettings(),
)

enum class AudioFormat(val ytDlpValue: String, val label: String) {
    BEST("best", "Best quality"),
    MP3("mp3", "MP3"),
    M4A("m4a", "M4A"),
    OPUS("opus", "Opus"),
    FLAC("flac", "FLAC"),
    WAV("wav", "WAV"),
}

enum class VideoFormatPreference(val label: String) {
    /** H.264/AAC in an .mp4: plays on anything, including old TVs and editors. */
    COMPATIBLE("Maximum compatibility (H.264/AAC mp4)"),

    /** Highest quality available, whatever codec that turns out to be. */
    BEST("Best quality"),
}

/**
 * Every knob that changes the generated command line.
 *
 * A plain data class with no Android types in it, so [com.teal.downloader.downloader.CommandBuilder]
 * can be unit-tested on the JVM without Robolectric.
 */
data class DownloadSettings(
    val audioOnly: Boolean = false,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val videoPreference: VideoFormatPreference = VideoFormatPreference.COMPATIBLE,
    /** Cap on height in pixels; null means "whatever the source offers". */
    val preferredHeight: Int? = null,
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = false,
    val writeSubtitles: Boolean = false,
    val embedSubtitles: Boolean = false,
    val subtitleLanguages: String = "en",
    val removeSponsorSegments: Boolean = false,
    val useAria2c: Boolean = false,
    val aria2cConnections: Int = 8,
    val concurrentFragments: Int = 4,
    val retries: Int = 3,
    val restrictFilenames: Boolean = false,
    /** false = follow a playlist URL into its entries. */
    val noPlaylist: Boolean = true,
    val playlistItems: String = "",
    val rateLimit: String = "",
    val proxy: String = "",
    val userAgent: String = "",
    val cookiesFile: String = "",
    val outputTemplate: String = DEFAULT_OUTPUT_TEMPLATE,
    /** Appended verbatim last, for power users. One argument per line. */
    val customArguments: String = "",
) {
    companion object {
        const val DEFAULT_OUTPUT_TEMPLATE = "%(title)s.%(ext)s"
    }
}
