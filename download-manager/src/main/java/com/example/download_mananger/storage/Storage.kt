package com.example.download_mananger.storage

import com.example.download_mananger.network.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

fun getStorageFor(path: String): Storage {
    return StorageImpl(path)
}

private fun String.isPartFile(): Boolean {
    return length > 5 && substring(length - 5, length) == ".part"
}

private fun String.getNameWithoutExtension(): String {
    return substring(0, lastIndexOf("."))
}

data class SavedRequestInfo(
    val checkSum: Int = 0,
    val savedTasks: List<SavedTask> = emptyList()
)

data class SavedTask(
    val start: Long,
    val byteSaved: Long
)

interface Storage {
    suspend fun saveToPartFile(task: DownloadTask, byteArray: ByteArray, byteCount: Int, onFinish: suspend () -> Unit)
    suspend fun getSavedRequestInfo(): SavedRequestInfo?
    suspend fun mergeParts()
}

internal class StorageImpl(val destination: String): Storage {
    val directory: File

    val saverMap = ConcurrentHashMap<String, PartFileSaver>()

    init {
        val directoryFolderPath = destination.getNameWithoutExtension()
        directory = File(directoryFolderPath)
        directory.mkdirs()
    }

    override suspend fun getSavedRequestInfo(): SavedRequestInfo? {
        if (!directory.exists()) {
            return null
        }

        return SavedRequestInfo(
            savedTasks = directory.listFiles()
                .filter {
                    it.name.isPartFile()
                }.map {
                    SavedTask(
                        start = it.name.getNameWithoutExtension().toLong(),
                        byteSaved = it.length()
                    )
                }.sortedBy {
                    it.start
                },
            checkSum = 0
        )
    }

    override suspend fun saveToPartFile(
        task: DownloadTask,
        byteArray: ByteArray,
        byteCount: Int,
        onFinish: suspend () -> Unit,
    ) {
        val saver = saverMap.getOrPut(task.id) {
            PartFileSaver(task, directory.path)
        }

        saver?.appendByte(byteCount, byteArray, onFinish)
    }

    override suspend fun mergeParts() {
        withContext(Dispatchers.IO) {
            for (saver in saverMap.values) {
                saver.close()
            }
            val destinationFile = File(destination)
            val buffer = ByteArray(8192)
            FileOutputStream(destinationFile, false).use { desFos ->
                val partFiles = directory.listFiles().filter {
                    it.name.isPartFile()
                }.toList().sortedBy {
                    it.name.getNameWithoutExtension().toInt()
                }
                for ((i, file) in partFiles.withIndex()) {
                    val end = if (i < partFiles.size - 1) {
                        partFiles[i + 1].name.getNameWithoutExtension().toLong()
                    } else {
                        null
                    }
                    val byteShouldReadCount: Long? = end?.let {
                        it - file.name.getNameWithoutExtension().toLong()
                    }

                    val fis = FileInputStream(file)
                    var totalByteRead = 0L
                    var byteRead = 0
                    while (true) {
                        byteRead = fis.read(buffer)
                        if (byteRead == -1) {
                            break
                        }
                        val remain = byteShouldReadCount?.let {
                            it - totalByteRead
                        }

                        if (remain != null && remain <= 0) {
                            break
                        }

                        totalByteRead += byteRead
                        desFos.write(buffer, 0, remain?.let { byteRead.toLong().coerceAtMost(it) }?.toInt() ?: byteRead)
                    }
                    fis.close()
                    file.delete()
                }
            }
            directory.delete()
        }
    }

}

internal class PartFileSaver(task: DownloadTask, directory: String) {
    private val filePath = "$directory/${task.start}.part"
    private val outputStream: FileOutputStream = FileOutputStream(filePath, true)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(dispatcher)
    private var byteSaved = 0L

    fun appendByte(byteCount: Int, byteArray: ByteArray, onFinish: suspend () -> Unit) {
        scope.launch {
            byteSaved += byteCount
            outputStream.write(byteArray, 0, byteCount)
            onFinish()
        }
    }

    fun close() {
        outputStream.close()
    }
}
