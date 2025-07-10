package com.example.download_mananger.network

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

interface DownloadConnection {
    suspend fun execute(task: DownloadTask): Flow<Pair<Int, ByteArray>>
    fun close()
    fun stop()
    fun getStart(): Long
    fun getProgress(): Flow<DownloadProgress>
    suspend fun updateNewEnd(end: Long): Boolean
}

data class DownloadProgress(
    val byteDownloaded: Long = 0,
    val speed: Double = 0.0
)

class DownloadConnectionPool(val url: String) {
    val connections: ConcurrentLinkedQueue<DownloadConnection> = ConcurrentLinkedQueue()
    var connectionCount: LongAdder = LongAdder()

    fun getConnectionFor(task: DownloadTask): DownloadConnection {
        return findConnection(task) ?: addConnection()
    }

    fun findConnection(task: DownloadTask): DownloadConnection? {
        synchronized(connections) {
            for (connection in connections) {
                if (connection.getStart() == task.start) {
                    return connection
                }
            }
        }
        return null
    }

    fun addConnection(): DownloadConnection {
        synchronized(connectionCount) {
            val newConnection = DownloadConnectionImpl(url)
            connections.add(newConnection)
            connectionCount.add(1)
            return newConnection
        }
    }

    fun getConnectionCount(): Int {
        return connectionCount.toInt()
    }

    fun cleanUp() {
        while (connections.isNotEmpty()) {
            connections.remove().close()
        }
    }
}

internal class DownloadConnectionImpl(url: String): NetworkConnection(url), DownloadConnection {
    private val byteDownloaded = LongAdder()

    companion object {
        val pageSize = 8192
    }

    private var end: AtomicLong = AtomicLong(0)
    private var start: AtomicLong = AtomicLong(0)

    private var monitorStart: Long = 0
    private var monitorEnd: Long = 0

    private var mutex = Mutex()

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override suspend fun execute(task: DownloadTask): Flow<Pair<Int, ByteArray>> = channelFlow  {
        println("start in ${task.start}")
        updateTask(task)

        connection.connect()

        byteDownloaded.reset()

        println("response code of ${task.start} is ${connection.responseCode}")

        if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            monitorStart = System.currentTimeMillis()
            val inputStream = connection.inputStream
            var byteRead: Int
            while (true) {
                val remain =  end.get() - (byteDownloaded.toLong() + start.get())
                val buffer = ByteArray(remain.coerceAtMost(pageSize.toLong()).toInt())

                byteRead = inputStream.read(buffer)

                val shouldBreak: Boolean
                val remainBefore: Long
                mutex.withLock {
                    remainBefore = end.toLong() - (byteDownloaded.toLong() + start.toLong())
                    if (byteRead > 0) {
                        byteDownloaded.add(byteRead.toLong())
                        task.byteDownloaded.add(byteRead.toLong())
                    }
                    shouldBreak = remainBefore <= 0 || byteRead <= 0
                }
                if (shouldBreak) {
                    break
                }

                send(Pair(byteRead.toLong().coerceAtMost(remainBefore).toInt(), buffer))
            }
            monitorEnd = System.currentTimeMillis()
            inputStream.close()
        }
    }

    private fun updateTask(task: DownloadTask) {
        start.set(task.start)
        end.set(task.end)
        connection.setRequestProperty("Range", "bytes=${task.start + task.byteSaved}-${task.end}")
    }

    override fun close() {
        connection.disconnect()
    }

    override fun stop() {
        connection.inputStream.close()
    }

    override fun getStart(): Long {
        return start.get()
    }

    override fun getProgress(): Flow<DownloadProgress> = channelFlow {
        var byteStart = 0L
        while (true) {
            if (monitorStart == 0L) {
                delay(10L)
                continue
            } else {
                delay(1000L)
            }

            val speed: Double
            val byteDownloadedUntilNow: Long
            synchronized(byteDownloaded) {
                val startTime = monitorStart
                monitorStart = System.currentTimeMillis()
                byteDownloadedUntilNow = byteDownloaded.toLong()
                val endTime = if (monitorEnd > 0) monitorEnd else System.currentTimeMillis()
                speed = (byteDownloadedUntilNow - byteStart).toDouble() / ((endTime - startTime) / 1000.0)
                byteStart = byteDownloadedUntilNow
            }
            val isFinished = byteDownloadedUntilNow >= (end.get() - start.get())
            send(DownloadProgress(byteDownloadedUntilNow, speed))
            if (isFinished) {
                break
            }
        }
    }

    override suspend fun updateNewEnd(newEnd: Long): Boolean {
        val result: Boolean
        mutex.withLock {
            if (byteDownloaded.toLong() > newEnd) {
                result = false
            } else {
                end.set(newEnd)
                result = true
            }
        }
        return result
    }
}

abstract class NetworkConnection(val url: String) {
    protected val connection: HttpURLConnection = URI(url).toURL().openConnection() as HttpURLConnection


}

data class RequestInfo(
    val contentLength: Long,
    val acceptRange: Boolean,
)

class HeaderRequest(url: String): NetworkConnection(url) {

    fun execute(): RequestInfo? {
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 10000
        connection.connect()

        return if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            return RequestInfo(
                contentLength = connection.getHeaderField("Content-Length").toLong(),
                acceptRange = connection.getHeaderField("Accept-Ranges").lowercase() == "true"
            )
        } else {
            null
        }
    }
}
