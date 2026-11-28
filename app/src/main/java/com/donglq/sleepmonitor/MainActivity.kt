package com.donglq.sleepmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
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
    val prefs = remember { context.getSharedPreferences(SleepMonitorService.PREFS, Context.MODE_PRIVATE) }
    var backgroundUri by remember { mutableStateOf(prefs.getString("app_background_uri", null)?.let(Uri::parse)) }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            backgroundUri = uri
            prefs.edit().putString("app_background_uri", uri.toString()).apply()
        }
    }
    val backgroundBitmap by produceState<Bitmap?>(initialValue = null, key1 = backgroundUri) {
        value = withContext(Dispatchers.IO) {
            try { backgroundUri?.let { context.contentResolver.openInputStream(it)?.use(BitmapFactory::decodeStream) } }
            catch (_: Exception) { null }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(Modifier.fillMaxSize()) {
                if (backgroundBitmap != null) {
                    Image(
                        bitmap = backgroundBitmap!!.asImageBitmap(),
                        contentDescription = "Ảnh nền Sleep Monitor",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)))
                }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        onEnter = { screen = Screen.PLAYER },
                        onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                        onClearBackground = {
                            backgroundUri = null
                            prefs.edit().remove("app_background_uri").apply()
                        },
                        hasBackground = backgroundUri != null
                    )
                    Screen.PLAYER -> PlayerMonitorScreen(onReport = {
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
}

@Composable
private fun HomeScreen(
    onEnter: () -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    hasBackground: Boolean
) {
    val context = LocalContext.current
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPickBackground) { Text(if (hasBackground) "Đổi ảnh nền" else "Chọn ảnh nền") }
            if (hasBackground) TextButton(onClick = onClearBackground) { Text("Bỏ ảnh nền") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Phát nhạc thư giãn và ghi nhận những âm thanh đáng chú ý trong đêm.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))
        val versionName = remember {
            try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
            catch (_: Exception) { "?" }
        }
        Text("Sleep Monitor · v$versionName", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
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
    val prefs = remember { context.getSharedPreferences(SleepMonitorService.PREFS, Context.MODE_PRIVATE) }
    var refresh by remember { mutableStateOf(0) }
    val allFiles = remember(refresh) {
        File(context.filesDir, "events").walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", true) }
            .toList()
    }
    val fileByTimestamp = remember(allFiles) {
        allFiles.mapNotNull { file ->
            file.nameWithoutExtension.substringAfter("event_", "").toLongOrNull()?.let { it to file }
        }.toMap()
    }
    val aiSuggestions = remember {
        mutableStateMapOf<Long, String>().apply {
            prefs.getStringSet("event_timestamps", emptySet())?.forEach { value ->
                value.toLongOrNull()?.let { timestamp ->
                    prefs.getString("event_ai_$timestamp", null)?.let { put(timestamp, it) }
                }
            }
        }
    }
    var aiRunning by remember { mutableStateOf<Long?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val statuses = remember {
        mutableStateMapOf<Long, String>().apply {
            prefs.getStringSet("event_timestamps", emptySet())?.forEach { value ->
                value.toLongOrNull()?.let { timestamp ->
                    put(timestamp, prefs.getString("event_status_$timestamp", "pending") ?: "pending")
                }
            }
        }
    }
    val currentFileTimestamps = fileByTimestamp.keys
    LaunchedEffect(currentFileTimestamps) {
        val known = prefs.getStringSet("event_timestamps", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentFileTimestamps.forEach { timestamp ->
            known.add(timestamp.toString())
            if (!statuses.containsKey(timestamp)) statuses[timestamp] = "pending"
        }
        prefs.edit().putStringSet("event_timestamps", known).apply()
    }
    val timestamps = remember(refresh, allFiles, statuses.size) {
        val saved = prefs.getStringSet("event_timestamps", emptySet())?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        (saved + currentFileTimestamps).distinct().sortedDescending()
    }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingName by remember { mutableStateOf<Long?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var csvToExport by remember { mutableStateOf("") }
    val dateFormatter = remember { SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dayKeyFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val grouped = remember(timestamps) {
        timestamps.mapNotNull { timestamp ->
            val anchor = sleepWindowAnchor(timestamp) ?: return@mapNotNull null
            dayKeyFormatter.format(Date(anchor)) to timestamp
        }.groupBy({ it.first }, { it.second })
            .toSortedMap(compareByDescending { it })
    }
    val nightTimestamps = remember(grouped) { grouped.values.flatten() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(csvToExport.toByteArray(Charsets.UTF_8))
                }
            } catch (error: Exception) {
                android.widget.Toast.makeText(context, "Không xuất được tệp: ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { activePlayer?.release() } catch (_: Exception) { }
            activePlayer = null
        }
    }

    fun setStatus(timestamp: Long, status: String) {
        statuses[timestamp] = status
        prefs.edit()
            .putString("event_status_$timestamp", status)
            .putStringSet("event_timestamps", (prefs.getStringSet("event_timestamps", emptySet())?.toMutableSet() ?: mutableSetOf()).apply { add(timestamp.toString()) })
            .apply()
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
                Text("Tổng quan sự kiện", style = MaterialTheme.typography.titleMedium)
                Text("Tổng cộng ${nightTimestamps.size} sự kiện trong khung 21:00–09:00 · Còn ${nightTimestamps.count { fileByTimestamp.containsKey(it) }} clip.")
                Text("Ngáy: ${nightTimestamps.count { statuses[it] == "snore" }} · Nói mơ: ${nightTimestamps.count { statuses[it] == "talking" }} · Ho: ${nightTimestamps.count { statuses[it] == "cough" }}")
                Text("Không ngáy: ${nightTimestamps.count { statuses[it] == "not_snore" }} · Chưa xác định: ${nightTimestamps.count { statuses[it] == null || statuses[it] == "pending" }}")
                Text("Các kết luận được lưu riêng, không mất khi xóa clip.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sơ đồ thời gian sự kiện", style = MaterialTheme.typography.titleMedium)
                Text("Khung giấc ngủ cố định: 21:00 hôm trước đến 09:00 hôm sau.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🟠 Chưa xác định", style = MaterialTheme.typography.labelSmall)
                    Text("🟢 Ngáy", style = MaterialTheme.typography.labelSmall)
                    Text("🔵 Nói mơ", style = MaterialTheme.typography.labelSmall)
                    Text("🟣 Ho", style = MaterialTheme.typography.labelSmall)
                }
                if (grouped.isEmpty()) Text("Chưa có dữ liệu sự kiện.")
                grouped.forEach { (dayKey, dayTimestamps) ->
                    val nightDate = try { dayKeyFormatter.parse(dayKey) } catch (_: Exception) { null }
                    Text(nightDate?.let { "Đêm " + dateFormatter.format(it) } ?: "Đêm $dayKey",
                        style = MaterialTheme.typography.labelMedium)
                    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                        val y = size.height / 2f
                        drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                        dayTimestamps.forEach { timestamp ->
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
                            val minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                            val minutesFromStart = if (minuteOfDay >= 21 * 60) minuteOfDay - 21 * 60 else minuteOfDay + 3 * 60
                            val x = size.width * (minutesFromStart / 720f)
                            val color = when (statuses[timestamp]) {
                                "snore" -> Color(0xFF35C979)
                                "not_snore" -> Color(0xFFB0B0B0)
                                "talking" -> Color(0xFF42A5F5)
                                "cough" -> Color(0xFFAB47BC)
                                else -> Color(0xFFFFA726)
                            }
                            drawCircle(color, radius = 5.dp.toPx(), center = Offset(x.coerceIn(5.dp.toPx(), size.width - 5.dp.toPx()), y))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("21:00", "00:00", "03:00", "06:00", "09:00").forEach {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val header = "Thoi_gian,Ngay,Gio,Trang_thai,Ten_trang_thai,Con_clip\n"
                    val rows = nightTimestamps.sorted().joinToString("") { timestamp ->
                        val status = statuses[timestamp] ?: "pending"
                        val label = when (status) {
                            "snore" -> "Ngáy"
                            "not_snore" -> "Không ngáy"
                            "talking" -> "Nói mơ"
                            "cough" -> "Ho"
                            else -> "Chưa xác định"
                        }
                        "${timestamp},${dayKeyFormatter.format(Date(timestamp))},${timeFormatter.format(Date(timestamp))},${status},${label},${if (fileByTimestamp.containsKey(timestamp)) "Có" else "Không"}\n"
                    }
                    csvToExport = "\uFEFF" + header + rows
                    exportLauncher.launch("sleep_monitor_statistics.csv")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Xuất Excel/CSV") }
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = allFiles.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Xóa hết clip") }
        }

        if (grouped.isEmpty()) {
            Text("Chưa có sự kiện nào được ghi nhận.")
        } else {
            grouped.forEach { (dayKey, dayTimestamps) ->
                val dayDate = try { dayKeyFormatter.parse(dayKey) } catch (_: Exception) { null }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dayDate?.let { dateFormatter.format(it).replaceFirstChar { c -> c.uppercase() } } ?: dayKey,
                            style = MaterialTheme.typography.titleMedium)
                        Text("${dayTimestamps.size} sự kiện", style = MaterialTheme.typography.bodySmall)
                        dayTimestamps.forEachIndexed { index, timestamp ->
                            val file = fileByTimestamp[timestamp]
                            val status = statuses[timestamp] ?: "pending"
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Sự kiện ${dayTimestamps.size - index} · ${timeFormatter.format(Date(timestamp))}",
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(when (status) {
                                            "snore" -> "Đã xác nhận: Ngáy"
                                            "not_snore" -> "Đã xác nhận: Không ngáy"
                                            "talking" -> "Đã xác nhận: Nói mơ"
                                            "cough" -> "Đã xác nhận: Ho"
                                            else -> "Đang nghi ngờ · Chưa xác định"
                                        }, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (file != null) {
                                        Button(onClick = {
                                            try { activePlayer?.stop(); activePlayer?.release() } catch (_: Exception) { }
                                            activePlayer = MediaPlayer.create(context, Uri.fromFile(file))
                                            if (activePlayer != null) {
                                                playingName = timestamp
                                                activePlayer?.setOnCompletionListener {
                                                    try { it.release() } catch (_: Exception) { }
                                                    activePlayer = null
                                                    playingName = null
                                                }
                                                activePlayer?.start()
                                            }
                                        }) { Text(if (playingName == timestamp) "Đang phát" else "Nghe lại") }
                                    } else {
                                        Text("Đã xóa clip", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = status == "snore", onCheckedChange = { if (it) setStatus(timestamp, "snore") else if (status == "snore") setStatus(timestamp, "pending") })
                                            Text("Ngáy", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = status == "not_snore", onCheckedChange = { if (it) setStatus(timestamp, "not_snore") else if (status == "not_snore") setStatus(timestamp, "pending") })
                                            Text("Không ngáy", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = status == "talking", onCheckedChange = { if (it) setStatus(timestamp, "talking") else if (status == "talking") setStatus(timestamp, "pending") })
                                            Text("Nói mơ", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = status == "cough", onCheckedChange = { if (it) setStatus(timestamp, "cough") else if (status == "cough") setStatus(timestamp, "pending") })
                                            Text("Ho", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = status == "pending", onCheckedChange = { if (it) setStatus(timestamp, "pending") })
                                        Text("Chưa xác định", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            if (index < dayTimestamps.lastIndex) androidx.compose.material3.HorizontalDivider()
                        }
                    }
                }
            }
        }
        Text("Bộ phát hiện hiện chỉ ghi nhận âm thanh vượt ngưỡng, chưa tự phân biệt ngáy, nói mơ hay ho. Bạn nghe lại và chọn một loại; các lựa chọn được lưu riêng cho từng sự kiện.",
            style = MaterialTheme.typography.bodySmall)
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Xóa toàn bộ clip âm thanh?") },
            text = { Text("Sẽ xóa ${allFiles.size} tệp WAV để giải phóng dung lượng. Mốc thời gian và kết quả Ngáy/Không ngáy vẫn được giữ trong báo cáo. Nên xuất CSV trước khi xóa nếu bạn muốn lưu bản thống kê bên ngoài.") },
            confirmButton = {
                TextButton(onClick = {
                    try { activePlayer?.stop(); activePlayer?.release() } catch (_: Exception) { }
                    activePlayer = null
                    playingName = null
                    allFiles.forEach { it.delete() }
                    showDeleteConfirm = false
                    refresh++
                }) { Text("Xóa clip") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Hủy") }
            }
        )
    }
}

private fun sleepWindowAnchor(timestamp: Long): Long? {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    if (hour in 9..20) return null
    if (hour < 9) calendar.add(java.util.Calendar.DAY_OF_MONTH, -1)
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "${hours} giờ ${minutes} phút" else "${minutes} phút ${seconds} giây"
}
