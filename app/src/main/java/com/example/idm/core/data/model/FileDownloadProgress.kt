package com.example.idm.core.data.model

import com.example.downloadexecutor.PartialProgress

data class PartProgress(
    val from: Long,
    val to: Long
)

fun PartialProgress.mapToModel(): PartProgress {
    return PartProgress(
        from = from,
        to = to,
    )
}