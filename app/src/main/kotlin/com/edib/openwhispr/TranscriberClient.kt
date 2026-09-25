package com.edib.openwhispr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.math.sqrt

object TranscriberClient {
    data class Result(val text: String?, val error: String?)
    private val client = OkHttpClient()
    // Whisper treats `prompt` as prior transcript context, not as an instruction.
    // A natural mixed-script vocabulary seed preserves Egyptian code-switching far
    // more reliably than an English instruction about language preservation.
    private const val MULTILINGUAL_PROMPT =
        "عاوز، اشيل، اللي، كان، بيتحط، دلوقتي، نجرب، العربي. " +
            "overlay button, applications, application, update, user interface, build, APK, English text, recording test."

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) { Result(null, e.message ?: "Parse error") }

    fun transcribe(wavData: ByteArray, apiKey: String, callback: (Result) -> Unit) {
        transcribeOnce(wavData, apiKey, null) { baseline ->
            val text = baseline.text?.trim().orEmpty()
            if (text.isBlank() || !containsArabic(text) || containsLatin(text)) {
                callback(baseline); return@transcribeOnce
            }
            transcribeOnce(wavData, apiKey, MULTILINGUAL_PROMPT) { prompted ->
                val promptedText = prompted.text?.trim().orEmpty()
                if (isArabicLatinMix(promptedText)) {
                    callback(Result(promptedText, null)); return@transcribeOnce
                }
                val chunks = splitOnSilence(wavData)
                transcribeChunks(chunks, apiKey, 0, mutableListOf()) { parts ->
                    val combined = parts.filter(::isMeaningfulChunk).joinToString(" ").trim()
                    if (isArabicLatinMix(combined)) {
                        callback(Result(combined, null))
                    } else {
                        // Groq's language decision can vary on very short
                        // phrases even at temperature zero. One bounded retry
                        // recovers code-switches without affecting normal audio.
                        transcribeChunks(chunks, apiKey, 0, mutableListOf()) { retryParts ->
                            val retry = retryParts.filter(::isMeaningfulChunk).joinToString(" ").trim()
                            callback(if (isArabicLatinMix(retry)) Result(retry, null) else baseline)
                        }
                    }
                }
            }
        }
    }

    private fun transcribeOnce(wavData: ByteArray, apiKey: String, prompt: String?, callback: (Result) -> Unit) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .apply { if (prompt != null) addFormDataPart("prompt", prompt) }.build()
        val request = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey").post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) = callback(parseResponse(response.body?.string() ?: ""))
        })
    }

    private fun transcribeChunks(chunks: List<ByteArray>, apiKey: String, index: Int,
        results: MutableList<String>, callback: (List<String>) -> Unit) {
        if (index >= chunks.size) { callback(results); return }
        transcribeOnce(chunks[index], apiKey, null) { result ->
            result.text?.trim()?.let(results::add)
            transcribeChunks(chunks, apiKey, index + 1, results, callback)
        }
    }

    internal fun containsArabic(text: String) = text.any { it in '\u0600'..'\u06FF' }
    internal fun containsLatin(text: String) = text.any { it in 'A'..'Z' || it in 'a'..'z' }
    internal fun isArabicLatinMix(text: String) = containsArabic(text) && containsLatin(text)
    internal fun isMeaningfulChunk(text: String): Boolean {
        val cleaned = text.trim().trim('.', '!', '?', '،', '؟')
        if (cleaned.length < 4) return false
        return cleaned.lowercase() !in setOf("thank you", "thanks", "yes", "preface")
    }

    internal fun splitOnSilence(wav: ByteArray): List<ByteArray> {
        if (wav.size <= 44) return listOf(wav)
        val pcm = wav.copyOfRange(44, wav.size); val frameBytes = 640
        val levels = pcm.asList().chunked(frameBytes).map { frame ->
            var sum = 0.0; var count = 0; var i = 0
            while (i + 1 < frame.size) {
                val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toInt()
                sum += sample.toDouble() * sample; count++; i += 2
            }
            if (count == 0) 0 else sqrt(sum / count).toInt()
        }
        val threshold = maxOf(250, ((levels.maxOrNull() ?: 0) * 0.035).toInt())
        val cuts = mutableListOf<Int>(); var silenceStart = -1
        for (i in 0..levels.size) {
            val silent = i < levels.size && levels[i] < threshold
            if (silent && silenceStart < 0) silenceStart = i
            if (!silent && silenceStart >= 0) {
                if (i - silenceStart >= 12) cuts += (silenceStart + i) / 2
                silenceStart = -1
            }
        }
        val boundaries = listOf(0) + cuts + listOf(levels.size)
        return boundaries.zipWithNext().mapNotNull { (start, end) ->
            if (end - start < 40) return@mapNotNull null
            val from = (start * frameBytes).coerceAtMost(pcm.size); val to = (end * frameBytes).coerceAtMost(pcm.size)
            WavWriter.encode(pcm.copyOfRange(from, to))
        }.ifEmpty { listOf(wav) }
    }
}
