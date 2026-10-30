package com.teal.downloader.downloader

import com.teal.downloader.data.model.DownloadRequest
import com.teal.downloader.data.model.DownloadSettings
import com.teal.downloader.data.model.VideoFormatPreference

/**
 * Turns a [DownloadRequest] into a yt-dlp argument list.
 *
 * Deliberately pure: no Context, no Android types, no I/O. Everything about the
 * command line is decided here and nowhere else, which makes it the one piece of
 * the download path that can be unit-tested on the JVM.
 *
 * The URL is *not* part of the returned list — `YoutubeDLRequest.buildCommand()`
 * appends the URLs itself, after the options.
 */
object CommandBuilder {

    /** Arguments for reading metadata only (`-J`), no download. */
    fun infoArgs(
        settings: DownloadSettings = DownloadSettings(),
        flatPlaylist: Boolean = false,
    ): List<String> = buildList {
        add("-J")
        // Without this, a playlist URL makes yt-dlp resolve every entry before
        // returning, which looks like a hang on a long playlist.
        if (flatPlaylist) add("--flat-playlist")
        addAll(networkArgs(settings))
    }

    /** Arguments for an actual download into [outputDir]. */
    fun downloadArgs(
        request: DownloadRequest,
        outputDir: String,
        outputTemplate: String = request.settings.outputTemplate,
    ): List<String> {
        val s = request.settings
        return buildList {
            add("-P"); add(outputDir)
            add("-o"); add(outputTemplate)
            addAll(formatArgs(request))
            addAll(postProcessingArgs(request))
            // --newline puts each progress update on its own line, which is what
            // the library's stream parser needs to report percentage/ETA.
            add("--newline")
            add("--progress")
            addAll(transferArgs(s))
            addAll(networkArgs(s))
            addAll(playlistArgs(s))
            addAll(tokenize(s.customArguments))
        }
    }

    // -- format selection ---------------------------------------------------

    private fun formatArgs(request: DownloadRequest): List<String> {
        val s = request.settings
        val explicit = request.formatId
        return when {
            s.audioOnly -> buildList {
                add("-f"); add(if (explicit != null) explicit else "bestaudio/best")
                add("-x")
                if (request.audioFormatId != null) {
                    // Already an audio stream; no need to extract again.
                } else {
                    when (s.audioFormat) {
                        com.teal.downloader.data.model.AudioFormat.BEST -> Unit
                        else -> {
                            add("--audio-format"); add(s.audioFormat.ytDlpValue)
                        }
                    }
                    add("--audio-quality"); add("0")
                }
            }

            explicit != null -> buildList {
                add("-f")
                add(
                    if (request.audioFormatId != null) {
                        "$explicit+${request.audioFormatId}"
                    } else {
                        explicit
                    },
                )
                add("--merge-output-format")
                add(if (s.videoPreference == VideoFormatPreference.COMPATIBLE) "mp4" else "mkv")
            }

            else -> buildList {
                add("-f"); add(videoFormatSelector(s))
                if (s.videoPreference == VideoFormatPreference.COMPATIBLE) {
                    // Remuxing the merged result into mp4 is what makes the
                    // "maximum compatibility" promise real for players that
                    // reject mkv/webm.
                    add("--merge-output-format"); add("mp4")
                }
            }
        }
    }

    /**
     * Compatibility mode prefers avc1 video with mp4a audio so the result plays
     * everywhere; best mode takes whatever is highest and lets the container
     * follow the codecs.
     *
     * `[height<=?N]` uses yt-dlp's `<=?` operator, which means "at most N, but
     * ignore the constraint if nothing matches" — otherwise a 720p-only source
     * asked for 1080p would fail instead of falling back.
     */
    internal fun videoFormatSelector(s: DownloadSettings): String {
        val cap = s.preferredHeight?.let { "[height<=?$it]" } ?: ""
        val candidates = if (s.videoPreference == VideoFormatPreference.COMPATIBLE) {
            listOf(
                "bv*$cap[vcodec^=avc1]+ba[acodec^=mp4a]",
                "bv*$cap[ext=mp4]+ba[ext=m4a]",
                "bv*$cap+ba",
                "b$cap",
                "bv*+ba",
                "b",
            )
        } else {
            listOf("bv*$cap+ba", "b$cap", "bv*+ba", "b")
        }
        return candidates.distinct().joinToString("/")
    }

    // -- post-processing ----------------------------------------------------

    private fun postProcessingArgs(request: DownloadRequest): List<String> {
        val s = request.settings
        return buildList {
            if (s.embedMetadata) add("--embed-metadata")
            if (s.embedThumbnail) add("--embed-thumbnail")
            if (s.writeSubtitles) {
                add("--write-subs")
                if (s.subtitleLanguages.isNotBlank()) {
                    add("--sub-langs"); add(s.subtitleLanguages.trim())
                }
            }
            if (s.embedSubtitles) {
                add("--embed-subs")
                if (s.subtitleLanguages.isNotBlank()) {
                    add("--sub-langs"); add(s.subtitleLanguages.trim())
                }
            }
            if (s.removeSponsorSegments) {
                add("--sponsorblock-remove"); add("all")
            }
        }
    }

    // -- transfer / network / playlist --------------------------------------

    private fun transferArgs(s: DownloadSettings): List<String> = buildList {
        if (s.retries > 0) {
            add("--retries"); add(s.retries.toString())
            add("--fragment-retries"); add(s.retries.toString())
        }
        if (s.concurrentFragments > 1) {
            add("--concurrent-fragments"); add(s.concurrentFragments.toString())
        }
        if (s.rateLimit.isNotBlank()) {
            add("--limit-rate"); add(s.rateLimit.trim())
        }
        if (s.restrictFilenames) add("--restrict-filenames")
        if (s.useAria2c) {
            add("--external-downloader"); add("aria2c")
            val n = s.aria2cConnections.coerceIn(1, 16)
            add("--external-downloader-args"); add("aria2c:-x$n -s$n -k1M")
        }
    }

    private fun networkArgs(s: DownloadSettings): List<String> = buildList {
        if (s.proxy.isNotBlank()) {
            add("--proxy"); add(s.proxy.trim())
        }
        if (s.userAgent.isNotBlank()) {
            add("--user-agent"); add(s.userAgent.trim())
        }
        if (s.cookiesFile.isNotBlank()) {
            add("--cookies"); add(s.cookiesFile.trim())
        }
    }

    private fun playlistArgs(s: DownloadSettings): List<String> = buildList {
        if (s.playlistItems.isNotBlank()) {
            add("--playlist-items"); add(s.playlistItems.trim())
        } else if (s.noPlaylist) {
            add("--no-playlist")
        }
    }

    /**
     * Splits user-typed extra arguments on whitespace while honouring double
     * quotes, so `--parse-metadata "%(title)s:%(meta)s"` arrives as two
     * arguments rather than three.
     */
    internal fun tokenize(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        // Set once a token has seen quotes, whether or not it also has content.
        // An explicit "" is a deliberate empty argument, and dropping it would
        // let the following token be consumed as this option's value — for
        // --exec that means running the next flag as a shell command. Emitting
        // the empty string instead lets yt-dlp reject it loudly.
        var sawQuote = false
        for (ch in raw) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    sawQuote = true
                }
                ch.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty() || sawQuote) {
                        out.add(current.toString())
                        current.setLength(0)
                        sawQuote = false
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty() || sawQuote) out.add(current.toString())
        return out
    }
}
