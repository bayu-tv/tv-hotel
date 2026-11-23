package com.bayutv.hotel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.abs
import kotlin.math.pow

object GameChiptuneEngine {

    enum class GameTheme(
        val stepMs: Int,
        val leadNotes: IntArray,
        val bassNotes: IntArray
    ) {
        // 1. Bomber Arena: Mars arcade ceria (C Major)
        BOMBER(
            stepMs = 135,
            leadNotes = intArrayOf(
                72, 0, 76, 79, 84, 0, 79, 76,
                77, 0, 81, 84, 81, 0, 77, 0,
                79, 0, 83, 86, 83, 0, 79, 74,
                76, 79, 84, 0, 84, 0, 0, 0
            ),
            bassNotes = intArrayOf(
                60, 0, 67, 0, 60, 0, 67, 0,
                65, 0, 72, 0, 65, 0, 72, 0,
                67, 0, 74, 0, 67, 0, 74, 0,
                60, 0, 67, 0, 60, 0, 0, 0
            )
        ),

        // 2. Neon Brick: Arpeggio retro-synth (A Minor)
        BRICK(
            stepMs = 125,
            leadNotes = intArrayOf(
                69, 72, 76, 81, 79, 76, 72, 76,
                65, 69, 72, 77, 76, 72, 69, 72,
                67, 71, 74, 79, 77, 74, 71, 74,
                64, 68, 71, 76, 74, 71, 68, 0
            ),
            bassNotes = intArrayOf(
                57, 57, 0, 57, 64, 0, 57, 0,
                53, 53, 0, 53, 60, 0, 53, 0,
                55, 55, 0, 55, 62, 0, 55, 0,
                52, 52, 0, 52, 59, 0, 64, 0
            )
        ),

        // 3. Gold Snake: Groove ringan fokus (F Major / D Minor)
        SNAKE(
            stepMs = 155,
            leadNotes = intArrayOf(
                65, 0, 69, 0, 72, 0, 74, 72,
                69, 0, 65, 0, 67, 0, 0, 0,
                62, 0, 65, 0, 69, 0, 72, 69,
                67, 0, 64, 0, 65, 0, 0, 0
            ),
            bassNotes = intArrayOf(
                53, 0, 60, 0, 53, 0, 60, 0,
                57, 0, 64, 0, 55, 0, 62, 0,
                50, 0, 57, 0, 50, 0, 57, 0,
                55, 0, 60, 0, 53, 0, 0, 0
            )
        ),

        // 4. Neon Tetris: Melodi balok klasik (E Minor)
        TETRIS(
            stepMs = 135,
            leadNotes = intArrayOf(
                76, 0, 71, 72, 74, 0, 72, 71,
                69, 0, 69, 72, 76, 0, 74, 72,
                71, 0, 71, 72, 74, 0, 76, 0,
                72, 0, 69, 0, 69, 0, 0, 0
            ),
            bassNotes = intArrayOf(
                52, 64, 52, 64, 52, 64, 52, 64,
                57, 69, 57, 69, 57, 69, 57, 69,
                56, 68, 56, 68, 52, 64, 52, 64,
                57, 69, 57, 69, 57, 0, 0, 0
            )
        ),

        // 5. Astro Rocket: Synth luar angkasa berenergi (D Minor)
        ROCKET(
            stepMs = 120,
            leadNotes = intArrayOf(
                62, 62, 74, 0, 69, 0, 68, 67,
                65, 0, 62, 65, 67, 0, 0, 0,
                60, 60, 72, 0, 69, 0, 67, 65,
                64, 0, 67, 69, 74, 0, 0, 0
            ),
            bassNotes = intArrayOf(
                50, 50, 62, 50, 50, 62, 50, 62,
                46, 46, 58, 46, 48, 48, 60, 48,
                48, 48, 60, 48, 48, 60, 48, 60,
                45, 45, 57, 45, 50, 0, 62, 0
            )
        )
    }

    private const val SAMPLE_RATE = 22050

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var muted = false

    @Volatile
    private var currentTheme: GameTheme = GameTheme.BOMBER

