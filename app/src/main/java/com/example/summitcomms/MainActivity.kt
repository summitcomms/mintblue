package com.example.summitcomms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import net.majorkernelpanic.streaming.rtsp.RtspServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.*

// ONVIF 관련 라이브러리 추가
import org.onvif.ver10.Device
import org.onvif.ver10.Media

class MainActivity : ComponentActivity() {

    private val REQUEST_PERMISSIONS = 1
    private var rtspUrl by mutableStateOf("")
    private var rtspPort: String by mutableStateOf("9554")
    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamingUI(rtspUrl, rtspPort)
        }

        // 필요한 권한 요청
        requestPermissionsIfNeeded()

        // LibVLC 초기화
        libVLC = LibVLC(this)
        mediaPlayer = MediaPlayer(libVLC)
    }

    private fun requestPermissionsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.INTERNET),
                REQUEST_PERMISSIONS
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun StreamingUI(rtspUrl: String, rtspPort: String) {
        var port by remember { mutableStateOf(rtspPort) }
        var statusMessage by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Greeting("Summitcomms")

            // 포트 입력 필드
            TextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("RTSP Port") }
            )

            // Start Streaming Button
            Button(onClick = {
                this@MainActivity.rtspPort = port
                startStreaming { success ->
                    statusMessage = if (success) "Streaming started successfully" else "Failed to start streaming"
                    if (success) {
                        try {
                            // 카메라 앱 실행
                            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            if (cameraIntent.resolveActivity(packageManager) != null) {
                                startActivity(cameraIntent)
                            } else {
                                Toast.makeText(applicationContext, "Camera app not found", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(applicationContext, "Error starting camera", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) {
                Text(text = "Start Streaming")
            }

            // Stop Streaming Button
            Button(onClick = { stopStreaming() }) {
                Text(text = "Stop Streaming")
            }

            // RTSP URL 표시
            if (rtspUrl.isNotEmpty()) {
                Text(text = "RTSP URL: $rtspUrl", fontSize = 18.sp)
            }

            // 스트리밍 상태 메시지 표시
            if (statusMessage.isNotEmpty()) {
                Text(text = statusMessage, fontSize = 18.sp)
            }
        }
    }

    private fun startStreaming(callback: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED) {

            Log.d("Streaming", "Permissions granted. Starting camera and audio...")

            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                // WiFi에 연결되어 있는 경우 IP 가져오기
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
                rtspUrl = "rtsp://$ipAddress:$rtspPort/live"
                startRtspServer()
                callback(true)
            } else {
                // LTE 또는 기타 네트워크의 경우 공인 IP 가져오기
                GlobalScope.launch(Dispatchers.IO) {
                    val ipAddress = getPublicIpAddress()
                    withContext(Dispatchers.Main) {
                        if (ipAddress.isNotEmpty()) {
                            rtspUrl = "rtsp://$ipAddress:$rtspPort/live"
                            startRtspServer()
                            callback(true)
                        } else {
                            Toast.makeText(applicationContext, "Failed to get public IP address", Toast.LENGTH_SHORT).show()
                            callback(false)
                        }
                    }
                }
            }
        } else {
            Toast.makeText(this, "Permissions are required to start streaming.", Toast.LENGTH_SHORT).show()
            callback(false)
        }
    }

    private fun startRtspServer() {
        val intent = Intent(this, RtspServer::class.java)
        intent.putExtra(RtspServer.KEY_PORT, rtspPort.toInt())
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Streaming started at $rtspUrl", Toast.LENGTH_LONG).show()

        // VLC 미디어 재생 시작
        val media = Media(libVLC, rtspUrl)
        mediaPlayer.media = media
        mediaPlayer.play()
    }

    private fun stopStreaming() {
        val intent = Intent(this, RtspServer::class.java)
        stopService(intent)
        rtspUrl = ""
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
        mediaPlayer.stop()
    }

    private fun getPublicIpAddress(): String {
        val apis = listOf("https://api.ipify.org", "https://checkip.amazonaws.com")
        for (api in apis) {
            try {
                val url = URL(api)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        return reader.readLine()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ""
    }

    // ONVIF 장치에서 스트리밍 URL 획득
    private fun getOnvifStreamUrl(ipAddress: String, username: String, password: String): String? {
        return try {
            val device = Device(ipAddress, username, password)
            val media = Media(device)
            val profiles = media.profiles
            if (profiles.isNotEmpty()) {
                profiles[0].streamUri
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Composable
    fun Greeting(name: String) {
        Text(text = name, fontSize = 24.sp)
    }
}

@Preview
@Composable
fun PreviewGreeting() {
    MainActivity().StreamingUI(rtspUrl = "rtsp://example.com:9554/", rtspPort = "9554")
}
