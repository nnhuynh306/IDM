package com.example.download_mananger.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

fun createExecutor(request: DownloadRequest): NetworkTaskExecutor {
    return DynamicSegmentNetworkTaskExecutor(request, StorageImpl(request.saveFileName))
}

interface Storage {
    suspend fun getDirectory(): File
    suspend fun savePartFile(byteArray: ByteArray, byteCount: Int, name: String)
    suspend fun getListTask(): File
    suspend fun finish()
}

internal class StorageImpl(val destination: String): Storage {
    val mapFile: ConcurrentHashMap<String, FileOutputStream> = ConcurrentHashMap()

    val directory = File("./temp")
    override suspend fun getDirectory(): File {
        return directory
    }

    override suspend fun savePartFile(
        byteArray: ByteArray,
        byteCount: Int,
        name: String
    ) {
        synchronized(directory) {
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
        val fos: FileOutputStream
        if (mapFile.contains(name)) {
            fos = mapFile[name]!!
        } else {
            val filePath = directory.path + "/" + name
            val file = File(filePath)
            fos = FileOutputStream(filePath, true)
            mapFile[name] = fos
        }

        try {
            fos.write(byteArray, 0, byteCount);
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    override suspend fun getListTask(): File {
        TODO("Not yet implemented")
    }

    override suspend fun finish() {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            for (fos in mapFile.values) {
                fos.close()
            }
            val destinationFile = File(directory.path + "/" + destination)
            val buffer = ByteArray(8192)
            FileOutputStream(destinationFile, true).use { desFos ->
                for (file in directory.listFiles().filter {
                    it.name != destination
                }.toList().sortedBy {
                    it.name.substring(it.name.lastIndexOf("_") + 1).toInt()
                }) {
                    val fis = FileInputStream(file)
                    var byteRead = 0
                    while (true) {
                        byteRead = fis.read(buffer)
                        if (byteRead == -1) {
                            break
                        }
                        desFos.write(buffer, 0, byteRead)
                    }
                    fis.close()
                    file.delete()
                }
            }
        }
    }

}

interface NetworkTaskExecutor {
    fun execute(): Flow<DownloadProgress>
}

data class DownloadRequest(
    val url: String,
    val saveFileName: String,
)

@Suppress("NewApi")
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

    fun getPartFileName(finalFileName: String): String {
        val fileName = if(finalFileName.contains(".")) {
            finalFileName.substring(0, finalFileName.lastIndexOf('.'))
        } else {
            finalFileName
        }
        return fileName + "_" + start
    }
}

internal class DynamicSegmentNetworkTaskExecutor(val request: DownloadRequest, val storage: Storage): NetworkTaskExecutor {
    private val tasks: ArrayList<DownloadTask> = arrayListOf()

    private val downloadConnectionPool = DownloadConnectionPool(url = request.url)

    private val progressState = MutableStateFlow(DownloadProgress(0, 0.0))

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun execute(): Flow<DownloadProgress> {
        executeInBackground()

        return progressState
    }

    @Suppress("NewApi")
    private fun executeInBackground() {
        executorScope.launch {
            initialize()

            val jobs = arrayListOf<Deferred<*>>()
            for (task in tasks) {
                jobs.add(async {
                    pushTask(task)
                })
            }

            jobs.awaitAll()
            storage.finish()
            progressState.emit(DownloadProgress(0, 100.0))
        }
    }

    @Suppress("NewApi")
    suspend fun pushTask(task: DownloadTask) = coroutineScope {
        val connection = downloadConnectionPool.getConnectionFor(task)
        launch(Dispatchers.Default) {
            connection.getProgress()
                .collect {
                    println("test download speed task ${(it.speed / 1024 / 1024)}")
                }
        }
        launch(Dispatchers.IO) {
            connection.execute(task).collect {
                storage.savePartFile(byteArray = it.second, byteCount = it.first, task.getPartFileName(request.saveFileName))
            }
        }
    }

    private fun initialize() {
        val headerRequest = HeaderRequest(request.url)
        val headerRequestInfo = headerRequest.execute()

        if (headerRequestInfo == null) {
            throw Exception("Null Header request")
        }

        val median = headerRequestInfo.contentLength / 2
        tasks.add(DownloadTask(
            url = request.url,
            start = 0,
            end = median
        ))
        tasks.add(DownloadTask(
            url = request.url,
            start = median,
            end = headerRequestInfo.contentLength
        ))


//        tasks.add(DownloadTask(
//            url = request.url,
//            start = 0,
//            end = headerRequestInfo.contentLength
//        ))
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