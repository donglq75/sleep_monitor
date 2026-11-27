package com.donglq.sleepmonitor

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, SOUNDS, MONITOR, REPORT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepMonitorApp() }
    }
}

@Composable
private fun SleepMonitorApp() {
    val appContext = LocalContext.current
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
                    onReport = {
                        val prefs = appContext.getSharedPreferences(SleepMonitorService.PREFS, Context.MODE_PRIVATE)
                        if (prefs.getBoolean("auto_stop_on_report", true)) {
                            appContext.stopService(Intent(appContext, SleepMonitorService::class.java))
                        }
                        screen = Screen.REPORT
                    }
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Sleep Monitor", style = MaterialTheme.typography.headlineLarge)
        Text("Ngủ ngon hơn · Hiểu giấc ngủ của bạn", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
        HomeCard("1. Âm thanh dễ ngủ", "Chọn nhạc, đặt thời lượng và tự dừng.", "Mở", onSounds)
        HomeCard("2. Giám sát ban đêm", "Ghi các đoạn âm thanh nghi ngáy, không lưu cả đêm.", "Giám sát", onMonitor)
        HomeCard("3. Báo cáo giấc ngủ", "Xem mốc sự kiện và nghe lại từng đoạn.", "Báo cáo", onReport)
        Spacer(Modifier.weight(1f))
        Text("Bản thử nghiệm 1.1.0 · Dữ liệu lưu trên thiết bị", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HomeCard(title: String, description: String, action: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(onClick = onClick) { Text(action, maxLines = 1) }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onBack) { Text("Quay lại", maxLines = 1) }
    }
}

@Composable
private fun SoundsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("sleep_monitor_preferences", Context.MODE_PRIVATE)
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
            try { player.release() } catch (_: Exception) { }
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
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
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

    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeader("Âm thanh dễ ngủ", onBack)
        Text("Tệp âm thanh")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selectedName, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Chọn tệp từ điện thoại")
                }
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
                                if (preferences.getBoolean("auto_start_after_music_5m", false) &&
                                    !preferences.getBoolean("monitor_running", false)
                                ) {
                                    val delayedIntent = Intent(context, SleepMonitorService::class.java)
                                        .putExtra(SleepMonitorService.EXTRA_DELAY_MS, 5 * 60 * 1000L)
                                    try {
                                        ContextCompat.startForegroundService(context, delayedIntent)
                                        message = "Đang phát · Giám sát sẽ tự bật sau 5 phút."
                                    } catch (error: Exception) {
                                        message = "Không thể đặt lịch giám sát: ${error.localizedMessage ?: "lỗi quyền"}"
                                    }
                                }
                                timerState.value = object : CountDownTimer(minutes.toLong() * 60_000L, 1_000L) {
                                    override fun onTick(millisUntilFinished: Long) {
                                        message = "Đang phát · Còn ${millisUntilFinished / 60_000L} phút."
                                    }
                                    override fun onFinish() {
                                        stopPlayback()
                                        message = "Đã hết thời gian. Âm thanh đã tự dừng."
                                    }
                                }.start()
                            }
                            player.setOnErrorListener { _, _, _ ->
                                stopPlayback()
                                message = "Không phát được tệp này. Hãy thử MP3, M4A hoặc WAV khác."
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
            "Tệp và thời lượng được ghi nhớ trên máy. Muốn theo dõi ngay từ lúc nghe nhạc, hãy bật giám sát ở mục 2 trước, sau đó quay lại đây phát nhạc; giám sát vẫn chạy khi bạn đổi trang.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SleepMonitorService.PREFS, Context.MODE_PRIVATE) }
    var isRunning by remember { mutableStateOf(prefs.getBoolean("monitor_running", false)) }
    var status by remember { mutableStateOf(if (isRunning) "Đang giám sát" else "Chưa bắt đầu") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SleepMonitorService::class.java))
                isRunning = true
                status = "Đang giám sát · Màn hình có thể tắt"
            } catch (error: Exception) {
                status = "Không thể bắt đầu: ${error.localizedMessage ?: "hãy kiểm tra quyền microphone"}"
            }
        } else {
            status = "Bạn chưa cấp quyền microphone."
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeader("Giám sát ban đêm", onBack)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Trạng thái", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.headlineSmall)
                if (isRunning) {
                    OutlinedButton(
                        onClick = {
                            context.stopService(Intent(context, SleepMonitorService::class.java))
                            isRunning = false
                            status = "Đã dừng giám sát"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Dừng giám sát") }
                } else {
                    Button(
                        onClick = {
                            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Bắt đầu giám sát") }
                }
            }
        }
        var autoStartAfterMusic by remember { mutableStateOf(prefs.getBoolean("auto_start_after_music_5m", false)) }
        var autoStopOnReport by remember { mutableStateOf(prefs.getBoolean("auto_stop_on_report", true)) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tự động hóa", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoStartAfterMusic, onCheckedChange = {
                        autoStartAfterMusic = it
                        prefs.edit().putBoolean("auto_start_after_music_5m", it).apply()
                    })
                    Column(Modifier.weight(1f)) {
                        Text("Tự bật giám sát sau 5 phút phát nhạc")
                        Text("Không tạo lịch mới nếu giám sát đã chạy.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoStopOnReport, onCheckedChange = {
                        autoStopOnReport = it
                        prefs.edit().putBoolean("auto_stop_on_report", it).apply()
                    })
                    Column(Modifier.weight(1f)) {
                        Text("Tự dừng giám sát khi mở Báo cáo")
                        Text("Mặc định bật.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Cách hoạt động", style = MaterialTheme.typography.titleMedium)
        Text("Bạn có thể bật thủ công tại đây, hoặc chọn tự bật sau 5 phút phát nhạc. Dịch vụ vẫn chạy khi đổi trang hoặc tắt màn hình. Ứng dụng phân tích microphone ở 16 kHz; khi âm thanh lớn liên tiếp vượt ngưỡng, ứng dụng lưu clip gồm khoảng 10 giây trước và 12 giây sau sự kiện.")
        Text("Khử vọng âm (AEC) và giảm nhiễu (nếu máy hỗ trợ) được bật để hạn chế âm thanh từ nhạc đang phát lọt vào nhận diện.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Lưu ý về độ chính xác", style = MaterialTheme.typography.titleMedium)
                Text("Đây là bộ phát hiện âm thanh nghi ngờ dựa trên ngưỡng âm lượng, chưa phải mô hình AI phân biệt chắc chắn tiếng ngáy với tiếng nói, tiếng động hoặc nhạc. Vì vậy báo cáo có thể có sự kiện nhầm hoặc bỏ sót; cần kiểm tra các clip thực tế.")
            }
        }
        Text("Chỉ lưu các clip sự kiện trên điện thoại, không lưu liên tục toàn bộ âm thanh ban đêm và không tự tải dữ liệu lên đám mây.")
    }
}

