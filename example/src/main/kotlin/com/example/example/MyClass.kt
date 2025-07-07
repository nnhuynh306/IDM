package com.example.example

import com.example.download_mananger.network.DownloadRequest
import com.example.download_mananger.network.createExecutor
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    System.setProperty("http.maxConnections", "16");
    System.setProperty("http.keepAlive", "false")
//    val url = "https://fastly.picsum.photos/id/873/200/300.jpg?hmac=CQHrOY67pytIwHLic3cAxphNbh2NwdxnFQtwaX5MLkM"
    val url = "https://redirector.gvt1.com/edgedl/android/studio/install/2025.1.1.13/android-studio-2025.1.1.13-windows.exe"
    createExecutor(
        DownloadRequest(url)
    ).execute().collect {

    }
}