package com.example.downloadexecutor

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder

interface NetworkTaskExecutor {
    fun start(): Flow<DownloadProgress>
    fun stop()
}

internal enum class ExecutorStatus {
    START,
    STOP,
    END
}

internal class DynamicSegmentNetworkTaskExecutor(val request: DownloadRequest,
                                                 val saveFileHandler: SaveFileHandler)
    : NetworkTaskExecutor, SpeedListener {
    private val tasks: ConcurrentLinkedQueue<DownloadTask> = ConcurrentLinkedQueue()

    private val progressState = Channel<InternalDownloadProgress>(
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val progress = InternalDownloadProgress(arrayListOf())

    private val speedMonitorInterval = 1000L
    private val speedMonitor = SpeedMonitor(speedMonitorInterval)

    private val downloadConnectionPool = DownloadConnectionPool(url = request.url, speedMonitorInterval)

    private var totalByteNeedToDownload: Long? = null

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val byteDownloadedMap: ConcurrentHashMap<String, LongAdder> = ConcurrentHashMap()

    private val statusFlow = MutableStateFlow(ExecutorStatus.START)

    private var rangeEnabled = true;

    private var isFinalized = false
    private var finalizedLock = Object()

    override fun start(): Flow<DownloadProgress> {
        executeInBackground()

        return combine(progressState.receiveAsFlow(), speedMonitor.getSpeedFlow(), statusFlow)
        { progress, speed, status ->

            if (status == ExecutorStatus.STOP) {
                internalStop()
                return@combine DownloadProgress(
                    progress.byteDownloaded,
                    speed = speed,
                    status = DownloadProgress.Status.PAUSED
                )
            }

            if (status == ExecutorStatus.END) {
                return@combine DownloadProgress(
                    progress.byteDownloaded,
                    speed = speed,
                    status = DownloadProgress.Status.FINISHED
                )
            }

            val byteDownloaded = progress.byteDownloaded.toList().sumOf {
                it.progress.toLong().let { totalProgress ->
                    val task = tasks.find { task -> task.start == it.from }
                    totalProgress.coerceAtMost(task?.let { it.end - it.start } ?: totalProgress)
                }
            }

            val totalByteNeedToDownload = this.totalByteNeedToDownload
            val needFinalizing = totalByteNeedToDownload != null && byteDownloaded >= totalByteNeedToDownload
            val status = if (needFinalizing) {
                finalize()
                DownloadProgress.Status.FINALIZING
            } else {
                DownloadProgress.Status.DOWNLOADING
            }

            DownloadProgress(
                progress.byteDownloaded,
                speed = speed,
                status = status
            )
        }
    }

    private fun finalize(): Boolean {
        val canFinalize: Boolean
        synchronized(finalizedLock) {
            canFinalize = !isFinalized
            if (!isFinalized) {
                isFinalized = true
            }
        }
        executorScope.launch {
            if (canFinalize) {
                saveFileHandler.mergeParts()
                internalStop()
                statusFlow.update {
                    ExecutorStatus.END
                }
            }
        }
        return canFinalize
    }

    private fun internalStop() {
        downloadConnectionPool.cleanUp()
        speedMonitor.stop()
        progressState.close()
        executorScope.cancel()
    }

    override fun stop() {
        statusFlow.update { ExecutorStatus.STOP }
    }

    @Suppress("NewApi")
    private fun executeInBackground() {
        val handler = CoroutineExceptionHandler { _, exception ->
            println("Caught exception: $exception")
            exception.printStackTrace()
            stop()
        }

        executorScope.launch(handler) {
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
                saveFileHandler.saveToPartFile(task = task, byteArray = byteArray, byteCount = byteCount) {
                    if (!progressState.isClosedForSend) {
                        progressState.send(progress.updateWith(task, byteCount))
                    }
                }
            }
            speedMonitor.reduceConnectionCount()
        }
    }

    private suspend fun initialize() = coroutineScope {
        val headerRequest = HeaderRequest(request.url)
        val headerRequestInfo = headerRequest.execute() ?: throw Exception("Null Header request")
//        rangeEnabled = headerRequestInfo.acceptRange

        val savedRequestInfo = saveFileHandler.getSavedRequestInfo()

        val initTasks: ArrayList<DownloadTask> = arrayListOf()

        if (savedRequestInfo != null && savedRequestInfo.savedTasks.isNotEmpty()) {
            for ((i, savedTask) in savedRequestInfo.savedTasks.withIndex()) {
                val end = if (i < savedRequestInfo.savedTasks.size - 1) {
                    savedRequestInfo.savedTasks[i + 1].start
                } else {
                    headerRequestInfo.contentLength
                }

                initTasks.add(DownloadTask(
                    url = request.url,
                    start = savedTask.start,
                    byteSaved = savedTask.byteSaved,
                    end = end
                ))
            }

            totalByteNeedToDownload = initTasks.sumOf { (it.end - it.start - it.byteSaved).coerceAtLeast(0) }
        } else {
            initTasks.add(DownloadTask(
                url = request.url,
                start = 0,
                byteSaved = 0,
                end = headerRequestInfo.contentLength
            ))
            totalByteNeedToDownload = headerRequestInfo.contentLength
        }

        if (totalByteNeedToDownload == 0L) {
            if (savedRequestInfo != null) {
                val finalProgress = InternalPartialProgress(0)
                finalProgress.progress.add(headerRequestInfo.contentLength)
                progressState.send(InternalDownloadProgress(
                    arrayListOf(finalProgress)
                ))
            }
        } else {
            for (task in initTasks) {
                launch {
                    pushTask(task)
                }
            }
        }
    }

    private fun tryCreateNewTask() {
        if (!rangeEnabled) {
            return
        }
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