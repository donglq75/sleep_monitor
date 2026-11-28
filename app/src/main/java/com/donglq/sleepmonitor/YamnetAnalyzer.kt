package com.donglq.sleepmonitor

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object YamnetAnalyzer {
    data class Suggestion(val title: String, val confidence: Float)

    private const val SAMPLE_RATE = 16_000
    private const val WINDOW_SAMPLES = 15_600
    private const val HOP_SAMPLES = 7_800

    fun analyze(context: Context, wavFile: File): Suggestion {
        val samples = readPcmWav(wavFile)
        require(samples.isNotEmpty()) { "Clip không có dữ liệu âm thanh" }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("yamnet.tflite")
            .build()
        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(521)
            .build()
        val classifier = AudioClassifier.createFromOptions(context, options)
        try {
            val format = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(SAMPLE_RATE.toFloat())
                .build()
            val audioData = AudioData.create(format, WINDOW_SAMPLES)
            run {
                val totals = mutableMapOf<String, Float>()
                val counts = mutableMapOf<String, Int>()
                var start = 0
                while (start < samples.size) {
                    val window = FloatArray(WINDOW_SAMPLES)
                    val length = min(WINDOW_SAMPLES, samples.size - start)
                    System.arraycopy(samples, start, window, 0, length)
                    audioData.load(window)
                    val result = classifier.classify(audioData)
                    result.classificationResults().forEach { classificationResult ->
                        classificationResult.classifications().firstOrNull()?.categories()?.forEach { category ->
                            val name = category.categoryName()
                            if (name.isNotBlank()) {
                                totals[name] = (totals[name] ?: 0f) + category.score()
                                counts[name] = (counts[name] ?: 0) + 1
                            }
                        }
                    }
                    if (start + WINDOW_SAMPLES >= samples.size) break
                    start += HOP_SAMPLES
                }
                val ranked = totals.map { (name, total) ->
                    name to (total / max(1, counts[name] ?: 1))
                }.sortedByDescending { it.second }
                val candidates = ranked.mapNotNull { (name, score) ->
                    val normalized = name.lowercase()
                    val title = when {
                        "snoring" in normalized || "snore" in normalized -> "Ngáy"
                        "cough" in normalized -> "Ho"
                        "speech" in normalized || "conversation" in normalized -> "Tiếng nói (cần xác nhận nói mơ)"
                        "alarm" in normalized || "siren" in normalized -> "Chuông / âm báo"
                        else -> null
                    }
                    if (title == null) null else title to score
                }
                val best = candidates.maxByOrNull { it.second }
                if (best == null || best.second < 0.12f) {
                    val fallback = ranked.firstOrNull()
                    return Suggestion(
                        if (fallback == null) "Chưa rõ" else "Âm thanh khác: ${fallback.first}",
                        fallback?.second ?: 0f
                    )
                }
                return Suggestion(best.first, best.second.coerceIn(0f, 1f))
            }
        } finally {
            classifier.close()
        }
    }

    private fun readPcmWav(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Tệp WAV không hợp lệ" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var dataOffset = -1
        var dataLength = 0
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4).coerceAtLeast(0)
            val chunkData = offset + 8
            if (chunkData + chunkSize > bytes.size) break
            when (chunkId) {
                "fmt " -> {
                    audioFormat = buffer.getShort(chunkData).toInt() and 0xffff
                    channels = buffer.getShort(chunkData + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(chunkData + 4)
                    bitsPerSample = buffer.getShort(chunkData + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = chunkData
                    dataLength = chunkSize
                    break
                }
            }
            offset = chunkData + chunkSize + (chunkSize and 1)
        }
        require(audioFormat == 1 && channels == 1 && bitsPerSample == 16 &&
            sampleRate == SAMPLE_RATE && dataOffset >= 0) {
            "Chỉ hỗ trợ WAV PCM 16-bit mono 16 kHz"
        }
        val output = FloatArray(dataLength / 2)
        for (i in output.indices) output[i] = buffer.getShort(dataOffset + i * 2) / 32768f
        return output
    }
}