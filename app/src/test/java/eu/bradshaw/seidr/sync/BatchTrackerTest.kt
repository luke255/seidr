package eu.bradshaw.seidr.sync

import eu.bradshaw.seidr.data.db.Download
import eu.bradshaw.seidr.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchTrackerTest {
    private fun d(id: String, state: String, size: Long = 100, done: Long = 0) =
        Download(trackId = id, state = state, size = size, suffix = "flac", bytesDone = done)

    private fun rows(vararg d: Download) = d.associateBy { it.trackId }

    @Test fun idleIsNull() {
        assertNull(BatchTracker().update(rows(d("a", DownloadState.DONE))))
    }

    @Test fun progressCoversTheWholeBatchNotEachFile() {
        val t = BatchTracker()
        t.update(rows(d("a", DownloadState.QUEUED), d("b", DownloadState.QUEUED), d("c", DownloadState.QUEUED), d("d", DownloadState.QUEUED)))
        // a done, b halfway: 1.5 of 4 files' bytes.
        val b = t.update(rows(d("a", DownloadState.DONE), d("b", DownloadState.DOWNLOADING, done = 50), d("c", DownloadState.QUEUED), d("d", DownloadState.QUEUED)))!!
        assertEquals(3, b.left)
        assertEquals(0.375f, b.fraction, 0.001f)
        // Between files: nothing fetching, still the batch's progress, not back to zero.
        val gap = t.update(rows(d("a", DownloadState.DONE), d("b", DownloadState.DONE), d("c", DownloadState.QUEUED), d("d", DownloadState.QUEUED)))!!
        assertEquals(0.5f, gap.fraction, 0.001f)
    }

    @Test fun finishedDownloadsFromEarlierBatchesDontCount() {
        val t = BatchTracker()
        val b = t.update(rows(d("old", DownloadState.DONE), d("new", DownloadState.QUEUED)))!!
        assertEquals(0f, b.fraction, 0.001f)
    }

    @Test fun failedAndRemovedLeaveTheBatch() {
        val t = BatchTracker()
        t.update(rows(d("a", DownloadState.QUEUED), d("b", DownloadState.QUEUED), d("c", DownloadState.QUEUED)))
        val b = t.update(rows(d("a", DownloadState.FAILED), d("c", DownloadState.DOWNLOADING, done = 50)))!!
        assertEquals(0.5f, b.fraction, 0.001f)
    }

    @Test fun aNewBatchStartsAfresh() {
        val t = BatchTracker()
        t.update(rows(d("a", DownloadState.QUEUED)))
        assertNull(t.update(rows(d("a", DownloadState.DONE))))
        val b = t.update(rows(d("a", DownloadState.DONE), d("b", DownloadState.QUEUED)))!!
        assertEquals(0f, b.fraction, 0.001f)
    }
}
