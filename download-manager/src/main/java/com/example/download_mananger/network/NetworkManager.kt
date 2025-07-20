package com.example.download_mananger.network

import com.example.download_mananger.storage.Storage
import com.example.download_mananger.storage.getStorageFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

fun createExecutor(request: DownloadRequest): NetworkTaskExecutor {
    return DynamicSegmentNetworkTaskExecutor(request, getStorageFor(request.saveFileName))
}

data class DownloadProcessInfo(
    val byteDownloaded: List<PartialProgress>,
    val speed: Double = 0.0,
    val isFinished: Boolean = false,
)

data class DownloadProgress(
    val byteDownloaded: ArrayList<PartialProgress>
) {
    fun addProgressFrom(task: DownloadTask): DownloadProgress {
        byteDownloaded.add(PartialProgress(
            from = task.start
        ))
        return this
    }

    fun updateWith(task: DownloadTask, byteCount: Int): DownloadProgress {
        byteDownloaded.find {
            it.from == task.start
        }?.progress?.add(byteCount.toLong())
        return this
    }
}

data class PartialProgress(
    val from: Long,
    val progress: LongAdder = LongAdder()
)

interface NetworkTaskExecutor {
    fun execute(): Flow<DownloadProcessInfo>
}

data class DownloadRequest(
    val url: String,
    val saveFileName: String,
)

@Suppress("NewApi")
data class DownloadTask @OptIn(ExperimentalUuidApi::class) constructor(
    val id: String = Uuid.random().toString(),
    val url: String,
    val start: Long,
    val end: Long,
    val byteSaved: Long,
) {
    fun equals(otherTask: DownloadTask): Boolean {
        return otherTask.id == id
    }

    fun copyWith(newEnd: Long): DownloadTask {
        return DownloadTask(
            id,
            url,
            start,
            newEnd,
            byteSaved
        )
    }
}