package com.bayutv.hotel

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameActivity : Activity() {

    private lateinit var gameView: ArcadeCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = ArcadeCanvasView(this)
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        gameView.resumeLoop()
    }

    override fun onPause() {
        gameView.pauseLoop()
        super.onPause()
    }

    override fun onDestroy() {
        gameView.releaseResources()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!gameView.onBackAction()) {
            super.onBackPressed()
        }
    }
}

class RetroAudioEngine(context: Context) {

    companion object {
        const val SFX_EXPLODE = 0
        const val SFX_BOUNCE = 1
        const val SFX_COIN = 2
        const val SFX_LASER = 3
        const val SFX_POWERUP = 4
        const val SFX_WIN = 5
        const val SFX_OVER = 6
        const val SFX_JUMP = 7
        const val SFX_STOMP = 8
    }

    private var soundPool: SoundPool? = null
    private val sfxIds = IntArray(9)
    var muted: Boolean = false

    init {
        try {
            soundPool = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(attrs)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                SoundPool(6, AudioManager.STREAM_MUSIC, 0)
            }

            val dir = context.cacheDir
            sfxIds[SFX_EXPLODE] = loadTone(dir, "exp.wav", 0.28f, 190f, 45f, noise = true)
            sfxIds[SFX_BOUNCE] = loadTone(dir, "bnc.wav", 0.08f, 440f, 620f, noise = false)
            sfxIds[SFX_COIN] = loadTone(dir, "cin.wav", 0.14f, 880f, 1320f, noise = false)
            sfxIds[SFX_LASER] = loadTone(dir, "lsr.wav", 0.10f, 920f, 240f, noise = false)
            sfxIds[SFX_POWERUP] = loadTone(dir, "pwr.wav", 0.22f, 360f, 960f, noise = false)
            sfxIds[SFX_WIN] = loadTone(dir, "win.wav", 0.34f, 440f, 1180f, noise = false)
            sfxIds[SFX_OVER] = loadTone(dir, "ovr.wav", 0.34f, 360f, 110f, noise = false)
            sfxIds[SFX_JUMP] = loadTone(dir, "jmp.wav", 0.13f, 260f, 680f, noise = false)
            sfxIds[SFX_STOMP] = loadTone(dir, "stm.wav", 0.10f, 320f, 95f, noise = true)
        } catch (_: Exception) {
        }
    }

    fun play(id: Int, rate: Float = 1f) {
        if (muted || id !in sfxIds.indices) return
        val sid = sfxIds[id]
        if (sid > 0) {
            try {
                soundPool?.play(sid, 0.85f, 0.85f, 1, 0, rate.coerceIn(0.6f, 1.6f))
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Exception) {
        }
        soundPool = null
    }

    private fun loadTone(
        dir: File,
        fileName: String,
        durationSec: Float,
        fStart: Float,
        fEnd: Float,
        noise: Boolean
    ): Int {
        return try {
            val file = File(dir, fileName)
            val sampleRate = 22050
            val numSamples = (sampleRate * durationSec).toInt().coerceAtLeast(256)
            val dataBytes = numSamples * 2
            val wav = ByteArray(44 + dataBytes)

            writeAscii(wav, 0, "RIFF")
            writeIntLE(wav, 4, 36 + dataBytes)
            writeAscii(wav, 8, "WAVE")
            writeAscii(wav, 12, "fmt ")
            writeIntLE(wav, 16, 16)
            writeShortLE(wav, 20, 1)
            writeShortLE(wav, 22, 1)
            writeIntLE(wav, 24, sampleRate)
            writeIntLE(wav, 28, sampleRate * 2)
            writeShortLE(wav, 32, 2)
            writeShortLE(wav, 34, 16)
            writeAscii(wav, 36, "data")
            writeIntLE(wav, 40, dataBytes)

            var phase = 0.0
            var rnd = 1337
            for (i in 0 until numSamples) {
                val t = i.toFloat() / numSamples
                val env = (1f - t) * min(1f, t * 22f)
                val freq = fStart + (fEnd - fStart) * t
                phase += (2.0 * Math.PI * freq) / sampleRate
                val tone = if (sin(phase) >= 0.0) 0.65f else -0.65f
                val sig = if (noise) {
                    rnd = rnd * 1103515245 + 12345
                    val n = ((rnd ushr 16) and 0x7FFF) / 16383.5f - 1f
                    (tone * 0.45f + n * 0.55f)
                } else {
                    tone
                }
                val sample = (sig * env * 22000f).toInt().coerceIn(-32767, 32767)
                wav[44 + i * 2] = (sample and 0xFF).toByte()
                wav[44 + i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
            }

            FileOutputStream(file).use { it.write(wav) }
            soundPool?.load(file.absolutePath, 1) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun writeAscii(buf: ByteArray, off: Int, text: String) {
        for (i in text.indices) buf[off + i] = text[i].code.toByte()
    }

    private fun writeIntLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}

class ArcadeCanvasView(context: Context) : View(context) {

    companion object {
        const val STATE_HUB = 0
        const val STATE_READY = 1
        const val STATE_PLAYING = 2
        const val STATE_PAUSED = 3
        const val STATE_GAMEOVER = 4

        const val MODE_HUB = 0
        const val MODE_BOMBER = 1
        const val MODE_BRICK = 2
        const val MODE_SNAKE = 3
        const val MODE_TETRIS = 4
        const val MODE_ROCKET = 5
        const val MODE_QUEST = 6

        const val DIFF_EASY = 0
        const val DIFF_NORMAL = 1
        const val DIFF_HARD = 2

        const val COL_BG_TOP = 0xFF0E0D0C.toInt()
        const val COL_BG_BOT = 0xFF191614.toInt()
        const val COL_PANEL = 0xFF1F1C19.toInt()
        const val COL_PANEL_HI = 0xFF2B2622.toInt()
        const val COL_GOLD = 0xFFE6C88F.toInt()
        const val COL_IVORY = 0xFFEFE8DA.toInt()
        const val COL_MUTED = 0xFF9C9386.toInt()
        const val COL_RED = 0xFFE05252.toInt()
        const val COL_CYAN = 0xFF4CC3D9.toInt()
        const val COL_PURPLE = 0xFF9C6ADE.toInt()
        const val COL_ORANGE = 0xFFFF9800.toInt()
        const val COL_GREEN = 0xFF4CAF7D.toInt()
    }

    private val prefs = context.getSharedPreferences("bayutv_arcade", Context.MODE_PRIVATE)
    private val audio = RetroAudioEngine(context)
    private val questEngine = BBoxQuestEngine(context)

    private val gameTitles = arrayOf(
        "Bomber Arena",
        "Neon Brick",
        "Gold Snake",
        "Neon Tetris",
        "Astro Rocket",
        "B-BOX Quest"
    )
    private val gameDescs = arrayOf(
        "Ledakkan Blok & Kalahkan Bot",
        "Hancurkan Dinding Bata Neon",
        "Kumpulkan Permata Emas",
        "Susun Balok Klasik & Hapus Baris",
        "Tembus Hujan Meteor & UFO",
        "Petualangan Kapten B-BOX & Kastil"
    )
    private val gameGuides = arrayOf(
        "Pasang bom di persimpangan, hindari ledakan, dan ambil power-up api!",
        "Pantulkan bola energi dan tangkap kapsul lebar/multi-bola!",
        "Makan permata emas untuk skor tinggi tanpa menabrak ekor sendiri!",
        "Putar dan susun balok agar baris penuh hilang dan skor berlipat!",
        "Tembak meteor & UFO musuh, kumpulkan perisai serta tembakan ganda!",
        "Lompat ke blok (?) untuk koin & mode Api, injak musuh, dan raih bendera kastil!"
    )
    private val gameAccents = intArrayOf(
        COL_RED,
        COL_CYAN,
        COL_GOLD,
        COL_PURPLE,
        COL_ORANGE,
        COL_GREEN
    )

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val rect = RectF()
    private val path = Path()

    private var bgShader: Shader? = null
    private var cachedBgH = 0f

    private var running = false
    private var lastFrameNs = 0L
    private var animTime = 0f

    private var state = STATE_HUB
    private var mode = MODE_HUB
    private var hubIndex = 0
    private var readyChoice = 0
    private var dialogIndex = 0
    private var difficulty = DIFF_NORMAL
    private var score = 0
    private var displayScore = 0f
    private var scorePopScale = 1f
    private var sessionHighScore = 0
    private var newRecordAnnounced = false
    private var newRecordBannerTimer = 0f
    private var stage = 1
    private var victory = false
    private var showTouchOverlay = false

    // Input states
    private var inLeft = false
    private var inRight = false
    private var inUp = false
    private var inDown = false
    private var btnA = false
    private var btnB = false
    private var boardHolding = false

    // Hitboxes
    private val hubCardRects = Array(6) { RectF() }
    private val diffRect = RectF()
    private val muteRect = RectF()
    private val exitRect = RectF()
    private val hudPauseRect = RectF()
    private val readyStartRect = RectF()
    private val readyBackRect = RectF()
    private val dialogItemRects = Array(3) { RectF() }

    private val dpadCenter = FloatArray(2)
    private var dpadRadius = 0f
    private val btnARect = RectF()
    private val btnBRect = RectF()

    // =========================================================================
    // GAME POLISH ENGINE (HIT-STOP, SHOCKWAVES, SHATTER SHARDS, TRAILS, COMBO)
    // =========================================================================
    private var hitStopTimer = 0f
    private var shakeTimer = 0f
    private var shakeStrength = 0f
    private var arenaGlowTimer = 0f
    private var arenaGlowColor = COL_GOLD

    private var comboCount = 0
    private var comboTimer = 0f
    private var comboPopScale = 1f

    private val maxParticles = 72
    private val ptActive = BooleanArray(maxParticles)
    private val ptX = FloatArray(maxParticles)
    private val ptY = FloatArray(maxParticles)
    private val ptVx = FloatArray(maxParticles)
    private val ptVy = FloatArray(maxParticles)
    private val ptLife = FloatArray(maxParticles)
    private val ptMaxLife = FloatArray(maxParticles)
    private val ptSize = FloatArray(maxParticles)
    private val ptColor = IntArray(maxParticles)

    // Geometric spinning shatter fragments
    private val maxShards = 48
    private val shActive = BooleanArray(maxShards)
    private val shX = FloatArray(maxShards)
    private val shY = FloatArray(maxShards)
    private val shVx = FloatArray(maxShards)
    private val shVy = FloatArray(maxShards)
    private val shAngle = FloatArray(maxShards)
    private val shVRot = FloatArray(maxShards)
    private val shW = FloatArray(maxShards)
    private val shH = FloatArray(maxShards)
    private val shLife = FloatArray(maxShards)
    private val shMaxLife = FloatArray(maxShards)
    private val shColor = IntArray(maxShards)

    private val maxShockwaves = 14
    private val swActive = BooleanArray(maxShockwaves)
    private val swX = FloatArray(maxShockwaves)
    private val swY = FloatArray(maxShockwaves)
    private val swMaxRadius = FloatArray(maxShockwaves)
    private val swLife = FloatArray(maxShockwaves)
    private val swMaxLife = FloatArray(maxShockwaves)
    private val swColor = IntArray(maxShockwaves)

    private val maxTrails = 48
    private val trActive = BooleanArray(maxTrails)
    private val trX = FloatArray(maxTrails)
    private val trY = FloatArray(maxTrails)
    private val trLife = FloatArray(maxTrails)
    private val trMaxLife = FloatArray(maxTrails)
    private val trSize = FloatArray(maxTrails)
    private val trColor = IntArray(maxTrails)

    private val maxFloats = 14
    private val flActive = BooleanArray(maxFloats)
    private val flX = FloatArray(maxFloats)
    private val flY = FloatArray(maxFloats)
    private val flLife = FloatArray(maxFloats)
    private val flText = Array(maxFloats) { "" }
    private val flColor = IntArray(maxFloats)

    // =========================================================================
    // GAME 1: BOMBER ARENA STATE
    // =========================================================================
    private val bmCols = 11
    private val bmRows = 9
    private val bmMap = IntArray(bmCols * bmRows)
    private val bmPower = IntArray(bmCols * bmRows)
    private val bmFireTimer = FloatArray(bmCols * bmRows)

    private var bmPx = 1f
    private var bmPy = 1f
    private var bmLives = 3
    private var bmMaxBombs = 1
    private var bmFlameRange = 2
    private var bmSpeedMult = 1f
    private var bmInvuln = 0f

    private val maxBombs = 8
    private val bmbActive = BooleanArray(maxBombs)
    private val bmbOwner = IntArray(maxBombs)
    private val bmbCol = IntArray(maxBombs)
    private val bmbRow = IntArray(maxBombs)
    private val bmbRange = IntArray(maxBombs)
    private val bmbTimer = FloatArray(maxBombs)

    private val maxBots = 3
    private val botAlive = BooleanArray(maxBots)
    private val botX = FloatArray(maxBots)
    private val botY = FloatArray(maxBots)
    private val botDir = IntArray(maxBots)
    private val botBombCd = FloatArray(maxBots)

    // =========================================================================
    // GAME 2: NEON BRICK STATE
    // =========================================================================
    private var brPaddleX = 0.5f
    private var brPaddleW = 0.22f
    private var brPaddleRecoil = 0f
    private var brWideTimer = 0f
    private var brLives = 3

    private val maxBalls = 3
    private val blActive = BooleanArray(maxBalls)
    private val blStuck = BooleanArray(maxBalls)
    private val blX = FloatArray(maxBalls)
    private val blY = FloatArray(maxBalls)
    private val blVx = FloatArray(maxBalls)
    private val blVy = FloatArray(maxBalls)

    private val brCols = 8
    private val brRows = 5
    private val brHp = IntArray(brCols * brRows)
    private val brColor = IntArray(brCols * brRows)
    private val brFlash = FloatArray(brCols * brRows)

    private val maxCaps = 4
    private val cpActive = BooleanArray(maxCaps)
    private val cpType = IntArray(maxCaps)
    private val cpX = FloatArray(maxCaps)
    private val cpY = FloatArray(maxCaps)

    // =========================================================================
    // GAME 3: GOLD SNAKE STATE
    // =========================================================================
    private val snCols = 20
    private val snRows = 14
    private val snBodyX = IntArray(snCols * snRows)
    private val snBodyY = IntArray(snCols * snRows)
    private var snLen = 4
    private var snDir = 1
    private var snNextDir = 1
    private var snMoveTimer = 0f
    private var snEatPulse = 0f
    private var snFoodX = 10
    private var snFoodY = 7
    private var snBonusActive = false
    private var snBonusX = 5
    private var snBonusY = 5
    private var snBonusTimer = 0f

    // =========================================================================
    // GAME 4: NEON TETRIS STATE
    // =========================================================================
    private val ttCols = 10
    private val ttRows = 20
    private val ttBoard = IntArray(ttCols * ttRows)
    private var ttCurType = 0
    private var ttNextType = 1
    private var ttRot = 0
    private var ttPx = 3
    private var ttPy = 0
    private var ttDropTimer = 0f
    private var ttDasTimer = 0f
    private var ttLinesTotal = 0
    private var ttBoardBounce = 0f
    private var ttClearFlashTimer = 0f
    private var ttClearFlashMask = 0

    private val tetrominoShapes = arrayOf(
        intArrayOf(0, 1, 1, 1, 2, 1, 3, 1),
        intArrayOf(1, 0, 2, 0, 1, 1, 2, 1),
        intArrayOf(1, 0, 0, 1, 1, 1, 2, 1),
        intArrayOf(1, 0, 2, 0, 0, 1, 1, 1),
        intArrayOf(0, 0, 1, 0, 1, 1, 2, 1),
        intArrayOf(0, 0, 0, 1, 1, 1, 2, 1),
        intArrayOf(2, 0, 0, 1, 1, 1, 2, 1)
    )
    private val tetrominoColors = intArrayOf(
        COL_CYAN, COL_GOLD, COL_PURPLE, COL_GREEN, COL_RED, 0xFF4A90E2.toInt(), COL_ORANGE
    )

    // =========================================================================
    // GAME 5: ASTRO ROCKET STATE
    // =========================================================================
    private var rkX = 0.5f
    private var rkY = 0.84f
    private var rkTilt = 0f
    private var rkRecoil = 0f
    private var rkMuzzleTimer = 0f
    private var rkTrailTimer = 0f
    private var rkHp = 3
    private var rkShield = 0f
    private var rkDualTimer = 0f
    private var rkFireCd = 0f
    private var rkSpawnCd = 0f
    private var rkKills = 0
    private var rkTargetKills = 18
    private var rocketAutoFire = true

    // Motion afterimage ghost ring buffer (3 slots)
    private val rkGhostX = FloatArray(3)
    private val rkGhostY = FloatArray(3)
    private val rkGhostTilt = FloatArray(3)
    private var rkGhostTimer = 0f

    private val maxRkShots = 16
    private val rsActive = BooleanArray(maxRkShots)
    private val rsFriendly = BooleanArray(maxRkShots)
    private val rsX = FloatArray(maxRkShots)
    private val rsY = FloatArray(maxRkShots)
    private val rsVy = FloatArray(maxRkShots)

    private val maxRkEnemies = 12
    private val reActive = BooleanArray(maxRkEnemies)
    private val reType = IntArray(maxRkEnemies)
    private val reHp = IntArray(maxRkEnemies)
    private val reX = FloatArray(maxRkEnemies)
    private val reY = FloatArray(maxRkEnemies)
    private val reVx = FloatArray(maxRkEnemies)
    private val reVy = FloatArray(maxRkEnemies)
    private val reShootCd = FloatArray(maxRkEnemies)
    private val reFlash = FloatArray(maxRkEnemies)
    private val reWarnTimer = FloatArray(maxRkEnemies)

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = if (lastFrameNs == 0L) 0.016f
            else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = now
            update(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        audio.muted = prefs.getBoolean("arcade_mute", false)
        GameChiptuneEngine.setMuted(audio.muted)
        difficulty = prefs.getInt("arcade_diff", DIFF_NORMAL).coerceIn(0, 2)
        showTouchOverlay = context.resources.displayMetrics.widthPixels < context.resources.displayMetrics.heightPixels

        questEngine.callbacks = object : BBoxQuestEngine.Callbacks {
            override fun onAddScore(basePoints: Int) = addPoints(basePoints)
            override fun onPlaySfx(sfxType: Int, rate: Float) = audio.play(sfxType, rate)
            override fun onShake(duration: Float, strengthDp: Float) = triggerShake(duration, strengthDp)
            override fun onParticles(
                x: Float,
                y: Float,
                count: Int,
                color: Int,
                speedMin: Float,
                speedMax: Float,
                sizeDp: Float
            ) = spawnParticles(x, y, count, color, speedMin, speedMax, sizeDp)

            override fun onFloatText(x: Float, y: Float, text: String, color: Int) =
                spawnFloatText(x, y, text, color)

            override fun onShockwave(x: Float, y: Float, maxRadiusDp: Float, color: Int) =
                spawnShockwave(x, y, maxRadiusDp, color)

            override fun onHitStop(duration: Float) = triggerHitStop(duration)
            override fun onComboEvent(x: Float, y: Float): Float = registerCombo(x, y)

            override fun onStageFinished(victory: Boolean, nextStage: Int) {
                this@ArcadeCanvasView.victory = victory
                if (victory) stage = nextStage
                clearInputs()
                state = STATE_GAMEOVER
                dialogIndex = 0
                saveHighScore(MODE_QUEST, score)
            }
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun resumeLoop() {
        if (running) return
        running = true
        lastFrameNs = System.nanoTime()
        postOnAnimation(frameRunnable)
    }

    fun pauseLoop() {
        running = false
        GameChiptuneEngine.pause()
        removeCallbacks(frameRunnable)
    }

    fun releaseResources() {
        pauseLoop()
        GameChiptuneEngine.stop()
        audio.release()
    }

    fun onBackAction(): Boolean {
        return when (state) {
            STATE_PLAYING -> {
                clearInputs()
                GameChiptuneEngine.pause()
                state = STATE_PAUSED
                dialogIndex = 0
                true
            }
            STATE_PAUSED, STATE_READY, STATE_GAMEOVER -> {
                clearInputs()
                clearEffects()
                GameChiptuneEngine.stop()
                mode = MODE_HUB
                state = STATE_HUB
                true
            }
            else -> false
        }
    }

    private fun clearInputs() {
        inLeft = false
        inRight = false
        inUp = false
        inDown = false
        btnA = false
        btnB = false
        boardHolding = false
    }

    private fun clearEffects() {
        ptActive.fill(false)
        shActive.fill(false)
        swActive.fill(false)
        trActive.fill(false)
        flActive.fill(false)
        shakeTimer = 0f
        hitStopTimer = 0f
        arenaGlowTimer = 0f
        comboCount = 0
        comboTimer = 0f
        comboPopScale = 1f
    }

    private fun getHighScore(m: Int): Int = prefs.getInt("hi_$m", 0)

    private fun saveHighScore(m: Int, sc: Int) {
        if (sc > getHighScore(m)) {
            prefs.edit().putInt("hi_$m", sc).apply()
        }
    }

    private fun addPoints(basePoints: Int) {
        val mult = when (difficulty) {
            DIFF_EASY -> 0.8f
            DIFF_HARD -> 1.4f
            else -> 1.0f
        }
        score += max(5, (basePoints * mult).toInt())
        scorePopScale = 1.32f
        saveHighScore(mode, score)

        if (!newRecordAnnounced && sessionHighScore > 0 && score > sessionHighScore) {
            newRecordAnnounced = true
            newRecordBannerTimer = 2.4f
            audio.play(RetroAudioEngine.SFX_WIN, 1.32f)
            val w = width.toFloat().coerceAtLeast(320f)
            spawnParticles(w * 0.5f, dp(56f), 22, COL_GOLD, dp(40f), dp(160f), 4.2f)
        }
    }

    private fun triggerHitStop(duration: Float) {
        hitStopTimer = max(hitStopTimer, duration)
    }

    private fun triggerShake(duration: Float, strengthDp: Float) {
        shakeTimer = max(shakeTimer, duration)
        shakeStrength = max(shakeStrength, dp(strengthDp))
    }

    private fun triggerArenaGlow(color: Int) {
        arenaGlowTimer = 0.28f
        arenaGlowColor = color
    }

    private fun registerCombo(x: Float, y: Float): Float {
        comboCount++
        comboTimer = 2.6f
        comboPopScale = 1.65f
        if (comboCount >= 2) {
            addPoints(comboCount * 15)
            spawnFloatText(x, y - dp(16f), "COMBO x$comboCount!", COL_CYAN)
        }
        return (1.0f + (comboCount - 1) * 0.08f).coerceAtMost(1.48f)
    }

    private fun spawnShockwave(x: Float, y: Float, maxRadiusDp: Float, color: Int) {
        for (i in 0 until maxShockwaves) {
            if (!swActive[i]) {
                swActive[i] = true
                swX[i] = x
                swY[i] = y
                swMaxRadius[i] = dp(maxRadiusDp)
                swMaxLife[i] = 0.32f
                swLife[i] = swMaxLife[i]
                swColor[i] = color
                return
            }
        }
    }

    private fun spawnShatterShards(x: Float, y: Float, color: Int, wDp: Float, hDp: Float) {
        val dirs = arrayOf(
            floatArrayOf(-1f, -1.2f),
            floatArrayOf(1f, -1.2f),
            floatArrayOf(-0.8f, -0.4f),
            floatArrayOf(0.8f, -0.4f)
        )
        var spawned = 0
        for (i in 0 until maxShards) {
            if (!shActive[i]) {
                val d = dirs[spawned]
                shActive[i] = true
                shX[i] = x + d[0] * dp(4f)
                shY[i] = y + d[1] * dp(3f)
                shVx[i] = d[0] * dp(55f + Random.nextFloat() * 45f)
                shVy[i] = d[1] * dp(85f + Random.nextFloat() * 45f)
                shAngle[i] = Random.nextFloat() * 360f
                shVRot[i] = (if (spawned % 2 == 0) 1f else -1f) * (360f + Random.nextFloat() * 260f)
                shW[i] = dp(wDp)
                shH[i] = dp(hDp)
                shMaxLife[i] = 0.45f
                shLife[i] = shMaxLife[i]
                shColor[i] = color
                spawned++
                if (spawned >= 4) break
            }
        }
    }

    private fun spawnTrailNode(x: Float, y: Float, sizeDp: Float, color: Int) {
        for (i in 0 until maxTrails) {
            if (!trActive[i]) {
                trActive[i] = true
                trX[i] = x
                trY[i] = y
                trMaxLife[i] = 0.22f
                trLife[i] = trMaxLife[i]
                trSize[i] = dp(sizeDp)
                trColor[i] = color
                return
            }
        }
    }

    private fun spawnParticles(
        x: Float,
        y: Float,
        count: Int,
        color: Int,
        speedMin: Float,
        speedMax: Float,
        sizeDp: Float
    ) {
        var spawned = 0
        for (i in 0 until maxParticles) {
            if (!ptActive[i]) {
                ptActive[i] = true
                ptX[i] = x
                ptY[i] = y
                val ang = Random.nextFloat() * 6.28318f
                val spd = speedMin + Random.nextFloat() * (speedMax - speedMin)
                ptVx[i] = cos(ang) * spd
                ptVy[i] = sin(ang) * spd
                ptMaxLife[i] = 0.28f + Random.nextFloat() * 0.28f
                ptLife[i] = ptMaxLife[i]
                ptSize[i] = dp(sizeDp) * (0.7f + Random.nextFloat() * 0.6f)
                ptColor[i] = color
                spawned++
                if (spawned >= count) break
            }
        }
    }

    private fun spawnFloatText(x: Float, y: Float, text: String, color: Int) {
        for (i in 0 until maxFloats) {
            if (!flActive[i]) {
                flActive[i] = true
                flX[i] = x
                flY[i] = y
                flLife[i] = 0.85f
                flText[i] = text
                flColor[i] = color
                return
            }
        }
    }

    private fun openReadyScreen(targetMode: Int) {
        clearInputs()
        clearEffects()
        mode = targetMode
        state = STATE_READY
        readyChoice = 0
        score = 0
        displayScore = 0f
        stage = 1
        victory = false
        audio.play(RetroAudioEngine.SFX_BOUNCE, 1.1f)
    }

    private fun startGame(targetMode: Int, keepProgress: Boolean = false) {
        clearInputs()
        clearEffects()
        mode = targetMode
        state = STATE_PLAYING
        victory = false
        if (!keepProgress) {
            score = 0
            displayScore = 0f
            stage = 1
            sessionHighScore = getHighScore(targetMode)
            newRecordAnnounced = false
            newRecordBannerTimer = 0f
        }
        audio.play(RetroAudioEngine.SFX_POWERUP, 1.15f)
        prepareBoardForMode(targetMode)
    }

    private fun prepareBoardForMode(targetMode: Int) {
        when (targetMode) {
            MODE_BOMBER -> initBomber()
            MODE_BRICK -> initBrick()
            MODE_SNAKE -> initSnake()
            MODE_TETRIS -> initTetris()
            MODE_ROCKET -> initRocket(keep = false)
            MODE_QUEST -> questEngine.initStage(stage, difficulty, resetStats = (stage == 1))
        }
    }

    private fun cycleDifficulty() {
        difficulty = (difficulty + 1) % 3
        prefs.edit().putInt("arcade_diff", difficulty).apply()
        audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
    }

    private fun toggleMute() {
        audio.muted = !audio.muted
        GameChiptuneEngine.setMuted(audio.muted)
        prefs.edit().putBoolean("arcade_mute", audio.muted).apply()
        if (!audio.muted) audio.play(RetroAudioEngine.SFX_BOUNCE, 1.2f)
    }

    private fun difficultyLabel(): String = when (difficulty) {
        DIFF_EASY -> "MUDAH"
        DIFF_HARD -> "SULIT"
        else -> "NORMAL"
    }

    private fun getControlDeckTop(h: Float): Float {
        return if (showTouchOverlay) h - dp(138f) else h
    }

    // =========================================================================
    // MAIN UPDATE LOOP (HIT-STOP, ROLLING SCORE, SHATTER SHARDS & POLISH)
    // =========================================================================
    private fun update(dt: Float) {
        animTime += dt
        GameChiptuneEngine.sync(mode, state, audio.muted)

        // Smooth rolling score counter
        if (displayScore < score) {
            val diff = score - displayScore
            displayScore = min(score.toFloat(), displayScore + max(120f * dt, diff * 12f * dt))
        } else if (displayScore > score) {
            displayScore = score.toFloat()
        }
        if (scorePopScale > 1f) scorePopScale = max(1f, scorePopScale - dt * 3.2f)
        if (newRecordBannerTimer > 0f) newRecordBannerTimer = max(0f, newRecordBannerTimer - dt)
        if (arenaGlowTimer > 0f) arenaGlowTimer = max(0f, arenaGlowTimer - dt)
        if (shakeTimer > 0f) shakeTimer = max(0f, shakeTimer - dt)

        if (comboTimer > 0f) {
            comboTimer = max(0f, comboTimer - dt)
            if (comboTimer <= 0f) comboCount = 0
        }
        if (comboPopScale > 1f) {
            comboPopScale = max(1f, comboPopScale - dt * 3.8f)
        }

        for (i in 0 until maxShockwaves) {
            if (swActive[i]) {
                swLife[i] -= dt
                if (swLife[i] <= 0f) swActive[i] = false
            }
        }

        for (i in 0 until maxTrails) {
            if (trActive[i]) {
                trLife[i] -= dt
                if (trLife[i] <= 0f) trActive[i] = false
            }
        }

        for (i in 0 until maxShards) {
            if (shActive[i]) {
                shLife[i] -= dt
                if (shLife[i] <= 0f) {
                    shActive[i] = false
                } else {
                    shX[i] += shVx[i] * dt
                    shY[i] += shVy[i] * dt
                    shVy[i] += dp(260f) * dt
                    shAngle[i] += shVRot[i] * dt
                }
            }
        }

        for (i in 0 until maxParticles) {
            if (ptActive[i]) {
                ptLife[i] -= dt
                if (ptLife[i] <= 0f) {
                    ptActive[i] = false
                } else {
                    ptX[i] += ptVx[i] * dt
                    ptY[i] += ptVy[i] * dt
                    ptVy[i] += dp(160f) * dt
                }
            }
        }

        for (i in 0 until maxFloats) {
            if (flActive[i]) {
                flLife[i] -= dt
                if (flLife[i] <= 0f) flActive[i] = false
                else flY[i] -= dp(36f) * dt
            }
        }

        if (state != STATE_PLAYING) return

        // Hit-Stop micro freeze on big impacts
        if (hitStopTimer > 0f) {
            hitStopTimer = max(0f, hitStopTimer - dt)
            return
        }

        when (mode) {
            MODE_BOMBER -> updateBomber(dt)
            MODE_BRICK -> updateBrick(dt)
            MODE_SNAKE -> updateSnake(dt)
            MODE_TETRIS -> updateTetris(dt)
            MODE_ROCKET -> updateRocket(dt)
            MODE_QUEST -> questEngine.update(dt, inLeft, inRight, btnA || inUp || boardHolding, btnB)
        }
    }

    // =========================================================================
    // RENDER PIPELINE
    // =========================================================================
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (bgShader == null || cachedBgH != h) {
            cachedBgH = h
            bgShader = LinearGradient(0f, 0f, 0f, h, COL_BG_TOP, COL_BG_BOT, Shader.TileMode.CLAMP)
        }
        pFill.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, pFill)
        pFill.shader = null

        canvas.save()
        if (shakeTimer > 0f && state == STATE_PLAYING) {
            val sx = (Random.nextFloat() * 2f - 1f) * shakeStrength
            val sy = (Random.nextFloat() * 2f - 1f) * shakeStrength
            canvas.translate(sx, sy)
        }

        if (state == STATE_HUB) {
            drawHub(canvas, w, h)
        } else {
            drawTrails(canvas)
            when (mode) {
                MODE_BOMBER -> drawBomber(canvas, w, h)
                MODE_BRICK -> drawBrick(canvas, w, h)
                MODE_SNAKE -> drawSnake(canvas, w, h)
                MODE_TETRIS -> drawTetris(canvas, w, h)
                MODE_ROCKET -> drawRocket(canvas, w, h)
                MODE_QUEST -> questEngine.draw(canvas, w, h, getControlDeckTop(h))
            }
            drawShockwavesAndParticles(canvas)
            drawHudBar(canvas, w)
            if (showTouchOverlay && state == STATE_PLAYING) {
                drawTouchControls(canvas, w, h)
            }
            if (state == STATE_READY) drawReadyOverlay(canvas, w, h)
            if (state == STATE_PAUSED || state == STATE_GAMEOVER) drawDialogOverlay(canvas, w, h)
        }

        canvas.restore()
    }

    private fun drawTrails(canvas: Canvas) {
        for (i in 0 until maxTrails) {
            if (!trActive[i]) continue
            val ratio = (trLife[i] / trMaxLife[i]).coerceIn(0f, 1f)
            val alpha = (ratio * 150).toInt()
            pFill.color = (trColor[i] and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawCircle(trX[i], trY[i], trSize[i] * ratio, pFill)
        }
    }

    private fun drawShockwavesAndParticles(canvas: Canvas) {
        for (i in 0 until maxShockwaves) {
            if (!swActive[i]) continue
            val progress = 1f - (swLife[i] / swMaxLife[i]).coerceIn(0f, 1f)
            val radius = swMaxRadius[i] * (0.2f + progress * 0.8f)
            val alpha = ((1f - progress) * 210).toInt()
            pStroke.strokeWidth = dp(3f) * (1f - progress * 0.6f)
            pStroke.color = (swColor[i] and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawCircle(swX[i], swY[i], radius, pStroke)
        }

        // Spinning geometric shatter fragments
        for (i in 0 until maxShards) {
            if (!shActive[i]) continue
            val alpha = ((shLife[i] / shMaxLife[i]).coerceIn(0f, 1f) * 255).toInt()
            pFill.color = (shColor[i] and 0x00FFFFFF) or (alpha shl 24)
            canvas.save()
            canvas.translate(shX[i], shY[i])
            canvas.rotate(shAngle[i])
            rect.set(-shW[i] * 0.5f, -shH[i] * 0.5f, shW[i] * 0.5f, shH[i] * 0.5f)
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
            canvas.restore()
        }

        for (i in 0 until maxParticles) {
            if (!ptActive[i]) continue
            val alpha = ((ptLife[i] / ptMaxLife[i]).coerceIn(0f, 1f) * 255).toInt()
            pFill.color = (ptColor[i] and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawCircle(ptX[i], ptY[i], ptSize[i], pFill)
        }

        pText.textAlign = Paint.Align.CENTER
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textSize = dp(13f)
        for (i in 0 until maxFloats) {
            if (!flActive[i]) continue
            val alpha = ((flLife[i] / 0.85f).coerceIn(0f, 1f) * 255).toInt()
            pText.color = (flColor[i] and 0x00FFFFFF) or (alpha shl 24)
            canvas.drawText(flText[i], flX[i], flY[i], pText)
        }
    }

    // =========================================================================
    // HUB MENU (6 GAMES GRID / STACK)
    // =========================================================================
    private fun drawHub(canvas: Canvas, w: Float, h: Float) {
        val pad = dp(18f)
        val topH = dp(52f)
        val isPortrait = h > w

        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = COL_GOLD
        pText.textSize = dp(20f)
        canvas.drawText("B-BOX ARCADE", pad, dp(33f), pText)

        val pillH = dp(30f)
        val pillY = dp(11f)
        var rx = w - pad

        val exitW = dp(74f)
        exitRect.set(rx - exitW, pillY, rx, pillY + pillH)
        drawPillButton(canvas, exitRect, "KELUAR", COL_RED, false)
        rx -= exitW + dp(8f)

        val muteW = dp(42f)
        muteRect.set(rx - muteW, pillY, rx, pillY + pillH)
        drawPillButton(canvas, muteRect, if (audio.muted) "\uD83D\uDD07" else "\uD83D\uDD0A", COL_GOLD, false)
        rx -= muteW + dp(8f)

        val diffW = dp(104f)
        diffRect.set(rx - diffW, pillY, rx, pillY + pillH)
        drawPillButton(canvas, diffRect, difficultyLabel(), COL_CYAN, false)

        val topY = topH + dp(8f)
        val botY = h - pad
        val gap = dp(12f)

        if (isPortrait) {
            val cardH = (botY - topY - gap * 5f) / 6f
            for (i in 0..5) {
                val y0 = topY + i * (cardH + gap)
                hubCardRects[i].set(pad, y0, w - pad, y0 + cardH)
                drawHubCard(canvas, hubCardRects[i], i, i == hubIndex, compact = true)
            }
        } else {
            val cols = 3
            val rows = 2
            val cardW = (w - pad * 2f - gap * (cols - 1)) / cols
            val cardH = (botY - topY - gap * (rows - 1)) / rows
            for (i in 0..5) {
                val c = i % cols
                val r = i / cols
                val x0 = pad + c * (cardW + gap)
                val y0 = topY + r * (cardH + gap)
                hubCardRects[i].set(x0, y0, x0 + cardW, y0 + cardH)
                drawHubCard(canvas, hubCardRects[i], i, i == hubIndex, compact = false)
            }
        }
    }

    private fun drawHubCard(canvas: Canvas, r: RectF, idx: Int, focused: Boolean, compact: Boolean) {
        val accent = gameAccents[idx]
        pFill.color = if (focused) COL_PANEL_HI else COL_PANEL
        canvas.drawRoundRect(r, dp(14f), dp(14f), pFill)

        pStroke.strokeWidth = if (focused) dp(2.5f) else dp(1f)
        pStroke.color = if (focused) accent else 0x33E6C88F
        canvas.drawRoundRect(r, dp(14f), dp(14f), pStroke)

        pFill.color = accent
        rect.set(r.left + dp(8f), r.top + dp(10f), r.left + dp(13f), r.bottom - dp(10f))
        canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)

        val textX = r.left + dp(24f)
        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = if (focused) COL_IVORY else COL_GOLD
        pText.textSize = if (compact) dp(15f) else dp(18f)
        canvas.drawText(gameTitles[idx], textX, r.top + (if (compact) dp(24f) else dp(34f)), pText)

        pText.typeface = Typeface.DEFAULT
        pText.color = COL_MUTED
        pText.textSize = if (compact) dp(11.5f) else dp(13f)
        canvas.drawText(gameDescs[idx], textX, r.top + (if (compact) dp(42f) else dp(56f)), pText)

        val hi = getHighScore(idx + 1)
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = accent
        pText.textSize = dp(11.5f)
        if (compact) {
            pText.textAlign = Paint.Align.RIGHT
            canvas.drawText("REKOR: $hi", r.right - dp(14f), r.centerY() + dp(4f), pText)
        } else {
            canvas.drawText("REKOR TERTINGGI: $hi", textX, r.bottom - dp(16f), pText)
        }
    }

    private fun drawPillButton(canvas: Canvas, r: RectF, label: String, accent: Int, active: Boolean) {
        pFill.color = if (active) accent else 0x26FFFFFF
        canvas.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, pFill)
        pStroke.strokeWidth = dp(1.2f)
        pStroke.color = accent
        canvas.drawRoundRect(r, r.height() * 0.5f, r.height() * 0.5f, pStroke)

        pText.textAlign = Paint.Align.CENTER
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = if (active) COL_BG_TOP else COL_IVORY
        pText.textSize = dp(11.5f)
        canvas.drawText(label, r.centerX(), r.centerY() + dp(4f), pText)
    }

    private fun drawHudBar(canvas: Canvas, w: Float) {
        val barH = dp(46f)
        pFill.color = 0xE6141210.toInt()
        canvas.drawRect(0f, 0f, w, barH, pFill)
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x33E6C88F
        canvas.drawLine(0f, barH, w, barH, pStroke)

        val idx = (mode - 1).coerceIn(0, 5)
        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = gameAccents[idx]
        pText.textSize = dp(14f)
        canvas.drawText("${gameTitles[idx]} · S$stage", dp(14f), dp(28f), pText)

        // Rolling Score with Pop-Scale
        canvas.save()
        canvas.translate(w * 0.52f, dp(24f))
        canvas.scale(scorePopScale, scorePopScale)
        pText.textAlign = Paint.Align.CENTER
        pText.color = if (newRecordAnnounced) COL_GOLD else COL_IVORY
        pText.textSize = dp(14f)
        canvas.drawText("SKOR: ${displayScore.toInt()}   |   REKOR: ${getHighScore(mode)}", 0f, dp(4f), pText)
        canvas.restore()

        // Live New Record Banner below HUD
        if (newRecordBannerTimer > 0f && state == STATE_PLAYING) {
            val alpha = ((min(1f, newRecordBannerTimer * 2f)) * 235).toInt()
            rect.set(w * 0.52f - dp(68f), barH + dp(6f), w * 0.52f + dp(68f), barH + dp(28f))
            pFill.color = (alpha shl 24) or 0x00E6C88F
            canvas.drawRoundRect(rect, dp(11f), dp(11f), pFill)
            pText.textAlign = Paint.Align.CENTER
            pText.textSize = dp(11.5f)
            pText.color = (alpha shl 24) or 0x00141210
            canvas.drawText("★ REKOR BARU! ★", rect.centerX(), rect.centerY() + dp(4f), pText)
        } else if (comboCount >= 2 && comboTimer > 0f && state == STATE_PLAYING) {
            canvas.save()
            val cx = w * 0.52f
            val cy = barH + dp(20f)
            canvas.translate(cx, cy)
            canvas.scale(comboPopScale, comboPopScale)
            rect.set(-dp(54f), -dp(12f), dp(54f), dp(12f))
            pFill.color = 0xDD1A1816.toInt()
            canvas.drawRoundRect(rect, dp(12f), dp(12f), pFill)
            pStroke.strokeWidth = dp(1.5f)
            pStroke.color = COL_CYAN
            canvas.drawRoundRect(rect, dp(12f), dp(12f), pStroke)
            pText.textAlign = Paint.Align.CENTER
            pText.textSize = dp(12f)
            pText.color = COL_CYAN
            canvas.drawText("$comboCount HITS!", 0f, dp(4f), pText)
            canvas.restore()
        }

        val pillH = dp(28f)
        val pillY = dp(9f)
        hudPauseRect.set(w - dp(76f), pillY, w - dp(12f), pillY + pillH)
        drawPillButton(canvas, hudPauseRect, "JEDA", COL_GOLD, false)

        muteRect.set(w - dp(122f), pillY, w - dp(82f), pillY + pillH)
        drawPillButton(canvas, muteRect, if (audio.muted) "\uD83D\uDD07" else "\uD83D\uDD0A", COL_GOLD, false)
    }

    private fun drawTouchControls(canvas: Canvas, w: Float, h: Float) {
        val deckTop = getControlDeckTop(h)
        pFill.color = 0xEE12100E.toInt()
        canvas.drawRect(0f, deckTop, w, h, pFill)
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x33E6C88F
        canvas.drawLine(0f, deckTop, w, deckTop, pStroke)

        val deckH = h - deckTop
        dpadCenter[0] = dp(78f)
        dpadCenter[1] = deckTop + deckH * 0.52f
        dpadRadius = dp(52f)

        pFill.color = 0x22FFFFFF
        canvas.drawCircle(dpadCenter[0], dpadCenter[1], dpadRadius, pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x55E6C88F
        canvas.drawCircle(dpadCenter[0], dpadCenter[1], dpadRadius, pStroke)

        pText.textAlign = Paint.Align.CENTER
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textSize = dp(14f)
        pText.color = if (inUp) COL_GOLD else COL_MUTED
        canvas.drawText("\u25B2", dpadCenter[0], dpadCenter[1] - dpadRadius * 0.52f, pText)
        pText.color = if (inDown) COL_GOLD else COL_MUTED
        canvas.drawText("\u25BC", dpadCenter[0], dpadCenter[1] + dpadRadius * 0.72f, pText)
        pText.color = if (inLeft) COL_GOLD else COL_MUTED
        canvas.drawText("\u25C0", dpadCenter[0] - dpadRadius * 0.62f, dpadCenter[1] + dp(5f), pText)
        pText.color = if (inRight) COL_GOLD else COL_MUTED
        canvas.drawText("\u25B6", dpadCenter[0] + dpadRadius * 0.62f, dpadCenter[1] + dp(5f), pText)

        val labelA = when (mode) {
            MODE_BOMBER -> "BOM"
            MODE_BRICK -> "LUNCUR"
            MODE_TETRIS -> "PUTAR"
            MODE_ROCKET -> "TEMBAK"
            MODE_QUEST -> "LOMPAT"
            else -> "AKSI"
        }
        val labelB = when (mode) {
            MODE_TETRIS -> "JATUH"
            MODE_ROCKET -> if (rocketAutoFire) "AUTO ON" else "AUTO"
            MODE_QUEST -> "API"
            else -> ""
        }

        val btnW = dp(82f)
        val btnH = dp(48f)
        val cy = deckTop + deckH * 0.52f
        btnARect.set(w - dp(18f) - btnW, cy - btnH * 0.5f, w - dp(18f), cy + btnH * 0.5f)
        drawPillButton(canvas, btnARect, labelA, COL_GOLD, btnA)

        if (hasSecondaryButton()) {
            btnBRect.set(btnARect.left - dp(12f) - btnW, cy - btnH * 0.5f, btnARect.left - dp(12f), cy + btnH * 0.5f)
            drawPillButton(canvas, btnBRect, labelB, COL_CYAN, btnB)
        } else {
            btnBRect.setEmpty()
        }
    }

    private fun drawReadyOverlay(canvas: Canvas, w: Float, h: Float) {
        pFill.color = 0xCC0A0908.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val idx = (mode - 1).coerceIn(0, 5)
        val cw = min(w - dp(32f), dp(440f))
        val ch = dp(240f)
        val cx = w * 0.5f
        val cy = h * 0.5f
        rect.set(cx - cw * 0.5f, cy - ch * 0.5f, cx + cw * 0.5f, cy + ch * 0.5f)

        pFill.color = COL_PANEL
        canvas.drawRoundRect(rect, dp(16f), dp(16f), pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = gameAccents[idx]
        canvas.drawRoundRect(rect, dp(16f), dp(16f), pStroke)

        pText.textAlign = Paint.Align.CENTER
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = gameAccents[idx]
        pText.textSize = dp(22f)
        canvas.drawText(gameTitles[idx], cx, rect.top + dp(40f), pText)

        pText.typeface = Typeface.DEFAULT
        pText.color = COL_IVORY
        pText.textSize = dp(13f)
        val guide = gameGuides[idx]
        val mid = guide.length / 2
        val splitIdx = guide.indexOf(' ', mid).let { if (it == -1) guide.length else it }
        canvas.drawText(guide.substring(0, splitIdx), cx, rect.top + dp(78f), pText)
        if (splitIdx < guide.length) {
            canvas.drawText(guide.substring(splitIdx + 1), cx, rect.top + dp(98f), pText)
        }

        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = COL_GOLD
        pText.textSize = dp(12.5f)
        canvas.drawText("Kesulitan: ${difficultyLabel()}   |   Rekor: ${getHighScore(mode)}", cx, rect.top + dp(134f), pText)

        val btnW = dp(156f)
        val btnH = dp(40f)
        val btnY = rect.bottom - dp(58f)
        readyStartRect.set(cx - btnW - dp(8f), btnY, cx - dp(8f), btnY + btnH)
        readyBackRect.set(cx + dp(8f), btnY, cx + btnW + dp(8f), btnY + btnH)

        drawPillButton(canvas, readyStartRect, "MULAI MAIN", COL_GREEN, readyChoice == 0)
        drawPillButton(canvas, readyBackRect, "KEMBALI", COL_MUTED, readyChoice == 1)
    }

    private fun drawDialogOverlay(canvas: Canvas, w: Float, h: Float) {
        pFill.color = 0xCC0A0908.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val cw = min(w - dp(40f), dp(360f))
        val items = getDialogOptions()
        val ch = dp(130f) + items.size * dp(48f)
        val cx = w * 0.5f
        val cy = h * 0.5f
        rect.set(cx - cw * 0.5f, cy - ch * 0.5f, cx + cw * 0.5f, cy + ch * 0.5f)

        pFill.color = COL_PANEL
        canvas.drawRoundRect(rect, dp(16f), dp(16f), pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = if (state == STATE_PAUSED) COL_GOLD else if (victory) COL_GREEN else COL_RED
        canvas.drawRoundRect(rect, dp(16f), dp(16f), pStroke)

        val title = when {
            state == STATE_PAUSED -> "PERMAINAN DIJEDA"
            victory -> "STAGE SELESAI!"
            else -> "GAME OVER"
        }
        pText.textAlign = Paint.Align.CENTER
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = pStroke.color
        pText.textSize = dp(20f)
        canvas.drawText(title, cx, rect.top + dp(38f), pText)

        pText.color = COL_IVORY
        pText.textSize = dp(13.5f)
        canvas.drawText("Skor Akhir: $score", cx, rect.top + dp(64f), pText)

        var y = rect.top + dp(86f)
        for (i in items.indices) {
            dialogItemRects[i].set(rect.left + dp(24f), y, rect.right - dp(24f), y + dp(38f))
            drawPillButton(canvas, dialogItemRects[i], items[i], COL_GOLD, i == dialogIndex)
            y += dp(46f)
        }
    }

    private fun getDialogOptions(): Array<String> {
        return when {
            state == STATE_PAUSED -> arrayOf("LANJUTKAN", "ULANGI STAGE", "MENU UTAMA")
            victory -> arrayOf("STAGE BERIKUTNYA", "ULANGI", "MENU UTAMA")
            else -> arrayOf("MAIN LAGI", "MENU UTAMA")
        }
    }

    private fun executeDialogChoice(idx: Int) {
        val opts = getDialogOptions()
        if (idx !in opts.indices) return
        when (opts[idx]) {
            "LANJUTKAN" -> {
                state = STATE_PLAYING
                audio.play(RetroAudioEngine.SFX_BOUNCE, 1.1f)
            }
            "STAGE BERIKUTNYA" -> startGame(mode, keepProgress = true)
            "ULANGI STAGE", "ULANGI", "MAIN LAGI" -> startGame(mode, keepProgress = false)
            "MENU UTAMA" -> {
                clearInputs()
                clearEffects()
                GameChiptuneEngine.stop()
                mode = MODE_HUB
                state = STATE_HUB
            }
        }
    }

    private fun selectHubCard(idx: Int) {
        hubIndex = idx
        openReadyScreen(idx + 1)
    }

    private fun hasSecondaryButton(): Boolean =
        mode == MODE_TETRIS || mode == MODE_ROCKET || mode == MODE_QUEST

    private fun handlePrimaryAction() {
        when (mode) {
            MODE_BOMBER -> placePlayerBomb()
            MODE_BRICK -> launchBrickBalls()
            MODE_TETRIS -> rotateTetrisPiece()
            MODE_ROCKET -> fireRocketLaser()
            MODE_QUEST -> questEngine.onJumpPressed()
        }
    }

    private fun handleSecondaryAction() {
        when (mode) {
            MODE_TETRIS -> hardDropTetris()
            MODE_ROCKET -> toggleRocketAutoFire()
            MODE_QUEST -> questEngine.onFirePressed()
        }
    }

    // =========================================================================
    // INPUT: KEYBOARD / REMOTE TV / BLUETOOTH GAMEPAD
    // =========================================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (state == STATE_HUB) {
            val cols = if (height > width) 1 else 3
            val maxCards = 6
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    hubIndex = (hubIndex - 1 + maxCards) % maxCards
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    hubIndex = (hubIndex + 1) % maxCards
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    hubIndex = (hubIndex - cols + maxCards) % maxCards
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    hubIndex = (hubIndex + cols) % maxCards
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    selectHubCard(hubIndex)
                    true
                }
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_MENU -> {
                    cycleDifficulty()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        if (state == STATE_READY) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    readyChoice = 1 - readyChoice
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.95f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (readyChoice == 0) startGame(mode, keepProgress = false)
                    else onBackAction()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        if (state == STATE_PAUSED || state == STATE_GAMEOVER) {
            val count = getDialogOptions().size
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    dialogIndex = (dialogIndex - 1 + count) % count
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.95f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dialogIndex = (dialogIndex + 1) % count
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.95f)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    executeDialogChoice(dialogIndex)
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> {
                inLeft = true
                if (mode == MODE_SNAKE && snDir != 1) snNextDir = 3
                if (mode == MODE_TETRIS) moveTetrisHorizontal(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> {
                inRight = true
                if (mode == MODE_SNAKE && snDir != 3) snNextDir = 1
                if (mode == MODE_TETRIS) moveTetrisHorizontal(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> {
                inUp = true
                if (mode == MODE_SNAKE && snDir != 2) snNextDir = 0
                if (mode == MODE_TETRIS) rotateTetrisPiece()
                if (mode == MODE_QUEST) questEngine.onJumpPressed()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> {
                inDown = true
                if (mode == MODE_SNAKE && snDir != 0) snNextDir = 2
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE -> {
                if (!btnA) {
                    btnA = true
                    handlePrimaryAction()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_SHIFT_LEFT -> {
                if (!btnB) {
                    btnB = true
                    handleSecondaryAction()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_P -> {
                onBackAction()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> { inLeft = false; true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> { inRight = false; true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> { inUp = false; true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> { inDown = false; true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE -> { btnA = false; true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_SHIFT_LEFT -> { btnB = false; true }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // =========================================================================
    // INPUT: MULTI-TOUCH SCREEN
    // =========================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val idx = event.actionIndex
        val x = event.getX(idx)
        val y = event.getY(idx)

        if (state == STATE_HUB) {
            if (action == MotionEvent.ACTION_UP) {
                if (exitRect.contains(x, y)) {
                    (context as? Activity)?.finish()
                    return true
                }
                if (muteRect.contains(x, y)) {
                    toggleMute()
                    return true
                }
                if (diffRect.contains(x, y)) {
                    cycleDifficulty()
                    return true
                }
                for (i in 0..5) {
                    if (hubCardRects[i].contains(x, y)) {
                        selectHubCard(i)
                        return true
                    }
                }
            }
            return true
        }

        if (state == STATE_READY) {
            if (action == MotionEvent.ACTION_UP) {
                if (readyStartRect.contains(x, y)) startGame(mode, keepProgress = false)
                else if (readyBackRect.contains(x, y)) onBackAction()
            }
            return true
        }

        if (state == STATE_PAUSED || state == STATE_GAMEOVER) {
            if (action == MotionEvent.ACTION_UP) {
                val opts = getDialogOptions()
                for (i in opts.indices) {
                    if (dialogItemRects[i].contains(x, y)) {
                        executeDialogChoice(i)
                        return true
                    }
                }
            }
            return true
        }

        if (action == MotionEvent.ACTION_DOWN) {
            if (hudPauseRect.contains(x, y)) {
                onBackAction()
                return true
            }
            if (muteRect.contains(x, y)) {
                toggleMute()
                return true
            }
        }

        var newLeft = false
        var newRight = false
        var newUp = false
        var newDown = false
        var newA = false
        var newB = false
        var newBoardHold = false

        val deckTop = getControlDeckTop(height.toFloat())
        val count = event.pointerCount
        for (p in 0 until count) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) && p == idx) {
                continue
            }
            val px = event.getX(p)
            val py = event.getY(p)

            if (showTouchOverlay && py >= deckTop) {
                if (btnARect.contains(px, py)) {
                    newA = true
                } else if (!btnBRect.isEmpty && btnBRect.contains(px, py)) {
                    newB = true
                } else {
                    val dx = px - dpadCenter[0]
                    val dy = py - dpadCenter[1]
                    if (dx * dx + dy * dy <= (dpadRadius * 1.45f) * (dpadRadius * 1.45f)) {
                        if (abs(dx) > abs(dy)) {
                            if (dx < -dp(8f)) newLeft = true
                            else if (dx > dp(8f)) newRight = true
                        } else {
                            if (dy < -dp(8f)) newUp = true
                            else if (dy > dp(8f)) newDown = true
                        }
                    }
                }
            } else if (py > dp(46f) && py < deckTop) {
                newBoardHold = true
                if (mode == MODE_BRICK) {
                    brPaddleX = (px / width.toFloat()).coerceIn(0.1f, 0.9f)
                } else if (mode == MODE_ROCKET) {
                    rkX = (px / width.toFloat()).coerceIn(0.08f, 0.92f)
                }
            }
        }

        if (newLeft && !inLeft) {
            if (mode == MODE_SNAKE && snDir != 1) snNextDir = 3
            if (mode == MODE_TETRIS) moveTetrisHorizontal(-1)
        }
        if (newRight && !inRight) {
            if (mode == MODE_SNAKE && snDir != 3) snNextDir = 1
            if (mode == MODE_TETRIS) moveTetrisHorizontal(1)
        }
        if (newUp && !inUp) {
            if (mode == MODE_SNAKE && snDir != 2) snNextDir = 0
            if (mode == MODE_TETRIS) rotateTetrisPiece()
            if (mode == MODE_QUEST) questEngine.onJumpPressed()
        }
        if (newDown && !inDown) {
            if (mode == MODE_SNAKE && snDir != 0) snNextDir = 2
        }
        if (newA && !btnA) handlePrimaryAction()
        if (newB && !btnB) handleSecondaryAction()
        if (newBoardHold && !boardHolding) {
            if (mode == MODE_BRICK) launchBrickBalls()
            else if (mode == MODE_QUEST) questEngine.onJumpPressed()
        }

        inLeft = newLeft
        inRight = newRight
        inUp = newUp
        inDown = newDown
        btnA = newA
        btnB = newB
        boardHolding = newBoardHold
        return true
    }

    // =========================================================================
    // GAME 1: BOMBER ARENA (CHARACTER SPRITES, LIT BOMBS, CRATES & STEEL WALLS)
    // =========================================================================
    private fun bomberScreenPos(colF: Float, rowF: Float): Pair<Float, Float> {
        val w = width.toFloat().coerceAtLeast(320f)
        val h = height.toFloat().coerceAtLeast(240f)
        val topHud = dp(52f)
        val deckTop = getControlDeckTop(h)
        val cell = min((w - dp(24f)) / bmCols, (deckTop - topHud - dp(12f)) / bmRows)
        val ox = (w - cell * bmCols) * 0.5f
        val oy = topHud + ((deckTop - topHud) - cell * bmRows) * 0.5f
        return Pair(ox + colF * cell, oy + rowF * cell)
    }

    private fun initBomber() {
        bmMap.fill(0)
        bmPower.fill(0)
        bmFireTimer.fill(0f)
        bmbActive.fill(false)

        for (r in 0 until bmRows) {
            for (c in 0 until bmCols) {
                val idx = r * bmCols + c
                if (r == 0 || r == bmRows - 1 || c == 0 || c == bmCols - 1 || (r % 2 == 0 && c % 2 == 0)) {
                    bmMap[idx] = 1
                } else {
                    val safeZone = (r <= 2 && c <= 2) ||
                        (r <= 2 && c >= bmCols - 3) ||
                        (r >= bmRows - 3 && c <= 2) ||
                        (r >= bmRows - 3 && c >= bmCols - 3)
                    if (!safeZone && Random.nextFloat() < 0.62f) {
                        bmMap[idx] = 2
                        if (Random.nextFloat() < 0.28f) {
                            bmPower[idx] = 1 + Random.nextInt(3)
                        }
                    }
                }
            }
        }

        bmPx = 1.5f
        bmPy = 1.5f
        bmLives = if (difficulty == DIFF_HARD) 2 else 3
        bmMaxBombs = 1
        bmFlameRange = 2
        bmSpeedMult = 1f
        bmInvuln = 1.5f

        val spawns = arrayOf(
            floatArrayOf(bmCols - 1.5f, 1.5f),
            floatArrayOf(1.5f, bmRows - 1.5f),
            floatArrayOf(bmCols - 1.5f, bmRows - 1.5f)
        )
        for (i in 0 until maxBots) {
            botAlive[i] = true
            botX[i] = spawns[i][0]
            botY[i] = spawns[i][1]
            botDir[i] = Random.nextInt(4)
            botBombCd[i] = 1.5f + i * 0.6f
        }
    }

    private fun placePlayerBomb() {
        var activeOwned = 0
        val pc = bmPx.toInt()
        val pr = bmPy.toInt()
        for (i in 0 until maxBombs) {
            if (bmbActive[i]) {
                if (bmbOwner[i] == 0) activeOwned++
                if (bmbCol[i] == pc && bmbRow[i] == pr) return
            }
        }
        if (activeOwned >= bmMaxBombs) return
        spawnBomb(0, pc, pr, bmFlameRange)
        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.85f)
    }

    private fun spawnBomb(owner: Int, c: Int, r: Int, range: Int) {
        for (i in 0 until maxBombs) {
            if (!bmbActive[i]) {
                bmbActive[i] = true
                bmbOwner[i] = owner
                bmbCol[i] = c
                bmbRow[i] = r
                bmbRange[i] = range
                bmbTimer[i] = 2.2f
                return
            }
        }
    }

    private fun updateBomber(dt: Float) {
        if (bmInvuln > 0f) bmInvuln = max(0f, bmInvuln - dt)
        for (i in bmFireTimer.indices) {
            if (bmFireTimer[i] > 0f) bmFireTimer[i] = max(0f, bmFireTimer[i] - dt)
        }

        var dx = 0f
        var dy = 0f
        if (inLeft) dx -= 1f
        if (inRight) dx += 1f
        if (inUp) dy -= 1f
        if (inDown) dy += 1f
        if (dx != 0f || dy != 0f) {
            val spd = 3.6f * bmSpeedMult
            val nx = bmPx + dx * spd * dt
            val ny = bmPy + dy * spd * dt
            if (canWalkBomber(nx, bmPy)) bmPx = nx
            if (canWalkBomber(bmPx, ny)) bmPy = ny
        }

        val pIdx = bmPy.toInt() * bmCols + bmPx.toInt()
        if (pIdx in bmPower.indices && bmMap[pIdx] == 0 && bmPower[pIdx] > 0) {
            val pw = bmPower[pIdx]
            bmPower[pIdx] = 0
            val (sx, sy) = bomberScreenPos(bmPx, bmPy)
            val label = when (pw) {
                1 -> { bmFlameRange = min(5, bmFlameRange + 1); "API +1!" }
                2 -> { bmMaxBombs = min(4, bmMaxBombs + 1); "BOM +1!" }
                else -> { bmSpeedMult = min(1.45f, bmSpeedMult + 0.15f); "CEPAT!" }
            }
            addPoints(80)
            audio.play(RetroAudioEngine.SFX_POWERUP)
            spawnShockwave(sx, sy, 44f, COL_CYAN)
            spawnParticles(sx, sy, 10, COL_CYAN, dp(25f), dp(110f), 3.5f)
            spawnFloatText(sx, sy, label, COL_CYAN)
        }

        for (i in 0 until maxBombs) {
            if (!bmbActive[i]) continue
            bmbTimer[i] -= dt
            if (bmbTimer[i] <= 0f) {
                bmbActive[i] = false
                detonateBomb(bmbCol[i], bmbRow[i], bmbRange[i])
            }
        }

        var aliveCount = 0
        for (i in 0 until maxBots) {
            if (!botAlive[i]) continue
            aliveCount++
            val bSpd = 2.1f + difficulty * 0.45f
            val dirs = arrayOf(intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0))
            val d = dirs[botDir[i]]
            val nx = botX[i] + d[0] * bSpd * dt
            val ny = botY[i] + d[1] * bSpd * dt
            if (canWalkBomber(nx, ny)) {
                botX[i] = nx
                botY[i] = ny
            } else {
                botDir[i] = Random.nextInt(4)
            }

            botBombCd[i] -= dt
            if (botBombCd[i] <= 0f) {
                botBombCd[i] = max(2.2f, 4.2f - difficulty * 0.7f)
                spawnBomb(i + 1, botX[i].toInt(), botY[i].toInt(), 2)
            }

            val bCell = botY[i].toInt() * bmCols + botX[i].toInt()
            if (bCell in bmFireTimer.indices && bmFireTimer[bCell] > 0f) {
                botAlive[i] = false
                aliveCount--
                addPoints(250)
                triggerHitStop(0.04f)
                val (bx, by) = bomberScreenPos(botX[i], botY[i])
                val pitch = registerCombo(bx, by)
                audio.play(RetroAudioEngine.SFX_EXPLODE, pitch)
                spawnShockwave(bx, by, 58f, COL_PURPLE)
                spawnParticles(bx, by, 20, COL_PURPLE, dp(35f), dp(160f), 4.5f)
                spawnFloatText(bx, by, "+250!", COL_GOLD)
            }
        }

        if (aliveCount <= 0) {
            victory = true
            stage++
            addPoints(500)
            audio.play(RetroAudioEngine.SFX_WIN)
            state = STATE_GAMEOVER
            dialogIndex = 0
            return
        }

        if (bmInvuln <= 0f && pIdx in bmFireTimer.indices && bmFireTimer[pIdx] > 0f) {
            bmLives--
            bmInvuln = 2.0f
            triggerHitStop(0.045f)
            triggerShake(0.22f, 6f)
            audio.play(RetroAudioEngine.SFX_EXPLODE, 0.85f)
            val (pxScreen, pyScreen) = bomberScreenPos(bmPx, bmPy)
            spawnShockwave(pxScreen, pyScreen, 52f, COL_RED)
            spawnParticles(pxScreen, pyScreen, 16, COL_RED, dp(30f), dp(140f), 4f)
            if (bmLives <= 0) {
                victory = false
                audio.play(RetroAudioEngine.SFX_OVER)
                state = STATE_GAMEOVER
                dialogIndex = 0
            }
        }
    }

    private fun canWalkBomber(x: Float, y: Float): Boolean {
        val r = 0.28f
        val corners = arrayOf(
            floatArrayOf(x - r, y - r),
            floatArrayOf(x + r, y - r),
            floatArrayOf(x - r, y + r),
            floatArrayOf(x + r, y + r)
        )
        for (pt in corners) {
            val c = pt[0].toInt()
            val row = pt[1].toInt()
            if (c !in 0 until bmCols || row !in 0 until bmRows) return false
            if (bmMap[row * bmCols + c] != 0) return false
        }
        return true
    }

    private fun detonateBomb(c: Int, r: Int, range: Int) {
        triggerShake(0.16f, 5f)
        audio.play(RetroAudioEngine.SFX_EXPLODE)
        val (bx, by) = bomberScreenPos(c + 0.5f, r + 0.5f)
        spawnShockwave(bx, by, 54f + range * 10f, COL_ORANGE)
        spawnParticles(bx, by, 18, COL_ORANGE, dp(35f), dp(155f), 4f)

        bmFireTimer[r * bmCols + c] = 0.45f
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        for (d in dirs) {
            for (step in 1..range) {
                val nc = c + d[0] * step
                val nr = r + d[1] * step
                if (nc !in 0 until bmCols || nr !in 0 until bmRows) break
                val idx = nr * bmCols + nc
                if (bmMap[idx] == 1) break
                bmFireTimer[idx] = 0.45f
                if (bmMap[idx] == 2) {
                    bmMap[idx] = 0
                    addPoints(20)
                    val (wx, wy) = bomberScreenPos(nc + 0.5f, nr + 0.5f)
                    spawnShatterShards(wx, wy, 0xFF8C5332.toInt(), 7f, 7f)
                    registerCombo(wx, wy)
                    break
                }
            }
        }
    }

    private fun drawBomber(canvas: Canvas, w: Float, h: Float) {
        val topHud = dp(52f)
        val deckTop = getControlDeckTop(h)
        val availW = w - dp(24f)
        val availH = deckTop - topHud - dp(12f)
        val cell = min(availW / bmCols, availH / bmRows)
        val ox = (w - cell * bmCols) * 0.5f
        val oy = topHud + (availH - cell * bmRows) * 0.5f

        for (r in 0 until bmRows) {
            for (c in 0 until bmCols) {
                val idx = r * bmCols + c
                val x0 = ox + c * cell
                val y0 = oy + r * cell
                rect.set(x0 + 1f, y0 + 1f, x0 + cell - 1f, y0 + cell - 1f)

                when (bmMap[idx]) {
                    1 -> {
                        // Riveted Steel-Stone Block
                        pFill.color = 0xFF3E3832.toInt()
                        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
                        pFill.color = 0x28FFFFFF
                        canvas.drawRect(rect.left + dp(2f), rect.top + dp(2f), rect.right - dp(2f), rect.top + dp(5f), pFill)
                        pFill.color = 0x33000000
                        canvas.drawRect(rect.left + dp(2f), rect.bottom - dp(5f), rect.right - dp(2f), rect.bottom - dp(2f), pFill)
                        pFill.color = 0x779C9386
                        val rOff = dp(5f)
                        canvas.drawCircle(rect.left + rOff, rect.top + rOff, dp(1.5f), pFill)
                        canvas.drawCircle(rect.right - rOff, rect.top + rOff, dp(1.5f), pFill)
                        canvas.drawCircle(rect.left + rOff, rect.bottom - rOff, dp(1.5f), pFill)
                        canvas.drawCircle(rect.right - rOff, rect.bottom - rOff, dp(1.5f), pFill)
                    }
                    2 -> {
                        // Cross-braced Wooden Crate
                        pFill.color = 0xFF8C5332.toInt()
                        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
                        pStroke.strokeWidth = dp(1.5f)
                        pStroke.color = 0xFF542D17.toInt()
                        canvas.drawRoundRect(rect, dp(4f), dp(4f), pStroke)
                        canvas.drawLine(rect.left + dp(3f), rect.top + dp(3f), rect.right - dp(3f), rect.bottom - dp(3f), pStroke)
                        canvas.drawLine(rect.left + dp(3f), rect.bottom - dp(3f), rect.right - dp(3f), rect.top + dp(3f), pStroke)
                    }
                    else -> {
                        pFill.color = if (((r + c) and 1) == 0) 0xFF191715.toInt() else 0xFF1D1A18.toInt()
                        canvas.drawRect(rect, pFill)
                        if (bmPower[idx] > 0) {
                            val pCol = when (bmPower[idx]) {
                                1 -> COL_ORANGE
                                2 -> COL_CYAN
                                else -> COL_GREEN
                            }
                            val cx = rect.centerX()
                            val cy = rect.centerY()
                            pFill.color = (pCol and 0x00FFFFFF) or 0x33000000
                            canvas.drawCircle(cx, cy, cell * 0.36f, pFill)
                            pFill.color = pCol
                            canvas.drawCircle(cx, cy, cell * 0.25f, pFill)
                            pText.textAlign = Paint.Align.CENTER
                            pText.typeface = Typeface.DEFAULT_BOLD
                            pText.textSize = cell * 0.28f
                            pText.color = COL_BG_TOP
                            val tag = when (bmPower[idx]) { 1 -> "F"; 2 -> "B"; else -> "S" }
                            canvas.drawText(tag, cx, cy + cell * 0.10f, pText)
                        }
                    }
                }

                if (bmFireTimer[idx] > 0f) {
                    pFill.color = 0xDDFF7043.toInt()
                    canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
                    pFill.color = 0xCCFFF59D.toInt()
                    canvas.drawCircle(rect.centerX(), rect.centerY(), cell * 0.28f, pFill)
                }
            }
        }

        // Iron Bombs with Burning Fuse & Spark
        for (i in 0 until maxBombs) {
            if (!bmbActive[i]) continue
            val cx = ox + (bmbCol[i] + 0.5f) * cell
            val cy = oy + (bmbRow[i] + 0.5f) * cell
            drawBomberBomb(canvas, cx, cy, cell * 0.33f, bmbTimer[i])
        }

        // Enemy Mecha-Bombers
        for (i in 0 until maxBots) {
            if (!botAlive[i]) continue
            val cx = ox + botX[i] * cell
            val cy = oy + botY[i] * cell
            drawBomberCharacter(
                canvas, cx, cy, cell * 0.38f,
                isPlayer = false,
                alert = botBombCd[i] < 0.45f,
                walkPhase = animTime * 10f + i
            )
        }

        // Player Bomber Hero
        if (bmInvuln <= 0f || ((animTime * 16f).toInt() and 1) == 0) {
            val moving = inLeft || inRight || inUp || inDown
            drawBomberCharacter(
                canvas, ox + bmPx * cell, oy + bmPy * cell, cell * 0.38f,
                isPlayer = true,
                alert = false,
                walkPhase = if (moving) animTime * 14f else 0f
            )
        }

        if (bmLives == 1) {
            val alpha = ((0.4f + 0.6f * abs(sin(animTime * 5.5f))) * 100).toInt()
            pStroke.strokeWidth = dp(8f)
            pStroke.color = (alpha shl 24) or 0x00E05252
            canvas.drawRect(ox, oy, ox + cell * bmCols, oy + cell * bmRows, pStroke)
        }

        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = COL_RED
        pText.textSize = dp(12f)
        canvas.drawText("NYAWA: ${"♥".repeat(bmLives.coerceAtLeast(0))}", ox, oy - dp(4f), pText)
    }

    private fun drawBomberBomb(canvas: Canvas, cx: Float, cy: Float, radius: Float, timer: Float) {
        val urgent = timer < 0.75f
        val freq = if (urgent) 28f else 14f
        val pulse = 1f + sin(animTime * freq) * (if (urgent) 0.16f else 0.08f)
        val r = radius * pulse

        // Shadow
        pFill.color = 0x55000000
        rect.set(cx - r * 0.9f, cy + r * 0.65f, cx + r * 0.9f, cy + r * 1.15f)
        canvas.drawOval(rect, pFill)

        // Iron sphere
        pFill.color = if (urgent && ((animTime * 20f).toInt() and 1) == 0) COL_RED else 0xFF23232A.toInt()
        canvas.drawCircle(cx, cy + r * 0.08f, r, pFill)
        pStroke.strokeWidth = dp(1.2f)
        pStroke.color = 0xFF555562.toInt()
        canvas.drawCircle(cx, cy + r * 0.08f, r, pStroke)

        // Glossy reflection
        pFill.color = 0x55FFFFFF
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.25f, r * 0.24f, pFill)

        // Metallic fuse cap & cord
        pFill.color = 0xFF7A7A88.toInt()
        rect.set(cx - r * 0.24f, cy - r * 1.05f, cx + r * 0.24f, cy - r * 0.80f)
        canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)

        pStroke.strokeWidth = dp(1.8f)
        pStroke.color = COL_GOLD
        path.reset()
        path.moveTo(cx, cy - r * 1.05f)
        path.quadTo(cx + r * 0.35f, cy - r * 1.38f, cx + r * 0.55f, cy - r * 1.25f)
        canvas.drawPath(path, pStroke)

        // Flickering fuse spark
        val sparkR = r * (0.24f + abs(sin(animTime * 32f)) * 0.16f)
        pFill.color = COL_ORANGE
        canvas.drawCircle(cx + r * 0.55f, cy - r * 1.25f, sparkR, pFill)
        pFill.color = Color.WHITE
        canvas.drawCircle(cx + r * 0.55f, cy - r * 1.25f, sparkR * 0.5f, pFill)
    }

    private fun drawBomberCharacter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        isPlayer: Boolean,
        alert: Boolean,
        walkPhase: Float
    ) {
        val bob = abs(sin(walkPhase)) * r * 0.14f
        val footSwing = sin(walkPhase) * r * 0.22f

        // Ground shadow
        pFill.color = 0x55000000
        rect.set(cx - r * 0.85f, cy + r * 0.72f, cx + r * 0.85f, cy + r * 1.12f)
        canvas.drawOval(rect, pFill)

        // Feet
        pFill.color = if (isPlayer) COL_ORANGE else 0xFF2B1B3D.toInt()
        canvas.drawCircle(cx - r * 0.36f, cy + r * 0.78f + footSwing, r * 0.24f, pFill)
        canvas.drawCircle(cx + r * 0.36f, cy + r * 0.78f - footSwing, r * 0.24f, pFill)

        // Torso
        pFill.color = if (isPlayer) 0xFF2B5B84.toInt() else 0xFF4A2574.toInt()
        rect.set(cx - r * 0.62f, cy + r * 0.05f - bob, cx + r * 0.62f, cy + r * 0.72f - bob)
        canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, pFill)

        // Helmet Head
        val headY = cy - r * 0.24f - bob
        pFill.color = if (isPlayer) COL_IVORY else if (alert && ((animTime * 24f).toInt() and 1) == 0) COL_ORANGE else COL_PURPLE
        canvas.drawCircle(cx, headY, r * 0.76f, pFill)

        // Visor Faceplate
        pFill.color = 0xFF1A1614.toInt()
        rect.set(cx - r * 0.54f, headY - r * 0.24f, cx + r * 0.54f, headY + r * 0.28f)
        canvas.drawRoundRect(rect, r * 0.24f, r * 0.24f, pFill)

        // Glowing Eyes
        pFill.color = if (isPlayer) COL_CYAN else COL_RED
        canvas.drawCircle(cx - r * 0.22f, headY + r * 0.02f, r * 0.13f, pFill)
        canvas.drawCircle(cx + r * 0.22f, headY + r * 0.02f, r * 0.13f, pFill)

        // Antenna / Horn Crest
        if (isPlayer) {
            pStroke.strokeWidth = dp(1.6f)
            pStroke.color = COL_GOLD
            canvas.drawLine(cx, headY - r * 0.74f, cx, headY - r * 1.12f, pStroke)
            pFill.color = COL_RED
            canvas.drawCircle(cx, headY - r * 1.16f, r * 0.18f, pFill)
        } else {
            pFill.color = COL_GOLD
            path.reset()
            path.moveTo(cx - r * 0.55f, headY - r * 0.45f)
            path.lineTo(cx - r * 0.78f, headY - r * 1.02f)
            path.lineTo(cx - r * 0.25f, headY - r * 0.68f)
            path.close()
            canvas.drawPath(path, pFill)

            path.reset()
            path.moveTo(cx + r * 0.55f, headY - r * 0.45f)
            path.lineTo(cx + r * 0.78f, headY - r * 1.02f)
            path.lineTo(cx + r * 0.25f, headY - r * 0.68f)
            path.close()
            canvas.drawPath(path, pFill)
        }
    }

    // =========================================================================
    // GAME 2: NEON BRICK (3D BEVELED BRICKS, ENERGY PADDLE & LABELED CAPSULES)
    // =========================================================================
    private fun initBrick() {
        brPaddleX = 0.5f
        brPaddleW = 0.22f
        brPaddleRecoil = 0f
        brWideTimer = 0f
        brLives = 3
        blActive.fill(false)
        cpActive.fill(false)
        brFlash.fill(0f)

        blActive[0] = true
        blStuck[0] = true
        blX[0] = 0.5f
        blY[0] = 0.84f
        blVx[0] = 0.36f
        blVy[0] = -0.58f

        val rowCols = intArrayOf(COL_RED, COL_ORANGE, COL_GOLD, COL_GREEN, COL_CYAN)
        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                val idx = r * brCols + c
                brHp[idx] = if (r == 0 && difficulty >= DIFF_NORMAL) 2 else 1
                brColor[idx] = rowCols[r % rowCols.size]
            }
        }
    }

    private fun launchBrickBalls() {
        for (i in 0 until maxBalls) {
            if (blActive[i] && blStuck[i]) {
                blStuck[i] = false
                audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
            }
        }
    }

    private fun updateBrick(dt: Float) {
        if (brPaddleRecoil > 0f) brPaddleRecoil = max(0f, brPaddleRecoil - dt * 6.5f)
        for (i in brFlash.indices) {
            if (brFlash[i] > 0f) brFlash[i] = max(0f, brFlash[i] - dt)
        }

        if (brWideTimer > 0f) {
            brWideTimer = max(0f, brWideTimer - dt)
            brPaddleW = if (brWideTimer > 0f) 0.32f else 0.22f
        }

        val pSpd = 0.85f
        if (inLeft) brPaddleX = max(brPaddleW * 0.5f, brPaddleX - pSpd * dt)
        if (inRight) brPaddleX = min(1f - brPaddleW * 0.5f, brPaddleX + pSpd * dt)

        val w = width.toFloat().coerceAtLeast(320f)
        val h = height.toFloat().coerceAtLeast(240f)
        val topY = dp(52f)
        val areaH = getControlDeckTop(h) - dp(12f) - topY
        val padX = dp(16f)
        val areaW = w - padX * 2f

        for (i in 0 until maxCaps) {
            if (!cpActive[i]) continue
            cpY[i] += 0.35f * dt
            if (cpY[i] > 1.05f) {
                cpActive[i] = false
            } else if (abs(cpY[i] - 0.88f) < 0.04f && abs(cpX[i] - brPaddleX) < brPaddleW * 0.55f) {
                val t = cpType[i]
                cpActive[i] = false
                audio.play(RetroAudioEngine.SFX_POWERUP)
                addPoints(100)
                val sx = padX + brPaddleX * areaW
                val sy = topY + 0.88f * areaH
                spawnShockwave(sx, sy, 48f, COL_GOLD)
                val msg = when (t) {
                    0 -> { brWideTimer = 10f; brPaddleW = 0.32f; "PADDLE LEBAR!" }
                    1 -> { spawnExtraBrickBalls(); "MULTI BOLA!" }
                    else -> { brLives = min(5, brLives + 1); "+1 BOLA!" }
                }
                spawnFloatText(sx, sy - dp(12f), msg, COL_GOLD)
            }
        }

        var activeBalls = 0
        for (i in 0 until maxBalls) {
            if (!blActive[i]) continue
            activeBalls++
            if (blStuck[i]) {
                blX[i] = brPaddleX
                blY[i] = 0.84f
                continue
            }

            spawnTrailNode(padX + blX[i] * areaW, topY + blY[i] * areaH, 6.5f, COL_CYAN)

            val spdMult = 1f + difficulty * 0.16f
            blX[i] += blVx[i] * spdMult * dt
            blY[i] += blVy[i] * spdMult * dt

            if (blX[i] < 0.02f) { blX[i] = 0.02f; blVx[i] = abs(blVx[i]); audio.play(RetroAudioEngine.SFX_BOUNCE) }
            if (blX[i] > 0.98f) { blX[i] = 0.98f; blVx[i] = -abs(blVx[i]); audio.play(RetroAudioEngine.SFX_BOUNCE) }
            if (blY[i] < 0.04f) { blY[i] = 0.04f; blVy[i] = abs(blVy[i]); audio.play(RetroAudioEngine.SFX_BOUNCE) }

            if (blVy[i] > 0f && blY[i] >= 0.86f && blY[i] <= 0.91f && abs(blX[i] - brPaddleX) <= brPaddleW * 0.54f) {
                val hitOffset = ((blX[i] - brPaddleX) / (brPaddleW * 0.5f)).coerceIn(-1f, 1f)
                blVx[i] = hitOffset * 0.52f
                blVy[i] = -abs(blVy[i])
                brPaddleRecoil = 1f
                audio.play(RetroAudioEngine.SFX_BOUNCE, 1.1f)
                val sx = padX + blX[i] * areaW
                val sy = topY + 0.87f * areaH
                spawnParticles(sx, sy, 6, COL_GOLD, dp(20f), dp(80f), 2.8f)
            }

            val brickTop = 0.10f
            val brickH = 0.055f
            val brickW = 1f / brCols
            val col = (blX[i] / brickW).toInt()
            val row = ((blY[i] - brickTop) / brickH).toInt()
            if (col in 0 until brCols && row in 0 until brRows) {
                val idx = row * brCols + col
                if (brHp[idx] > 0) {
                    brHp[idx]--
                    blVy[i] = -blVy[i]
                    addPoints(40)
                    val bx = padX + (col + 0.5f) * (areaW / brCols)
                    val by = topY + (brickTop + (row + 0.5f) * brickH) * areaH
                    if (brHp[idx] <= 0) {
                        val bCol = brColor[idx]
                        val pitch = registerCombo(bx, by)
                        audio.play(RetroAudioEngine.SFX_COIN, pitch)
                        triggerArenaGlow(bCol)
                        spawnShockwave(bx, by, 34f, bCol)
                        spawnShatterShards(bx, by, bCol, 10f, 5f)
                        if (Random.nextFloat() < 0.22f) {
                            spawnBrickCapsule((col + 0.5f) * brickW, brickTop + (row + 0.5f) * brickH)
                        }
                    } else {
                        brFlash[idx] = 0.14f
                        audio.play(RetroAudioEngine.SFX_BOUNCE, 1.35f)
                        spawnParticles(bx, by, 5, Color.WHITE, dp(20f), dp(75f), 2.5f)
                    }
                }
            }

            if (blY[i] > 1.03f) {
                blActive[i] = false
                activeBalls--
            }
        }

        if (activeBalls <= 0) {
            brLives--
            triggerHitStop(0.04f)
            triggerShake(0.2f, 5f)
            if (brLives <= 0) {
                victory = false
                audio.play(RetroAudioEngine.SFX_OVER)
                state = STATE_GAMEOVER
                dialogIndex = 0
                return
            }
            audio.play(RetroAudioEngine.SFX_EXPLODE, 0.9f)
            blActive[0] = true
            blStuck[0] = true
            blX[0] = brPaddleX
            blY[0] = 0.84f
            blVx[0] = 0.36f
            blVy[0] = -0.58f
        }

        if (brHp.all { it <= 0 }) {
            victory = true
            stage++
            addPoints(400)
            audio.play(RetroAudioEngine.SFX_WIN)
            state = STATE_GAMEOVER
            dialogIndex = 0
        }
    }

    private fun spawnBrickCapsule(x: Float, y: Float) {
        for (i in 0 until maxCaps) {
            if (!cpActive[i]) {
                cpActive[i] = true
                cpType[i] = Random.nextInt(3)
                cpX[i] = x
                cpY[i] = y
                return
            }
        }
    }

    private fun spawnExtraBrickBalls() {
        val src = blActive.indexOfFirst { it }.let { if (it < 0) 0 else it }
        for (i in 0 until maxBalls) {
            if (!blActive[i]) {
                blActive[i] = true
                blStuck[i] = false
                blX[i] = blX[src]
                blY[i] = blY[src]
                blVx[i] = if (i % 2 == 0) 0.42f else -0.42f
                blVy[i] = -0.56f
            }
        }
    }

    private fun drawBrick(canvas: Canvas, w: Float, h: Float) {
        val topY = dp(52f)
        val botY = getControlDeckTop(h) - dp(12f)
        val areaH = botY - topY
        val padX = dp(16f)
        val areaW = w - padX * 2f

        pStroke.strokeWidth = if (arenaGlowTimer > 0f) dp(3f) else dp(1.2f)
        pStroke.color = if (arenaGlowTimer > 0f) arenaGlowColor else 0x334CC3D9
        rect.set(padX, topY, padX + areaW, botY)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), pStroke)

        val brickTop = topY + areaH * 0.10f
        val brickH = areaH * 0.055f
        val brickW = areaW / brCols

        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                val idx = r * brCols + c
                if (brHp[idx] <= 0) continue
                val x0 = padX + c * brickW + dp(2f)
                val y0 = brickTop + r * brickH + dp(2f)
                val bw = brickW - dp(4f)
                val bh = brickH - dp(4f)
                rect.set(x0, y0, x0 + bw, y0 + bh)
                pFill.color = if (brFlash[idx] > 0f) Color.WHITE else brColor[idx]
                canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)

                // 3D glossy top highlight & bottom bevel shadow
                pFill.color = 0x44FFFFFF
                canvas.drawRect(x0 + dp(3f), y0 + dp(2f), x0 + bw - dp(3f), y0 + bh * 0.34f, pFill)
                pFill.color = 0x33000000
                canvas.drawRect(x0 + dp(3f), y0 + bh * 0.72f, x0 + bw - dp(3f), y0 + bh - dp(2f), pFill)

                if (brHp[idx] > 1) {
                    pStroke.strokeWidth = dp(2f)
                    pStroke.color = COL_IVORY
                    canvas.drawRoundRect(rect, dp(5f), dp(5f), pStroke)
                }
            }
        }

        // Futuristic Thruster Paddle
        val px = padX + brPaddleX * areaW
        val py = topY + areaH * 0.88f + sin(brPaddleRecoil * Math.PI.toFloat()) * dp(5f)
        val pw = brPaddleW * areaW
        val ph = dp(13f)

        // Twin bottom thruster glows
        val thrR = dp(4.5f) + sin(animTime * 36f) * dp(1.2f)
        pFill.color = 0xAA4CC3D9.toInt()
        canvas.drawCircle(px - pw * 0.28f, py + ph * 0.55f, thrR, pFill)
        canvas.drawCircle(px + pw * 0.28f, py + ph * 0.55f, thrR, pFill)

        // Main paddle hull
        rect.set(px - pw * 0.5f, py - ph * 0.5f, px + pw * 0.5f, py + ph * 0.5f)
        pFill.color = COL_GOLD
        canvas.drawRoundRect(rect, dp(6f), dp(6f), pFill)

        // Metallic side bumpers
        pFill.color = COL_RED
        rect.set(px - pw * 0.5f, py - ph * 0.5f, px - pw * 0.5f + dp(12f), py + ph * 0.5f)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
        rect.set(px + pw * 0.5f - dp(12f), py - ph * 0.5f, px + pw * 0.5f, py + ph * 0.5f)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)

        // Top energy reflector strip
        pFill.color = COL_CYAN
        canvas.drawRect(px - pw * 0.34f, py - ph * 0.5f + dp(1.5f), px + pw * 0.34f, py - ph * 0.5f + dp(4f), pFill)

        // Pill-shaped Power-Up Capsules with Icon Tag
        for (i in 0 until maxCaps) {
            if (!cpActive[i]) continue
            val cx = padX + cpX[i] * areaW
            val cy = topY + cpY[i] * areaH
            val capCol = when (cpType[i]) { 0 -> COL_GOLD; 1 -> COL_CYAN; else -> COL_GREEN }
            rect.set(cx - dp(13f), cy - dp(7f), cx + dp(13f), cy + dp(7f))
            pFill.color = capCol
            canvas.drawRoundRect(rect, dp(7f), dp(7f), pFill)
            pStroke.strokeWidth = dp(1.4f)
            pStroke.color = Color.WHITE
            canvas.drawRoundRect(rect, dp(7f), dp(7f), pStroke)
            pText.textAlign = Paint.Align.CENTER
            pText.typeface = Typeface.DEFAULT_BOLD
            pText.textSize = dp(9.5f)
            pText.color = COL_BG_TOP
            val label = when (cpType[i]) { 0 -> "L"; 1 -> "M"; else -> "+" }
            canvas.drawText(label, cx, cy + dp(3.5f), pText)
        }

        // Glowing Energy Balls
        for (i in 0 until maxBalls) {
            if (!blActive[i]) continue
            val bx = padX + blX[i] * areaW
            val by = topY + blY[i] * areaH
            pFill.color = 0x444CC3D9
            canvas.drawCircle(bx, by, dp(10.5f), pFill)
            pFill.color = Color.WHITE
            canvas.drawCircle(bx, by, dp(6.8f), pFill)
            pFill.color = COL_CYAN
            canvas.drawCircle(bx - dp(2f), by - dp(2f), dp(2.2f), pFill)
        }

        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = COL_CYAN
        pText.textSize = dp(12f)
        canvas.drawText("BOLA: ${"●".repeat(brLives.coerceAtLeast(0))}", padX + dp(8f), topY + dp(18f), pText)
    }

    // =========================================================================
    // GAME 3: GOLD SNAKE (SCALED SERPENT, FORKED TONGUE & FACETED GEMS)
    // =========================================================================
    private fun snakeScreenPos(col: Int, row: Int): Pair<Float, Float> {
        val w = width.toFloat().coerceAtLeast(320f)
        val h = height.toFloat().coerceAtLeast(240f)
        val topHud = dp(52f)
        val deckTop = getControlDeckTop(h)
        val cell = min((w - dp(24f)) / snCols, (deckTop - topHud - dp(12f)) / snRows)
        val ox = (w - cell * snCols) * 0.5f
        val oy = topHud + ((deckTop - topHud) - cell * snRows) * 0.5f
        return Pair(ox + (col + 0.5f) * cell, oy + (row + 0.5f) * cell)
    }

    private fun initSnake() {
        snLen = 4
        snDir = 1
        snNextDir = 1
        snMoveTimer = 0f
        snEatPulse = 0f
        for (i in 0 until snLen) {
            snBodyX[i] = 8 - i
            snBodyY[i] = 7
        }
        snBonusActive = false
        spawnSnakeFood()
    }

    private fun spawnSnakeFood() {
        for (attempt in 0..80) {
            val fx = Random.nextInt(snCols)
            val fy = Random.nextInt(snRows)
            var occupied = false
            for (i in 0 until snLen) {
                if (snBodyX[i] == fx && snBodyY[i] == fy) {
                    occupied = true
                    break
                }
            }
            if (!occupied) {
                snFoodX = fx
                snFoodY = fy
                return
            }
        }
    }

    private fun updateSnake(dt: Float) {
        if (snEatPulse > 0f) snEatPulse = max(0f, snEatPulse - dt * 2.5f)
        if (snBonusActive) {
            snBonusTimer -= dt
            if (snBonusTimer <= 0f) snBonusActive = false
        }

        val stepInterval = max(0.075f, (0.17f - difficulty * 0.03f) - snLen * 0.0015f)
        snMoveTimer += dt
        if (snMoveTimer < stepInterval) return
        snMoveTimer = 0f

        snDir = snNextDir
        var nx = snBodyX[0]
        var ny = snBodyY[0]
        when (snDir) {
            0 -> ny--
            1 -> nx++
            2 -> ny++
            3 -> nx--
        }

        if (nx !in 0 until snCols || ny !in 0 until snRows) {
            victory = false
            triggerShake(0.2f, 5f)
            audio.play(RetroAudioEngine.SFX_OVER)
            state = STATE_GAMEOVER
            dialogIndex = 0
            return
        }

        for (i in 0 until snLen) {
            if (snBodyX[i] == nx && snBodyY[i] == ny) {
                victory = false
                triggerShake(0.2f, 5f)
                audio.play(RetroAudioEngine.SFX_OVER)
                state = STATE_GAMEOVER
                dialogIndex = 0
                return
            }
        }

        for (i in snLen downTo 1) {
            snBodyX[i] = snBodyX[i - 1]
            snBodyY[i] = snBodyY[i - 1]
        }
        snBodyX[0] = nx
        snBodyY[0] = ny

        if (nx == snFoodX && ny == snFoodY) {
            snLen = min(snCols * snRows - 1, snLen + 1)
            snEatPulse = 1f
            addPoints(50)
            val (fx, fy) = snakeScreenPos(nx, ny)
            val pitch = registerCombo(fx, fy)
            audio.play(RetroAudioEngine.SFX_COIN, pitch)
            spawnShockwave(fx, fy, 38f, COL_GOLD)
            spawnParticles(fx, fy, 12, COL_GOLD, dp(25f), dp(110f), 3.4f)
            spawnFloatText(fx, fy, "+50", COL_GOLD)
            spawnSnakeFood()
            if (!snBonusActive && snLen % 5 == 0) {
                snBonusActive = true
                snBonusX = Random.nextInt(snCols)
                snBonusY = Random.nextInt(snRows)
                snBonusTimer = 6.5f
            }
        }

        if (snBonusActive && nx == snBonusX && ny == snBonusY) {
            snBonusActive = false
            snEatPulse = 1f
            addPoints(200)
            audio.play(RetroAudioEngine.SFX_POWERUP, 1.25f)
            val (bx, by) = snakeScreenPos(nx, ny)
            spawnShockwave(bx, by, 54f, COL_CYAN)
            spawnParticles(bx, by, 18, COL_CYAN, dp(30f), dp(135f), 4f)
            registerCombo(bx, by)
            spawnFloatText(bx, by, "+200!", COL_CYAN)
        }
    }

    private fun drawSnake(canvas: Canvas, w: Float, h: Float) {
        val topHud = dp(52f)
        val deckTop = getControlDeckTop(h)
        val cell = min((w - dp(24f)) / snCols, (deckTop - topHud - dp(12f)) / snRows)
        val ox = (w - cell * snCols) * 0.5f
        val oy = topHud + ((deckTop - topHud) - cell * snRows) * 0.5f

        pFill.color = 0xFF171513.toInt()
        canvas.drawRect(ox, oy, ox + cell * snCols, oy + cell * snRows, pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x55E6C88F
        canvas.drawRect(ox, oy, ox + cell * snCols, oy + cell * snRows, pStroke)

        // Faceted Gold Gemstone
        val gemPulse = 1f + sin(animTime * 8f) * 0.12f
        val fcx = ox + (snFoodX + 0.5f) * cell
        val fcy = oy + (snFoodY + 0.5f) * cell
        drawFacetedGem(canvas, fcx, fcy, cell * 0.42f * gemPulse, COL_GOLD, 0xFFFFF59D.toInt())

        // Faceted Cyan Diamond Bonus
        if (snBonusActive) {
            val bcx = ox + (snBonusX + 0.5f) * cell
            val bcy = oy + (snBonusY + 0.5f) * cell
            drawFacetedGem(canvas, bcx, bcy, cell * 0.48f * gemPulse, COL_CYAN, Color.WHITE)
        }

        // Tapered Serpent Body with Dorsal Scales
        for (i in snLen - 1 downTo 1) {
            val wavePos = (1f - snEatPulse) * snLen
            val waveBoost = if (snEatPulse > 0f) max(0f, 1f - abs(i - wavePos) * 0.45f) * dp(2.8f) else 0f
            val tailTaper = if (i > snLen - 4) (snLen - i) / 4f else 1f
            val baseRadius = cell * (0.34f + tailTaper * 0.10f) + waveBoost
            val scx = ox + (snBodyX[i] + 0.5f) * cell
            val scy = oy + (snBodyY[i] + 0.5f) * cell

            pFill.color = if (waveBoost > 0.5f) COL_GOLD else if ((i and 1) == 0) COL_GREEN else 0xFF3B9668.toInt()
            canvas.drawCircle(scx, scy, baseRadius, pFill)

            // Dorsal diamond scale marking
            pFill.color = if (waveBoost > 0.5f) Color.WHITE else 0x55E6C88F
            canvas.drawCircle(scx, scy, baseRadius * 0.42f, pFill)
        }

        // Sculpted Serpent Head + Forked Y-Tongue + Tracking Eyes
        val hcx = ox + (snBodyX[0] + 0.5f) * cell
        val hcy = oy + (snBodyY[0] + 0.5f) * cell
        val dirX = when (snDir) { 1 -> 1f; 3 -> -1f; else -> 0f }
        val dirY = when (snDir) { 2 -> 1f; 0 -> -1f; else -> 0f }

        // Forked Red Tongue
        if (sin(animTime * 8f) > 0.25f) {
            val tongueLen = cell * 0.34f
            val tx = hcx + dirX * (cell * 0.46f)
            val ty = hcy + dirY * (cell * 0.46f)
            val tipX = tx + dirX * tongueLen
            val tipY = ty + dirY * tongueLen
            pStroke.strokeWidth = dp(1.8f)
            pStroke.color = COL_RED
            canvas.drawLine(tx, ty, tipX, tipY, pStroke)
            val perpX = -dirY * dp(3f)
            val perpY = dirX * dp(3f)
            canvas.drawLine(tipX, tipY, tipX + dirX * dp(3.5f) + perpX, tipY + dirY * dp(3.5f) + perpY, pStroke)
            canvas.drawLine(tipX, tipY, tipX + dirX * dp(3.5f) - perpX, tipY + dirY * dp(3.5f) - perpY, pStroke)
        }

        // Head base & snout
        pFill.color = 0xFF56BD8B.toInt()
        canvas.drawCircle(hcx, hcy, cell * 0.48f, pFill)
        pFill.color = COL_GOLD
        canvas.drawCircle(hcx - dirX * cell * 0.08f, hcy - dirY * cell * 0.08f, cell * 0.22f, pFill)

        // Nostrils
        pFill.color = COL_BG_TOP
        val nBaseX = hcx + dirX * cell * 0.32f
        val nBaseY = hcy + dirY * cell * 0.32f
        canvas.drawCircle(nBaseX - dirY * cell * 0.10f, nBaseY + dirX * cell * 0.10f, cell * 0.04f, pFill)
        canvas.drawCircle(nBaseX + dirY * cell * 0.10f, nBaseY - dirX * cell * 0.10f, cell * 0.04f, pFill)

        // Gem-tracking eyes
        val targetX = if (snBonusActive) ox + (snBonusX + 0.5f) * cell else fcx
        val targetY = if (snBonusActive) oy + (snBonusY + 0.5f) * cell else fcy
        val lookAngle = atan2(targetY - hcy, targetX - hcx)
        val pupilOff = cell * 0.055f
        val eyeOff1X = if (dirX != 0f) dirX * cell * 0.12f else -cell * 0.20f
        val eyeOff1Y = if (dirY != 0f) dirY * cell * 0.12f else -cell * 0.20f
        val eyeOff2X = if (dirX != 0f) dirX * cell * 0.12f else cell * 0.20f
        val eyeOff2Y = if (dirY != 0f) dirY * cell * 0.12f else cell * 0.20f

        pFill.color = COL_IVORY
        canvas.drawCircle(hcx + eyeOff1X, hcy + eyeOff1Y, cell * 0.13f, pFill)
        canvas.drawCircle(hcx + eyeOff2X, hcy + eyeOff2Y, cell * 0.13f, pFill)
        pFill.color = COL_BG_TOP
        canvas.drawCircle(hcx + eyeOff1X + cos(lookAngle) * pupilOff, hcy + eyeOff1Y + sin(lookAngle) * pupilOff, cell * 0.065f, pFill)
        canvas.drawCircle(hcx + eyeOff2X + cos(lookAngle) * pupilOff, hcy + eyeOff2Y + sin(lookAngle) * pupilOff, cell * 0.065f, pFill)
    }

    private fun drawFacetedGem(canvas: Canvas, cx: Float, cy: Float, r: Float, mainCol: Int, glintCol: Int) {
        pFill.color = (mainCol and 0x00FFFFFF) or 0x33000000
        canvas.drawCircle(cx, cy, r * 1.25f, pFill)

        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.78f, cy - r * 0.35f)
        path.lineTo(cx + r * 0.78f, cy + r * 0.35f)
        path.lineTo(cx, cy + r)
        path.lineTo(cx - r * 0.78f, cy + r * 0.35f)
        path.lineTo(cx - r * 0.78f, cy - r * 0.35f)
        path.close()
        pFill.color = mainCol
        canvas.drawPath(path, pFill)

        // Inner bright crown facet
        path.reset()
        path.moveTo(cx, cy - r * 0.55f)
        path.lineTo(cx + r * 0.42f, cy - r * 0.18f)
        path.lineTo(cx, cy + r * 0.48f)
        path.lineTo(cx - r * 0.42f, cy - r * 0.18f)
        path.close()
        pFill.color = glintCol
        canvas.drawPath(path, pFill)
    }

    // =========================================================================
    // GAME 4: NEON TETRIS (3D BEVELED JEWEL BLOCKS & MULTI-ROW FLASH)
    // =========================================================================
    private fun initTetris() {
        ttBoard.fill(0)
        ttLinesTotal = 0
        ttBoardBounce = 0f
        ttClearFlashTimer = 0f
        ttClearFlashMask = 0
        ttCurType = Random.nextInt(7)
        ttNextType = Random.nextInt(7)
        spawnTetrisPiece()
    }

    private fun spawnTetrisPiece() {
        ttCurType = ttNextType
        ttNextType = Random.nextInt(7)
        ttRot = 0
        ttPx = 3
        ttPy = 0
        if (!isValidTetris(ttPx, ttPy, ttRot)) {
            victory = false
            audio.play(RetroAudioEngine.SFX_OVER)
            state = STATE_GAMEOVER
            dialogIndex = 0
        }
    }

    private fun getTetrominoCell(type: Int, rot: Int, idx: Int): Pair<Int, Int> {
        val base = tetrominoShapes[type]
        var x = base[idx * 2]
        var y = base[idx * 2 + 1]
        if (type == 1) return Pair(x, y)
        val size = if (type == 0) 4 else 3
        repeat(rot and 3) {
            val nx = size - 1 - y
            val ny = x
            x = nx
            y = ny
        }
        return Pair(x, y)
    }

    private fun isValidTetris(px: Int, py: Int, rot: Int): Boolean {
        for (i in 0..3) {
            val (dx, dy) = getTetrominoCell(ttCurType, rot, i)
            val cx = px + dx
            val cy = py + dy
            if (cx !in 0 until ttCols || cy !in 0 until ttRows) return false
            if (ttBoard[cy * ttCols + cx] != 0) return false
        }
        return true
    }

    private fun moveTetrisHorizontal(dir: Int) {
        if (isValidTetris(ttPx + dir, ttPy, ttRot)) {
            ttPx += dir
            audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
        }
    }

    private fun rotateTetrisPiece() {
        val nextRot = (ttRot + 1) and 3
        val kicks = intArrayOf(0, -1, 1, -2, 2)
        for (k in kicks) {
            if (isValidTetris(ttPx + k, ttPy, nextRot)) {
                ttPx += k
                ttRot = nextRot
                audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
                return
            }
        }
    }

    private fun hardDropTetris() {
        while (isValidTetris(ttPx, ttPy + 1, ttRot)) {
            ttPy++
        }
        ttBoardBounce = 1f
        lockTetrisPiece()
    }

    private fun lockTetrisPiece() {
        val pieceColor = tetrominoColors[ttCurType]
        for (i in 0..3) {
            val (dx, dy) = getTetrominoCell(ttCurType, ttRot, i)
            val cx = ttPx + dx
            val cy = ttPy + dy
            if (cx in 0 until ttCols && cy in 0 until ttRows) {
                ttBoard[cy * ttCols + cx] = ttCurType + 1
            }
        }
        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.8f)

        var cleared = 0
        var lastClearedRow = -1
        var mask = 0
        var r = ttRows - 1
        while (r >= 0) {
            var full = true
            for (c in 0 until ttCols) {
                if (ttBoard[r * ttCols + c] == 0) {
                    full = false
                    break
                }
            }
            if (full) {
                cleared++
                mask = mask or (1 shl r)
                if (lastClearedRow == -1) lastClearedRow = r
                for (yy in r downTo 1) {
                    for (c in 0 until ttCols) {
                        ttBoard[yy * ttCols + c] = ttBoard[(yy - 1) * ttCols + c]
                    }
                }
                for (c in 0 until ttCols) ttBoard[c] = 0
            } else {
                r--
            }
        }

        if (cleared > 0) {
            ttLinesTotal += cleared
            stage = 1 + ttLinesTotal / 10
            ttClearFlashMask = mask
            ttClearFlashTimer = 0.24f
            ttBoardBounce = 1f
            triggerArenaGlow(pieceColor)
            val pts = when (cleared) { 1 -> 100; 2 -> 300; 3 -> 500; else -> 800 }
            addPoints(pts)
            triggerHitStop(0.03f)
            triggerShake(0.14f, 4f)

            val w = width.toFloat().coerceAtLeast(320f)
            val h = height.toFloat().coerceAtLeast(240f)
            val topHud = dp(52f)
            val deckTop = getControlDeckTop(h)
            val cell = ((deckTop - topHud - dp(12f)) / ttRows).coerceAtMost((w * 0.56f) / ttCols)
            val ox = (w - cell * ttCols) * 0.44f
            val oy = topHud + ((deckTop - topHud) - cell * ttRows) * 0.5f
            val fx = ox + cell * ttCols * 0.5f
            val fy = oy + (lastClearedRow + 0.5f) * cell
            val pitch = registerCombo(fx, fy)
            audio.play(RetroAudioEngine.SFX_COIN, pitch)
            spawnShockwave(fx, fy, 68f, COL_GOLD)
            spawnParticles(fx, fy, 18, COL_GOLD, dp(30f), dp(145f), 4f)
            spawnFloatText(fx, fy, "+$pts!", COL_GOLD)
        }
        spawnTetrisPiece()
    }

    private fun updateTetris(dt: Float) {
        if (ttBoardBounce > 0f) ttBoardBounce = max(0f, ttBoardBounce - dt * 6f)
        if (ttClearFlashTimer > 0f) ttClearFlashTimer = max(0f, ttClearFlashTimer - dt)

        if (inLeft || inRight) {
            ttDasTimer += dt
            if (ttDasTimer > 0.16f) {
                ttDasTimer = 0.08f
                moveTetrisHorizontal(if (inLeft) -1 else 1)
            }
        } else {
            ttDasTimer = 0f
        }

        val baseSpeed = max(0.08f, (0.55f - difficulty * 0.12f) - (stage - 1) * 0.04f)
        val interval = if (inDown) 0.05f else baseSpeed
        ttDropTimer += dt
        if (ttDropTimer >= interval) {
            ttDropTimer = 0f
            if (isValidTetris(ttPx, ttPy + 1, ttRot)) {
                ttPy++
            } else {
                lockTetrisPiece()
            }
        }
    }

    private fun drawTetris(canvas: Canvas, w: Float, h: Float) {
        val topHud = dp(52f)
        val deckTop = getControlDeckTop(h)
        val cell = ((deckTop - topHud - dp(12f)) / ttRows).coerceAtMost((w * 0.56f) / ttCols)
        val ox = (w - cell * ttCols) * 0.44f
        val bounceY = sin(ttBoardBounce * Math.PI.toFloat()) * dp(6f)
        val oy = topHud + ((deckTop - topHud) - cell * ttRows) * 0.5f + bounceY

        pFill.color = 0xFF171513.toInt()
        canvas.drawRect(ox, oy, ox + cell * ttCols, oy + cell * ttRows, pFill)
        pStroke.strokeWidth = if (arenaGlowTimer > 0f) dp(3f) else dp(1.5f)
        pStroke.color = if (arenaGlowTimer > 0f) arenaGlowColor else 0x55E6C88F
        canvas.drawRect(ox, oy, ox + cell * ttCols, oy + cell * ttRows, pStroke)

        for (r in 0 until ttRows) {
            for (c in 0 until ttCols) {
                val v = ttBoard[r * ttCols + c]
                if (v == 0) continue
                drawTetrisBlock(canvas, ox + c * cell + 1f, oy + r * cell + 1f, cell - 2f, tetrominoColors[(v - 1) % tetrominoColors.size])
            }
        }

        if (ttClearFlashTimer > 0f) {
            val alpha = ((ttClearFlashTimer / 0.24f) * 200).toInt()
            pFill.color = (alpha shl 24) or 0x00FFFFFF
            for (r in 0 until ttRows) {
                if ((ttClearFlashMask and (1 shl r)) != 0) {
                    val fy = oy + r * cell
                    canvas.drawRect(ox, fy, ox + cell * ttCols, fy + cell, pFill)
                }
            }
        }

        var ghostY = ttPy
        while (isValidTetris(ttPx, ghostY + 1, ttRot)) ghostY++
        pStroke.strokeWidth = dp(1.2f)
        pStroke.color = 0x55FFFFFF
        for (i in 0..3) {
            val (dx, dy) = getTetrominoCell(ttCurType, ttRot, i)
            val x0 = ox + (ttPx + dx) * cell + 2f
            val y0 = oy + (ghostY + dy) * cell + 2f
            rect.set(x0, y0, x0 + cell - 4f, y0 + cell - 4f)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), pStroke)
        }

        val curCol = tetrominoColors[ttCurType]
        for (i in 0..3) {
            val (dx, dy) = getTetrominoCell(ttCurType, ttRot, i)
            drawTetrisBlock(canvas, ox + (ttPx + dx) * cell + 1f, oy + (ttPy + dy) * cell + 1f, cell - 2f, curCol)
        }

        val nx = ox + cell * ttCols + dp(14f)
        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = COL_GOLD
        pText.textSize = dp(12f)
        canvas.drawText("NEXT", nx, oy + dp(16f), pText)
        canvas.drawText("BARIS: $ttLinesTotal", nx, oy + dp(110f), pText)

        val nextCol = tetrominoColors[ttNextType]
        val mini = cell * 0.72f
        for (i in 0..3) {
            val (dx, dy) = getTetrominoCell(ttNextType, 0, i)
            drawTetrisBlock(canvas, nx + dx * mini, oy + dp(26f) + dy * mini, mini - 2f, nextCol)
        }
    }

    private fun drawTetrisBlock(canvas: Canvas, x0: Float, y0: Float, sz: Float, col: Int) {
        rect.set(x0, y0, x0 + sz, y0 + sz)
        pFill.color = col
        canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)

        // Top-left bevel highlight
        pFill.color = 0x55FFFFFF
        canvas.drawRect(x0 + dp(1.5f), y0 + dp(1.5f), x0 + sz - dp(1.5f), y0 + sz * 0.26f, pFill)

        // Bottom-right bevel shade
        pFill.color = 0x44000000
        canvas.drawRect(x0 + dp(1.5f), y0 + sz * 0.74f, x0 + sz - dp(1.5f), y0 + sz - dp(1.5f), pFill)

        // Inner jewel facet frame
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x66FFFFFF
        rect.set(x0 + sz * 0.24f, y0 + sz * 0.24f, x0 + sz * 0.76f, y0 + sz * 0.76f)
        canvas.drawRect(rect, pStroke)
    }

    // =========================================================================
    // GAME 5: ASTRO ROCKET (STARFIGHTER SPRITE, SAUCER UFO & CRATERED ASTEROIDS)
    // =========================================================================
    private fun initRocket(keep: Boolean) {
        rkX = 0.5f
        rkY = 0.85f
        rkTilt = 0f
        rkRecoil = 0f
        rkMuzzleTimer = 0f
        rkGhostX.fill(rkX)
        rkGhostY.fill(rkY)
        rkGhostTilt.fill(0f)
        if (!keep) {
            rkHp = 3
            rkShield = 1.5f
            rkDualTimer = 0f
        }
        rkKills = 0
        rkTargetKills = 16 + stage * 6
        rsActive.fill(false)
        reActive.fill(false)
        reFlash.fill(0f)
        reWarnTimer.fill(0f)
    }

    private fun fireRocketLaser() {
        if (rkFireCd > 0f) return
        rkFireCd = 0.22f
        rkRecoil = 1f
        rkMuzzleTimer = 0.07f
        audio.play(RetroAudioEngine.SFX_LASER)
        if (rkDualTimer > 0f) {
            spawnRocketShot(true, rkX - 0.032f, rkY - 0.03f, -1.1f)
            spawnRocketShot(true, rkX + 0.032f, rkY - 0.03f, -1.1f)
        } else {
            spawnRocketShot(true, rkX, rkY - 0.045f, -1.15f)
        }
    }

    private fun toggleRocketAutoFire() {
        rocketAutoFire = !rocketAutoFire
        audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
    }

    private fun spawnRocketShot(friendly: Boolean, x: Float, y: Float, vy: Float) {
        for (i in 0 until maxRkShots) {
            if (!rsActive[i]) {
                rsActive[i] = true
                rsFriendly[i] = friendly
                rsX[i] = x
                rsY[i] = y
                rsVy[i] = vy
                return
            }
        }
    }

    private fun updateRocket(dt: Float) {
        if (rkShield > 0f) rkShield = max(0f, rkShield - dt)
        if (rkDualTimer > 0f) rkDualTimer = max(0f, rkDualTimer - dt)
        if (rkFireCd > 0f) rkFireCd = max(0f, rkFireCd - dt)
        if (rkRecoil > 0f) rkRecoil = max(0f, rkRecoil - dt * 7f)
        if (rkMuzzleTimer > 0f) rkMuzzleTimer = max(0f, rkMuzzleTimer - dt)
        for (i in reFlash.indices) {
            if (reFlash[i] > 0f) reFlash[i] = max(0f, reFlash[i] - dt)
        }

        val prevX = rkX
        val spd = 0.78f
        if (inLeft) rkX = max(0.08f, rkX - spd * dt)
        if (inRight) rkX = min(0.92f, rkX + spd * dt)
        if (inUp) rkY = max(0.25f, rkY - spd * 0.8f * dt)
        if (inDown) rkY = min(0.90f, rkY + spd * 0.8f * dt)

        val moveDelta = (rkX - prevX) / dt
        val targetTilt = (moveDelta * 26f).coerceIn(-22f, 22f)
        rkTilt += (targetTilt - rkTilt) * min(1f, dt * 12f)

        rkGhostTimer -= dt
        if (rkGhostTimer <= 0f) {
            rkGhostTimer = 0.035f
            for (g in 2 downTo 1) {
                rkGhostX[g] = rkGhostX[g - 1]
                rkGhostY[g] = rkGhostY[g - 1]
                rkGhostTilt[g] = rkGhostTilt[g - 1]
            }
            rkGhostX[0] = rkX
            rkGhostY[0] = rkY
            rkGhostTilt[0] = rkTilt
        }

        val w = width.toFloat().coerceAtLeast(320f)
        val h = height.toFloat().coerceAtLeast(240f)
        val topY = dp(52f)
        val areaH = getControlDeckTop(h) - topY

        rkTrailTimer -= dt
        if (rkTrailTimer <= 0f) {
            rkTrailTimer = 0.025f
            spawnTrailNode(rkX * w, topY + rkY * areaH + dp(18f), 6.5f, COL_ORANGE)
        }

        if (rocketAutoFire || btnA) fireRocketLaser()

        rkSpawnCd -= dt
        if (rkSpawnCd <= 0f) {
            rkSpawnCd = max(0.38f, 0.95f - difficulty * 0.16f - stage * 0.05f)
            for (i in 0 until maxRkEnemies) {
                if (!reActive[i]) {
                    reActive[i] = true
                    val roll = Random.nextFloat()
                    reType[i] = when {
                        roll < 0.14f -> 2
                        roll < 0.45f -> 1
                        else -> 0
                    }
                    reHp[i] = if (reType[i] == 1) 2 else 1
                    reFlash[i] = 0f
                    reX[i] = 0.1f + Random.nextFloat() * 0.8f
                    reY[i] = -0.05f
                    reVx[i] = if (reType[i] == 1) (if (Random.nextBoolean()) 0.22f else -0.22f) else 0f
                    reVy[i] = 0.26f + Random.nextFloat() * 0.16f + difficulty * 0.05f
                    reShootCd[i] = 1.4f + Random.nextFloat()
                    reWarnTimer[i] = if (reType[i] == 0 && reVy[i] > 0.38f) 0.42f else 0f
                    break
                }
            }
        }

        for (i in 0 until maxRkShots) {
            if (!rsActive[i]) continue
            rsY[i] += rsVy[i] * dt
            if (rsY[i] < -0.05f || rsY[i] > 1.05f) {
                rsActive[i] = false
                continue
            }
            if (rsFriendly[i]) {
                for (e in 0 until maxRkEnemies) {
                    if (reActive[e] && reType[e] != 2 && reWarnTimer[e] <= 0f &&
                        abs(rsX[i] - reX[e]) < 0.055f && abs(rsY[i] - reY[e]) < 0.055f
                    ) {
                        rsActive[i] = false
                        reHp[e]--
                        val ex = reX[e] * w
                        val ey = topY + reY[e] * areaH
                        if (reHp[e] <= 0) {
                            val isUfo = reType[e] == 1
                            reActive[e] = false
                            rkKills++
                            val pts = if (isUfo) 120 else 60
                            addPoints(pts)
                            val pitch = registerCombo(ex, ey)
                            audio.play(RetroAudioEngine.SFX_EXPLODE, pitch)
                            triggerHitStop(0.03f)
                            triggerShake(0.10f, 3.5f)
                            val col = if (isUfo) COL_PURPLE else COL_ORANGE
                            spawnShockwave(ex, ey, if (isUfo) 52f else 38f, col)
                            spawnParticles(ex, ey, if (isUfo) 18 else 12, col, dp(30f), dp(145f), 4f)
                            spawnFloatText(ex, ey, "+$pts", col)
                        } else {
                            reFlash[e] = 0.12f
                            reY[e] -= 0.015f
                            audio.play(RetroAudioEngine.SFX_BOUNCE, 1.35f)
                            spawnParticles(ex, ey, 6, Color.WHITE, dp(20f), dp(80f), 2.8f)
                        }
                        break
                    }
                }
            } else if (abs(rsX[i] - rkX) < 0.045f && abs(rsY[i] - rkY) < 0.045f) {
                rsActive[i] = false
                damageRocketPlayer()
            }
        }

        for (i in 0 until maxRkEnemies) {
            if (!reActive[i]) continue
            if (reWarnTimer[i] > 0f) {
                reWarnTimer[i] = max(0f, reWarnTimer[i] - dt)
                continue
            }
            reX[i] += reVx[i] * dt
            if (reX[i] < 0.08f || reX[i] > 0.92f) reVx[i] = -reVx[i]
            reY[i] += reVy[i] * dt
            if (reY[i] > 1.06f) {
                reActive[i] = false
                continue
            }
            if (reType[i] == 1) {
                reShootCd[i] -= dt
                if (reShootCd[i] <= 0f) {
                    reShootCd[i] = 1.9f
                    spawnRocketShot(false, reX[i], reY[i] + 0.04f, 0.55f)
                }
            }
            if (abs(reX[i] - rkX) < 0.06f && abs(reY[i] - rkY) < 0.06f) {
                val t = reType[i]
                reActive[i] = false
                if (t == 2) {
                    val isDual = Random.nextBoolean()
                    if (isDual) rkDualTimer = 10f else rkShield = 5f
                    addPoints(100)
                    audio.play(RetroAudioEngine.SFX_POWERUP)
                    val sx = rkX * w
                    val sy = topY + rkY * areaH
                    spawnShockwave(sx, sy, 52f, COL_GREEN)
                    spawnParticles(sx, sy, 14, COL_GREEN, dp(25f), dp(120f), 3.6f)
                    spawnFloatText(sx, sy - dp(12f), if (isDual) "LASER GANDA!" else "PERISAI AKTIF!", COL_GREEN)
                } else {
                    damageRocketPlayer()
                }
            }
        }

        if (rkKills >= rkTargetKills) {
            victory = true
            stage++
            addPoints(450)
            audio.play(RetroAudioEngine.SFX_WIN)
            state = STATE_GAMEOVER
            dialogIndex = 0
        }
    }

    private fun damageRocketPlayer() {
        if (rkShield > 0f) return
        rkHp--
        rkShield = 1.6f
        triggerHitStop(0.045f)
        triggerShake(0.22f, 6f)
        audio.play(RetroAudioEngine.SFX_EXPLODE, 0.85f)
        val sx = rkX * width.toFloat()
        val sy = dp(52f) + rkY * (getControlDeckTop(height.toFloat()) - dp(52f))
        spawnShockwave(sx, sy, 54f, COL_RED)
        spawnParticles(sx, sy, 18, COL_RED, dp(30f), dp(150f), 4f)
        if (rkHp <= 0) {
            victory = false
            audio.play(RetroAudioEngine.SFX_OVER)
            state = STATE_GAMEOVER
            dialogIndex = 0
        }
    }

    private fun drawRocket(canvas: Canvas, w: Float, h: Float) {
        val topY = dp(52f)
        val botY = getControlDeckTop(h)
        val areaH = botY - topY

        // 3-Layer Parallax Starfield
        for (i in 0..32) {
            val layer = i % 3
            val speed = when (layer) { 0 -> 14f; 1 -> 34f; else -> 75f }
            val alpha = when (layer) { 0 -> 55; 1 -> 125; else -> 210 }
            val sz = when (layer) { 0 -> dp(1.1f); 1 -> dp(1.7f); else -> dp(2.3f) }
            pFill.color = (alpha shl 24) or 0x00FFFFFF
            val sx = ((i * 73) % 100) / 100f * w
            val sy = topY + (((i * 41 + animTime * speed).toInt() % 100) / 100f) * areaH
            canvas.drawCircle(sx, sy, sz, pFill)
        }

        for (i in 0 until maxRkEnemies) {
            if (!reActive[i]) continue
            val ex = reX[i] * w
            if (reWarnTimer[i] > 0f) {
                if (((animTime * 22f).toInt() and 1) == 0) {
                    pFill.color = 0xCCFF5252.toInt()
                    canvas.drawCircle(ex, topY + dp(18f), dp(11f), pFill)
                    pText.textAlign = Paint.Align.CENTER
                    pText.typeface = Typeface.DEFAULT_BOLD
                    pText.textSize = dp(13f)
                    pText.color = Color.WHITE
                    canvas.drawText("!", ex, topY + dp(23f), pText)
                }
                continue
            }

            val ey = topY + reY[i] * areaH
            val flash = reFlash[i] > 0f
            when (reType[i]) {
                0 -> drawRocketMeteor(canvas, ex, ey, dp(15f), i, flash)
                1 -> drawRocketUfo(canvas, ex, ey, flash, reShootCd[i])
                2 -> {
                    // Spinning Energy Power-Up Capsule
                    val pulse = 1f + sin(animTime * 10f) * 0.12f
                    pFill.color = 0x444CAF7D
                    canvas.drawCircle(ex, ey, dp(16f) * pulse, pFill)
                    pFill.color = COL_GREEN
                    canvas.drawCircle(ex, ey, dp(11f), pFill)
                    pStroke.strokeWidth = dp(1.6f)
                    pStroke.color = Color.WHITE
                    canvas.drawCircle(ex, ey, dp(11f), pStroke)
                    pText.textAlign = Paint.Align.CENTER
                    pText.typeface = Typeface.DEFAULT_BOLD
                    pText.textSize = dp(11f)
                    pText.color = COL_BG_TOP
                    canvas.drawText("P", ex, ey + dp(4f), pText)
                }
            }
        }

        // Glowing Plasma Laser Bolts
        for (i in 0 until maxRkShots) {
            if (!rsActive[i]) continue
            val lx = rsX[i] * w
            val ly = topY + rsY[i] * areaH
            if (rsFriendly[i]) {
                pFill.color = 0x554CC3D9
                rect.set(lx - dp(4f), ly - dp(11f), lx + dp(4f), ly + dp(11f))
                canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
                pFill.color = COL_CYAN
                rect.set(lx - dp(2.2f), ly - dp(9f), lx + dp(2.2f), ly + dp(9f))
                canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
                pFill.color = Color.WHITE
                canvas.drawRect(lx - dp(1f), ly - dp(6f), lx + dp(1f), ly + dp(6f), pFill)
            } else {
                pFill.color = 0x66E05252
                canvas.drawCircle(lx, ly, dp(6.5f), pFill)
                pFill.color = COL_RED
                canvas.drawCircle(lx, ly, dp(4f), pFill)
                pFill.color = 0xFFFFF59D.toInt()
                canvas.drawCircle(lx, ly, dp(1.8f), pFill)
            }
        }

        // Motion Afterimage Starfighter Ghosts when banking fast
        if (abs(rkTilt) > 6f) {
            for (g in 2 downTo 0) {
                val alpha = (2 - g) * 26 + 18
                drawRocketShip(
                    canvas,
                    rkGhostX[g] * w,
                    topY + rkGhostY[g] * areaH,
                    rkGhostTilt[g],
                    isGhost = true,
                    ghostAlpha = alpha
                )
            }
        }

        // Detailed Starfighter Spacecraft
        val px = rkX * w
        val py = topY + rkY * areaH + rkRecoil * dp(5f)
        drawRocketShip(canvas, px, py, rkTilt, isGhost = false, ghostAlpha = 255)

        if (rkHp == 1) {
            val alpha = ((0.4f + 0.6f * abs(sin(animTime * 5.5f))) * 105).toInt()
            pStroke.strokeWidth = dp(9f)
            pStroke.color = (alpha shl 24) or 0x00E05252
            canvas.drawRect(0f, topY, w, botY, pStroke)
        }

        pText.textAlign = Paint.Align.LEFT
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.color = COL_GOLD
        pText.textSize = dp(12f)
        canvas.drawText("ARMOR: ${"♥".repeat(rkHp.coerceAtLeast(0))}  |  TARGET: $rkKills/$rkTargetKills", dp(14f), topY + dp(16f), pText)
    }

    private fun drawRocketShip(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        tiltDeg: Float,
        isGhost: Boolean,
        ghostAlpha: Int
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(tiltDeg)

        if (isGhost) {
            path.reset()
            path.moveTo(0f, -dp(22f))
            path.lineTo(-dp(8f), -dp(4f))
            path.lineTo(-dp(22f), dp(10f))
            path.lineTo(-dp(18f), dp(16f))
            path.lineTo(-dp(7f), dp(13f))
            path.lineTo(0f, dp(16f))
            path.lineTo(dp(7f), dp(13f))
            path.lineTo(dp(18f), dp(16f))
            path.lineTo(dp(22f), dp(10f))
            path.lineTo(dp(8f), -dp(4f))
            path.close()
            pFill.color = (ghostAlpha shl 24) or 0x004CC3D9
            canvas.drawPath(path, pFill)
            canvas.restore()
            return
        }

        // Animated Twin Plasma Thruster Flames
        val flameLen = dp(12f) + abs(sin(animTime * 42f)) * dp(7f)
        val nozOffsets = floatArrayOf(-dp(6.5f), dp(6.5f))
        for (nx in nozOffsets) {
            path.reset()
            path.moveTo(nx - dp(3.5f), dp(14f))
            path.lineTo(nx, dp(14f) + flameLen)
            path.lineTo(nx + dp(3.5f), dp(14f))
            path.close()
            pFill.color = COL_ORANGE
            canvas.drawPath(path, pFill)

            path.reset()
            path.moveTo(nx - dp(1.8f), dp(14f))
            path.lineTo(nx, dp(14f) + flameLen * 0.62f)
            path.lineTo(nx + dp(1.8f), dp(14f))
            path.close()
            pFill.color = Color.WHITE
            canvas.drawPath(path, pFill)
        }

        // Swept-Back Delta Wings (Metallic Slate + Crimson Trim)
        path.reset()
        path.moveTo(0f, -dp(8f))
        path.lineTo(-dp(23f), dp(10f))
        path.lineTo(-dp(19f), dp(16f))
        path.lineTo(-dp(6f), dp(12f))
        path.lineTo(dp(6f), dp(12f))
        path.lineTo(dp(19f), dp(16f))
        path.lineTo(dp(23f), dp(10f))
        path.close()
        pFill.color = 0xFF343B48.toInt()
        canvas.drawPath(path, pFill)
        pStroke.strokeWidth = dp(1.3f)
        pStroke.color = COL_RED
        canvas.drawPath(path, pStroke)

        // Wingtip Laser Pods (expanded when Dual-Laser is active)
        val podH = if (rkDualTimer > 0f) dp(16f) else dp(10f)
        pFill.color = if (rkDualTimer > 0f) COL_CYAN else COL_RED
        canvas.drawRect(-dp(22f), dp(11f) - podH, -dp(19f), dp(12f), pFill)
        canvas.drawRect(dp(19f), dp(11f) - podH, dp(22f), dp(12f), pFill)

        // Main Starfighter Fuselage Hull
        path.reset()
        path.moveTo(0f, -dp(23f))
        path.lineTo(-dp(8.5f), -dp(3f))
        path.lineTo(-dp(8.5f), dp(14f))
        path.lineTo(0f, dp(17f))
        path.lineTo(dp(8.5f), dp(14f))
        path.lineTo(dp(8.5f), -dp(3f))
        path.close()
        pFill.color = COL_GOLD
        canvas.drawPath(path, pFill)

        // Center Spine Ridge & Nose Tip
        path.reset()
        path.moveTo(0f, -dp(23f))
        path.lineTo(-dp(3.5f), -dp(6f))
        path.lineTo(0f, dp(15f))
        path.lineTo(dp(3.5f), -dp(6f))
        path.close()
        pFill.color = COL_IVORY
        canvas.drawPath(path, pFill)

        // Glass Cockpit Canopy + Specular Highlight
        rect.set(-dp(4.2f), -dp(9f), dp(4.2f), dp(4f))
        pFill.color = 0xFF132A38.toInt()
        canvas.drawOval(rect, pFill)
        rect.set(-dp(3.2f), -dp(8f), dp(3.2f), dp(2.8f))
        pFill.color = COL_CYAN
        canvas.drawOval(rect, pFill)
        pFill.color = Color.WHITE
        canvas.drawCircle(-dp(1.2f), -dp(4.5f), dp(1.3f), pFill)

        // Muzzle Flash
        if (rkMuzzleTimer > 0f) {
            pFill.color = 0xEEFFF59D.toInt()
            canvas.drawCircle(0f, -dp(25f), dp(6.5f), pFill)
        }

        // Active Energy Shield Bubble
        if (rkShield > 0f) {
            pStroke.strokeWidth = dp(2f)
            pStroke.color = COL_CYAN
            canvas.drawCircle(0f, -dp(2f), dp(26f), pStroke)
            pFill.color = 0x224CC3D9
            canvas.drawCircle(0f, -dp(2f), dp(26f), pFill)
        }

        canvas.restore()
    }

    private fun drawRocketUfo(canvas: Canvas, ex: Float, ey: Float, flash: Boolean, shootCd: Float) {
        // Glass Cockpit Dome on Top
        rect.set(ex - dp(10f), ey - dp(15f), ex + dp(10f), ey - dp(1f))
        pFill.color = if (flash) Color.WHITE else 0xAA4CC3D9.toInt()
        canvas.drawArc(rect, 180f, 180f, true, pFill)
        pFill.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(ex - dp(3.5f), ey - dp(10f), dp(2f), pFill)

        // Saucer Main Disc
        rect.set(ex - dp(21f), ey - dp(8f), ex + dp(21f), ey + dp(8f))
        pFill.color = if (flash) Color.WHITE else COL_PURPLE
        canvas.drawOval(rect, pFill)
        pStroke.strokeWidth = dp(1.4f)
        pStroke.color = COL_GOLD
        canvas.drawOval(rect, pStroke)

        // 3 Blinking Rim Lights
        val blinkPhase = (animTime * 8f).toInt()
        for (k in -1..1) {
            val lit = ((blinkPhase + k + 3) % 3) == 0
            pFill.color = if (lit) COL_GOLD else 0xFF2D1E42.toInt()
            canvas.drawCircle(ex + k * dp(10f), ey + dp(1f), dp(2.3f), pFill)
        }

        // Telegraphing charge ring before UFO shoots
        if (shootCd < 0.45f) {
            val chargeRadius = dp(4f) + (shootCd / 0.45f) * dp(14f)
            pStroke.strokeWidth = dp(2f)
            pStroke.color = COL_RED
            canvas.drawCircle(ex, ey + dp(9f), chargeRadius, pStroke)
        }
    }

    private fun drawRocketMeteor(canvas: Canvas, ex: Float, ey: Float, r: Float, seed: Int, flash: Boolean) {
        canvas.save()
        canvas.translate(ex, ey)
        val rotDir = if ((seed and 1) == 0) 1f else -1f
        canvas.rotate(animTime * 55f * rotDir + seed * 37f)

        // 8-point irregular asteroid polygon
        val factors = floatArrayOf(1.0f, 0.82f, 0.96f, 0.78f, 1.04f, 0.86f, 0.94f, 0.80f)
        path.reset()
        for (v in 0 until 8) {
            val ang = (v * 45.0 * Math.PI / 180.0).toFloat()
            val rad = r * factors[(v + seed) and 7]
            val vx = cos(ang) * rad
            val vy = sin(ang) * rad
            if (v == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
        }
        path.close()

        pFill.color = if (flash) Color.WHITE else 0xFF8C5332.toInt()
        canvas.drawPath(path, pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = if (flash) Color.WHITE else COL_ORANGE
        canvas.drawPath(path, pStroke)

        if (!flash) {
            // Impact craters
            pFill.color = 0xFF5A321B.toInt()
            canvas.drawCircle(-r * 0.28f, -r * 0.20f, r * 0.24f, pFill)
            canvas.drawCircle(r * 0.30f, r * 0.24f, r * 0.18f, pFill)
            canvas.drawCircle(-r * 0.10f, r * 0.34f, r * 0.13f, pFill)
        }

        canvas.restore()
    }
}
