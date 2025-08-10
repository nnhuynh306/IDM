package com.example.idm.features.download

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.URLUtil
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.downloadexecutor.PartialProgress
import com.example.idm.core.data.model.FileInfo
import com.example.idm.core.data.model.PartProgress
import com.example.idm.core.data.model.Status
import com.example.idm.core.design.theme.IDMTheme
import kotlinx.coroutines.flow.update
import androidx.core.net.toUri

@Composable
fun DownloadScreen(
    viewModel: DownloadScreenViewModel = hiltViewModel()
) {
    val localContext = LocalContext.current
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.showDialog()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "add"
                )
            }
        }
    ) { innerPadding ->

        val uiState by viewModel.state.collectAsStateWithLifecycle()

        DownloadScreenContent(
            modifier = Modifier.padding(innerPadding),
            uiState = uiState,
            onFileItemButtonClick = {
                viewModel.onFileItemButtonClick(it)
            },
            onDismiss = {
                viewModel.dismissDialog()
            },
            onConfirm = {
                viewModel.addDownloadRequest(
                    localContext.getExternalFilesDir(null)!!.absolutePath
                )
            },
            onTextChanged = {
                viewModel.updateUrlText(it)
            }
        )
    }
}

@Composable
fun DownloadScreenContent(
    modifier: Modifier = Modifier,
    uiState: DownloadScreenState,
    onFileItemButtonClick: (FileInfo) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onTextChanged: (String) -> Unit
) {
    val context = LocalContext.current
    when(uiState) {
        is DownloadScreenState.DownloadScreenError -> TODO()
        is DownloadScreenState.DownloadScreenLoaded -> {
            if (uiState.isShowDialog) {
                AddDownloadFileDialog(
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    url = uiState.urlText,
                    onTextChanged = onTextChanged,
                    errorText = uiState.error,
                    isProcessing = uiState.isProcessing
                )
            }

            LazyColumn(
                modifier = modifier
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(
                    count = uiState.requests.size,
                    key = {
                        uiState.requests[it].requestUrl
                    },
                ) {
                    val fileInfo = uiState.requests[it]

                    FileItem(
                        fileInfo = fileInfo,
                        onButtonClick = {
                            onFileItemButtonClick(fileInfo)
                        },
                        onItemClick = {
                            context.startActivity(Intent("android.intent.action.VIEW_DOWNLOADS"))
                        }
                    )
                }
            }
        }
        is DownloadScreenState.DownloadScreenLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize()
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun FileItem(
    fileInfo: FileInfo,
    onButtonClick: () -> Unit,
    onItemClick: () -> Unit,
) {
    val status by fileInfo.status.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .clickable(
                enabled = status == Status.Finished,
                onClick = onItemClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    Text(fileInfo.requestUrl, maxLines = 1)
                    Spacer(Modifier.height(10.dp))
                    Text(fileInfo.name)
                }

                IconButton(
                    onClick = onButtonClick
                ) {
                    when(status) {
                        is Status.NotStarted, is Status.Paused, is Status.Error -> {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircleOutline,
                                contentDescription = "",
                                tint = Color.Green
                            )
                        }
                        is Status.Downloading, is Status.Finalizing -> {
                            Icon(
                                imageVector = Icons.Rounded.PauseCircleOutline,
                                contentDescription = "",
                                tint = Color.Gray
                            )
                        }
                        is Status.Finished -> {
                            Icon(
                                imageVector = Icons.Outlined.RemoveCircleOutline,
                                contentDescription = "",
                                tint = Color.Red
                            )
                        }
                    }
                }
            }
            StatusView(status, totalFileSize = fileInfo.totalSize)
        }
    }
}

@Composable
fun StatusView(status: Status, totalFileSize: Long) {
    if (status == Status.NotStarted) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Spacer(Modifier.height(10.dp))

        Text(
            text = when(status) {
                is Status.Downloading -> "Downloading - ${"%.2f".format(status.speed/1024/1024)} MB/s"
                Status.Finished -> "Finished"
                Status.Finalizing -> "Finalizing"
                Status.NotStarted -> ""
                is Status.Paused -> "Paused"
                is Status.Error -> {
                    "Error: ${status.error.message}"
                }
            },
            color = if (status is Status.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.typography.bodyMedium.color
            },
            maxLines = 3
        )

        if (status is Status.ProgressStatus) {
            Spacer(Modifier.height(10.dp))
            ProgressView(
                color = if (status is Status.Downloading) {
                    Color.Green
                } else {
                    Color.Gray
                },
                progress = status.progress,
                total = totalFileSize
            )
        }

    }
}

@Composable
fun ProgressView(color: Color, progress: List<PartProgress>, total: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .border(width = 1.dp, color = Color.DarkGray)
    ) {
        for ((index, partialProgress) in progress.withIndex()) {
            val end = if (index >= progress.size - 1) {
                total
            } else {
                progress[index + 1].from
            }

            val totalOfPart = end - partialProgress.from
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight((totalOfPart).toFloat() / total),
                color = color,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                progress = {
                    (partialProgress.to.coerceAtMost(end) - partialProgress.from).toFloat() / totalOfPart
                },
                drawStopIndicator = {

                }
            )
        }
    }
}

@Preview
@Composable
fun StatusPreview() {
    StatusView(
        status = Status.Paused(progress = listOf(
            PartProgress(0, 65000),
            PartProgress(62000, 92000)
        )),
        totalFileSize = 100000
    )
}

@Composable
fun AddDownloadFileDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    url: String,
    onTextChanged: (String) -> Unit,
    errorText: String,
    isProcessing: Boolean
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(size = 20.dp))
                .border(width = 1.dp, color = Color.Transparent, shape = RoundedCornerShape(size = 5.dp))
                .padding(10.dp),
        ) {
            Column {
                Text(
                    text = "Add download request",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(5.dp))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth(),
                    value = url,
                    shape = RoundedCornerShape(2.dp),
                    onValueChange = onTextChanged,
                    label = { Text("URL") },
                    isError = errorText.isNotEmpty(),
                    enabled = !isProcessing
                )
                if (errorText.isNotEmpty()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onConfirm,
                        enabled = !isProcessing
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                            Text("Confirm")
                        }
                    }
                }
            }
        }
    }
}