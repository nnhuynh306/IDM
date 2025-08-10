package com.example.example

import com.example.downloadexecutor.DownloadManager
import com.example.downloadexecutor.DownloadProgress
import com.example.downloadexecutor.DownloadRequest
import com.example.downloadexecutor.createManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

fun main() {
    run()
}

fun run() = runBlocking {
    System.setProperty("http.maxConnections", "16");
    System.setProperty("http.keepAlive", "false")
    val url = "https://download1507.mediafire.com/0dak9bphwrdgO65dYqUT3rvBdGUO3mmtWL9UiINuAAUOyYoBfB2EOgeXayWw-B_YbzNPniJ4s6nHwZviL5iwS01L1EbbJbmyfL69RTvuRQFpZB5NCvRm05mkYI-rgbh6HUr0hqtqV8w6mUX7wjThRYmUm64ag2iuxGcdwM0820Gc/r5wgmylt5qneld9/99mb.pdf"
//    val url = "https://redirector.gvt1.com/edgedl/android/studio/install/2025.1.1.13/android-studio-2025.1.1.13-windows.exe"
//    val url = "https://ash-speed.hetzner.com/100MB.bin"
    createManager().download(
        DownloadRequest(url, "temp/image.pdf", startThreadCount = 4)
    ).collect {
        if (it.isFailure) {
            it.exceptionOrNull()?.printStackTrace()
            this.coroutineContext.job.cancel()
        }
    }
}
