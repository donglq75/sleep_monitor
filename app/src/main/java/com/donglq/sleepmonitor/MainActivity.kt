package com.donglq.sleepmonitor

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private enum class Screen { HOME, SOUNDS, MONITOR, REPORT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepMonitorApp() }
    }
}

@Composable
private fun SleepMonitorApp() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onSounds = { screen = Screen.SOUNDS },
                    onMonitor = { screen = Screen.MONITOR },
                    onReport = { screen = Screen.REPORT }
                )
                Screen.SOUNDS -> SoundsScreen(onBack = { screen = Screen.HOME })
                Screen.MONITOR -> MonitorScreen(onBack = { screen = Screen.HOME })
                Screen.REPORT -> ReportScreen(onBack = { screen = Screen.HOME })
            }
        }
    }
}

@Composable
private fun HomeScreen(onSounds: () -> Unit, onMonitor: () -> Unit, onReport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sleep Monitor", style = MaterialTheme.typography.headlineLarge)
        Text("Ngủ ngon hơn · Hiểu giấc ngủ của bạn", style = MaterialTheme.typography.bodyLarge)
        HomeCard("1. Âm thanh dễ ngủ",
            "Chọn tệp âm thanh trên điện thoại, đặt thời lượng và phát lặp khi ngủ.",
            "Thiết lập âm thanh", onSounds)
        HomeCard("2. Giám sát ban đêm",
            "Phát hiện thử nghiệm tiếng ngáy và tiếng nói. Chỉ lưu đoạn có sự kiện.",
            "Bắt đầu giám sát", onMonitor)
        HomeCard("3. Báo cáo giấc ngủ",
            "Xem dòng thời gian sự kiện và nghe lại các đoạn ghi âm.",
            "Xem báo cáo", onReport)
        Spacer(Modifier.weight(1f))
        Text("Thử nghiệm 1.0.2 · Dữ liệu lưu trên thiết bị", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HomeCard(title: String, description: String, action: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(description)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = onBack) { Text("Quay lại") }
    }
}

@Composable
private fun SoundsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("sleep_monitor_preferences", android.content.Context.MODE_PRIVATE)
    }
    var selectedUri by remember {
        mutableStateOf(preferences.getString("last_audio_uri", null)?.let(Uri::parse))
    }
    var selectedName by remember {
        mutableStateOf(preferences.getString("last_audio_name", "Chưa chọn tệp") ?: "Chưa chọn tệp")
    }
    var minutes by remember {
        mutableFloatStateOf(preferences.getInt("last_duration_minutes", 30).toFloat().coerceIn(5f, 180f))
    }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Chọn một tệp âm thanh có sẵn trên điện thoại để thử phát.") }
    val playerState = remember { mutableStateOf<MediaPlayer?>(null) }
    val timerState = remember { mutableStateOf<CountDownTimer?>(null) }

    fun stopPlayback() {
        timerState.value?.cancel()
        timerState.value = null
        playerState.value?.let { player ->
            try { if (player.isPlaying) player.stop() } catch (_: IllegalStateException) { }
            player.release()
        }
        playerState.value = null
        playing = false
        preparing = false
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            stopPlayback()
            selectedUri = uri
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: "Tệp âm thanh đã chọn"
            selectedName = displayName
            preferences.edit().putString("last_audio_uri", uri.toString())
                .putString("last_audio_name", displayName).apply()
            message = "Đã chọn tệp. Nhấn Phát để bắt đầu."
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader("Âm thanh dễ ngủ", onBack)
        Text("Tệp âm thanh")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selectedName, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("audio/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Chọn tệp từ điện thoại") }
            }
        }
        Text("Thời lượng phát: ${minutes.toInt()} phút")
        Slider(value = minutes, onValueChange = {
            minutes = it
            preferences.edit().putInt("last_duration_minutes", it.toInt()).apply()
        }, valueRange = 5f..180f)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val uri = selectedUri
                    if (uri == null) {
                        message = "Bạn cần chọn tệp âm thanh trước."
                    } else {
                        stopPlayback()
                        preparing = true
                        message = "Đang chuẩn bị âm thanh…"
                        try {
                            val player = MediaPlayer()
                            playerState.value = player
                            player.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                            player.setDataSource(context, uri)
                            player.isLooping = true
                            player.setOnPreparedListener {
                                it.start()
                                preparing = false
                                playing = true
                                message = "Đang phát · Tự dừng sau ${minutes.toInt()} phút."
                                timerState.value = object : CountDownTimer(minutes.toLong() * 60_000L, 1_000L) {
                                    override fun onTick(millisUntilFinished: Long) {
                                        message = "Đang phát · Còn ${(millisUntilFinished / 60_000L)} phút."
                                    }
                                    override fun onFinish() {
                                        stopPlayback()
                                        message = "Đã hết thời gian. Âm thanh đã tự dừng."
                                    }
                                }.start()
                            }
                            player.setOnErrorListener { _, _, _ ->
                                stopPlayback()
                                message = "Không phát được tệp này. Hãy thử một tệp MP3, M4A hoặc WAV khác."
                                true
                            }
                            player.prepareAsync()
                        } catch (error: Exception) {
                            stopPlayback()
                            message = "Không mở được tệp âm thanh: ${error.localizedMessage ?: "lỗi không xác định"}"
                        }
                    }
                },
                enabled = !playing && !preparing
            ) { Text(if (preparing) "Đang tải…" else "Phát") }
            OutlinedButton(onClick = {
                stopPlayback()
                message = "Đã dừng phát."
            }, enabled = playing || preparing) { Text("Dừng") }
        }
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Bản thử nghiệm hiện phát tệp âm thanh cục bộ và tự dừng theo hẹn giờ. Chưa có thư viện âm thanh dựng sẵn; chưa tự chuyển sang giám sát.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MonitorScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Giám sát ban đêm", onBack)
        Text("Trạng thái: Chưa bắt đầu", style = MaterialTheme.typography.titleMedium)
        Text("Phần ghi âm nền và phát hiện sự kiện đang được phát triển; bản này chưa bật microphone.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Nguyên tắc riêng tư", style = MaterialTheme.typography.titleMedium)
                Text("Chỉ lưu các đoạn âm thanh quanh sự kiện được phát hiện. Không ghi và lưu toàn bộ đêm.")
                Text("Âm thanh được xử lý trên điện thoại; không tự động tải lên đám mây.")
            }
        }
    }
}

@Composable
private fun ReportScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Báo cáo giấc ngủ", onBack)
        Text("Chưa có dữ liệu đêm ngủ", style = MaterialTheme.typography.titleMedium)
        Text("Sau khi hoàn tất một phiên giám sát, các sự kiện sẽ xuất hiện tại đây.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Dự kiến hiển thị")
                Text("• Mốc thời gian bắt đầu/kết thúc")
                Text("• Số lần phát hiện tiếng ngáy")
                Text("• Số lần phát hiện tiếng nói")
                Text("• Nghe lại từng đoạn âm thanh sự kiện")
            }
        }
    }
}
