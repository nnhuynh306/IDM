package com.example.downloadexecutor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

fun createManager(): DownloadManager {
    return DownloadManagerImpl()
}

interface DownloadManager {
    suspend fun getSavedProgressOf(request: DownloadRequest): SavedProgress?
    fun download(request: DownloadRequest): Flow<Result<DownloadProgress>>
    fun stop(request: DownloadRequest)
    suspend fun getHeaderInfo(url: String): HeaderRequestInfo
}

internal class DownloadManagerImpl: DownloadManager {

    val handlerMap = ConcurrentHashMap<String, SaveFileHandler>()

    val dispatcher = Dispatchers.IO

    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val executorMap = ConcurrentHashMap<String, NetworkTaskExecutor>()

    fun getExecutor(request: DownloadRequest): NetworkTaskExecutor {
        return executorMap.getOrPut(request.saveFileName) {
            DynamicSegmentNetworkTaskExecutor(request, getSaveFileHandlerFor(request))
        }
    }

    override suspend fun getSavedProgressOf(request: DownloadRequest): SavedProgress? {
        val saveFileHandler = getSaveFileHandlerFor(request)
       return withContext(dispatcher) {
            saveFileHandler.getSavedRequestInfo()
        }?.let {
           if (it.savedTasks.isNotEmpty()) {
               val listPartialProgress = arrayListOf<PartialProgress>()

               for ((i, savedTask) in it.savedTasks.withIndex()) {
                   val end = if (i < it.savedTasks.size - 1) {
                       it.savedTasks[i + 1].start
                   } else {
                       null
                   }

                   val progressFromStart = savedTask.start + savedTask.byteSaved

                   listPartialProgress.add(
                       PartialProgress(
                           from = savedTask.start,
                           to = end?.let { progressFromStart.coerceAtMost(it) } ?: progressFromStart
                       )
                   )
               }

               SavedProgress(
                   byteSaved = listPartialProgress
               )
           } else {
               null
           }
        }
    }

    override fun download(request: DownloadRequest): Flow<Result<DownloadProgress>> {
        return getExecutor(request).start()
    }

    override fun stop(request: DownloadRequest) {
        executorMap.remove(request.saveFileName)?.stop()
    }

    override suspend fun getHeaderInfo(url: String): HeaderRequestInfo {
        val headerRequest = HeaderRequest(url)
        return headerRequest.execute() ?: throw Exception("Null Header request")
    }

    fun getSaveFileHandlerFor(request: DownloadRequest): SaveFileHandler {
        val path = request.saveFileName;
        return handlerMap.getOrPut(path) {
            SaveFileHandlerImpl(path)
        }
    }
}