    private var playThread: Thread? = null

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    /**
     * Dipanggil otomatis dari loop game:
     * mode: 1=Bomber, 2=Brick, 3=Snake, 4=Tetris, 5=Rocket
     * state: 0=Hub, 1=Ready, 2=Playing, 3=Paused, 4=GameOver
     */
    fun sync(mode: Int, state: Int, isMuted: Boolean) {
        muted = isMuted
        if (state == 2 && mode in 1..5) {
            val targetTheme = when (mode) {
                1 -> GameTheme.BOMBER
                2 -> GameTheme.BRICK
                3 -> GameTheme.SNAKE
                4 -> GameTheme.TETRIS
                else -> GameTheme.ROCKET
            }
            currentTheme = targetTheme
            if (isMuted) {
                paused = true
            } else if (!running || playThread?.isAlive != true) {
                start(targetTheme, false)
            } else {
                paused = false
            }
        } else if (state == 3 || state == 4) {
            paused = true
        } else {
            if (running) stop()
        }
    }

    fun start(theme: GameTheme, isMuted: Boolean) {
        currentTheme = theme
        muted = isMuted
        paused = false

        if (running && playThread?.isAlive == true) {
            return
        }

        stop()
        running = true
        paused = false

        val thread = Thread({
            var audioTrack: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                audioTrack = createAudioTrack(minBuf * 2)
                val warmup = ShortArray(512)
                audioTrack.write(warmup, 0, warmup.size)
                audioTrack.play()

                var stepIndex = 0
                var leadPhase = 0.0
                var bassPhase = 0.0

                while (running) {
                    if (paused || muted || isBackgroundRadioMusicPlaying()) {
                        Thread.sleep(60L)
                        continue
                    }

                    val activeTheme = currentTheme
                    val len = activeTheme.leadNotes.size
                    if (stepIndex >= len) stepIndex = 0

                    val leadMidi = activeTheme.leadNotes[stepIndex]
                    val bassMidi = activeTheme.bassNotes.getOrElse(stepIndex) { 0 }
                    val samplesCount = (SAMPLE_RATE * activeTheme.stepMs) / 1000
                    val pcm = ShortArray(samplesCount)

                    val leadFreq = midiToFreq(leadMidi)
                    val bassFreq = midiToFreq(bassMidi)
                    val leadInc = if (leadFreq > 0.0) leadFreq / SAMPLE_RATE else 0.0
                    val bassInc = if (bassFreq > 0.0) bassFreq / SAMPLE_RATE else 0.0

                    for (i in 0 until samplesCount) {
                        val progress = i.toDouble() / samplesCount
                        val env = when {
                            progress < 0.05 -> progress / 0.05
                            progress > 0.84 -> ((1.0 - progress) / 0.16).coerceAtLeast(0.0)
                            else -> 1.0
                        }

                        var sampleVal = 0.0

                        // Jalur 1: Lead melodi utama
                        if (leadInc > 0.0) {
                            leadPhase += leadInc
                            if (leadPhase >= 1.0) leadPhase -= 1.0
                            val square = if (leadPhase < 0.45) 1.0 else -1.0
                            sampleVal += square * 0.34 * env
                        } else {
                            leadPhase = 0.0
                        }

                        // Jalur 2: Bass pengiring hangat
                        if (bassInc > 0.0) {
                            bassPhase += bassInc
                            if (bassPhase >= 1.0) bassPhase -= 1.0
                            val tri = 1.0 - 4.0 * abs(bassPhase - 0.5)
                            sampleVal += tri * 0.30 * env
                        } else {
                            bassPhase = 0.0
                        }

                        val clamped = sampleVal.coerceIn(-0.90, 0.90)
                        pcm[i] = (clamped * 32767.0).toInt().toShort()
                    }

                    if (running && !paused && !muted) {
                        audioTrack.write(pcm, 0, pcm.size)
                    }
                    stepIndex = (stepIndex + 1) % len
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
            } finally {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.release()
                } catch (_: Exception) {
                }
            }
        }, "BBoxGameChiptune")

        playThread = thread
        thread.start()
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        running = false
        paused = false
        try {
            playThread?.interrupt()
        } catch (_: Exception) {
        }
        playThread = null
    }

    private fun isBackgroundRadioMusicPlaying(): Boolean {
        return try {
            RadioMusicActivity.MusicEngine.state == RadioMusicActivity.PlayState.PLAYING
        } catch (_: Exception) {
            false
        }
    }

    private fun midiToFreq(midi: Int): Double {
        if (midi <= 0) return 0.0
        return 440.0 * 2.0.pow((midi - 69.0) / 12.0)
    }

    @Suppress("DEPRECATION")
    private fun createAudioTrack(bufferBytes: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM
            )
        }
    }
}
