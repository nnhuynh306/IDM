package com.example.download_mananger.network

import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun createExecutor(request: DownloadRequest): NetworkTaskExecutor {
    return DynamicSegmentNetworkTaskExecutor(request, object : Storage {
        val mapFile: HashMap<String, FileOutputStream> = hashMapOf()
        override suspend fun getDirectory(): File {
            TODO("Not yet implemented")
        }

        override suspend fun savePartFile(
            byteArray: ByteArray,
            byteCount: Int,
            name: String
        ) {
            val fos: FileOutputStream
            if (mapFile.contains(name)) {
                fos = mapFile[name]!!
            } else {
                fos = FileOutputStream(name, true)
                mapFile[name] = fos
            }

            fos.write(byteArray, 0, byteCount);
        }


        override suspend fun getListTask(): File {
            TODO("Not yet implemented")
        }

    })
}

class Progress(
    val byteDownloaded: Long = 0,
    val speed: Double = 0.0,
)

interface Storage {
    suspend fun getDirectory(): File
    suspend fun savePartFile(byteArray: ByteArray, byteCount: Int, name: String)
    suspend fun getListTask(): File
}

interface NetworkTaskExecutor {
    fun execute(): Flow<Progress>
}


interface DownloadConnection {
    fun execute(task: DownloadTask): Flow<Pair<Int, ByteArray>>
    fun close()
    fun stop()
    fun getStart(): Long
    fun getProgress(): Flow<Progress>
    suspend fun updateNewEnd(end: Long): Boolean
}

data class DownloadRequest(
    val url: String,
)

data class DownloadTask(
    val url: String,
    val start: Long,
    val end: Long,
    var byteSaved: Long = 0,
    val byteDownloaded: LongAdder = LongAdder(),
) {

    var isRunning: Boolean = false

    fun isFinished(): Boolean {
        return end - start <= byteSaved
    }
}

internal class DynamicSegmentNetworkTaskExecutor(val request: DownloadRequest, val storage: Storage): NetworkTaskExecutor {
    private val tasks: ArrayList<DownloadTask> = arrayListOf()

    private val downloadConnectionPool = DownloadConnectionPool(url = request.url)

    private val progressState = MutableStateFlow(Progress(0, 0.0))

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun execute(): Flow<Progress> {
        executeInBackground()

        return progressState
    }

    private fun executeInBackground() {
        executorScope.launch {
            initialize()

            for (task in tasks) {
                pushTask(task)
            }
        }
    }

    var count = 0
    @Suppress("NewApi")
    suspend fun pushTask(task: DownloadTask) = coroutineScope {
        val connection = downloadConnectionPool.getConnectionFor(task)
        executorScope.launch {
            connection.getProgress()
                .collect {
                    println("test download speed ${(it.speed / 1024 / 1024)}")
                    if (count++ == 2) {
                         _tryCreateNewTask()?.let {
                             pushTask2(it)
                         }
                    }
                }
        }
        executorScope.launch {
            connection.execute(task).collect {
//                storage.savePartFile(byteArray = it.second, byteCount = it.first, task.start.toString() + "_file.part")
                storage.savePartFile(byteArray = it.second, byteCount = it.first, "/" + Paths.get(URI(task.url).getPath()).getFileName().toString())
            }
        }
    }


    suspend fun pushTask2(task: DownloadTask) = coroutineScope {
        val connection = downloadConnectionPool.getConnectionFor(task)
        executorScope.launch {
            connection.getProgress()
                .collect {
                    println("test download speed 2 ${(it.speed / 1024 / 1024)}")
                }
        }
        executorScope.launch {
            connection.execute(task).collect {
                println(it.second)
//                storage.savePartFile(byteArray = it.second, byteCount = it.first, task.start.toString() + "_file.part")
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

    private suspend fun _tryCreateNewTask(): DownloadTask? {
        var largestRemain: Long = -1
        var largestRemainTask: DownloadTask? = null
        for (task in tasks) {
            val remain = task.end - task.start - task.byteDownloaded.toLong()
            if (remain > largestRemain) {
                largestRemain = remain
                largestRemainTask = task
            }
        }

        if (largestRemainTask == null) {
            return null
        }

        val connection = downloadConnectionPool.findConnection(largestRemainTask)
        if (connection == null) {
            return null
        }

        val byteDownloaded = largestRemainTask.byteDownloaded.toLong()
        val newEnd = largestRemainTask.start + byteDownloaded +
                ((largestRemainTask.end - largestRemainTask.start - byteDownloaded) / 2)
        return if (connection.updateNewEnd(newEnd)) {
            DownloadTask(
                url = largestRemainTask.url,
                start = newEnd + 1,
                end = largestRemainTask.end,
            )
        } else {
            null
        }
    }
}

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

    private var mutex = Mutex()

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override fun execute(task: DownloadTask): Flow<Pair<Int, ByteArray>> = channelFlow  {
        launch(newSingleThreadContext(task.start.toString())) {
            updateTask(task)

            connection.connect()

            byteDownloaded.reset()

            if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                monitorStart = System.currentTimeMillis()
                val inputStream = connection.inputStream
                var byteRead = pageSize
                while (byteRead != -1) {
                    val remain =  end.get() - (byteDownloaded.toLong() + start.get())
                    val buffer = ByteArray(remain.coerceAtMost(pageSize.toLong()).toInt())

                    byteRead = inputStream.read(buffer)

                    val shouldBreak: Boolean
                    mutex.withLock {
                        if (byteRead != -1) {
                            byteDownloaded.add(byteRead.toLong())
                            task.byteDownloaded.add(byteRead.toLong())
                        }
                        shouldBreak = byteDownloaded.toLong() + task.start >= task.end
                    }
                    if (shouldBreak) {
                        break
                    }

                    send(Pair(byteRead, buffer))
                }
                inputStream.close()
            }
        }
    }

    private fun updateTask(task: DownloadTask) {
        start.set(task.start)
        end.set(task.end)
        connection.setRequestProperty("Range", "bytes=${task.start + task.byteSaved}-${task.end}")
        connection.setRequestProperty("Connection", "close");
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

    override fun getProgress(): Flow<Progress> = channelFlow {
        var byteStart = 0L
        while (true) {
            delay(1000)
            val speed: Double
            val byteDownloadedUntilNow: Long
            synchronized(byteDownloaded) {
                val startTime = monitorStart
                monitorStart = System.currentTimeMillis()
                byteDownloadedUntilNow = byteDownloaded.toLong()
                speed = (byteDownloadedUntilNow - byteStart).toDouble() / ((System.currentTimeMillis() - startTime) / 1000.0)
                byteStart = byteDownloadedUntilNow
            }
            send(Progress(byteDownloadedUntilNow, speed))
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
