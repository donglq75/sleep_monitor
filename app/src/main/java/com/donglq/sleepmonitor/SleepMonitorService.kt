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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sleep Monitor đang giám sát")
                .setContentText("Đang phân tích âm thanh trên thiết bị · Chỉ lưu đoạn sự kiện")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sleep Monitor đang giám sát")
                .setContentText("Chỉ lưu đoạn âm thanh sự kiện")
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("monitor_running", true).putLong("monitor_started_at", System.currentTimeMillis()).apply()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepMonitor::Capture").also {
            it.setReferenceCounted(false)
            it.acquire()
        }

        running = true
        worker = Thread({ captureLoop() }, "SleepMonitor-Audio").also {
            it.priority = Process.THREAD_PRIORITY_AUDIO
            it.start()
        }
    }

    private fun captureLoop() {
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
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
            }
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
            val folder = File(filesDir, "events").apply { mkdirs() }
            val file = File(folder, "event_${System.currentTimeMillis()}.wav")
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
        try { recorder?.stop() } catch (_: Exception) { }
        worker?.interrupt()
        worker = null
        try { echoCanceler?.release() } catch (_: Exception) { }
        try { noiseSuppressor?.release() } catch (_: Exception) { }
        echoCanceler = null
        noiseSuppressor = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("monitor_running", false)
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
        const val PREFS = "sleep_monitor_preferences"
        private const val CHANNEL_ID = "sleep_monitor_capture"
        private const val NOTIFICATION_ID = 7102
    }
}
