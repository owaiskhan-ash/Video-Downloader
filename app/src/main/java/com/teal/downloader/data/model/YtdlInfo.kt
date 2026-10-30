package com.teal.downloader.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Our own mapping of yt-dlp's `-J` output.
 *
 * The library also ships a Jackson-mapped `VideoInfo`, but its `VideoFormat`
 * carries no width/height/fps/bitrate — only a `format` string — which is not
 * enough to build a format picker. Parsing the JSON ourselves with
 * kotlinx.serialization gives the UI real numbers and keeps Android out of the
 * model layer, so [CommandBuilder] stays unit-testable.
 */
val TealJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class YtdlFormat(
    @SerialName("format_id") val formatId: String = "",
    val ext: String? = null,
    val format: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val tbr: Double? = null,
    val vbr: Double? = null,
    val abr: Double? = null,
    val asr: Double? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
    val protocol: String? = null,
    val resolution: String? = null,
    val language: String? = null,
) {
    /** yt-dlp spells "this stream has no video" as the literal string "none". */
    val hasVideo: Boolean get() = !vcodec.isNullOrBlank() && vcodec != "none"
    val hasAudio: Boolean get() = !acodec.isNullOrBlank() && acodec != "none"

    /** Best guess at a size to show in the picker; often absent until download. */
    val sizeBytes: Long? get() = filesize?.takeIf { it > 0 } ?: filesizeApprox?.takeIf { it > 0 }

    /** True when this single format is enough on its own (video and audio). */
    val isProgressive: Boolean get() = hasVideo && hasAudio

    val codecLabel: String
        get() = buildList {
            vcodec?.takeIf { it != "none" }?.substringBefore('.')?.let { add(it) }
            acodec?.takeIf { it != "none" }?.substringBefore('.')?.let { add(it) }
        }.joinToString(" + ").ifBlank { ext ?: "?" }
}

@Serializable
data class YtdlSubtitle(
    val ext: String? = null,
    val url: String? = null,
    val name: String? = null,
)

@Serializable
data class YtdlPlaylistEntry(
    val id: String? = null,
    val title: String? = null,
    val url: String? = null,
    val duration: Double? = null,
    val extractor: String? = null,
)

@Serializable
data class YtdlInfo(
    val id: String = "",
    val title: String? = null,
    val fulltitle: String? = null,
    val description: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    val ext: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val extractor: String? = null,
    @SerialName("extractor_key") val extractorKey: String? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    @SerialName("view_count") val viewCount: Long? = null,
    @SerialName("like_count") val likeCount: Long? = null,
    @SerialName("is_live") val isLive: Boolean? = null,
    @SerialName("_type") val type: String? = null,
    @SerialName("playlist_title") val playlistTitle: String? = null,
    @SerialName("playlist_count") val playlistCount: Int? = null,
    val formats: List<YtdlFormat> = emptyList(),
    @SerialName("requested_formats") val requestedFormats: List<YtdlFormat> = emptyList(),
    val entries: List<YtdlPlaylistEntry>? = null,
    val subtitles: Map<String, List<YtdlSubtitle>> = emptyMap(),
    @SerialName("automatic_captions") val automaticCaptions: Map<String, List<YtdlSubtitle>> = emptyMap(),
) {
    val isPlaylist: Boolean get() = type == "playlist" || !entries.isNullOrEmpty()

    val displayTitle: String get() = fulltitle?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: playlistTitle?.takeIf { it.isNotBlank() }
        ?: id

    val uploaderName: String? get() = uploader ?: channel

    val durationSeconds: Long? get() = duration?.takeIf { it > 0 }?.toLong()

    /**
     * Formats worth showing, best first: drop storyboards/manifests, then sort
     * by height and bitrate so the picker reads top-down like the quality list.
     */
    val selectableFormats: List<YtdlFormat>
        get() = formats
            .filter { it.formatId.isNotBlank() && it.protocol != "storyboard" }
            .sortedWith(
                compareByDescending<YtdlFormat> { it.height ?: 0 }
                    .thenByDescending { it.fps ?: 0.0 }
                    .thenByDescending { it.tbr ?: 0.0 },
            )

    val videoFormats: List<YtdlFormat> get() = selectableFormats.filter { it.hasVideo }
    val audioFormats: List<YtdlFormat> get() = selectableFormats.filter { it.hasAudio && !it.hasVideo }
}
