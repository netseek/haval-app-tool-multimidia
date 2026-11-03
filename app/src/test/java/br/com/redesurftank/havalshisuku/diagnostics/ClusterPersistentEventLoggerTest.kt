package br.com.redesurftank.havalshisuku.diagnostics

import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterPersistentEventLoggerTest {
    @Test
    fun captureLeasesAreIndependentAndIdempotentOnClose() {
        val first = ClusterPersistentEventLogger.acquireCaptureLease("test-first")
        val second = ClusterPersistentEventLogger.acquireCaptureLease("test-second")
        assertTrue(ClusterPersistentEventLogger.activeCaptureLeaseCount() == 2)

        first.close()
        first.close()
        assertTrue(ClusterPersistentEventLogger.activeCaptureLeaseCount() == 1)

        second.close()
        assertTrue(ClusterPersistentEventLogger.activeCaptureLeaseCount() == 0)
    }

    @Test
    fun deletesOnlyLogsOlderThanThreeDayRetentionWindow() {
        val now =
                Calendar.getInstance(Locale.US)
                        .apply {
                            set(2026, Calendar.JUNE, 15, 12, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        .timeInMillis

        assertTrue(
                ClusterPersistentEventLogger.shouldDeleteLogFile(
                        "cluster-events-20260612.log",
                        now
                )
        )
        assertFalse(
                ClusterPersistentEventLogger.shouldDeleteLogFile(
                        "cluster-events-20260613.log",
                        now
                )
        )
        assertFalse(
                ClusterPersistentEventLogger.shouldDeleteLogFile(
                        "cluster-events-20260615.log",
                        now
                )
        )
    }

    @Test
    fun ignoresFilesThatDoNotMatchLoggerPattern() {
        val now =
                Calendar.getInstance(Locale.US)
                        .apply {
                            set(2026, Calendar.JUNE, 15, 12, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        .timeInMillis

        assertFalse(ClusterPersistentEventLogger.shouldDeleteLogFile("notes.txt", now))
        assertFalse(ClusterPersistentEventLogger.shouldDeleteLogFile("cluster-events-latest.log", now))
    }
}
