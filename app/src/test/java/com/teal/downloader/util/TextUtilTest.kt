package com.teal.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextUtilTest {

    @Test
    fun `formats byte sizes`() {
        assertEquals("—", TextUtil.formatBytes(null))
        assertEquals("—", TextUtil.formatBytes(0))
        assertEquals("512 B", TextUtil.formatBytes(512))
        assertEquals("1.0 KB", TextUtil.formatBytes(1024))
        assertEquals("1.5 MB", TextUtil.formatBytes(1536 * 1024))
        assertEquals("2.3 GB", TextUtil.formatBytes((2.3 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formats speed as a size per second`() {
        assertEquals("—", TextUtil.formatSpeed(0.0))
        assertEquals("1.0 MB/s", TextUtil.formatSpeed(1024.0 * 1024))
    }

    @Test
    fun `formats duration with and without hours`() {
        assertEquals("—", TextUtil.formatDuration(null))
        assertEquals("—", TextUtil.formatDuration(0))
        assertEquals("0:45", TextUtil.formatDuration(45))
        assertEquals("3:45", TextUtil.formatDuration(225))
        assertEquals("1:02:03", TextUtil.formatDuration(3723))
    }

    @Test
    fun `formats eta`() {
        assertEquals("—", TextUtil.formatEta(null))
        assertEquals("45s", TextUtil.formatEta(45))
        assertEquals("2m 5s", TextUtil.formatEta(125))
        assertEquals("1h 5m", TextUtil.formatEta(3900))
    }

    @Test
    fun `formats quality, showing fps only when it is unusual`() {
        assertEquals("1080p", TextUtil.formatQuality(1080, 30.0))
        assertEquals("720p60", TextUtil.formatQuality(720, 60.0))
        assertEquals("—", TextUtil.formatQuality(null, null))
        assertEquals("audio", TextUtil.formatQuality(null, null, "audio"))
    }
}
