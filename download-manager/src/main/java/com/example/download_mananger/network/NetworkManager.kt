package com.example.download_mananger.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder

fun createExecutor(request: DownloadRequest): NetworkTaskExecutor {
    return DynamicSegmentNetworkTaskExecutor(request, object : Storage {
        override suspend fun getDirectory(): File {
            TODO("Not yet implemented")
        }

        override suspend fun savePartFile(dataStream: InputStream, name: String) {
            TODO("Not yet implemented")
        }

        override suspend fun getListTask(): File {
            TODO("Not yet implemented")
        }

    })
}

class Progress(
    val byteDownloaded: Long = 0,
    val speed: Int = 0,
)

interface Storage {
    suspend fun getDirectory(): File
    suspend fun savePartFile(dataStream: InputStream, name: String)
    suspend fun getListTask(): File
}

interface NetworkTaskExecutor {
    fun execute(): Flow<Progress>
}


interface DownloadConnection {
    suspend fun execute(task: DownloadTask): Flow<ByteArray>
    fun getCurrentTask(): DownloadTask?
    fun close()
    fun stop()
}

data class DownloadRequest(
    val url: String,
)

data class DownloadTask(
    val url: String,
    val start: Long,
    val end: Long,
    var byteDownloaded: Long = 0,
) {

    var isRunning: Boolean = false

    fun isFinished(): Boolean {
        return end - start <= byteDownloaded
    }
}

internal class DynamicSegmentNetworkTaskExecutor(val request: DownloadRequest, val storage: Storage): NetworkTaskExecutor, ConnectionAvailabilityListener {
    private val tasks: ArrayList<DownloadTask> = arrayListOf()

    private val downloadConnectionPool = DownloadConnectionPool(url = request.url, connectionAvailabilityListener = this)

    private val progressState = MutableStateFlow(Progress(0, 0))

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun execute(): Flow<Progress> {
        executeInBackground()

        return progressState
    }

    private fun executeInBackground() {
        executorScope.launch {
            initialize()

            for (task in tasks) {
                downloadConnectionPool.pushTask(task)
            }
        }
    }

    private fun initialize() {
        val headerRequest = HeaderRequest(request.url)
        val headerRequestInfo = headerRequest.execute()

        if (headerRequestInfo == null) {
            throw Exception("Null Header request")
        }

        tasks.add(DownloadTask(
            url = request.url,
            start = 0,
            end = headerRequestInfo.contentLength
        ))
    }

    override fun onNewConnectionAvailable() {
        TODO("Not yet implemented")
    }
}

interface ConnectionAvailabilityListener {
    fun onNewConnectionAvailable()
}

class DownloadConnectionPool(val url: String, val limit: Int = 16, connectionAvailabilityListener: ConnectionAvailabilityListener) {
    val connections: ConcurrentLinkedQueue<DownloadConnection> = ConcurrentLinkedQueue()
    var connectionCount: LongAdder = LongAdder()

    suspend fun pushTask(task: DownloadTask): Flow<ByteArray>? {
        return (findConnection(task) ?: addConnection()).execute(task)
    }

    fun findConnection(task: DownloadTask): DownloadConnection? {
        synchronized(connections) {
            for (connection in connections) {
                if (connection.getCurrentTask()?.start == task.start) {
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

    fun canOpenMoreConnection(): Boolean {
        return connectionCount.toInt() < limit
    }

    fun cleanUp() {
        while (connections.isNotEmpty()) {
            connections.remove().close()
        }
    }
}

internal class DownloadConnectionImpl(url: String): NetworkConnection(url), DownloadConnection {
    private var currentTask: DownloadTask? = null

    override suspend fun execute(task: DownloadTask): Flow<ByteArray> {
        connection.connect()
        TODO()
    }

    override fun getCurrentTask(): DownloadTask? {
        return currentTask
    }

    override fun close() {
        connection.disconnect()
    }

    override fun stop() {
        connection.inputStream.close()
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
