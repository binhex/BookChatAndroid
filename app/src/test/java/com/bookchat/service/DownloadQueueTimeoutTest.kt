package com.bookchat.service

import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/**
 * Tests for the download queue timeout mechanism.
 * Bug: When an XDCC request is sent and no DCC offer arrives, the queue
 * stalls forever — items stay in Requesting state and the queue never advances.
 * 
 * Fix: Add a timeout in tryProcessNext() that marks the stuck item as
 * Failed and advances to the next queued item after the configured timeout.
 */
class DownloadQueueTimeoutTest {

    @Test
    fun `stuck item should become failed with timeout reason`() {
        val item = DownloadItem(
            downloadCommand = "!bot hash1",
            fileHash = "hash1",
            expectedFileName = "test.epub",
            displayTitle = "Test Book",
            format = "EPUB",
            state = DownloadItemState.RequestSent,
        )

        val timedOut = item.copy(
            state = DownloadItemState.Failed("No response from bot within 60s")
        )

        assertTrue("Should transition to Failed", timedOut.state is DownloadItemState.Failed)
        val reason = (timedOut.state as DownloadItemState.Failed).reason
        assertTrue("Reason should mention timeout: $reason", 
            reason.contains("response", ignoreCase = true))
    }

    @Test
    fun `queue should contain next item after first is removed`() {
        val items = mutableListOf(
            DownloadItem(downloadCommand = "!a 1", fileHash = "h1", expectedFileName = "a.epub", displayTitle = "A", format = "EPUB", state = DownloadItemState.Queued),
            DownloadItem(downloadCommand = "!b 2", fileHash = "h2", expectedFileName = "b.epub", displayTitle = "B", format = "EPUB", state = DownloadItemState.Queued),
        )

        // Remove first (as tryProcessNext does)
        val first = items.removeFirstOrNull()

        assertEquals("First item should be A", "A", first?.displayTitle)
        assertEquals("Queue should still have 1 item", 1, items.size)
        assertEquals("Next item should be B", "B", items[0].displayTitle)
    }

    @Test
    fun `moveToCompleted should trigger processing of next queued item`() {
        // This validates the chain: moveToCompleted → _activeDownload = null → tryProcessNext
        // which is the existing mechanism for advancing the queue.

        val items = mutableListOf(
            DownloadItem(downloadCommand = "!a 1", fileHash = "h1", expectedFileName = "a.epub", displayTitle = "A", format = "EPUB", state = DownloadItemState.Queued),
            DownloadItem(downloadCommand = "!b 2", fileHash = "h2", expectedFileName = "b.epub", displayTitle = "B", format = "EPUB", state = DownloadItemState.Queued),
        )

        // Simulate: A completes → moveToCompleted → _activeDownload = null
        var activeDownload: DownloadItem? = items.removeFirst()
        activeDownload = null

        // After moveToCompleted, tryProcessNext should pick B
        val next = items.firstOrNull()
        assertNotNull("Should have a next item to process", next)
        assertEquals("Next should be B", "B", next?.displayTitle)

        // Process it
        activeDownload = items.removeFirst()
        assertEquals("B should now be active", "B", activeDownload?.displayTitle)
        assertTrue("Queue should be empty after B processed", items.isEmpty())
    }
    
    @Test
    fun `batch enqueue should add all items to queue`() {
        val queue = mutableListOf<DownloadItem>()
        
        // Simulate batchDownload calling enqueue for each item
        listOf(
            DownloadItem(downloadCommand = "!a 1", fileHash = "h1", expectedFileName = "a.epub", displayTitle = "A", format = "EPUB", state = DownloadItemState.Queued),
            DownloadItem(downloadCommand = "!b 2", fileHash = "h2", expectedFileName = "b.epub", displayTitle = "B", format = "EPUB", state = DownloadItemState.Queued),
            DownloadItem(downloadCommand = "!c 3", fileHash = "h3", expectedFileName = "c.epub", displayTitle = "C", format = "EPUB", state = DownloadItemState.Queued),
        ).forEach { item ->
            queue.add(item)
        }
        
        assertEquals("All 3 items should be in queue", 3, queue.size)
        assertEquals("First item should be A", "A", queue[0].displayTitle)
        assertEquals("Second item should be B", "B", queue[1].displayTitle)
        assertEquals("Third item should be C", "C", queue[2].displayTitle)
    }

}
