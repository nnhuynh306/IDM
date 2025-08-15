package com.example.downloadexecutor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

private fun String.isPartFile(): Boolean {
    return length > 5 && substring(length - 5, length) == ".part"
}

private fun String.getNameWithoutExtension(): String {
    return substring(0, lastIndexOf("."))
}

data class InternalDirectory(
    val destination: String,
): File(destination.getNameWithoutExtension()) {

}

data class SavedRequestInfo(
    val checkSum: Int = 0,
    val savedTasks: List<SavedTask> = emptyList()
)

data class SavedTask(
    val start: Long,
    val byteSaved: Long
)

interface FileHandler {
    suspend fun initialize()
    suspend fun saveToPartFile(task: DownloadTask, byteArray: ByteArray, byteCount: Int, onFinish: suspend () -> Unit)
    suspend fun getSavedRequestInfo(): SavedRequestInfo?
    suspend fun mergeParts()
    suspend fun clearAll()
}

internal class FileHandlerImpl(val destination: String): FileHandler {
    val partFileContainer: InternalDirectory = InternalDirectory(destination)

    val saverMap = ConcurrentHashMap<String, PartFileSaver>()

    override suspend fun initialize() {
        partFileContainer.mkdirs()
    }

    override suspend fun getSavedRequestInfo(): SavedRequestInfo? {
        if (!partFileContainer.exists()) {
            return null
        }

        return SavedRequestInfo(
            savedTasks = partFileContainer.listFiles()
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
            PartFileSaver(task, partFileContainer.path)
        }

        saver?.appendByte(byteCount, byteArray, onFinish)
    }

    override suspend fun mergeParts() {
        println("merge parts")
        withContext(Dispatchers.IO) {
            for (saver in saverMap.values) {
                saver.close()
            }
            val destinationFile = File(destination)
            val buffer = ByteArray(8192)
            FileOutputStream(destinationFile, false).use { desFos ->
                val partFiles = partFileContainer.listFiles().filter {
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
            partFileContainer.delete()
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            partFileContainer.deleteRecursively()
            File(destination).delete()
        }
    }

}

internal class PartFileSaver(task: DownloadTask, directory: String) {
    private val filePath = "$directory/${task.start}.part"
    private var outputStream: FileOutputStream? = null
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(dispatcher)
    private var byteSaved = 0L

    init {
        try {
            outputStream = FileOutputStream(filePath, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun appendByte(byteCount: Int, byteArray: ByteArray, onFinish: suspend () -> Unit) {
        scope.launch {
            byteSaved += byteCount
            outputStream?.write(byteArray, 0, byteCount)
            onFinish()
        }
    }

    fun close() {
        outputStream?.close()
    }
}
