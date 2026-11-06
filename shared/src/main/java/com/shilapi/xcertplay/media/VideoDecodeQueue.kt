package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
        /** Local diagnostic identity, never part of the compressed stream. */
        val frameId: Long = frameIds.incrementAndGet(),
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
    companion object { private val frameIds = java.util.concurrent.atomic.AtomicLong() }
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Recovery observes sustained backlog; short delayed bursts keep their reference chain.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = ArrayDeque<VideoJob>()
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var frameCount = 0
    private var frameBytes = 0L

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)
    data class Snapshot(val frames: Int, val bytes: Long, val oldestNs: Long)

    fun snapshot(): Snapshot = lock.withLock {
        // Control jobs can precede the oldest frame; no temporary frame list is needed.
        val oldest = jobs.firstOrNull { it is VideoJob.Frame } as? VideoJob.Frame
        Snapshot(frameCount, frameBytes, oldest?.receivedNs ?: 0)
    }

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    fun backlogAfterCurrent(): Backlog = lock.withLock {
        var frames = 0
        var newest: Long? = null
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            frames++
            newest = newest?.let { maxOf(it, job.receivedNs) } ?: job.receivedNs
        }
        Backlog(frames, newest)
    }

    fun offer(job: VideoJob) = lock.withLock {
        if (job is VideoJob.Frame) {
            if (frameCount >= maxFrames || frameBytes + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.addLast(VideoJob.Resync)
                available.signal()
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            frameCount++
            frameBytes += job.nalus.size
        }
        jobs.addLast(job)
        available.signal()
    }

    private fun removed(job: VideoJob) {
        if (job is VideoJob.Frame) {
            frameCount--
            frameBytes -= job.nalus.size
        }
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    fun discardCurrentChain() = lock.withLock {
        while (true) {
            val job = jobs.peekFirst() ?: return
            if (job !is VideoJob.Frame && job != VideoJob.Resync) return
            removed(jobs.removeFirst())
        }
    }

    fun discardFrames() = lock.withLock {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job is VideoJob.Frame || job == VideoJob.Resync) {
                iterator.remove()
                removed(job)
            }
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? {
        lock.lockInterruptibly()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (jobs.isEmpty()) {
                if (remaining <= 0) return null
                remaining = available.awaitNanos(remaining)
            }
            return jobs.removeFirst().also { removed(it) }
        } finally {
            lock.unlock()
        }
    }

    /** Removes and returns every queued job, for a worker that is shutting down. */
    fun drain(): List<VideoJob> = lock.withLock {
        ArrayList(jobs).also {
            jobs.clear()
            frameCount = 0
            frameBytes = 0
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. Video and audio decoders share it. */
internal object DecoderInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
