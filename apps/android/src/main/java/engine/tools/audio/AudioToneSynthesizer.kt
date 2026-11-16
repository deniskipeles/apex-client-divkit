package engine.tools.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Universal PCM Audio Tone Synthesizer (44.1 kHz, 16-bit Mono).
 * Generates pure mathematical acoustic waves with attack/decay anti-pop envelopes.
 * Completely free of vendor or domain coupling.
 */
object AudioToneSynthesizer {

    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeTrack: AudioTrack? = null
    private val lock = Any()

    enum class Waveform { SINE, SQUARE, TRIANGLE, SAWTOOTH }

    fun playTone(
        frequencyHz: Double = 1200.0,
        durationMs: Int = 120,
        volume: Float = 0.85f,
        waveform: Waveform = Waveform.SINE
    ) {
        audioScope.launch {
            stopActiveTone()

            val sampleRate = 44100
            val numSamples = ((durationMs * sampleRate) / 1000).coerceAtLeast(1)
            val pcm = ShortArray(numSamples)
            val angularFreq = 2.0 * PI * frequencyHz.coerceIn(20.0, 20000.0) / sampleRate

            val attackSamples = (sampleRate * 0.005).toInt().coerceAtMost(numSamples / 4)
            val decaySamples = (sampleRate * 0.010).toInt().coerceAtMost(numSamples / 4)
            val safeVolume = volume.coerceIn(0.0f, 1.0f)

            for (i in 0 until numSamples) {
                val phase = (angularFreq * i) % (2.0 * PI)
                val rawSample = when (waveform) {
                    Waveform.SINE -> sin(phase)
                    Waveform.SQUARE -> if (phase < PI) 1.0 else -1.0
                    Waveform.TRIANGLE -> 2.0 * abs(2.0 * (phase / (2.0 * PI) - floor(phase / (2.0 * PI) + 0.5))) - 1.0
                    Waveform.SAWTOOTH -> 2.0 * (phase / (2.0 * PI) - floor(phase / (2.0 * PI) + 0.5))
                }

                // Smooth cosine envelope prevents audio pops/clicks at start and end
                val envelope = when {
                    i < attackSamples -> 0.5 * (1.0 - cos(PI * i / attackSamples))
                    i > numSamples - decaySamples -> 0.5 * (1.0 - cos(PI * (numSamples - i) / decaySamples))
                    else -> 1.0
                }

                pcm[i] = (rawSample * envelope * Short.MAX_VALUE * safeVolume).toInt().toShort()
            }

            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                synchronized(lock) { activeTrack = track }

                track.write(pcm, 0, pcm.size)
                track.play()

                delay(durationMs.toLong() + 30L)
                synchronized(lock) {
                    if (activeTrack == track) {
                        try {
                            track.stop()
                            track.release()
                        } catch (_: Exception) {}
                        activeTrack = null
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Plays a rapid multi-tone sequence (e.g. chime, arpeggio, or melody).
     */
    fun playSequence(
        sequence: List<Pair<Double, Int>>,
        volume: Float = 0.85f,
        waveform: Waveform = Waveform.SINE
    ) {
        audioScope.launch {
            for ((freq, dur) in sequence) {
                playTone(freq, dur, volume, waveform)
                delay(dur.toLong() + 10L)
            }
        }
    }

    fun stopActiveTone() {
        synchronized(lock) {
            activeTrack?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Exception) {}
                activeTrack = null
            }
        }
    }
}