package com.givenvoice.wearable

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Minimal ElevenLabs text-to-speech client for the GB Artificial Voice.
 *
 * Uses only HttpURLConnection + org.json (both built into Android), so
 * no new Gradle dependencies are needed.
 *
 * Settings match the Synthetic Voice Audit's best configuration
 * (Round 2): Multilingual v2, stability 0.75, similarity 0.75, speed 0.9,
 * style 0, speaker boost on. Output is MP3 44.1kHz/128kbps rather than
 * WAV because it plays directly in MediaPlayer and keeps downloads small
 * on the glasses' Wi-Fi.
 *
 * The API key comes from BuildConfig (read from local.properties at build
 * time), so it never appears in source code or in the GitHub repo.
 */
object ElevenLabsTts {

    private const val VOICE_ID = "fVEKkLfx1mw1HkAfWaHW" // GB Artificial Voice
    private const val MODEL_ID = "eleven_multilingual_v2"
    private const val OUTPUT_FORMAT = "mp3_44100_128"

    /** Generates [text] in Gabe's voice and saves it to [outFile].
     * Runs on the IO dispatcher; throws on any network or API error. */
    suspend fun synthesize(text: String, outFile: File) = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ELEVENLABS_API_KEY
        if (apiKey.isBlank()) {
            throw IllegalStateException("ELEVENLABS_API_KEY is missing from local.properties")
        }

        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$VOICE_ID?output_format=$OUTPUT_FORMAT")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
        }

        try {
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", MODEL_ID)
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.75)
                    put("similarity_boost", 0.75)
                    put("style", 0.0)
                    put("use_speaker_boost", true)
                    put("speed", 0.9)
                })
            }
            // Logs exactly what is sent, so the settings can be verified in
            // Logcat (filter: GivenVoiceTTS). The API key is not logged.
            android.util.Log.i(
                "GivenVoiceTTS",
                "voice=$VOICE_ID model=$MODEL_ID format=$OUTPUT_FORMAT " +
                        "settings=${body.getJSONObject("voice_settings")} text=\"$text\""
            )
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException("ElevenLabs HTTP $code: $error")
            }

            // Write to a temp file first so a dropped connection never
            // leaves a half-written file that looks like a valid cache hit.
            outFile.parentFile?.mkdirs()
            val tmp = File(outFile.parentFile, outFile.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(outFile)) {
                tmp.delete()
                throw IOException("Could not save audio to ${outFile.absolutePath}")
            }
        } finally {
            conn.disconnect()
        }
    }
}