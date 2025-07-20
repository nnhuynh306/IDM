package com.example.download_mananger.network

import com.example.download_mananger.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder

internal class DynamicSegmentNetworkTaskExecutor(val request: DownloadRequest, val storage: Storage)
    : NetworkTaskExecutor, SpeedListener {
    private val tasks: ConcurrentLinkedQueue<DownloadTask> = ConcurrentLinkedQueue()

    private val progressState = Channel<DownloadProgress>(
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val progress = DownloadProgress(arrayListOf())

    private val speedMonitorInterval = 1000L
    private val speedMonitor = SpeedMonitor(speedMonitorInterval)

    private val downloadConnectionPool = DownloadConnectionPool(url = request.url, speedMonitorInterval)

    private var totalByteNeedToDownload = 0L

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val byteDownloadedMap: ConcurrentHashMap<String, LongAdder> = ConcurrentHashMap()


    override fun execute(): Flow<DownloadProcessInfo> {
        executeInBackground()

        return combine(progressState.receiveAsFlow(), speedMonitor.getSpeedFlow()) { progress, speed ->

            val byteDownloaded = progress.byteDownloaded.sumOf { it.progress.toLong() }
            val isFinished = byteDownloaded >= totalByteNeedToDownload && totalByteNeedToDownload != 0L
            if (isFinished) {
                speedMonitor.stop()
                storage.mergeParts()
            }

            DownloadProcessInfo(
                progress.byteDownloaded,
                speed = speed,
                isFinished = isFinished
            )
        }
    }

    @Suppress("NewApi")
    private fun executeInBackground() {
        executorScope.launch {
            speedMonitor.addListener(this@DynamicSegmentNetworkTaskExecutor)

            launch {
                val start = System.currentTimeMillis()
                delay(1000)
                speedMonitor.startMonitoring(start)
            }

            initialize()
        }
    }

    @Suppress("NewApi")
    suspend fun pushTask(task: DownloadTask) = coroutineScope {
        tasks.add(task)
        speedMonitor.addConnectionCount()
        progressState.send(progress.addProgressFrom(task))

        val byteDownloaded = LongAdder()
        byteDownloadedMap[task.id] = byteDownloaded

        val connection = downloadConnectionPool.getConnectionFor(task)
        withContext(Dispatchers.IO) {
            connection.execute(task).collect {
                val byteCount = it.first
                val byteArray = it.second
                speedMonitor.addByteCount(byteCount.toLong())
                byteDownloaded.add(byteCount.toLong())
                storage.saveToPartFile(task = task, byteArray = byteArray, byteCount = byteCount) {
                    progressState.send(progress.updateWith(task, byteCount))
                }
            }
            speedMonitor.reduceConnectionCount()
        }
    }

    private suspend fun initialize() = coroutineScope {
        val headerRequest = HeaderRequest(request.url)
        val headerRequestInfo = headerRequest.execute() ?: throw Exception("Null Header request")

        val savedRequestInfo = storage.getSavedRequestInfo()

        val initTasks: ArrayList<DownloadTask> = arrayListOf()

        if (savedRequestInfo != null && savedRequestInfo.savedTasks.isNotEmpty()) {
            for ((i, savedTask) in savedRequestInfo.savedTasks.withIndex()) {
                val end = if (i < savedRequestInfo.savedTasks.size - 1) {
                    savedRequestInfo.savedTasks[i + 1].start
                } else {
                    headerRequestInfo.contentLength
                }

                val byteSaved = savedTask.byteSaved.coerceAtMost(end)

                if (savedTask.start + byteSaved >= end) {
                    continue
                }

                initTasks.add(DownloadTask(
                    url = request.url,
                    start = savedTask.start,
                    byteSaved = byteSaved,
                    end = end
                ))
            }

            totalByteNeedToDownload = initTasks.sumOf { it.end - it.start - it.byteSaved }
        } else {
            initTasks.add(DownloadTask(
                url = request.url,
                start = 0,
                byteSaved = 0,
                end = headerRequestInfo.contentLength
            ))
            totalByteNeedToDownload = headerRequestInfo.contentLength
        }

        for (task in initTasks) {
            launch {
                pushTask(task)
            }
        }
    }

    private fun tryCreateNewTask() {
        var largestRemain: Long = -1
        var largestRemainTask: DownloadTask? = null
        var largestByteDownloaded: LongAdder? = null
        var newEnd: Long? = null

        synchronized(tasks) {
            for (task in tasks) {
                val byteDownloaded = byteDownloadedMap[task.id]!!
                val remain = task.end - task.start - byteDownloaded.toLong()
                if (remain > largestRemain) {
                    largestRemain = remain
                    largestRemainTask = task
                    largestByteDownloaded = byteDownloaded
                }
            }

            if (largestRemainTask == null) {
                return
            }


            val byteDownloaded = largestByteDownloaded!!.toLong()
            newEnd = largestRemainTask.start + byteDownloaded +
                    ((largestRemainTask.end - largestRemainTask.start - byteDownloaded) / 2)

            updateTask(largestRemainTask, newEnd)
        }

        if (largestRemainTask != null && newEnd != null) {
            executorScope.launch {
                pushTask(DownloadTask(
                    url = largestRemainTask.url,
                    start = newEnd,
                    end = largestRemainTask.end,
                    byteSaved = 0
                ))
            }
        }
    }

    private fun updateTask(task: DownloadTask, newEnd: Long) {
        val connection = downloadConnectionPool.findConnection(task) ?: return
        connection.updateEnd(newEnd)
        val result = tasks.removeIf { it.equals(task) } && tasks.add(task.copyWith(newEnd))
        if (!result) {
            throw IllegalStateException("update task failed");
        }
    }

    override fun onTotalSpeedIncreased() {
        executorScope.launch {
            tryCreateNewTask()
        }
    }
}