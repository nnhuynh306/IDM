package com.example.idm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.download_mananger.network.DownloadRequest
import com.example.download_mananger.network.createExecutor
import com.example.idm.ui.theme.IDMTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IDMTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                val url = "https://fastly.picsum.photos/id/873/200/300.jpg?hmac=CQHrOY67pytIwHLic3cAxphNbh2NwdxnFQtwaX5MLkM"
//                                val url = "https://redirector.gvt1.com/edgedl/android/studio/install/2025.1.1.13/android-studio-2025.1.1.13-windows.exe"
                                createExecutor(
                                    DownloadRequest(url, "./test.something")
                                ).execute()
                            }
                        ) {
                            Text("DOWNLOAD")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    IDMTheme {
        Greeting("Android")
    }
}