package com.example.idm.core.data.repository

import com.example.downloadexecutor.DownloadManager
import com.example.downloadexecutor.DownloadProgress
import com.example.downloadexecutor.DownloadRequest
import com.example.downloadexecutor.HeaderRequestInfo
import com.example.idm.core.data.database.dao.DownloadRequestDao
import com.example.idm.core.data.database.entities.DownloadRequestEntity
import com.example.idm.core.data.model.FileInfo
import com.example.idm.core.data.model.PartProgress
import com.example.idm.core.data.model.Status
import com.example.idm.core.data.model.mapToModel
import com.example.idm.core.di.IODispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.delayFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

interface FileRepository {
    fun getAllDownloadRequest(): Flow<List<FileInfo>>
    suspend fun getCurrentStatusOf(url: String, destination: String): MutableStateFlow<Status>
    suspend fun startOrResumeRequest(url: String, destination: String)
    fun pauseRequest(url: String, destination: String)
    suspend fun saveRequest(url: String, destination: String)
    suspend fun remove(fileInfo: FileInfo)
}

class FileRepositoryImpl @Inject constructor(
    @IODispatcher val dispatcher: CoroutineDispatcher,
    val downloadManager: DownloadManager,
    val downloadRequestDao: DownloadRequestDao,
): FileRepository {

    private val statusMap = ConcurrentHashMap<String, MutableStateFlow<Status>>()

    override fun getAllDownloadRequest(): Flow<List<FileInfo>> {
        return downloadRequestDao.getAll().map { requests ->
            requests.map { request ->
                FileInfo(
                    requestUrl = request.url,
                    destination = request.destination,
                    name = request.destination.substring(request.destination.lastIndexOf("/") + 1),
                    status = getCurrentStatusOf(url = request.url, destination = request.destination),
                    totalSize = request.totalSize
                )
            }
        }
    }



    override suspend fun getCurrentStatusOf(url: String, destination: String): MutableStateFlow<Status> {
        return statusMap.getOrPut(destination) {
            MutableStateFlow(
                downloadManager.getSavedProgressOf(DownloadRequest(
                    url = url,
                    saveFileName = destination
                ))?.let {
                    Status.Paused(it.byteSaved.map { it.mapToModel() })
                } ?: run {
                    val destinationFile = File(destination)
                    if (destinationFile.exists()) {
                        Status.Finished
                    } else {
                        Status.NotStarted
                    }
                }
            )
        }
    }

    override suspend fun startOrResumeRequest(url: String, destination: String) {
        val statusState = getCurrentStatusOf(url = url, destination = destination)
        downloadManager.download(DownloadRequest(
            url = url,
            saveFileName = destination
        ))
            .transformWhile {
                emit(it)
                it.isSuccess
            }
            .collect {
                if (it.isSuccess) {
                    val progressResult = it.getOrThrow()
                    statusState.update { currentState ->
                        when(progressResult.status) {
                            DownloadProgress.Status.DOWNLOADING -> {
                                Status.Downloading(progress = progressResult.byteDownloaded.map { progress ->
                                    PartProgress(
                                        from = progress.from,
                                        to = progress.from + progress.progress.toLong()
                                    )
                                }.toList(), speed = progressResult.speed)
                            }
                            DownloadProgress.Status.PAUSED -> {
                                Status.Paused(progress = progressResult.byteDownloaded.map { progress ->
                                    PartProgress(
                                        from = progress.from,
                                        to = progress.from + progress.progress.toLong()
                                    )
                                }.toList())
                            }
                            DownloadProgress.Status.FINALIZING -> {
                                Status.Finalizing
                            }
                            DownloadProgress.Status.FINISHED -> {
                                Status.Finished
                            }
                        }
                    }
                } else {
                    Status.Error(it.exceptionOrNull()!!)
                }
            }
    }

    override fun pauseRequest(url: String, destination: String) {
        downloadManager.stop(DownloadRequest(
            url = url,
            saveFileName = destination
        ))
    }

    override suspend fun saveRequest(url: String, destination: String) {
        val headerInfo = withContext(dispatcher) {
            downloadManager.getHeaderInfo(url);
        }

        downloadRequestDao.insert(DownloadRequestEntity(
            url = url,
            destination = destination,
            totalSize = headerInfo.contentLength
        ))
    }

    override suspend fun remove(fileInfo: FileInfo) {
        downloadRequestDao.delete(destination = fileInfo.destination)
        withContext(dispatcher) {
            File(fileInfo.destination).delete()
        }
    }
}