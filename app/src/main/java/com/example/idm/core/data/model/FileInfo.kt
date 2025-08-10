package com.example.idm.core.data.model

import kotlinx.coroutines.flow.MutableStateFlow

data class FileInfo(
    val requestUrl: String,
    val status: MutableStateFlow<Status>,
    val destination: String,
    val name: String,
    val totalSize: Long
)

sealed interface Status {
    object NotStarted: Status
    object Finished: Status
    object Finalizing: Status
    data class Error(val error: Throwable): Status

    abstract class ProgressStatus(data: List<PartProgress>) {
        val progress: List<PartProgress> = data.sortedBy { it.from }
    }

    class Downloading(progress: List<PartProgress>, val speed: Double)
        : ProgressStatus(progress), Status

    class Paused(progress: List<PartProgress>):  ProgressStatus(progress), Status
}