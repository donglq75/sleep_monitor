package com.donglq.sleepmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, PLAYER, REPORT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepMonitorApp() }
    }
}

@Composable
private fun SleepMonitorApp() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (screen) {
                Screen.HOME -> HomeScreen { screen = Screen.PLAYER }
                Screen.PLAYER -> PlayerMonitorScreen(onReport = {
                    val prefs = context.getSharedPreferences(SleepMonitorService.PREFS, Context.MODE_PRIVATE)
                    if (prefs.getBoolean("auto_stop_on_report", true)) {
                        context.stopService(Intent(context, SleepMonitorService::class.java))
                    }
                    screen = Screen.REPORT
                })
                Screen.REPORT -> ReportScreen(onBack = { screen = Screen.PLAYER })
            }
        }
    }
}

@Composable
private fun HomeScreen(onEnter: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Sleep Monitor", style = MaterialTheme.typography.headlineLarge)
        Text("Ngủ ngon hơn · Hiểu giấc ngủ của bạn", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onEnter, modifier = Modifier.fillMaxWidth()) {
            Text("Vào trình phát & giám sát")
        }
        Spacer(Modifier.height(12.dp))
        Text("Phát nhạc thư giãn và ghi nhận những âm thanh đáng chú ý trong đêm.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlayerMonitorScreen(onReport: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SleepMonitorService.PREFS, Context.MODE_PRIVATE) }
    var selectedUri by remember { mutableStateOf(prefs.getString("last_audio_uri", null)?.let(Uri::parse)) }
    var selectedName by remember { mutableStateOf(prefs.getString("last_audio_name", "Chưa chọn nhạc") ?: "Chưa chọn nhạc") }
    var minutes by remember { mutableFloatStateOf(prefs.getInt("last_duration_minutes", 30).toFloat().coerceIn(5f, 180f)) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var monitorRunning by remember { mutableStateOf(prefs.getBoolean("monitor_running", false)) }
    var message by remember { mutableStateOf("Chọn nhạc rồi nhấn Phát. Bạn cũng có thể giám sát mà không cần phát nhạc.") }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start_after_music_5m", false)) }
    var autoStop by remember { mutableStateOf(prefs.getBoolean("auto_stop_on_report", true)) }
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

    fun startMonitoring() {
        val intent = Intent(context, SleepMonitorService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
            monitorRunning = true
            message = "Đã khởi động giám sát. Dịch vụ tiếp tục chạy khi tắt màn hình."
        } catch (error: Exception) {
            message = "Không thể bật giám sát: ${error.localizedMessage ?: "hãy kiểm tra quyền microphone"}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) startMonitoring() else message = "Bạn chưa cấp quyền microphone."
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            stopPlayback()
            selectedUri = uri
            selectedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "Tệp âm thanh đã chọn"
            prefs.edit().putString("last_audio_uri", uri.toString()).putString("last_audio_name", selectedName).apply()
            message = "Đã chọn nhạc: $selectedName"
        }
    }

    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Sleep Monitor", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(selectedName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val uri = selectedUri
                            if (uri == null) message = "Bạn hãy chọn một tệp nhạc trước."
                            else {
                                stopPlayback()
                                preparing = true
                                message = "Đang chuẩn bị phát nhạc…"
                                try {
                                    val player = MediaPlayer()
                                    playerState.value = player
                                    player.setAudioAttributes(AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                    player.setDataSource(context, uri)
                                    player.isLooping = true
                                    player.setOnPreparedListener {
                                        it.start()
                                        preparing = false
                                        playing = true
                                        message = "Đang phát nhạc · Tự dừng sau ${minutes.toInt()} phút."
                                        if (autoStart && !prefs.getBoolean("monitor_running", false)) {
                                            val delayed = Intent(context, SleepMonitorService::class.java)
                                                .putExtra(SleepMonitorService.EXTRA_DELAY_MS, 5 * 60 * 1000L)
                                            try {
                                                ContextCompat.startForegroundService(context, delayed)
                                                monitorRunning = true
                                                message = "Đang phát · Giám sát sẽ tự bắt đầu sau 5 phút."
                                            } catch (error: Exception) {
                                                message = "Không đặt được lịch giám sát: ${error.localizedMessage ?: "lỗi"}"
                                            }
                                        }
                                        timerState.value = object : CountDownTimer(minutes.toLong() * 60_000L, 1_000L) {
                                            override fun onTick(millisUntilFinished: Long) {
                                                message = "Đang phát · Còn ${millisUntilFinished / 60_000L} phút."
                                            }
                                            override fun onFinish() {
                                                stopPlayback()
                                                message = "Đã hết thời lượng phát nhạc."
                                            }
                                        }.start()
                                    }
                                    player.setOnErrorListener { _, _, _ ->
                                        stopPlayback()
                                        message = "Không phát được tệp này. Hãy thử MP3, M4A hoặc WAV."
                                        true
                                    }
                                    player.prepareAsync()
                                } catch (error: Exception) {
                                    stopPlayback()
                                    message = "Không mở được tệp: ${error.localizedMessage ?: "lỗi không xác định"}"
                                }
                            }
                        },
                        enabled = !playing && !preparing
                    ) { Text(if (preparing) "Đang tải…" else "Phát") }
                    OutlinedButton(onClick = {
                        val player = playerState.value
                        if (player != null && playing) {
                            try { player.pause(); playing = false; message = "Đã tạm dừng nhạc." } catch (_: Exception) { }
                        } else if (player != null && !preparing) {
                            try { player.start(); playing = true; message = "Đang phát nhạc." } catch (_: Exception) { }
                        }
                    }, enabled = playerState.value != null && !preparing) { Text(if (playing) "Tạm dừng" else "Tiếp tục") }
                }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Chọn nhạc từ điện thoại")
                }
                Text("Thời lượng phát: ${minutes.toInt()} phút", style = MaterialTheme.typography.bodySmall)
                Slider(value = minutes, onValueChange = {
                    minutes = it
                    prefs.edit().putInt("last_duration_minutes", it.toInt()).apply()
                }, valueRange = 5f..180f)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tự động hóa", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoStart, onCheckedChange = {
                        autoStart = it
                        prefs.edit().putBoolean("auto_start_after_music_5m", it).apply()
                    })
                    Text("Tự bật giám sát sau 5 phút phát nhạc")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoStop, onCheckedChange = {
                        autoStop = it
                        prefs.edit().putBoolean("auto_stop_on_report", it).apply()
                    })
                    Text("Tự dừng giám sát khi mở Báo cáo")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (monitorRunning) "Giám sát đang bật" else "Giám sát đang tắt",
                    style = MaterialTheme.typography.titleMedium)
                if (monitorRunning) {
                    OutlinedButton(onClick = {
                        context.stopService(Intent(context, SleepMonitorService::class.java))
                        monitorRunning = false
                        message = "Đã dừng giám sát."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Dừng giám sát") }
                } else {
                    Button(onClick = {
                        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startMonitoring()
                        } else permissionLauncher.launch(permissions.toTypedArray())
                    }, modifier = Modifier.fillMaxWidth()) { Text("Khởi động giám sát thủ công") }
                }
            }
        }

        Text(message, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onReport, modifier = Modifier.fillMaxWidth()) { Text("BÁO CÁO GIÁM SÁT") }
        Text("Chỉ lưu các đoạn âm thanh sự kiện trên điện thoại, không ghi liên tục thành tệp cả đêm. Bộ phát hiện hiện dựa trên ngưỡng âm lượng, chưa phải AI phân loại tiếng ngáy.",
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val allFiles = remember {
        File(context.filesDir, "events").walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", true) }
            .toList()
            .sortedByDescending { it.nameWithoutExtension.substringAfter("event_", "").toLongOrNull() ?: 0L }
    }
    val grouped = remember(allFiles) {
        allFiles.groupBy { file ->
            val timestamp = file.nameWithoutExtension.substringAfter("event_", "").toLongOrNull() ?: file.lastModified()
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        }.toSortedMap(compareByDescending { it })
    }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingName by remember { mutableStateOf<String?>(null) }
    val dateFormatter = remember { SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dayKeyFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    DisposableEffect(Unit) {
        onDispose { try { activePlayer?.release() } catch (_: Exception) { }; activePlayer = null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Báo cáo giám sát", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Quay lại") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Nhật ký theo ngày", style = MaterialTheme.typography.titleMedium)
                Text("Đã ghi nhận ${allFiles.size} đoạn âm thanh trong ${grouped.size} ngày.")
                Text("Clip được lưu theo ngày trên điện thoại; các bản ghi cũ cũng được tự nhận diện.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (allFiles.isEmpty()) {
            Text("Chưa có sự kiện nào được ghi nhận.")
        } else {
            grouped.forEach { (dayKey, files) ->
                val dayDate = try { dayKeyFormatter.parse(dayKey) } catch (_: Exception) { null }
                val times = files.mapNotNull { it.nameWithoutExtension.substringAfter("event_", "").toLongOrNull() }
                val firstTime = times.minOrNull()
                val lastTime = times.maxOrNull()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dayDate?.let { dateFormatter.format(it).replaceFirstChar { c -> c.uppercase() } } ?: dayKey,
                            style = MaterialTheme.typography.titleMedium)
                        Text("${files.size} sự kiện" +
                            (if (firstTime != null && lastTime != null) " · ${timeFormatter.format(Date(firstTime))} – ${timeFormatter.format(Date(lastTime))}" else ""),
                            style = MaterialTheme.typography.bodySmall)
                        files.sortedByDescending { it.name }.forEachIndexed { index, file ->
                            val timestamp = file.nameWithoutExtension.substringAfter("event_", "").toLongOrNull()
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Sự kiện ${files.size - index}", style = MaterialTheme.typography.bodyMedium)
                                    Text(timestamp?.let { timeFormatter.format(Date(it)) } ?: file.name,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = {
                                        try { activePlayer?.stop(); activePlayer?.release() } catch (_: Exception) { }
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
                                    }) { Text(if (playingName == file.name) "Phát lại" else "Nghe") }
                                    if (playingName == file.name) OutlinedButton(onClick = {
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
        }
        Text("Mỗi bản ghi là âm thanh được bộ phát hiện kích hoạt, chưa phải kết luận đã ngáy. Cần nghe/AI phân loại clip trước khi thống kê tiếng ngáy.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "${hours} giờ ${minutes} phút" else "${minutes} phút ${seconds} giây"
}
