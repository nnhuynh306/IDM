package com.example.example

import com.example.download_mananger.network.DownloadRequest
import com.example.download_mananger.network.createExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

fun main() {
    run()
}

fun run() = runBlocking {
    System.setProperty("http.maxConnections", "16");
    System.setProperty("http.keepAlive", "false")
//    val url = "https://images.unsplash.com/photo-1536232038510-337303acd6e0?q=80&w=687&auto=format&fit=crop&ixlib=rb-4.1.0&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D"
//    val url = "https://redirector.gvt1.com/edgedl/android/studio/install/2025.1.1.13/android-studio-2025.1.1.13-windows.exe"
    val url = "https://research.nhm.org/pdfs/10840/10840-002.pdf"
    createExecutor(
        DownloadRequest(url, "temp/image.pdf")
    ).execute().collect {
//        print(it.speed);
        if (it.isFinished) {
            throw CancellationException()
        }
    }
}
