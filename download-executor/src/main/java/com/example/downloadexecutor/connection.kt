package com.example.downloadexecutor

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

class ResponseCodeException(code: Int): Exception()

internal interface DownloadConnection {
    suspend fun execute(task: DownloadTask): Flow<Pair<Int, ByteArray>>
    fun stop()
    fun isRunning(task: DownloadTask): Boolean
    fun updateEnd(end: Long)
}

internal class DownloadConnectionPool(val url: String, val speedMonitorInterval: Long) {
    val connections: ConcurrentLinkedQueue<DownloadConnection> = ConcurrentLinkedQueue()
    var connectionCount: LongAdder = LongAdder()

    fun getConnectionFor(task: DownloadTask): DownloadConnection {
        return findConnection(task) ?: addConnection()
    }

    fun findConnection(task: DownloadTask): DownloadConnection? {
        synchronized(connections) {
            for (connection in connections) {
                if (connection.isRunning(task)) {
                    return connection
                }
            }
        }
        return null
    }

    fun addConnection(): DownloadConnection {
        synchronized(connectionCount) {
            val newConnection = DownloadConnectionImpl()
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
            connections.remove().stop()
        }
    }
}

internal class DownloadConnectionImpl
    : NetworkConnection(), DownloadConnection {
    companion object {
        val pageSize = 8192
    }
    val end: AtomicLong = AtomicLong(0)
    val dispatcher = Dispatchers.IO.limitedParallelism(1)

    var runningTask: DownloadTask? = null

    var isStopped = false;

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override suspend fun execute(task: DownloadTask): Flow<Pair<Int, ByteArray>> = channelFlow  {
        runningTask = task
        end.set(task.end)

        println("start download from ${task.start} to ${task.end} with ${task.byteSaved} bytes downloaded")

        val rangeStart = task.start + task.byteSaved;
        val rangeEnd = task.end;

        if (rangeEnd <= rangeStart) {
            return@channelFlow
        }

        val connection = openConnection(task.url)

        connection.setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")

        launch(dispatcher) {
            connection.connect()

            println("response code of ${task.start} is ${connection.responseCode}")

            var byteDownloaded: Long = task.byteSaved

            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL
                && connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw ResponseCodeException(connection.responseCode)
            }

            val inputStream = connection.inputStream
            var byteRead: Int
            while (!isStopped) {
                val remain =  end.get() - (byteDownloaded + task.start)
                val buffer = ByteArray(remain.coerceAtMost(pageSize.toLong()).toInt())

                byteRead = inputStream.read(buffer)

                if (byteRead <= 0) {
                    break
                }

                val remainBefore: Long = end.get() - (byteDownloaded + task.start)
                if (remainBefore <= 0) {
                    break
                }

                val byteWillSend = byteRead.toLong().coerceAtMost(remainBefore).toInt()
                byteDownloaded += byteWillSend

                send(Pair(byteWillSend.toInt(), buffer))
            }
            inputStream.close()
        }
    }

    override fun stop() {
        isStopped = true
    }

    override fun isRunning(task: DownloadTask): Boolean {
        return runningTask?.equals(task) == true
    }

    override fun updateEnd(newEnd: Long) {
        end.set(newEnd)
    }
}

internal abstract class NetworkConnection {
    fun openConnection(url: String): HttpURLConnection {
         return URI(url).toURL().openConnection() as HttpURLConnection
    }
}

data class HeaderRequestInfo(
    val contentLength: Long,
    val acceptRange: Boolean,
    val fileName: String? = null,
)

internal class HeaderRequest(val url: String): NetworkConnection() {

    fun execute(): HeaderRequestInfo? {
        val connection = openConnection(url)
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.connect()

        return if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            return HeaderRequestInfo(
                contentLength = connection.getHeaderField("Content-Length").toLong(),
                acceptRange = connection.getHeaderField("Accept-Ranges")?.lowercase() == "true"
            )
        } else {
            null
        }
    }
}
