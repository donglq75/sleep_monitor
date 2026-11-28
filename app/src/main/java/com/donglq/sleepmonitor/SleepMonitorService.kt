package com.donglq.sleepmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

class SleepMonitorService : Service() {
    @Volatile private var running = false
    @Volatile private var paused = false
    private var recorder: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseCapture()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (paused) startCapture()
                return START_NOT_STICKY
            }
        }
        if (!running && !paused) startCapture(intent?.getLongExtra(EXTRA_DELAY_MS, 0L) ?: 0L)
        return START_NOT_STICKY
    }

    private fun startCapture(delayMs: Long = 0L) {
        createChannel()
        val notification = buildNotification(false)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("monitor_running", true)
            .putBoolean("monitor_paused", false)
            .putLong("monitor_started_at", System.currentTimeMillis()).apply()
        paused = false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepMonitor::Capture").also {
            it.setReferenceCounted(false)
            it.acquire()
        }

        running = true
        worker = Thread({
            if (delayMs > 0) {
                val deadline = System.currentTimeMillis() + delayMs
                while (running && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(minOf(500L, deadline - System.currentTimeMillis())) }
                    catch (_: InterruptedException) { return@Thread }
                }
            }
            if (running) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                captureLoop()
            }
        }, "SleepMonitor-Audio").also { it.start() }
    }

    private fun pauseCapture() {
        if (paused) return
        running = false
        paused = true
        try { recorder?.stop() } catch (_: Exception) { }
        worker?.interrupt()
        try { worker?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        worker = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("monitor_running", false)
            .putBoolean("monitor_paused", true)
            .apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(true))
    }

    private fun buildNotification(isPaused: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (isPaused) "Sleep Monitor đang tạm dừng" else "Sleep Monitor đang giám sát")
            .setContentText(if (isPaused) "Microphone đã ngừng · Chạm Tiếp tục khi chuẩn bị ngủ" else "Đang phân tích âm thanh · Chỉ lưu đoạn sự kiện")
            .setContentIntent(openApp)
            .setOngoing(true)
        val action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val actionLabel = if (isPaused) "Tiếp tục" else "Tạm dừng"
        val actionIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val actionIntent = PendingIntent.getService(
            this, if (isPaused) 7104 else 7103,
            Intent(this, SleepMonitorService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(Notification.Action.Builder(actionIcon, actionLabel, actionIntent).build())
        val stopIntent = PendingIntent.getService(
            this, 7105,
            Intent(this, SleepMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Kết thúc", stopIntent).build())
        return builder.build()
    }

    private fun captureLoop() {
        // Ban ngày không mở microphone; nếu bật trước 21:00 thì chờ đến khung giám sát.
        while (running && !isInsideSleepWindow(System.currentTimeMillis())) {
            try { Thread.sleep(30_000L) } catch (_: InterruptedException) { return }
        }
        if (!running) return
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            stopCapture()
            return
        }
        val bufferBytes = maxOf(minBuffer * 2, 8192)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        recorder = audio
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            recorder = null
            stopCapture()
            return
        }
        try {
            audio.startRecording()
        } catch (_: Exception) {
            stopCapture()
            return
        }

        val block = ShortArray(2048)
        val preRoll = ArrayDeque<ShortArray>()
        val preRollSamples = sampleRate * 10
        var storedPreSamples = 0
        var loudBlocks = 0
        var eventBuffer: ByteArrayOutputStream? = null
        var postSamplesLeft = 0
        var eventTimestamp = 0L
        var cooldownUntil = 0L

        while (running && !Thread.currentThread().isInterrupted) {
            if (!isInsideSleepWindow(System.currentTimeMillis())) break
            val count = try { audio.read(block, 0, block.size, AudioRecord.READ_BLOCKING) } catch (_: Exception) { -1 }
            if (count <= 0) continue
            val chunk = block.copyOf(count)
            preRoll.addLast(chunk)
            storedPreSamples += count
            while (storedPreSamples > preRollSamples && preRoll.isNotEmpty()) {
                storedPreSamples -= preRoll.removeFirst().size
            }

            var sum = 0.0
            for (i in 0 until count) {
                val value = chunk[i].toDouble()
                sum += value * value
            }
            val rms = sqrt(sum / count)
            val now = System.currentTimeMillis()
            if (rms > 1050.0) loudBlocks++ else loudBlocks = maxOf(0, loudBlocks - 1)

            if (eventBuffer == null && now >= cooldownUntil && loudBlocks >= 3) {
                val output = ByteArrayOutputStream()
                for (oldChunk in preRoll) writePcm(output, oldChunk)
                eventBuffer = output
                eventTimestamp = now
                postSamplesLeft = sampleRate * 12
                loudBlocks = 0
            } else if (eventBuffer != null) {
                writePcm(eventBuffer!!, chunk)
                postSamplesLeft -= count
                if (postSamplesLeft <= 0) {
                    saveWav(eventBuffer!!.toByteArray(), eventTimestamp)
                    eventBuffer = null
                    eventTimestamp = 0L
                    cooldownUntil = now + 20_000L
                }
            }
        }

        try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) { }
        audio.release()
        if (recorder === audio) recorder = null
        eventBuffer?.let { saveWav(it.toByteArray(), eventTimestamp) }
        if (running && !isInsideSleepWindow(System.currentTimeMillis())) {
            stopCapture()
            stopSelf()
        }
    }

    private fun isInsideSleepWindow(timestamp: Long): Boolean {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 21 || hour < 9
    }

    private fun writePcm(out: ByteArrayOutputStream, samples: ShortArray) {
        for (sample in samples) {
            out.write(sample.toInt() and 0xff)
            out.write((sample.toInt() shr 8) and 0xff)
        }
    }

    private fun saveWav(pcm: ByteArray, timestamp: Long) {
        if (pcm.isEmpty()) return
        try {
            val dayFolder = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
            val folder = File(File(filesDir, "events"), dayFolder).apply { mkdirs() }
            val file = File(folder, "event_${timestamp}.wav")
            FileOutputStream(file).use { out ->
                val dataLength = pcm.size
                val header = ByteArray(44)
                fun putAscii(offset: Int, value: String) {
                    value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
                }
                fun putInt(offset: Int, value: Int) {
                    for (i in 0..3) header[offset + i] = (value shr (8 * i)).toByte()
                }
                fun putShort(offset: Int, value: Int) {
                    header[offset] = value.toByte()
                    header[offset + 1] = (value shr 8).toByte()
                }
                putAscii(0, "RIFF"); putInt(4, 36 + dataLength); putAscii(8, "WAVE")
                putAscii(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
                putInt(24, 16_000); putInt(28, 32_000); putShort(32, 2); putShort(34, 16)
                putAscii(36, "data"); putInt(40, dataLength)
                out.write(header); out.write(pcm)
            }
        } catch (_: Exception) { }
    }

    private fun stopCapture() {
        running = false
        paused = false
        try { recorder?.stop() } catch (_: Exception) { }
        worker?.interrupt()
        worker = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("monitor_running", false)
            .putBoolean("monitor_paused", false)
            .putLong("monitor_stopped_at", System.currentTimeMillis())
            .apply()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Giám sát giấc ngủ", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.donglq.sleepmonitor.STOP_MONITOR"
        const val ACTION_PAUSE = "com.donglq.sleepmonitor.PAUSE_MONITOR"
        const val ACTION_RESUME = "com.donglq.sleepmonitor.RESUME_MONITOR"
        const val EXTRA_DELAY_MS = "extra_delay_ms"
        const val PREFS = "sleep_monitor_preferences"
        private const val CHANNEL_ID = "sleep_monitor_capture"
        private const val NOTIFICATION_ID = 7102
    }
}