@Composable
private fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember {
        File(context.filesDir, "events").listFiles()
            ?.filter { it.isFile && it.extension.equals("wav", true) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingName by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try { activePlayer?.release() } catch (_: Exception) { }
            activePlayer = null
        }
    }
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy · HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeader("Báo cáo giấc ngủ", onBack)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tổng hợp sự kiện đã lưu", style = MaterialTheme.typography.titleMedium)
                Text("${files.size} clip âm thanh")
                Text("Các mốc dưới đây là thời điểm ghi nhận sự kiện, không phải số lần ngáy đã được xác nhận.")
            }
        }
        if (files.isEmpty()) {
            Text("Chưa có dữ liệu. Hãy bật giám sát ban đêm để ghi nhận các sự kiện đầu tiên.")
        } else {
            files.forEachIndexed { index, file ->
                val timestamp = file.nameWithoutExtension.substringAfter("event_", "").toLongOrNull()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sự kiện ${files.size - index}", style = MaterialTheme.typography.titleMedium)
                        Text(timestamp?.let { formatter.format(Date(it)) } ?: file.name)
                        Text("Âm thanh nghi ngáy / âm thanh lớn · cần nghe kiểm tra", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                try {
                                    activePlayer?.stop()
                                    activePlayer?.release()
                                } catch (_: Exception) { }
                                activePlayer = MediaPlayer.create(context, Uri.fromFile(file))
                                if (activePlayer != null) {
                                    playingName = file.name
                                    activePlayer?.setOnCompletionListener {
                                        try { it.release() } catch (_: Exception) { }
                                        activePlayer = null
                                        playingName = null
                                    }
                                    activePlayer?.start()
                                }
                            }) { Text(if (playingName == file.name) "Phát lại" else "Nghe clip") }
                            if (playingName == file.name) {
                                OutlinedButton(onClick = {
                                    try { activePlayer?.stop(); activePlayer?.release() } catch (_: Exception) { }
                                    activePlayer = null
                                    playingName = null
                                }) { Text("Dừng") }
                            }
                        }
                    }
                }
            }
        }
        Text("Các clip được lưu nội bộ trong ứng dụng. Xóa dữ liệu ứng dụng sẽ xóa cả lịch sử này.")
    }
}
