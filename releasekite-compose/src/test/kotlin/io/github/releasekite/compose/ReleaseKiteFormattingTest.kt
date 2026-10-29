package io.github.releasekite.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 JVM 格式化工具测试（无 Robolectric）：字节数/日期/进度百分比。
 *
 * 这些函数刻意不依赖 java.time（minSdk 24 的 desugaring 负担），因此可以用普通 JUnit 覆盖。
 */
class ReleaseKiteFormattingTest {

    @Test
    fun formatBytesHandlesZeroAndNegative() {
        assertEquals("—", formatBytes(0L))
        assertEquals("—", formatBytes(-1L))
    }

    @Test
    fun formatBytesUsesMegabytesAndGigabytes() {
        assertEquals("70.0 MB", formatBytes(70L * 1024 * 1024))
        assertEquals("0.5 MB", formatBytes(512L * 1024))
        assertEquals("1.50 GB", formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun formatReleaseDateKeepsIsoDatePrefix() {
        assertEquals("2026-10-27", formatReleaseDate("2026-10-27T00:00:00Z"))
        assertEquals("2026-10-27", formatReleaseDate("2026-10-27"))
        assertEquals("2026-10-27", formatReleaseDate("  2026-10-27T12:34:56+08:00  "))
    }

    @Test
    fun formatReleaseDateRejectsMissingOrMalformedValues() {
        assertNull(formatReleaseDate(null))
        assertNull(formatReleaseDate(""))
        assertNull(formatReleaseDate("   "))
        assertNull(formatReleaseDate("not-a-date"))
        assertNull(formatReleaseDate("2026/10/27T00:00:00Z"))
    }

    @Test
    fun progressPercentComputesAndClamps() {
        assertEquals(50, progressPercent(35L * 1024 * 1024, 70L * 1024 * 1024))
        assertEquals(0, progressPercent(0L, 70L * 1024 * 1024))
        assertEquals(0, progressPercent(1024L, 0L))
        assertEquals(0, progressPercent(1024L, -1L))
        assertEquals(100, progressPercent(100L, 70L))
        assertEquals(0, progressPercent(-5L, 70L))
    }
}
