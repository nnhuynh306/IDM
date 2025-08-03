package com.example.idm.features.download

import android.os.Environment
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination
import com.example.idm.core.data.model.FileInfo
import com.example.idm.core.data.model.Status
import com.example.idm.core.data.repository.FileRepository
import com.example.idm.features.download.DownloadScreenState.DownloadScreenLoading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class DownloadScreenViewModel @Inject constructor(
    val fileRepository: FileRepository,
): ViewModel() {

    val errorState: MutableStateFlow<String> = MutableStateFlow("")

    val urlText = MutableStateFlow("")

    val isShowDialog = MutableStateFlow(false)

    val isProcessing = MutableStateFlow(false)

    val state: StateFlow<DownloadScreenState> = combine(
        fileRepository.getAllDownloadRequest(),
        errorState,
        urlText,
        isShowDialog,
        isProcessing,
    ) { requests, error, url, isShowDialog, isProcessing ->
        DownloadScreenState.DownloadScreenLoaded(
            requests = requests,
            error = error,
            urlText = url,
            isShowDialog = isShowDialog,
            isProcessing = isProcessing
        )
    }.stateIn(scope = viewModelScope, started =  SharingStarted.Eagerly, initialValue = DownloadScreenLoading())

    fun addDownloadRequest(saveFolderPath: String) {
        val url = urlText.value
        val destination = saveFolderPath + "/" + URLUtil.guessFileName(url, null, null)
        if (url.isBlank() || destination.isBlank()) {
            errorState.update {
                "URL can't be empty"
            }
            return
        }

        isProcessing.update { true }

        viewModelScope.launch {
            try {
                fileRepository.saveRequest(url = url, destination = destination)
                urlText.update { "" }
                dismissDialog()
            } catch (e: Exception) {
                e.printStackTrace()
                errorState.update {
                    "Can't handle URL"
                }
            } finally {
                isProcessing.update { false }
            }
        }
    }

    fun showDialog() {
        isShowDialog.update { true }
    }

    fun dismissDialog() {
        isShowDialog.update { false }
    }

    fun onFileItemButtonClick(fileInfo: FileInfo) {
        when(fileInfo.status.value) {
            is Status.Downloading, Status.Finalizing -> {
                stop(fileInfo)
            }
            Status.Finished -> {
                viewModelScope.launch {
                    fileRepository.remove(fileInfo)
                }
            }
            Status.NotStarted, is Status.Paused -> {
                startOrResume(fileInfo)
            }
        }
    }

    private fun startOrResume(fileInfo: FileInfo) {
        viewModelScope.launch {
            fileRepository.startOrResumeRequest(url = fileInfo.requestUrl, destination = fileInfo.destination)
        }
    }

    private fun stop(fileInfo: FileInfo) {
        fileRepository.pauseRequest(fileInfo.requestUrl, fileInfo.destination)
    }

    fun updateUrlText(string: String) {
        urlText.update { string }
    }
}

sealed interface DownloadScreenState {
    class DownloadScreenLoading: DownloadScreenState
    class DownloadScreenError(val error: String): DownloadScreenState
    class DownloadScreenLoaded(
        val requests: List<FileInfo>,
        val error: String,
        val urlText: String,
        val isShowDialog: Boolean,
        val isProcessing: Boolean,
    ): DownloadScreenState
}
