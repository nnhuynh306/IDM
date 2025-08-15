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
    fun start(): Flow<Result<DownloadProgress>>
    fun stop()
}

internal sealed interface ExecutorStatus {
    object Start: ExecutorStatus
    object Stop: ExecutorStatus
    object End: ExecutorStatus
    class Error(val exception: Throwable): ExecutorStatus
}

internal class DynamicSegmentNetworkTaskExecutor(val request: DownloadRequest,
                                                 val fileHandler: FileHandler)
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

    private val statusFlow: MutableStateFlow<ExecutorStatus> = MutableStateFlow(ExecutorStatus.Start)

    private var rangeEnabled = true;

    private var isFinalized = false
    private var finalizedLock = Object()

    private var isStopped = false;

    private val scopeErrorHandler = CoroutineExceptionHandler { _, exception ->
        sendStopOnException(exception)
    }

    private fun sendStopOnException(exception: Throwable) {
        statusFlow.update {
            ExecutorStatus.Error(exception)
        }
    }

    override fun start(): Flow<Result<DownloadProgress>> {
        executeInBackground()

        return combine(progressState.receiveAsFlow(), speedMonitor.getSpeedFlow(), statusFlow)
        { progress, speed, status ->

            if (status is ExecutorStatus.Error) {
                println("Stop on error ${status.exception.toString()}")
                internalStop()
                return@combine Result.failure(status.exception)
            }

            if (status is ExecutorStatus.Stop) {
                println("Stop on stop")
                internalStop()
                return@combine Result.success(DownloadProgress(
                    progress.byteDownloaded,
                    speed = speed,
                    status = DownloadProgress.Status.PAUSED
                ))
            }

            if (status is ExecutorStatus.End) {
                println("Stop on end")
                internalStop()
                return@combine Result.success(DownloadProgress(
                    progress.byteDownloaded,
                    speed = speed,
                    status = DownloadProgress.Status.FINISHED
                ))
            }

            val byteDownloaded = progress.byteDownloaded.toList().sumOf {
                it.progress.toLong().let { totalProgress ->
                    val task = tasks.find { task -> task.start == it.from }
                    totalProgress.coerceAtMost(task?.let { it.end - it.start } ?: totalProgress)
                }
            }

            val totalByteNeedToDownload = this.totalByteNeedToDownload
            val needFinalizing = totalByteNeedToDownload != null && byteDownloaded >= totalByteNeedToDownload
            val downloadStatus = if (needFinalizing) {
                finalize()
                DownloadProgress.Status.FINALIZING
            } else {
                DownloadProgress.Status.DOWNLOADING
            }

            return@combine Result.success(DownloadProgress(
                progress.byteDownloaded,
                speed = speed,
                status = downloadStatus
            ))
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
        executorScope.launch(scopeErrorHandler) {
            if (canFinalize) {
                fileHandler.mergeParts()
                internalStop()
                statusFlow.update {
                    ExecutorStatus.End
                }
            }
        }
        return canFinalize
    }

    private fun internalStop() {
        if (isStopped) {
            return
        }
        isStopped = true
        downloadConnectionPool.cleanUp()
        speedMonitor.stop()
        progressState.close()
        executorScope.cancel()
    }

    override fun stop() {
        statusFlow.update { ExecutorStatus.Stop }
    }

    @Suppress("NewApi")
    private fun executeInBackground() {
        executorScope.launch(scopeErrorHandler) {
            progressState.send(progress)

            speedMonitor.addListener(this@DynamicSegmentNetworkTaskExecutor)

            launch(scopeErrorHandler) {
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
            try {
                connection.execute(task).collect {
                    val byteCount = it.first
                    val byteArray = it.second
                    speedMonitor.addByteCount(byteCount.toLong())
                    byteDownloaded.add(byteCount.toLong())
                    fileHandler.saveToPartFile(task = task, byteArray = byteArray, byteCount = byteCount) {
                        if (!progressState.isClosedForSend) {
                            progressState.send(progress.updateWith(task, byteCount))
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                sendStopOnException(ex)
            }
            speedMonitor.reduceConnectionCount()
        }
    }

    private suspend fun initialize() = coroutineScope {
        val headerRequest = HeaderRequest(request.url)
        val headerRequestInfo = headerRequest.execute() ?: throw Exception("Null Header request")
        rangeEnabled = headerRequestInfo.acceptRange

        fileHandler.initialize()

        val savedRequestInfo = fileHandler.getSavedRequestInfo()

        val initTasks: ArrayList<DownloadTask> = arrayListOf()

        totalByteNeedToDownload = headerRequestInfo.contentLength
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
                    end = end,
                    useRangeRequest = rangeEnabled || savedRequestInfo.savedTasks.size >= 2
                ))
            }
        } else {
            val startTaskCount = if (rangeEnabled) request.startThreadCount.coerceAtLeast(1) else 1
            var max = headerRequestInfo.contentLength
            var start = 0L
            for (i in 0 until startTaskCount) {
                val end = (max * (i + 1)) / startTaskCount
                initTasks.add(DownloadTask(
                    url = request.url,
                    start =  start,
                    byteSaved = 0,
                    end = end,
                    useRangeRequest = rangeEnabled
                ))
                start = end
            }
        }

        val remainFromDownloaded = initTasks.sumOf { (it.end - it.start - it.byteSaved).coerceAtLeast(0) }
        if (remainFromDownloaded == 0L) {
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

    private suspend fun tryCreateNewTask() {
        if (!rangeEnabled) {
            return
        }
        var largestRemain: Long = -1
        var largestRemainTask: DownloadTask? = null
        var largestByteDownloaded: LongAdder? = null
        var newEnd: Long? = null

        val updateTaskResult: Boolean
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

            updateTaskResult = updateTask(largestRemainTask, newEnd)
        }

        if (updateTaskResult && largestRemainTask != null && newEnd != null) {
            pushTask(DownloadTask(
                url = largestRemainTask.url,
                start = newEnd,
                end = largestRemainTask.end,
                byteSaved = 0,
                useRangeRequest = rangeEnabled
            ))
        }
    }

    private fun updateTask(task: DownloadTask, newEnd: Long): Boolean {
        val connection = downloadConnectionPool.findConnection(task) ?: return false
        val result = tasks.removeIf { it.equals(task) } && tasks.add(task.copyWith(newEnd))
        if (result) {
            connection.updateEnd(newEnd)
        }
        return result
    }

    override fun onTotalSpeedIncreased() {
        executorScope.launch(scopeErrorHandler) {
            tryCreateNewTask()
        }
    }
}