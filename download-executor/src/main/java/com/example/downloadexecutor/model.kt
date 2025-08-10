package com.example.downloadexecutor

import java.util.concurrent.atomic.LongAdder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class SavedProgress(
    val byteSaved: List<PartialProgress>,
)

data class PartialProgress(
    val from: Long,
    val to: Long,
)

data class DownloadProgress(
    val byteDownloaded: List<InternalPartialProgress>,
    val speed: Double = 0.0,
    val status: Status,
) {
    enum class Status {
        DOWNLOADING,
        PAUSED,
        FINALIZING,
        FINISHED
    }

    fun isFinished(): Boolean {
        return status == Status.FINISHED
    }
}

internal data class InternalDownloadProgress(
    val byteDownloaded: ArrayList<InternalPartialProgress>
) {
    fun addProgressFrom(task: DownloadTask): InternalDownloadProgress {
        val progress = InternalPartialProgress(
            from = task.start
        )
        progress.progress.add(task.byteSaved)
        byteDownloaded.add(progress)
        return this
    }

    fun updateWith(task: DownloadTask, byteCount: Int): InternalDownloadProgress {
        val find = byteDownloaded.find {
            it.from == task.start
        }
        find?.progress?.add(byteCount.toLong())
        return this
    }
}

data class InternalPartialProgress(
    val from: Long,
    val progress: LongAdder = LongAdder()
)

data class DownloadRequest(
    val url: String,
    val saveFileName: String,
    val startThreadCount: Int = 1
)

@Suppress("NewApi")
data class DownloadTask @OptIn(ExperimentalUuidApi::class) constructor(
    val id: String = Uuid.random().toString(),
    val url: String,
    val start: Long,
    val end: Long,
    val byteSaved: Long,
    val useRangeRequest: Boolean = false
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
            byteSaved,
            useRangeRequest
        )
    }
}