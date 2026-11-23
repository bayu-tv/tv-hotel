package com.bayutv.hotel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: ArcadeCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = ArcadeCanvasView(this)
        setContentView(gameView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!gameView.onBackAction()) {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        gameView.resumeLoop()
    }

    override fun onPause() {
        super.onPause()
        gameView.pauseLoop()
    }

    override fun onDestroy() {
        gameView.releaseResources()
        super.onDestroy()
    }
}

private class RetroAudioEngine(context: Context) {
    companion object {
        const val SFX_EXPLODE = 0
        const val SFX_BOUNCE = 1
        const val SFX_COIN = 2
        const val SFX_LASER = 3
        const val SFX_POWERUP = 4
        const val SFX_WIN = 5
        const val SFX_OVER = 6
    }

    private var soundPool: SoundPool? = null
    private val sfxIds = IntArray(7)
    private var ready = false
    var muted = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        Thread {
            try {
                val dir = File(context.cacheDir, "bbox_sfx")
                if (!dir.exists()) dir.mkdirs()

                sfxIds[SFX_EXPLODE] = loadTone(dir, "exp.wav", 0.24f, 150f, 38f, noise = true)
                sfxIds[SFX_BOUNCE] = loadTone(dir, "bnc.wav", 0.07f, 440f, 660f, noise = false)
                sfxIds[SFX_COIN] = loadTone(dir, "coin.wav", 0.12f, 587f, 987f, noise = false)
                sfxIds[SFX_LASER] = loadTone(dir, "lsr.wav", 0.09f, 780f, 220f, noise = false)
                sfxIds[SFX_POWERUP] = loadTone(dir, "pwr.wav", 0.20f, 330f, 880f, noise = false)
                sfxIds[SFX_WIN] = loadTone(dir, "win.wav", 0.28f, 440f, 1046f, noise = false)
                sfxIds[SFX_OVER] = loadTone(dir, "ovr.wav", 0.30f, 330f, 110f, noise = false)
                ready = true
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun loadTone(dir: File, name: String, durSec: Float, fStart: Float, fEnd: Float, noise: Boolean): Int {
        val sp = soundPool ?: return 0
        val file = File(dir, name)
        if (!file.exists() || file.length() < 100L) {
            val sr = 22050
            val samples = (sr * durSec).toInt()
            val dataLen = samples * 2
            val buf = ByteArray(44 + dataLen)

            writeAscii(buf, 0, "RIFF")
            writeIntLE(buf, 4, 36 + dataLen)
            writeAscii(buf, 8, "WAVE")
            writeAscii(buf, 12, "fmt ")
            writeIntLE(buf, 16, 16)
            writeShortLE(buf, 20, 1)
            writeShortLE(buf, 22, 1)
            writeIntLE(buf, 24, sr)
            writeIntLE(buf, 28, sr * 2)
            writeShortLE(buf, 32, 2)
            writeShortLE(buf, 34, 16)
            writeAscii(buf, 36, "data")
            writeIntLE(buf, 40, dataLen)

            var phase = 0.0
            var rndSeed = 1337
            for (i in 0 until samples) {
                val t = i.toFloat() / samples
                val env = (1f - t) * (1f - t)
                val freq = fStart + (fEnd - fStart) * t
                phase += 2.0 * PI * freq / sr
                val wave = if (noise) {
                    rndSeed = rndSeed * 1103515245 + 12345
                    val n = ((rndSeed shr 16) and 0x7FFF) / 16383.5 - 1.0
                    val sq = if (sin(phase) >= 0.0) 0.5 else -0.5
                    n * 0.65 + sq * 0.35
                } else {
                    if (sin(phase) >= 0.0) 0.75 else -0.75
                }
                val pcm = (wave * env * 14000.0).toInt().coerceIn(-32767, 32767)
                buf[44 + i * 2] = (pcm and 0xFF).toByte()
                buf[44 + i * 2 + 1] = ((pcm shr 8) and 0xFF).toByte()
            }
            FileOutputStream(file).use { it.write(buf) }
        }
        return sp.load(file.absolutePath, 1)
    }

    private fun writeAscii(b: ByteArray, off: Int, s: String) {
        for (i in s.indices) b[off + i] = s[i].code.toByte()
    }

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun play(sfx: Int, rate: Float = 1f) {
        if (muted || !ready) return
        val id = sfxIds[sfx]
        if (id != 0) soundPool?.play(id, 0.65f, 0.65f, 1, 0, rate)
    }

    fun release() {
        ready = false
        soundPool?.release()
        soundPool = null
    }
}

private class ArcadeCanvasView(context: Context) : View(context) {

    companion object {
        const val MODE_HUB = 0
        const val MODE_BOMBER = 1
        const val MODE_BRICK = 2
        const val MODE_SNAKE = 3
        const val MODE_TETRIS = 4
        const val MODE_ROCKET = 5

        const val STATE_HUB = 0
        const val STATE_READY = 1
        const val STATE_PLAYING = 2
        const val STATE_PAUSED = 3
        const val STATE_GAMEOVER = 4

        const val DIFF_EASY = 0
        const val DIFF_NORMAL = 1
        const val DIFF_HARD = 2

        const val COL_BG = 0xFF0A0A0D.toInt()
        const val COL_CARD = 0xFF15151A.toInt()
        const val COL_GOLD = 0xFFB8975A.toInt()
        const val COL_GOLD_LIGHT = 0xFFE6C88F.toInt()
        const val COL_IVORY = 0xFFEFE8DA.toInt()
        const val COL_MUTE = 0xFFA79F90.toInt()
        const val COL_RED = 0xFFE05252.toInt()
        const val COL_GREEN = 0xFF4CAF7D.toInt()
        const val COL_CYAN = 0xFF4CC3D9.toInt()
        const val COL_PURPLE = 0xFF9C6ADE.toInt()
        const val COL_ORANGE = 0xFFFF9800.toInt()
    }

    private val prefs = context.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
    private val audio = RetroAudioEngine(context)
    private var running = false
    private var lastFrameNs = 0L
    private var globalTime = 0f

    private var mode = MODE_HUB
    private var state = STATE_HUB
    private var difficulty = DIFF_NORMAL
    private var hubIndex = 0
    private var dialogIndex = 0
    private var victory = false
    private var score = 0
    private var stage = 1

    private var shakeTimer = 0f
    private var shakeStrength = 0f
    private var flashAlpha = 0f
    private var flashColor = Color.WHITE

    // Static Particle Object Pool (64 slots, zero GC)
    private val maxParticles = 64
    private val ptLife = FloatArray(maxParticles)
    private val ptMaxLife = FloatArray(maxParticles)
    private val ptX = FloatArray(maxParticles)
    private val ptY = FloatArray(maxParticles)
    private val ptVx = FloatArray(maxParticles)
    private val ptVy = FloatArray(maxParticles)
    private val ptSize = FloatArray(maxParticles)
    private val ptColor = IntArray(maxParticles)

    // Static Shockwave Ring Pool (8 slots, zero GC)
    private val maxRings = 8
    private val rgLife = FloatArray(maxRings)
    private val rgMaxLife = FloatArray(maxRings)
    private val rgX = FloatArray(maxRings)
    private val rgY = FloatArray(maxRings)
    private val rgMaxR = FloatArray(maxRings)
    private val rgColor = IntArray(maxRings)

    // Static Floating Score Text Pool (8 slots)
    private val maxFloatTexts = 8
    private val ftLife = FloatArray(maxFloatTexts)
    private val ftX = FloatArray(maxFloatTexts)
    private val ftY = FloatArray(maxFloatTexts)
    private val ftColor = IntArray(maxFloatTexts)
    private val ftText = Array(maxFloatTexts) { "" }

    // Input state + DAS/ARR smooth hold repeat
    private var inLeft = false
    private var inRight = false
    private var inUp = false
    private var inDown = false
    private var btnA = false
    private var btnB = false
    private var dasTimer = 0f
    private var arrTimer = 0f
    private var showTouchOverlay = false

    // Smartphone dynamic virtual stick
    private var dpadPointerId = -1
    private var dpadBaseX = 0f
    private var dpadBaseY = 0f
    private var dpadActiveX = 0f
    private var dpadActiveY = 0f
    private var dpadThumbX = 0f
    private var dpadThumbY = 0f
    private var touchDpadRadius = 0f

    // Board direct touch/swipe
    private var boardPointerId = -1
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var boardHolding = false

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val path = Path()
    private val rect = RectF()
    private val hubRects = Array(6) { RectF() }
    private val diffRects = Array(3) { RectF() }
    private val dialogBtnRects = Array(3) { RectF() }
    private val hudPauseRect = RectF()
    private val hudMuteRect = RectF()
    private val touchBtnARect = RectF()
    private val touchBtnBRect = RectF()

    private val gameTitles = arrayOf(
        "Bomber Arena",
        "Neon Brick",
        "Gold Snake",
        "Neon Tetris",
        "Astro Rocket"
    )
    private val gameDescs = arrayOf(
        "Ledakkan blok, ambil perisai & kalahkan bot",
        "Laser, Bola Api, Multi-Ball & Pemukul Lebar",
        "Ular emas + Orb Raksasa & pengali Combo",
        "Simpan balok (HOLD), bayangan & Combo baris",
        "Roket astronot, Magnet Bintang & Bos Alien"
    )
    private val gameGuides = arrayOf(
        "Kumpulkan item Biru (+Bom), Emas (+Api), Hijau (+Cepat), dan Ungu (+Perisai pelindung)!",
        "Tangkap kapsul W (Lebar), M (3 Bola), L (Laser Pemukul), dan F (Bola Api tembus balok)!",
        "Makan orb beruntun untuk Combo x4! Orb Emas besar bernilai +150 poin!",
        "Tahan Kiri/Kanan untuk geser mulus, tekan PUTAR untuk rotasi, dan JATUH/HOLD untuk strategi!",
        "Tahan GAS untuk terbang, tekan TEMBAK hancurkan asteroid, dan kalahkan Bos tiap 400m!"
    )
    private val gameAccents = intArrayOf(COL_RED, COL_CYAN, COL_GOLD, COL_PURPLE, COL_ORANGE)
    private val diffLabels = arrayOf("SANTAI (1.0x)", "NORMAL (1.25x)", "SULIT (1.5x)")

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        audio.muted = prefs.getBoolean("arcade_mute", false)
        difficulty = prefs.getInt("arcade_diff", DIFF_NORMAL).coerceIn(0, 2)
        showTouchOverlay = context.resources.displayMetrics.widthPixels < context.resources.displayMetrics.heightPixels
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun scoreMult(): Float = when (difficulty) {
        DIFF_EASY -> 1.0f
        DIFF_HARD -> 1.5f
        else -> 1.25f
    }

    private fun addPoints(base: Int) {
        score += (base * scoreMult()).toInt()
    }

    private fun getHighScore(gameMode: Int): Int = prefs.getInt("hs_game_$gameMode", 0)

    private fun saveHighScore(gameMode: Int, value: Int) {
        if (value > getHighScore(gameMode)) {
            prefs.edit().putInt("hs_game_$gameMode", value).apply()
        }
    }

    private fun setDifficulty(d: Int) {
        difficulty = d.coerceIn(0, 2)
        prefs.edit().putInt("arcade_diff", difficulty).apply()
        audio.play(RetroAudioEngine.SFX_BOUNCE, 1.0f + difficulty * 0.15f)
    }

    private fun toggleMute() {
        audio.muted = !audio.muted
        prefs.edit().putBoolean("arcade_mute", audio.muted).apply()
        if (!audio.muted) audio.play(RetroAudioEngine.SFX_BOUNCE, 1.2f)
    }

    private fun triggerShake(duration: Float, strengthDp: Float) {
        shakeTimer = max(shakeTimer, duration)
        shakeStrength = max(shakeStrength, dp(strengthDp))
    }

    private fun triggerFlash(color: Int, alpha: Float = 0.26f) {
        flashColor = color
        flashAlpha = alpha
    }

    private fun spawnRing(x: Float, y: Float, maxRadiusDp: Float, color: Int, duration: Float = 0.35f) {
        for (i in 0 until maxRings) {
            if (rgLife[i] <= 0f) {
                rgLife[i] = duration
                rgMaxLife[i] = duration
                rgX[i] = x
                rgY[i] = y
                rgMaxR[i] = dp(maxRadiusDp)
                rgColor[i] = color
                return
            }
        }
    }

    private fun spawnParticles(x: Float, y: Float, count: Int, color: Int, speedMin: Float, speedMax: Float, sizeDp: Float) {
        var spawned = 0
        for (i in 0 until maxParticles) {
            if (ptLife[i] <= 0f) {
                val ang = Random.nextFloat() * (2f * PI.toFloat())
                val spd = speedMin + Random.nextFloat() * (speedMax - speedMin)
                ptX[i] = x
                ptY[i] = y
                ptVx[i] = cos(ang) * spd
                ptVy[i] = sin(ang) * spd
                ptMaxLife[i] = 0.28f + Random.nextFloat() * 0.25f
                ptLife[i] = ptMaxLife[i]
                ptSize[i] = dp(sizeDp) * (0.7f + Random.nextFloat() * 0.6f)
                ptColor[i] = color
                spawned++
                if (spawned >= count) break
            }
        }
    }

    private fun spawnFloatText(x: Float, y: Float, text: String, color: Int) {
        for (i in 0 until maxFloatTexts) {
            if (ftLife[i] <= 0f) {
                ftLife[i] = 0.85f
                ftX[i] = x
                ftY[i] = y
                ftColor[i] = color
                ftText[i] = text
                return
            }
        }
    }

    private fun clearEffects() {
        ptLife.fill(0f)
        rgLife.fill(0f)
        ftLife.fill(0f)
        shakeTimer = 0f
        shakeStrength = 0f
        flashAlpha = 0f
    }

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

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = now
            globalTime += dt
            update(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun clearInputs() {
        inLeft = false
        inRight = false
        inUp = false
        inDown = false
        btnA = false
        btnB = false
        dasTimer = 0f
        arrTimer = 0f
        dpadPointerId = -1
        boardPointerId = -1
        boardHolding = false
        dpadActiveX = dpadBaseX
        dpadActiveY = dpadBaseY
        dpadThumbX = dpadBaseX
        dpadThumbY = dpadBaseY
    }

    fun onBackAction(): Boolean {
        return when (state) {
            STATE_PLAYING -> {
                clearInputs()
                state = STATE_PAUSED
                dialogIndex = 0
                true
            }
            STATE_PAUSED, STATE_READY, STATE_GAMEOVER -> {
                clearInputs()
                clearEffects()
                mode = MODE_HUB
                state = STATE_HUB
                true
            }
            else -> false
        }
    }

    private fun selectHubCard(idx: Int) {
        if (idx == 5) {
            val rndMode = Random.nextInt(1, 6)
            hubIndex = rndMode - 1
            openReadyScreen(rndMode)
        } else {
            hubIndex = idx
            openReadyScreen(idx + 1)
        }
    }

    private fun openReadyScreen(targetMode: Int) {
        clearInputs()
        clearEffects()
        mode = targetMode
        state = STATE_READY
        dialogIndex = 0
        score = 0
        stage = 1
        audio.play(RetroAudioEngine.SFX_BOUNCE, 1.1f)
        prepareBoardForMode(targetMode)
    }

    private fun startGame(targetMode: Int, keepProgress: Boolean = false) {
        clearInputs()
        clearEffects()
        mode = targetMode
        state = STATE_PLAYING
        victory = false
        if (!keepProgress) {
            score = 0
            stage = 1
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
            MODE_ROCKET -> initRocket()
        }
    }

    private fun getDialogButtonCount(): Int {
        return when (state) {
            STATE_READY -> 2
            STATE_PAUSED -> 3
            STATE_GAMEOVER -> 2
            else -> 0
        }
    }

    private fun executeDialogChoice(idx: Int) {
        when (state) {
            STATE_READY -> {
                if (idx == 0) startGame(mode, keepProgress = false)
                else {
                    clearEffects()
                    mode = MODE_HUB
                    state = STATE_HUB
                }
            }
            STATE_PAUSED -> {
                when (idx) {
                    0 -> {
                        clearInputs()
                        state = STATE_PLAYING
                    }
                    1 -> startGame(mode, keepProgress = false)
                    else -> {
                        clearInputs()
                        clearEffects()
                        mode = MODE_HUB
                        state = STATE_HUB
                    }
                }
            }
            STATE_GAMEOVER -> {
                if (idx == 0) startGame(mode, keepProgress = victory)
                else {
                    clearInputs()
                    clearEffects()
                    mode = MODE_HUB
                    state = STATE_HUB
                }
            }
        }
    }

    // =========================================================================
    // INPUT: JOYSTICK ANALOG, GAMEPAD BUTTONS, REMOTE D-PAD
    // =========================================================================
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            showTouchOverlay = false
            val lx = event.getAxisValue(MotionEvent.AXIS_X)
            val ly = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            val ax = if (abs(hx) > 0.2f) hx else lx
            val ay = if (abs(hy) > 0.2f) hy else ly

            val prevL = inLeft; val prevR = inRight; val prevU = inUp; val prevD = inDown
            inLeft = ax < -0.45f
            inRight = ax > 0.45f
            inUp = ay < -0.45f
            inDown = ay > 0.45f

            if ((inLeft && !prevL) || (inRight && !prevR)) {
                dasTimer = 0f
                arrTimer = 0f
            }

            val maxCards = if (height > width) 5 else 6
            if (state == STATE_HUB) {
                if (inLeft && !prevL) { hubIndex = (hubIndex + maxCards - 1) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inRight && !prevR) { hubIndex = (hubIndex + 1) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inUp && !prevU) { hubIndex = (hubIndex + maxCards - (if (height > width) 1 else 3)) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inDown && !prevD) { hubIndex = (hubIndex + (if (height > width) 1 else 3)) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
            } else if (state == STATE_READY) {
                if (inUp && !prevU) setDifficulty((difficulty + 2) % 3)
                if (inDown && !prevD) setDifficulty((difficulty + 1) % 3)
                if (inLeft && !prevL) { dialogIndex = (dialogIndex + 1) % 2; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inRight && !prevR) { dialogIndex = (dialogIndex + 1) % 2; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
            } else if (state == STATE_PAUSED || state == STATE_GAMEOVER) {
                val cnt = getDialogButtonCount()
                if (cnt > 0) {
                    if ((inLeft && !prevL) || (inUp && !prevU)) {
                        dialogIndex = (dialogIndex + cnt - 1) % cnt
                        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    }
                    if ((inRight && !prevR) || (inDown && !prevD)) {
                        dialogIndex = (dialogIndex + 1) % cnt
                        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    }
                }
            } else if (state == STATE_PLAYING) {
                if (mode == MODE_SNAKE) {
                    if (inLeft && !prevL) queueSnakeDir(-1, 0)
                    if (inRight && !prevR) queueSnakeDir(1, 0)
                    if (inUp && !prevU) queueSnakeDir(0, -1)
                    if (inDown && !prevD) queueSnakeDir(0, 1)
                } else if (mode == MODE_TETRIS) {
                    if (inLeft && !prevL) moveTetrisHorizontal(-1)
                    if (inRight && !prevR) moveTetrisHorizontal(1)
                    if (inUp && !prevU) rotateTetrisPiece()
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        showTouchOverlay = false

        if (state == STATE_READY) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { setDifficulty((difficulty + 2) % 3); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { setDifficulty((difficulty + 1) % 3); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    dialogIndex = (dialogIndex + 1) % 2
                    audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                    executeDialogChoice(dialogIndex)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> { onBackAction(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (state == STATE_PAUSED || state == STATE_GAMEOVER) {
            val cnt = getDialogButtonCount()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (cnt > 0) {
                        dialogIndex = (dialogIndex + cnt - 1) % cnt
                        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (cnt > 0) {
                        dialogIndex = (dialogIndex + 1) % cnt
                        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                    executeDialogChoice(dialogIndex)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> { onBackAction(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }

        val maxCards = if (height > width) 5 else 6
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                inLeft = true; dasTimer = 0f; arrTimer = 0f
                if (state == STATE_HUB) { hubIndex = (hubIndex + maxCards - 1) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(-1, 0)
                else if (mode == MODE_TETRIS) moveTetrisHorizontal(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                inRight = true; dasTimer = 0f; arrTimer = 0f
                if (state == STATE_HUB) { hubIndex = (hubIndex + 1) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(1, 0)
                else if (mode == MODE_TETRIS) moveTetrisHorizontal(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                inUp = true
                if (state == STATE_HUB) { hubIndex = (hubIndex + maxCards - (if (height > width) 1 else 3)) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(0, -1)
                else if (mode == MODE_TETRIS) rotateTetrisPiece()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                inDown = true
                if (state == STATE_HUB) { hubIndex = (hubIndex + (if (height > width) 1 else 3)) % maxCards; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(0, 1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                btnA = true
                handlePrimaryAction()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                btnB = true
                handleSecondaryAction()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (state == STATE_PLAYING && mode == MODE_TETRIS) holdTetrisPiece()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                if (state == STATE_PLAYING) {
                    clearInputs()
                    state = STATE_PAUSED
                    dialogIndex = 0
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { inLeft = false; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { inRight = false; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { inUp = false; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { inDown = false; return true }
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE -> { btnA = false; return true }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y -> { btnB = false; return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    // =========================================================================
    // INPUT: SMARTPHONE TOUCHSCREEN
    // =========================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        showTouchOverlay = true
        val w = width.toFloat()
        val h = height.toFloat()
        val actionMasked = event.actionMasked

        if (state == STATE_HUB) {
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                if (hudMuteRect.contains(x, y)) {
                    toggleMute()
                    return true
                }
                val count = if (h > w) 5 else 6
                for (i in 0 until count) {
                    if (hubRects[i].contains(x, y)) {
                        selectHubCard(i)
                        return true
                    }
                }
            }
            return true
        }

        if (state == STATE_READY || state == STATE_PAUSED || state == STATE_GAMEOVER) {
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                if (hudMuteRect.contains(x, y)) {
                    toggleMute()
                    return true
                }
                if (state == STATE_READY) {
                    for (d in 0..2) {
                        if (diffRects[d].contains(x, y)) {
                            setDifficulty(d)
                            return true
                        }
                    }
                }
                val cnt = getDialogButtonCount()
                for (i in 0 until cnt) {
                    if (dialogBtnRects[i].contains(x, y)) {
                        dialogIndex = i
                        executeDialogChoice(i)
                        return true
                    }
                }
            }
            return true
        }

        val hudH = dp(48f)
        val deckTop = getControlDeckTop(h)

        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            val idx = event.actionIndex
            val pid = event.getPointerId(idx)
            val px = event.getX(idx)
            val py = event.getY(idx)

            if (hudMuteRect.contains(px, py)) {
                toggleMute()
                return true
            }

            if (hudPauseRect.contains(px, py)) {
                clearInputs()
                state = STATE_PAUSED
                dialogIndex = 0
                return true
            }

            if (mode == MODE_TETRIS && tetrisHoldRect.contains(px, py)) {
                holdTetrisPiece()
                return true
            }

            if (isInsideCircleOrRect(px, py, touchBtnARect, dp(14f))) {
                if (!btnA) {
                    btnA = true
                    handlePrimaryAction()
                }
                return true
            }
            if (hasSecondaryButton() && isInsideCircleOrRect(px, py, touchBtnBRect, dp(14f))) {
                if (!btnB) {
                    btnB = true
                    handleSecondaryAction()
                }
                return true
            }

            if (py >= deckTop && px <= w * 0.58f && dpadPointerId == -1) {
                dpadPointerId = pid
                val margin = touchDpadRadius + dp(12f)
                dpadActiveX = px.coerceIn(margin, w * 0.48f)
                dpadActiveY = py.coerceIn(deckTop + touchDpadRadius * 0.7f, h - margin)
                updateVirtualStickFromPoint(px, py)
                return true
            }

            if (py > hudH && py < deckTop && boardPointerId == -1) {
                boardPointerId = pid
                boardHolding = true
                swipeStartX = px
                swipeStartY = py
                if (mode == MODE_BRICK) {
                    brickPaddleX = ((px - brickBoard.left) / max(1f, brickBoard.width())).coerceIn(0.08f, 0.92f)
                }
                return true
            }
        } else if (actionMasked == MotionEvent.ACTION_MOVE) {
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                val px = event.getX(i)
                val py = event.getY(i)

                if (pid == dpadPointerId) {
                    updateVirtualStickFromPoint(px, py)
                } else if (pid == boardPointerId) {
                    if (mode == MODE_BRICK) {
                        brickPaddleX = ((px - brickBoard.left) / max(1f, brickBoard.width())).coerceIn(0.08f, 0.92f)
                    } else if (mode == MODE_SNAKE) {
                        val sdx = px - swipeStartX
                        val sdy = py - swipeStartY
                        val minSwipe = dp(20f)
                        if (abs(sdx) > minSwipe || abs(sdy) > minSwipe) {
                            if (abs(sdx) > abs(sdy)) queueSnakeDir(if (sdx < 0) -1 else 1, 0)
                            else queueSnakeDir(0, if (sdy < 0) -1 else 1)
                            swipeStartX = px
                            swipeStartY = py
                        }
                    } else if (mode == MODE_TETRIS) {
                        val stepPx = max(dp(22f), tetrisCellPx)
                        val sdx = px - swipeStartX
                        val sdy = py - swipeStartY
                        if (abs(sdx) >= stepPx) {
                            moveTetrisHorizontal(if (sdx < 0) -1 else 1)
                            swipeStartX = px
                        }
                        if (sdy >= stepPx * 1.4f) inDown = true
                    }
                }
            }
        } else if (actionMasked == MotionEvent.ACTION_UP ||
            actionMasked == MotionEvent.ACTION_POINTER_UP ||
            actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            val idx = event.actionIndex
            val pid = event.getPointerId(idx)
            if (pid == dpadPointerId) {
                dpadPointerId = -1
                inLeft = false; inRight = false; inUp = false; inDown = false
                dpadActiveX = dpadBaseX
                dpadActiveY = dpadBaseY
                dpadThumbX = dpadBaseX
                dpadThumbY = dpadBaseY
            } else if (pid == boardPointerId) {
                if (mode == MODE_TETRIS) {
                    val totalDx = abs(event.getX(idx) - swipeStartX)
                    val totalDy = abs(event.getY(idx) - swipeStartY)
                    if (totalDx < dp(12f) && totalDy < dp(12f)) rotateTetrisPiece()
                    inDown = false
                }
                boardPointerId = -1
                boardHolding = false
            } else {
                var stillA = false
                var stillB = false
                for (i in 0 until event.pointerCount) {
                    if (i == idx) continue
                    val rx = event.getX(i)
                    val ry = event.getY(i)
                    if (isInsideCircleOrRect(rx, ry, touchBtnARect, dp(14f))) stillA = true
                    if (hasSecondaryButton() && isInsideCircleOrRect(rx, ry, touchBtnBRect, dp(14f))) stillB = true
                }
                btnA = stillA
                btnB = stillB
            }
        }
        return true
    }

    private fun isInsideCircleOrRect(x: Float, y: Float, r: RectF, pad: Float): Boolean {
        return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
    }

    private fun updateVirtualStickFromPoint(px: Float, py: Float) {
        val dx = px - dpadActiveX
        val dy = py - dpadActiveY
        val dist = hypot(dx, dy)
        val maxR = touchDpadRadius
        if (dist > maxR) {
            dpadThumbX = dpadActiveX + (dx / dist) * maxR
            dpadThumbY = dpadActiveY + (dy / dist) * maxR
        } else {
            dpadThumbX = px
            dpadThumbY = py
        }

        val dead = maxR * 0.22f
        val prevL = inLeft; val prevR = inRight; val prevU = inUp
        inLeft = false; inRight = false; inUp = false; inDown = false
        if (dist > dead) {
            when (mode) {
                MODE_BOMBER, MODE_SNAKE -> {
                    if (abs(dx) > abs(dy)) {
                        if (dx < 0f) inLeft = true else inRight = true
                        if (mode == MODE_SNAKE) queueSnakeDir(if (dx < 0f) -1 else 1, 0)
                    } else {
                        if (dy < 0f) inUp = true else inDown = true
                        if (mode == MODE_SNAKE) queueSnakeDir(0, if (dy < 0f) -1 else 1)
                    }
                }
                MODE_BRICK -> {
                    if (dx < -dead) inLeft = true
                    if (dx > dead) inRight = true
                }
                MODE_TETRIS -> {
                    if (dx < -dead) {
                        inLeft = true
                        if (!prevL) { dasTimer = 0f; moveTetrisHorizontal(-1) }
                    } else if (dx > dead) {
                        inRight = true
                        if (!prevR) { dasTimer = 0f; moveTetrisHorizontal(1) }
                    }
                    if (dy > dead) inDown = true
                    if (dy < -maxR * 0.55f) {
                        inUp = true
                        if (!prevU) rotateTetrisPiece()
                    }
                }
                MODE_ROCKET -> {
                    if (dx < -dead) inLeft = true
                    if (dx > dead) inRight = true
                    if (dy < -dead * 0.6f) inUp = true
                    if (dy > dead * 0.6f) inDown = true
                }
            }
        }
    }

    private fun hasSecondaryButton(): Boolean = (mode == MODE_BOMBER || mode == MODE_TETRIS || mode == MODE_ROCKET)

    private fun handlePrimaryAction() {
        if (state == STATE_HUB) {
            selectHubCard(hubIndex)
            return
        }
        if (state != STATE_PLAYING) return
        when (mode) {
            MODE_BOMBER -> placePlayerBomb()
            MODE_BRICK -> {
                launchAllInactiveBalls()
                if (brickLaserTimer > 0f) fireBrickLaser()
            }
            MODE_TETRIS -> rotateTetrisPiece()
            MODE_ROCKET -> rkVy = -0.58f
        }
    }

    private fun handleSecondaryAction() {
        if (state != STATE_PLAYING) return
        when (mode) {
            MODE_BOMBER -> detonateEarliestBomb()
            MODE_TETRIS -> hardDropTetrisPiece()
            MODE_ROCKET -> fireRocketLaser()
        }
    }

    private fun getControlDeckTop(h: Float): Float {
        if (!showTouchOverlay) return h
        val isPortrait = h > width
        return if (isPortrait) h * 0.72f else h - dp(148f)
    }

    // =========================================================================
    // MASTER UPDATE & DRAW
    // =========================================================================
    private fun update(dt: Float) {
        GameChiptuneEngine.sync(mode, state, audio.muted)
        if (shakeTimer > 0f) {
            shakeTimer = max(0f, shakeTimer - dt)
            if (shakeTimer <= 0f) shakeStrength = 0f
        }
        if (flashAlpha > 0f) {
            flashAlpha = max(0f, flashAlpha - dt * 1.4f)
        }

        for (i in 0 until maxParticles) {
            if (ptLife[i] > 0f) {
                ptLife[i] -= dt
                ptX[i] += ptVx[i] * dt
                ptY[i] += ptVy[i] * dt
                if (mode != MODE_ROCKET) ptVy[i] += dp(140f) * dt
            }
        }

        for (i in 0 until maxRings) {
            if (rgLife[i] > 0f) {
                rgLife[i] -= dt
            }
        }

        for (i in 0 until maxFloatTexts) {
            if (ftLife[i] > 0f) {
                ftLife[i] -= dt
                ftY[i] -= dp(36f) * dt
            }
        }

        if (state != STATE_PLAYING) return
        when (mode) {
            MODE_BOMBER -> updateBomber(dt)
            MODE_BRICK -> updateBrick(dt)
            MODE_SNAKE -> updateSnake(dt)
            MODE_TETRIS -> updateTetris(dt)
            MODE_ROCKET -> updateRocket(dt)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(COL_BG)

        val isPortrait = h > w
        touchDpadRadius = if (isPortrait) min(w * 0.16f, dp(64f)) else dp(52f)
        dpadBaseX = dp(26f) + touchDpadRadius
        dpadBaseY = h - dp(24f) - touchDpadRadius
        if (dpadPointerId == -1) {
            dpadActiveX = dpadBaseX
            dpadActiveY = dpadBaseY
            dpadThumbX = dpadBaseX
            dpadThumbY = dpadBaseY
        }

        val btnDia = if (isPortrait) min(w * 0.17f, dp(68f)) else dp(58f)
        touchBtnARect.set(w - dp(22f) - btnDia, h - dp(26f) - btnDia, w - dp(22f), h - dp(26f))
        touchBtnBRect.set(
            touchBtnARect.left - btnDia - dp(16f),
            touchBtnARect.top - dp(14f),
            touchBtnARect.left - dp(16f),
            touchBtnARect.bottom - dp(14f)
        )

        if (state == STATE_HUB) {
            drawHub(canvas, w, h)
            return
        }

        canvas.save()
        if (shakeTimer > 0f && shakeStrength > 0f) {
            val sx = (Random.nextFloat() * 2f - 1f) * shakeStrength
            val sy = (Random.nextFloat() * 2f - 1f) * shakeStrength
            canvas.translate(sx, sy)
        }

        when (mode) {
            MODE_BOMBER -> drawBomber(canvas, w, h)
            MODE_BRICK -> drawBrick(canvas, w, h)
            MODE_SNAKE -> drawSnake(canvas, w, h)
            MODE_TETRIS -> drawTetris(canvas, w, h)
            MODE_ROCKET -> drawRocket(canvas, w, h)
        }

        drawParticlesAndFloatTexts(canvas)
        canvas.restore()

        if (flashAlpha > 0f) {
            pFill.color = (flashColor and 0x00FFFFFF) or ((flashAlpha * 255).toInt() shl 24)
            canvas.drawRect(0f, 0f, w, h, pFill)
        }

        drawHud(canvas, w, isPortrait)

        if (showTouchOverlay && state == STATE_PLAYING) {
            drawTouchControls(canvas, w, h)
        }

        when (state) {
            STATE_READY -> drawReadyOverlay(canvas, w, h)
            STATE_PAUSED -> drawPauseOverlay(canvas, w, h)
            STATE_GAMEOVER -> drawGameOverOverlay(canvas, w, h)
        }
    }

    private fun drawParticlesAndFloatTexts(canvas: Canvas) {
        // Shockwave rings
        for (i in 0 until maxRings) {
            val life = rgLife[i]
            if (life > 0f) {
                val progress = 1f - (life / max(0.01f, rgMaxLife[i])).coerceIn(0f, 1f)
                val alpha = ((1f - progress) * 200).toInt()
                pStroke.strokeWidth = dp(2.5f) * (1f - progress * 0.6f)
                pStroke.color = (rgColor[i] and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawCircle(rgX[i], rgY[i], rgMaxR[i] * progress, pStroke)
            }
        }

        // Particles
        for (i in 0 until maxParticles) {
            val life = ptLife[i]
            if (life > 0f) {
                val ratio = (life / max(0.01f, ptMaxLife[i])).coerceIn(0f, 1f)
                val alpha = (ratio * 255).toInt()
                pFill.color = (ptColor[i] and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawCircle(ptX[i], ptY[i], ptSize[i] * ratio, pFill)
            }
        }

        // Floating texts
        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = dp(13f)
        for (i in 0 until maxFloatTexts) {
            val life = ftLife[i]
            if (life > 0f) {
                val alpha = ((life / 0.85f).coerceIn(0f, 1f) * 255).toInt()
                pText.color = (ftColor[i] and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawText(ftText[i], ftX[i], ftY[i], pText)
            }
        }
    }

    private fun drawHubMiniArt(canvas: Canvas, idx: Int, cx: Float, cy: Float, r: Float) {
        when (idx) {
            0 -> {
                pFill.color = 0x33E05252
                canvas.drawCircle(cx, cy, r * 1.15f, pFill)
                pFill.color = 0xFF1F1F26.toInt()
                canvas.drawCircle(cx, cy + r * 0.1f, r * 0.65f, pFill)
                pStroke.strokeWidth = dp(2f)
                pStroke.color = COL_RED
                canvas.drawCircle(cx, cy + r * 0.1f, r * 0.65f, pStroke)
                pStroke.color = COL_GOLD_LIGHT
                canvas.drawLine(cx + r * 0.25f, cy - r * 0.45f, cx + r * 0.55f, cy - r * 0.75f, pStroke)
            }
            1 -> {
                val bw = r * 0.58f
                val bh = r * 0.24f
                pFill.color = COL_RED
                rect.set(cx - bw - dp(2f), cy - bh * 1.4f, cx - dp(2f), cy - bh * 0.4f)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)
                pFill.color = COL_CYAN
                rect.set(cx + dp(2f), cy - bh * 1.4f, cx + bw + dp(2f), cy - bh * 0.4f)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)
                pFill.color = Color.WHITE
                canvas.drawCircle(cx, cy + r * 0.55f, dp(4.5f), pFill)
            }
            2 -> {
                pStroke.strokeWidth = dp(5f)
                pStroke.strokeCap = Paint.Cap.ROUND
                pStroke.color = COL_GOLD
                rect.set(cx - r * 0.65f, cy - r * 0.45f, cx + r * 0.55f, cy + r * 0.55f)
                canvas.drawArc(rect, 30f, 260f, false, pStroke)
                pFill.color = COL_RED
                canvas.drawCircle(cx + r * 0.15f, cy + r * 0.08f, dp(4.5f), pFill)
            }
            3 -> {
                val s = r * 0.44f
                pFill.color = COL_PURPLE
                rect.set(cx - s * 1.5f, cy, cx - s * 0.5f, cy + s)
                canvas.drawRoundRect(rect, dp(2.5f), dp(2.5f), pFill)
                rect.set(cx - s * 0.5f, cy, cx + s * 0.5f, cy + s)
                canvas.drawRoundRect(rect, dp(2.5f), dp(2.5f), pFill)
                rect.set(cx + s * 0.5f, cy, cx + s * 1.5f, cy + s)
                canvas.drawRoundRect(rect, dp(2.5f), dp(2.5f), pFill)
                pFill.color = COL_CYAN
                rect.set(cx - s * 0.5f, cy - s, cx + s * 0.5f, cy)
                canvas.drawRoundRect(rect, dp(2.5f), dp(2.5f), pFill)
            }
            4 -> {
                pFill.color = COL_ORANGE
                canvas.drawCircle(cx - r * 0.55f, cy, r * 0.25f, pFill)
                path.reset()
                path.moveTo(cx + r * 0.72f, cy)
                path.lineTo(cx - r * 0.35f, cy - r * 0.45f)
                path.lineTo(cx - r * 0.35f, cy + r * 0.45f)
                path.close()
                pFill.color = COL_IVORY
                canvas.drawPath(path, pFill)
                pFill.color = COL_CYAN
                canvas.drawCircle(cx + r * 0.10f, cy, r * 0.18f, pFill)
            }
            else -> {
                pStroke.strokeWidth = dp(2.2f)
                pStroke.color = COL_GOLD_LIGHT
                rect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f)
                canvas.drawRoundRect(rect, dp(6f), dp(6f), pStroke)
                pFill.color = COL_GOLD_LIGHT
                canvas.drawCircle(cx - r * 0.24f, cy - r * 0.24f, dp(2.5f), pFill)
                canvas.drawCircle(cx, cy, dp(2.5f), pFill)
                canvas.drawCircle(cx + r * 0.24f, cy + r * 0.24f, dp(2.5f), pFill)
            }
        }
    }

    private fun drawHub(canvas: Canvas, w: Float, h: Float) {
        val isPortrait = h > w
        val pad = dp(16f)

        val muteSz = dp(34f)
        hudMuteRect.set(w - pad - muteSz, pad, w - pad, pad + muteSz)
        pFill.color = COL_CARD
        canvas.drawRoundRect(hudMuteRect, dp(10f), dp(10f), pFill)
        pStroke.strokeWidth = dp(1.2f)
        pStroke.color = 0x33FFFFFF
        canvas.drawRoundRect(hudMuteRect, dp(10f), dp(10f), pStroke)
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.color = if (audio.muted) COL_RED else COL_GOLD_LIGHT
        pText.textSize = dp(14f)
        canvas.drawText(if (audio.muted) "🔇" else "🔊", hudMuteRect.centerX(), hudMuteRect.centerY() + dp(5f), pText)

        pText.typeface = Typeface.SERIF
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(if (isPortrait) 22f else 26f)
        canvas.drawText("B-BOX Arcade", pad, pad + dp(24f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_MUTE
        pText.textSize = dp(if (isPortrait) 11.5f else 13f)
        canvas.drawText("100% Offline · Ringan RAM 1–2 GB · 5 Game Klasik", pad, pad + dp(42f), pText)

        val topY = pad + dp(56f)
        val botY = h - dp(36f)
        val gap = dp(10f)

        if (isPortrait) {
            val cardH = (botY - topY - gap * 4f) / 5f
            for (i in 0..4) {
                val y = topY + i * (cardH + gap)
                hubRects[i].set(pad, y, w - pad, y + cardH)
            }
        } else {
            val cardW = (w - pad * 2f - gap * 2f) / 3f
            val cardH = (botY - topY - gap) / 2f
            for (i in 0..5) {
                val col = i % 3
                val row = i / 3
                val x = pad + col * (cardW + gap)
                val y = topY + row * (cardH + gap)
                hubRects[i].set(x, y, x + cardW, y + cardH)
            }
        }

        val count = if (isPortrait) 5 else 6
        for (i in 0 until count) {
            val r = hubRects[i]
            val focused = i == hubIndex
            val isRandomSlot = (i == 5)
            val accent = if (isRandomSlot) COL_GOLD_LIGHT else gameAccents[i]

            pFill.color = if (focused) ((accent and 0x00FFFFFF) or 0x36000000) else COL_CARD
            canvas.drawRoundRect(r, dp(15f), dp(15f), pFill)

            pStroke.strokeWidth = dp(if (focused) 2.5f else 1f)
            pStroke.color = if (focused) COL_GOLD_LIGHT else 0x28FFFFFF
            canvas.drawRoundRect(r, dp(15f), dp(15f), pStroke)

            val artR = min(r.height() * 0.32f, dp(23f))
            val artCx = r.left + dp(16f) + artR
            val artCy = r.centerY()
            drawHubMiniArt(canvas, i, artCx, artCy, artR)

            val tx = artCx + artR + dp(14f)
            pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            pText.textAlign = Paint.Align.LEFT
            pText.color = if (focused) COL_GOLD_LIGHT else Color.WHITE
            pText.textSize = dp(if (isPortrait) 16f else 18f)
            val title = if (isRandomSlot) "Main Acak" else gameTitles[i]
            canvas.drawText(title, tx, r.top + dp(if (isPortrait) 27f else 34f), pText)

            pText.typeface = Typeface.SANS_SERIF
            pText.color = COL_MUTE
            pText.textSize = dp(if (isPortrait) 11.5f else 12f)
            val desc = if (isRandomSlot) "Pilih game acak otomatis" else gameDescs[i]
            canvas.drawText(desc, tx, r.top + dp(if (isPortrait) 44f else 54f), pText)

            if (!isRandomSlot) {
                val hs = getHighScore(i + 1)
                pText.color = COL_GOLD_LIGHT
                pText.textSize = dp(11f)
                canvas.drawText("Rekor: $hs", tx, r.bottom - dp(if (isPortrait) 10f else 14f), pText)
            }
        }

        pText.textAlign = Paint.Align.CENTER
        pText.color = COL_MUTE
        pText.textSize = dp(11f)
        canvas.drawText("Sentuh Kartu / Tekan OK · Tombol BACK Kembali ke Beranda", w * 0.5f, h - dp(12f), pText)
    }

    private fun drawHud(canvas: Canvas, w: Float, isPortrait: Boolean) {
        val topH = dp(48f)
        pFill.color = 0xFF131317.toInt()
        canvas.drawRect(0f, 0f, w, topH, pFill)
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x22FFFFFF
        canvas.drawLine(0f, topH, w, topH, pStroke)

        val btnW = dp(80f)
        val btnH = dp(32f)
        hudPauseRect.set(w - dp(10f) - btnW, (topH - btnH) * 0.5f, w - dp(10f), (topH + btnH) * 0.5f)
        pFill.color = 0x2AE05252
        canvas.drawRoundRect(hudPauseRect, dp(10f), dp(10f), pFill)
        pStroke.strokeWidth = dp(1.2f)
        pStroke.color = COL_RED
        canvas.drawRoundRect(hudPauseRect, dp(10f), dp(10f), pStroke)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.color = Color.WHITE
        pText.textSize = dp(11.5f)
        canvas.drawText("❚❚ JEDA", hudPauseRect.centerX(), hudPauseRect.centerY() + dp(4f), pText)

        val muteW = dp(34f)
        hudMuteRect.set(hudPauseRect.left - dp(8f) - muteW, hudPauseRect.top, hudPauseRect.left - dp(8f), hudPauseRect.bottom)
        pFill.color = 0x1EFFFFFF
        canvas.drawRoundRect(hudMuteRect, dp(10f), dp(10f), pFill)
        pText.color = if (audio.muted) COL_RED else COL_GOLD_LIGHT
        pText.textSize = dp(13f)
        canvas.drawText(if (audio.muted) "🔇" else "🔊", hudMuteRect.centerX(), hudMuteRect.centerY() + dp(5f), pText)

        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(if (isPortrait) 13f else 14.5f)
        canvas.drawText("${gameTitles[mode - 1]} · S$stage", dp(12f), dp(29f), pText)

        pText.textAlign = Paint.Align.RIGHT
        pText.color = Color.WHITE
        pText.textSize = dp(if (isPortrait) 13f else 14f)
        val scoreStr = if (isPortrait) "SKOR: $score" else "SKOR: $score  (MAX: ${max(score, getHighScore(mode))})"
        canvas.drawText(scoreStr, hudMuteRect.left - dp(10f), dp(29f), pText)
    }

    private fun drawTouchControls(canvas: Canvas, w: Float, h: Float) {
        val deckTop = getControlDeckTop(h)
        pFill.color = 0xFF101014.toInt()
        canvas.drawRect(0f, deckTop, w, h, pFill)
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x24FFFFFF
        canvas.drawLine(0f, deckTop, w, deckTop, pStroke)

        pFill.color = 0x1EFFFFFF
        canvas.drawCircle(dpadActiveX, dpadActiveY, touchDpadRadius, pFill)
        pStroke.strokeWidth = dp(1.8f)
        pStroke.color = 0x55E6C88F
        canvas.drawCircle(dpadActiveX, dpadActiveY, touchDpadRadius, pStroke)

        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = dp(13f)
        val rOff = touchDpadRadius * 0.68f
        pText.color = if (inUp) COL_GOLD_LIGHT else 0x66FFFFFF
        canvas.drawText("▲", dpadActiveX, dpadActiveY - rOff + dp(4f), pText)
        pText.color = if (inDown) COL_GOLD_LIGHT else 0x66FFFFFF
        canvas.drawText("▼", dpadActiveX, dpadActiveY + rOff + dp(5f), pText)
        pText.color = if (inLeft) COL_GOLD_LIGHT else 0x66FFFFFF
        canvas.drawText("◄", dpadActiveX - rOff, dpadActiveY + dp(4f), pText)
        pText.color = if (inRight) COL_GOLD_LIGHT else 0x66FFFFFF
        canvas.drawText("►", dpadActiveX + rOff, dpadActiveY + dp(4f), pText)

        pFill.color = 0x99E6C88F.toInt()
        canvas.drawCircle(dpadThumbX, dpadThumbY, touchDpadRadius * 0.34f, pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = Color.WHITE
        canvas.drawCircle(dpadThumbX, dpadThumbY, touchDpadRadius * 0.34f, pStroke)

        val labelA = when (mode) {
            MODE_BOMBER -> "BOM"
            MODE_BRICK -> if (brickLaserTimer > 0f) "LASER" else "LEPAS"
            MODE_SNAKE -> "CEPAT"
            MODE_TETRIS -> "PUTAR"
            MODE_ROCKET -> "GAS"
            else -> "A"
        }
        pFill.color = if (btnA) 0x99E6C88F.toInt() else 0x3CE6C88F
        canvas.drawOval(touchBtnARect, pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = COL_GOLD_LIGHT
        canvas.drawOval(touchBtnARect, pStroke)
        pText.textAlign = Paint.Align.CENTER
        pText.color = Color.WHITE
        pText.textSize = dp(13f)
        canvas.drawText(labelA, touchBtnARect.centerX(), touchBtnARect.centerY() + dp(5f), pText)

        if (hasSecondaryButton()) {
            val labelB = when (mode) {
                MODE_BOMBER -> "LEDAK"
                MODE_TETRIS -> "JATUH"
                MODE_ROCKET -> "TEMBAK"
                else -> "B"
            }
            pFill.color = if (btnB) 0x99E05252.toInt() else 0x33FFFFFF
            canvas.drawOval(touchBtnBRect, pFill)
            pStroke.strokeWidth = dp(1.8f)
            pStroke.color = 0x88FFFFFF.toInt()
            canvas.drawOval(touchBtnBRect, pStroke)
            pText.color = Color.WHITE
            pText.textSize = dp(12f)
            canvas.drawText(labelB, touchBtnBRect.centerX(), touchBtnBRect.centerY() + dp(4f), pText)
        }
    }

    private fun drawActionButton(canvas: Canvas, r: RectF, label: String, focused: Boolean, accentColor: Int) {
        pFill.color = if (focused) accentColor else 0xFF23232A.toInt()
        canvas.drawRoundRect(r, dp(12f), dp(12f), pFill)
        pStroke.strokeWidth = dp(if (focused) 2f else 1f)
        pStroke.color = if (focused) Color.WHITE else 0x33FFFFFF
        canvas.drawRoundRect(r, dp(12f), dp(12f), pStroke)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.color = if (focused && accentColor == COL_GOLD_LIGHT) Color.BLACK else Color.WHITE
        pText.textSize = dp(13.5f)
        canvas.drawText(label, r.centerX(), r.centerY() + dp(5f), pText)
    }

    private fun drawReadyOverlay(canvas: Canvas, w: Float, h: Float) {
        pFill.color = 0xDC09090B.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val boxW = min(w * 0.90f, dp(470f))
        val boxH = dp(285f)
        rect.set((w - boxW) * 0.5f, (h - boxH) * 0.5f, (w + boxW) * 0.5f, (h + boxH) * 0.5f)
        pFill.color = COL_CARD
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = gameAccents[mode - 1]
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pStroke)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(22f)
        canvas.drawText(gameTitles[mode - 1], w * 0.5f, rect.top + dp(38f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_MUTE
        pText.textSize = dp(12f)
        canvas.drawText("Rekor Tertinggi: ${getHighScore(mode)}", w * 0.5f, rect.top + dp(60f), pText)

        // Difficulty Selector Pills (Santai / Normal / Sulit)
        val diffY1 = rect.top + dp(74f)
        val diffY2 = diffY1 + dp(34f)
        val dGap = dp(8f)
        val dW = (boxW - dp(36f) - dGap * 2f) / 3f
        for (d in 0..2) {
            val dx = rect.left + dp(18f) + d * (dW + dGap)
            diffRects[d].set(dx, diffY1, dx + dW, diffY2)
            val sel = (d == difficulty)
            pFill.color = if (sel) COL_GOLD else 0xFF202028.toInt()
            canvas.drawRoundRect(diffRects[d], dp(9f), dp(9f), pFill)
            pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            pText.color = if (sel) Color.BLACK else COL_IVORY
            pText.textSize = dp(11f)
            canvas.drawText(diffLabels[d], diffRects[d].centerX(), diffRects[d].centerY() + dp(4f), pText)
        }

        val guide = gameGuides[mode - 1]
        val mid = guide.length / 2
        val splitAt = guide.indexOf(' ', mid).let { if (it == -1) guide.length else it }
        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_IVORY
        pText.textSize = dp(12.5f)
        canvas.drawText(guide.substring(0, splitAt).trim(), w * 0.5f, rect.top + dp(142f), pText)
        if (splitAt < guide.length) {
            canvas.drawText(guide.substring(splitAt).trim(), w * 0.5f, rect.top + dp(162f), pText)
        }

        val btnY1 = rect.bottom - dp(62f)
        val btnY2 = rect.bottom - dp(18f)
        val gap = dp(12f)
        val halfW = (boxW - dp(36f) - gap) * 0.5f
        dialogBtnRects[0].set(rect.left + dp(18f), btnY1, rect.left + dp(18f) + halfW, btnY2)
        dialogBtnRects[1].set(dialogBtnRects[0].right + gap, btnY1, rect.right - dp(18f), btnY2)

        drawActionButton(canvas, dialogBtnRects[0], "▶ MULAI MAIN", dialogIndex == 0, COL_GOLD_LIGHT)
        drawActionButton(canvas, dialogBtnRects[1], "KEMBALI", dialogIndex == 1, COL_RED)
    }

    private fun drawPauseOverlay(canvas: Canvas, w: Float, h: Float) {
        pFill.color = 0xDC09090B.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val boxW = min(w * 0.86f, dp(380f))
        val boxH = dp(245f)
        rect.set((w - boxW) * 0.5f, (h - boxH) * 0.5f, (w + boxW) * 0.5f, (h + boxH) * 0.5f)
        pFill.color = COL_CARD
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = COL_GOLD_LIGHT
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pStroke)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(22f)
        canvas.drawText("PERMAINAN DIJEDA", w * 0.5f, rect.top + dp(42f), pText)

        val labels = arrayOf("▶ LANJUTKAN", "↻ ULANGI", "■ STOP / KELUAR")
        val colors = intArrayOf(COL_GREEN, COL_GOLD_LIGHT, COL_RED)
        val btnH = dp(42f)
        val startY = rect.top + dp(64f)
        for (i in 0..2) {
            val y = startY + i * (btnH + dp(10f))
            dialogBtnRects[i].set(rect.left + dp(24f), y, rect.right - dp(24f), y + btnH)
            drawActionButton(canvas, dialogBtnRects[i], labels[i], dialogIndex == i, colors[i])
        }
    }

    private fun drawGameOverOverlay(canvas: Canvas, w: Float, h: Float) {
        pFill.color = 0xDC09090B.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val boxW = min(w * 0.86f, dp(400f))
        val boxH = dp(200f)
        rect.set((w - boxW) * 0.5f, (h - boxH) * 0.5f, (w + boxW) * 0.5f, (h + boxH) * 0.5f)
        val titleCol = if (victory) COL_GREEN else COL_RED
        pFill.color = COL_CARD
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = titleCol
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pStroke)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.color = titleCol
        pText.textSize = dp(23f)
        canvas.drawText(if (victory) "STAGE SELESAI!" else "GAME OVER", w * 0.5f, rect.top + dp(46f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_IVORY
        pText.textSize = dp(15f)
        canvas.drawText("Skor Anda: $score  ·  Rekor: ${getHighScore(mode)}", w * 0.5f, rect.top + dp(82f), pText)

        val btnY1 = rect.bottom - dp(64f)
        val btnY2 = rect.bottom - dp(18f)
        val gap = dp(12f)
        val halfW = (boxW - dp(36f) - gap) * 0.5f
        dialogBtnRects[0].set(rect.left + dp(18f), btnY1, rect.left + dp(18f) + halfW, btnY2)
        dialogBtnRects[1].set(dialogBtnRects[0].right + gap, btnY1, rect.right - dp(18f), btnY2)

        val nextLabel = if (victory) "▶ STAGE $stage" else "↻ MAIN LAGI"
        drawActionButton(canvas, dialogBtnRects[0], nextLabel, dialogIndex == 0, COL_GOLD_LIGHT)
        drawActionButton(canvas, dialogBtnRects[1], "■ MENU GAME", dialogIndex == 1, COL_RED)
    }

    // =========================================================================
    // GAME 1: BOMBER ARENA (WITH SHIELD POWER-UP & SHOCKWAVE RINGS)
    // =========================================================================
    private val bCols = 13
    private val bRows = 9
    private val bGrid = IntArray(bCols * bRows) // 3=+bomb, 4=+range, 5=+speed, 6=+shield
    private var bpX = 1.5f
    private var bpY = 1.5f
    private var bpMaxBombs = 1
    private var bpRange = 2
    private var bpSpeed = 4.4f
    private var bpShield = false
    private var bpInvuln = 0f
    private var bpAlive = true
    private var bomberCellPx = 32f
    private var bomberOx = 0f
    private var bomberOy = 0f

    private val botX = FloatArray(3)
    private val botY = FloatArray(3)
    private val botDir = IntArray(3)
    private val botAlive = BooleanArray(3)
    private val botBombCd = FloatArray(3)

    private val maxBombs = 8
    private val bombX = IntArray(maxBombs)
    private val bombY = IntArray(maxBombs)
    private val bombTimer = FloatArray(maxBombs)
    private val bombRange = IntArray(maxBombs)
    private val bombOwner = IntArray(maxBombs)
    private val fireGrid = FloatArray(bCols * bRows)

    private fun initBomber() {
        bpX = 1.5f; bpY = 1.5f
        bpMaxBombs = 1
        bpRange = 2
        bpSpeed = 4.5f
        bpShield = (difficulty == DIFF_EASY)
        bpInvuln = 0f
        bpAlive = true
        for (i in 0 until maxBombs) bombTimer[i] = 0f
        fireGrid.fill(0f)

        for (r in 0 until bRows) {
            for (c in 0 until bCols) {
                val idx = r * bCols + c
                bGrid[idx] = when {
                    r == 0 || r == bRows - 1 || c == 0 || c == bCols - 1 -> 1
                    r % 2 == 0 && c % 2 == 0 -> 1
                    (r <= 2 && c <= 2) || (r >= bRows - 3 && c >= bCols - 3) ||
                        (r <= 2 && c >= bCols - 3) || (r >= bRows - 3 && c <= 2) -> 0
                    Random.nextFloat() < 0.65f -> 2
                    else -> 0
                }
            }
        }

        val spawns = arrayOf(
            floatArrayOf(bCols - 1.5f, bRows - 1.5f),
            floatArrayOf(bCols - 1.5f, 1.5f),
            floatArrayOf(1.5f, bRows - 1.5f)
        )
        for (i in 0..2) {
            botX[i] = spawns[i][0]
            botY[i] = spawns[i][1]
            botDir[i] = i
            botAlive[i] = true
            botBombCd[i] = 2.5f + i
        }
    }

    private fun isBomberWalkable(gx: Int, gy: Int): Boolean {
        if (gx < 1 || gx >= bCols - 1 || gy < 1 || gy >= bRows - 1) return false
        val cell = bGrid[gy * bCols + gx]
        if (cell == 1 || cell == 2) return false
        for (i in 0 until maxBombs) {
            if (bombTimer[i] > 0f && bombX[i] == gx && bombY[i] == gy) return false
        }
        return true
    }

    private fun placePlayerBomb() {
        if (!bpAlive) return
        val gx = bpX.toInt()
        val gy = bpY.toInt()
        var activeCount = 0
        for (i in 0 until maxBombs) {
            if (bombTimer[i] > 0f) {
                if (bombX[i] == gx && bombY[i] == gy) return
                if (bombOwner[i] == 0) activeCount++
            }
        }
        if (activeCount >= bpMaxBombs) return
        for (i in 0 until maxBombs) {
            if (bombTimer[i] <= 0f) {
                bombX[i] = gx
                bombY[i] = gy
                bombTimer[i] = 2.2f
                bombRange[i] = bpRange
                bombOwner[i] = 0
                audio.play(RetroAudioEngine.SFX_BOUNCE, 0.7f)
                return
            }
        }
    }

    private fun detonateEarliestBomb() {
        var best = -1
        var minT = 99f
        for (i in 0 until maxBombs) {
            if (bombTimer[i] > 0f && bombOwner[i] == 0 && bombTimer[i] < minT) {
                minT = bombTimer[i]
                best = i
            }
        }
        if (best >= 0) bombTimer[best] = 0.01f
    }

    private fun triggerExplosion(idx: Int) {
        val bx = bombX[idx]
        val by = bombY[idx]
        val rng = bombRange[idx]
        bombTimer[idx] = 0f
        fireGrid[by * bCols + bx] = 0.45f

        audio.play(RetroAudioEngine.SFX_EXPLODE)
        triggerShake(0.18f, 6f)
        val cx = bomberOx + (bx + 0.5f) * bomberCellPx
        val cy = bomberOy + (by + 0.5f) * bomberCellPx
        spawnRing(cx, cy, 55f, COL_ORANGE, 0.32f)
        spawnParticles(cx, cy, 14, COL_ORANGE, dp(40f), dp(160f), 4.5f)

        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        for (d in dirs) {
            for (step in 1..rng) {
                val nx = bx + d[0] * step
                val ny = by + d[1] * step
                if (nx < 1 || nx >= bCols - 1 || ny < 1 || ny >= bRows - 1) break
                val cellIdx = ny * bCols + nx
                val cell = bGrid[cellIdx]
                if (cell == 1) break
                fireGrid[cellIdx] = 0.45f
                val fx = bomberOx + (nx + 0.5f) * bomberCellPx
                val fy = bomberOy + (ny + 0.5f) * bomberCellPx
                spawnParticles(fx, fy, 4, COL_GOLD_LIGHT, dp(25f), dp(95f), 3.5f)
                if (cell == 2) {
                    val roll = Random.nextFloat()
                    bGrid[cellIdx] = when {
                        roll < 0.13f -> 3
                        roll < 0.26f -> 4
                        roll < 0.37f -> 5
                        roll < 0.46f -> 6
                        else -> 0
                    }
                    addPoints(15)
                    spawnFloatText(fx, fy, "+15", COL_GOLD_LIGHT)
                    break
                }
            }
        }
    }

    private fun updateBomber(dt: Float) {
        if (bpInvuln > 0f) bpInvuln = max(0f, bpInvuln - dt)
        var vx = 0f
        var vy = 0f
        if (inLeft) vx -= 1f
        if (inRight) vx += 1f
        if (inUp) vy -= 1f
        if (inDown) vy += 1f

        val curGx = bpX.toInt()
        val curGy = bpY.toInt()
        if (vx != 0f) {
            val nextX = bpX + vx * bpSpeed * dt
            val checkGx = (nextX + (if (vx > 0) 0.34f else -0.34f)).toInt()
            if (checkGx == curGx || isBomberWalkable(checkGx, curGy)) {
                bpX = nextX
                bpY += ((curGy + 0.5f) - bpY) * 12f * dt
            }
        }
        if (vy != 0f) {
            val nextY = bpY + vy * bpSpeed * dt
            val checkGy = (nextY + (if (vy > 0) 0.34f else -0.34f)).toInt()
            if (checkGy == curGy || isBomberWalkable(curGx, checkGy)) {
                bpY = nextY
                bpX += ((curGx + 0.5f) - bpX) * 12f * dt
            }
        }

        val pCellIdx = bpY.toInt() * bCols + bpX.toInt()
        val item = bGrid[pCellIdx]
        if (item in 3..6) {
            bGrid[pCellIdx] = 0
            audio.play(RetroAudioEngine.SFX_POWERUP)
            val px = bomberOx + bpX * bomberCellPx
            val py = bomberOy + bpY * bomberCellPx
            spawnRing(px, py, 36f, COL_GOLD_LIGHT, 0.25f)
            when (item) {
                3 -> { bpMaxBombs = min(4, bpMaxBombs + 1); spawnFloatText(px, py, "+BOM!", COL_CYAN) }
                4 -> { bpRange = min(5, bpRange + 1); spawnFloatText(px, py, "+API!", COL_GOLD_LIGHT) }
                5 -> { bpSpeed = min(6.4f, bpSpeed + 0.65f); spawnFloatText(px, py, "+CEPAT!", COL_GREEN) }
                6 -> { bpShield = true; spawnFloatText(px, py, "+PERISAI!", COL_PURPLE) }
            }
            addPoints(50)
        }

        for (i in 0 until maxBombs) {
            if (bombTimer[i] > 0f) {
                bombTimer[i] -= dt
                if (bombTimer[i] <= 0f) triggerExplosion(i)
            }
        }
        for (i in fireGrid.indices) {
            if (fireGrid[i] > 0f) fireGrid[i] = max(0f, fireGrid[i] - dt)
        }

        if (fireGrid[bpY.toInt() * bCols + bpX.toInt()] > 0f && bpInvuln <= 0f) {
            if (bpShield) {
                bpShield = false
                bpInvuln = 1.6f
                triggerShake(0.15f, 5f)
                audio.play(RetroAudioEngine.SFX_EXPLODE, 1.2f)
                val px = bomberOx + bpX * bomberCellPx
                val py = bomberOy + bpY * bomberCellPx
                spawnRing(px, py, 44f, COL_PURPLE, 0.3f)
                spawnFloatText(px, py, "PERISAI PECAH!", COL_PURPLE)
            } else {
                bpAlive = false
                audio.play(RetroAudioEngine.SFX_OVER)
                clearInputs()
                state = STATE_GAMEOVER
                dialogIndex = 0
                victory = false
                saveHighScore(MODE_BOMBER, score)
                return
            }
        }

        var aliveBots = 0
        val dxs = floatArrayOf(1f, -1f, 0f, 0f)
        val dys = floatArrayOf(0f, 0f, 1f, -1f)
        val botBaseSpd = (2.1f + difficulty * 0.45f) + stage * 0.15f
        for (b in 0..2) {
            if (!botAlive[b]) continue
            aliveBots++
            val bgx = botX[b].toInt()
            val bgy = botY[b].toInt()
            if (fireGrid[bgy * bCols + bgx] > 0f) {
                botAlive[b] = false
                addPoints(200)
                audio.play(RetroAudioEngine.SFX_COIN, 1.1f)
                val bx = bomberOx + botX[b] * bomberCellPx
                val by = bomberOy + botY[b] * bomberCellPx
                spawnRing(bx, by, 45f, COL_RED, 0.3f)
                spawnParticles(bx, by, 16, COL_RED, dp(30f), dp(140f), 4f)
                spawnFloatText(bx, by, "+200!", COL_RED)
                continue
            }

            val dir = botDir[b]
            val nx = botX[b] + dxs[dir] * botBaseSpd * dt
            val ny = botY[b] + dys[dir] * botBaseSpd * dt
            val checkX = (nx + dxs[dir] * 0.35f).toInt()
            val checkY = (ny + dys[dir] * 0.35f).toInt()
            if ((checkX == bgx && checkY == bgy) || isBomberWalkable(checkX, checkY)) {
                botX[b] = nx
                botY[b] = ny
            } else {
                botDir[b] = Random.nextInt(4)
            }

            botBombCd[b] -= dt
            if (botBombCd[b] <= 0f) {
                botBombCd[b] = max(2.2f, (4.6f - difficulty * 0.6f) + Random.nextFloat() * 1.5f)
                for (i in 0 until maxBombs) {
                    if (bombTimer[i] <= 0f) {
                        bombX[i] = bgx
                        bombY[i] = bgy
                        bombTimer[i] = 2.4f
                        bombRange[i] = 2
                        bombOwner[i] = 1
                        botDir[b] = (dir + 1) % 4
                        break
                    }
                }
            }
        }

        if (aliveBots == 0) {
            addPoints(300 * stage)
            stage++
            audio.play(RetroAudioEngine.SFX_WIN)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = true
            saveHighScore(MODE_BOMBER, score)
        }
    }

    private fun drawBomber(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(56f)
        val botOffset = (h - getControlDeckTop(h)) + dp(12f)
        val availW = w - dp(20f)
        val availH = h - topOffset - botOffset
        val cell = min(availW / bCols, availH / bRows)
        val ox = (w - cell * bCols) * 0.5f
        val oy = topOffset + (availH - cell * bRows) * 0.5f
        bomberCellPx = cell
        bomberOx = ox
        bomberOy = oy

        for (r in 0 until bRows) {
            for (c in 0 until bCols) {
                val x = ox + c * cell
                val y = oy + r * cell
                rect.set(x + 1f, y + 1f, x + cell - 1f, y + cell - 1f)
                val v = bGrid[r * bCols + c]
                pFill.color = when (v) {
                    1 -> 0xFF282830.toInt()
                    2 -> 0xFF7A5636.toInt()
                    else -> 0xFF131518.toInt()
                }
                canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)

                if (v in 3..6) {
                    val col = when (v) { 3 -> COL_CYAN; 4 -> COL_GOLD_LIGHT; 5 -> COL_GREEN; else -> COL_PURPLE }
                    pFill.color = (col and 0x00FFFFFF) or 0x44000000
                    canvas.drawCircle(x + cell * 0.5f, y + cell * 0.5f, cell * 0.36f, pFill)
                    pFill.color = col
                    canvas.drawCircle(x + cell * 0.5f, y + cell * 0.5f, cell * 0.23f, pFill)
                }

                if (fireGrid[r * bCols + c] > 0f) {
                    pFill.color = 0xEEFF8F00.toInt()
                    canvas.drawRoundRect(rect, dp(6f), dp(6f), pFill)
                }
            }
        }

        for (i in 0 until maxBombs) {
            if (bombTimer[i] > 0f) {
                val pulse = 0.30f + 0.06f * sin(bombTimer[i] * 14f)
                pFill.color = if (bombTimer[i] < 0.7f) COL_RED else Color.BLACK
                canvas.drawCircle(ox + (bombX[i] + 0.5f) * cell, oy + (bombY[i] + 0.5f) * cell, cell * pulse, pFill)
                pStroke.strokeWidth = dp(2f)
                pStroke.color = COL_GOLD_LIGHT
                canvas.drawCircle(ox + (bombX[i] + 0.5f) * cell, oy + (bombY[i] + 0.5f) * cell, cell * pulse, pStroke)
            }
        }

        for (b in 0..2) {
            if (!botAlive[b]) continue
            val bx = ox + botX[b] * cell
            val by = oy + botY[b] * cell
            pFill.color = COL_RED
            canvas.drawCircle(bx, by, cell * 0.34f, pFill)
        }

        if (bpAlive) {
            val px = ox + bpX * cell
            val py = oy + bpY * cell
            pFill.color = COL_GOLD_LIGHT
            canvas.drawCircle(px, py, cell * 0.36f, pFill)
            if (bpShield) {
                pStroke.strokeWidth = dp(2.2f)
                pStroke.color = COL_PURPLE
                canvas.drawCircle(px, py, cell * 0.45f, pStroke)
            }
        }
    }

    // =========================================================================
    // GAME 2: NEON BRICK (LASER PADDLE, FIREBALL, MULTI-BALL & WIDE PADDLE)
    // =========================================================================
    private val brickBoard = RectF()
    private val brCols = 9
    private val brRows = 6
    private val bricks = IntArray(brCols * brRows)
    private var brickPaddleX = 0.5f
    private var brickPaddleW = 0.24f
    private var brickWideTimer = 0f
    private var brickLaserTimer = 0f
    private var brickFireTimer = 0f
    private var brickLaserCd = 0f
    private var brickLives = 3

    private val maxBalls = 3
    private val ballActive = BooleanArray(maxBalls)
    private val ballLaunched = BooleanArray(maxBalls)
    private val ballX = FloatArray(maxBalls)
    private val ballY = FloatArray(maxBalls)
    private val ballVx = FloatArray(maxBalls)
    private val ballVy = FloatArray(maxBalls)

    // Power-up capsules: 1=Wide, 2=MultiBall, 3=Laser, 4=Fireball, 5=+100
    private val maxCapsules = 5
    private val capActive = BooleanArray(maxCapsules)
    private val capType = IntArray(maxCapsules)
    private val capX = FloatArray(maxCapsules)
    private val capY = FloatArray(maxCapsules)

    // Paddle Laser bolts (6 slots)
    private val maxBrickLasers = 6
    private val bLsrActive = BooleanArray(maxBrickLasers)
    private val bLsrX = FloatArray(maxBrickLasers)
    private val bLsrY = FloatArray(maxBrickLasers)

    private fun initBrick() {
        brickPaddleX = 0.5f
        brickPaddleW = 0.24f
        brickWideTimer = 0f
        brickLaserTimer = 0f
        brickFireTimer = 0f
        brickLaserCd = 0f
        brickLives = if (difficulty == DIFF_HARD) 2 else 3
        capActive.fill(false)
        bLsrActive.fill(false)
        resetSingleBall()
        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                bricks[r * brCols + c] = if (r < 2 && (stage > 1 || difficulty == DIFF_HARD)) 2 else 1
            }
        }
    }

    private fun resetSingleBall() {
        val spdMult = 0.88f + difficulty * 0.14f
        ballActive.fill(false)
        ballLaunched.fill(false)
        ballActive[0] = true
        ballLaunched[0] = false
        ballX[0] = brickPaddleX
        ballY[0] = 0.84f
        ballVx[0] = (if (Random.nextBoolean()) 1f else -1f) * (0.40f + stage * 0.03f) * spdMult
        ballVy[0] = -(0.65f + stage * 0.04f) * spdMult
    }

    private fun launchAllInactiveBalls() {
        var launchedAny = false
        for (i in 0 until maxBalls) {
            if (ballActive[i] && !ballLaunched[i]) {
                ballLaunched[i] = true
                launchedAny = true
            }
        }
        if (launchedAny) audio.play(RetroAudioEngine.SFX_BOUNCE, 1.2f)
    }

    private fun fireBrickLaser() {
        if (brickLaserCd > 0f) return
        for (i in 0 until maxBrickLasers - 1) {
            if (!bLsrActive[i] && !bLsrActive[i + 1]) {
                bLsrActive[i] = true
                bLsrX[i] = brickPaddleX - brickPaddleW * 0.35f
                bLsrY[i] = 0.85f
                bLsrActive[i + 1] = true
                bLsrX[i + 1] = brickPaddleX + brickPaddleW * 0.35f
                bLsrY[i + 1] = 0.85f
                brickLaserCd = 0.32f
                audio.play(RetroAudioEngine.SFX_LASER)
                return
            }
        }
    }

    private fun spawnMultiBalls() {
        var src = 0
        for (i in 0 until maxBalls) if (ballActive[i]) { src = i; break }
        for (i in 0 until maxBalls) {
            if (!ballActive[i]) {
                ballActive[i] = true
                ballLaunched[i] = true
                ballX[i] = ballX[src]
                ballY[i] = ballY[src]
                ballVx[i] = (if (i == 1) -0.48f else 0.48f)
                ballVy[i] = -abs(ballVy[src])
            }
        }
    }

    private fun updateBrick(dt: Float) {
        if (brickWideTimer > 0f) {
            brickWideTimer -= dt
            brickPaddleW = if (brickWideTimer > 0f) 0.36f else 0.24f
        }
        if (brickLaserTimer > 0f) brickLaserTimer = max(0f, brickLaserTimer - dt)
        if (brickFireTimer > 0f) brickFireTimer = max(0f, brickFireTimer - dt)
        if (brickLaserCd > 0f) brickLaserCd = max(0f, brickLaserCd - dt)

        if (btnA && brickLaserTimer > 0f) fireBrickLaser()

        val pSpeed = 1.35f
        if (inLeft) brickPaddleX -= pSpeed * dt
        if (inRight) brickPaddleX += pSpeed * dt
        brickPaddleX = brickPaddleX.coerceIn(brickPaddleW * 0.5f, 1f - brickPaddleW * 0.5f)

        val rowColors = intArrayOf(COL_RED, COL_GOLD, COL_GOLD_LIGHT, COL_GREEN, COL_CYAN, COL_PURPLE)
        val topArea = 0.08f
        val rowH = 0.055f
        val colW = 1f / brCols

        // Update paddle lasers
        for (l in 0 until maxBrickLasers) {
            if (!bLsrActive[l]) continue
            bLsrY[l] -= 1.25f * dt
            if (bLsrY[l] < 0.02f) {
                bLsrActive[l] = false
                continue
            }
            val c = (bLsrX[l] / colW).toInt().coerceIn(0, brCols - 1)
            val r = ((bLsrY[l] - topArea) / rowH).toInt()
            if (r in 0 until brRows) {
                val idx = r * brCols + c
                if (bricks[idx] > 0) {
                    bricks[idx]--
                    bLsrActive[l] = false
                    addPoints(20)
                    val px = brickBoard.left + brickBoard.width() * ((c + 0.5f) * colW)
                    val py = brickBoard.top + brickBoard.height() * (topArea + (r + 0.5f) * rowH)
                    spawnParticles(px, py, 6, COL_RED, dp(25f), dp(90f), 3f)
                }
            }
        }

        // Update falling power-up capsules
        for (k in 0 until maxCapsules) {
            if (!capActive[k]) continue
            capY[k] += 0.38f * dt
            if (capY[k] in 0.84f..0.91f && abs(capX[k] - brickPaddleX) <= brickPaddleW * 0.55f) {
                capActive[k] = false
                audio.play(RetroAudioEngine.SFX_POWERUP)
                val fx = brickBoard.left + brickBoard.width() * capX[k]
                val fy = brickBoard.top + brickBoard.height() * capY[k]
                spawnRing(fx, fy, 40f, COL_GOLD_LIGHT, 0.25f)
                when (capType[k]) {
                    1 -> { brickWideTimer = 14f; brickPaddleW = 0.36f; spawnFloatText(fx, fy, "LEBAR!", COL_GREEN) }
                    2 -> { spawnMultiBalls(); spawnFloatText(fx, fy, "MULTI-BOLA!", COL_CYAN) }
                    3 -> { brickLaserTimer = 12f; spawnFloatText(fx, fy, "LASER!", COL_RED) }
                    4 -> { brickFireTimer = 9f; spawnFloatText(fx, fy, "BOLA API!", COL_ORANGE) }
                    else -> { addPoints(100); spawnFloatText(fx, fy, "+100!", COL_GOLD_LIGHT) }
                }
            } else if (capY[k] > 1.03f) {
                capActive[k] = false
            }
        }

        var activeBallCount = 0
        for (b in 0 until maxBalls) {
            if (!ballActive[b]) continue
            activeBallCount++

            if (!ballLaunched[b]) {
                ballX[b] = brickPaddleX
                ballY[b] = 0.84f
                continue
            }

            ballX[b] += ballVx[b] * dt
            ballY[b] += ballVy[b] * dt

            if (ballX[b] < 0.02f) { ballX[b] = 0.02f; ballVx[b] = abs(ballVx[b]); audio.play(RetroAudioEngine.SFX_BOUNCE, 0.85f) }
            if (ballX[b] > 0.98f) { ballX[b] = 0.98f; ballVx[b] = -abs(ballVx[b]); audio.play(RetroAudioEngine.SFX_BOUNCE, 0.85f) }
            if (ballY[b] < 0.02f) { ballY[b] = 0.02f; ballVy[b] = abs(ballVy[b]); audio.play(RetroAudioEngine.SFX_BOUNCE, 0.95f) }

            if (ballVy[b] > 0f && ballY[b] in 0.85f..0.90f &&
                abs(ballX[b] - brickPaddleX) <= brickPaddleW * 0.55f
            ) {
                val hitOffset = (ballX[b] - brickPaddleX) / (brickPaddleW * 0.5f)
                ballVx[b] = hitOffset * 0.74f
                ballVy[b] = -abs(ballVy[b])
                audio.play(RetroAudioEngine.SFX_BOUNCE, 1.1f)
            }

            for (r in 0 until brRows) {
                var hitBrick = false
                for (c in 0 until brCols) {
                    val idx = r * brCols + c
                    if (bricks[idx] <= 0) continue
                    val bx1 = c * colW + 0.008f
                    val bx2 = (c + 1) * colW - 0.008f
                    val by1 = topArea + r * rowH + 0.006f
                    val by2 = topArea + (r + 1) * rowH - 0.006f
                    if (ballX[b] in (bx1 - 0.015f)..(bx2 + 0.015f) &&
                        ballY[b] in (by1 - 0.015f)..(by2 + 0.015f)
                    ) {
                        if (brickFireTimer > 0f) {
                            bricks[idx] = 0 // Fireball pierces straight through
                        } else {
                            bricks[idx]--
                            ballVy[b] = -ballVy[b]
                        }
                        addPoints(25)
                        audio.play(RetroAudioEngine.SFX_COIN, 1f + (brRows - r) * 0.06f)

                        val px = brickBoard.left + brickBoard.width() * ((bx1 + bx2) * 0.5f)
                        val py = brickBoard.top + brickBoard.height() * ((by1 + by2) * 0.5f)
                        spawnParticles(px, py, 8, if (brickFireTimer > 0f) COL_ORANGE else rowColors[r % rowColors.size], dp(30f), dp(110f), 3.5f)

                        if (bricks[idx] == 0 && Random.nextFloat() < 0.26f) {
                            for (k in 0 until maxCapsules) {
                                if (!capActive[k]) {
                                    capActive[k] = true
                                    capType[k] = Random.nextInt(1, 6)
                                    capX[k] = (bx1 + bx2) * 0.5f
                                    capY[k] = (by1 + by2) * 0.5f
                                    break
                                }
                            }
                        }
                        hitBrick = true
                        break
                    }
                }
                if (hitBrick) break
            }

            if (ballY[b] > 1.02f) {
                ballActive[b] = false
                activeBallCount--
            }
        }

        var remaining = 0
        for (hp in bricks) if (hp > 0) remaining++

        if (remaining == 0) {
            addPoints(250 * stage)
            stage++
            audio.play(RetroAudioEngine.SFX_WIN)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = true
            saveHighScore(MODE_BRICK, score)
            return
        }

        if (activeBallCount <= 0) {
            brickLives--
            triggerShake(0.15f, 5f)
            if (brickLives <= 0) {
                audio.play(RetroAudioEngine.SFX_OVER)
                clearInputs()
                state = STATE_GAMEOVER
                dialogIndex = 0
                victory = false
                saveHighScore(MODE_BRICK, score)
            } else {
                audio.play(RetroAudioEngine.SFX_LASER)
                resetSingleBall()
            }
        }
    }

    private fun drawBrick(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(56f)
        val botOffset = (h - getControlDeckTop(h)) + dp(12f)
        val boardW = min(w - dp(20f), dp(680f))
        val boardH = h - topOffset - botOffset
        brickBoard.set((w - boardW) * 0.5f, topOffset, (w + boardW) * 0.5f, topOffset + boardH)

        pFill.color = 0xFF121216.toInt()
        canvas.drawRoundRect(brickBoard, dp(14f), dp(14f), pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x2EFFFFFF
        canvas.drawRoundRect(brickBoard, dp(14f), dp(14f), pStroke)

        val rowColors = intArrayOf(COL_RED, COL_GOLD, COL_GOLD_LIGHT, COL_GREEN, COL_CYAN, COL_PURPLE)
        val colW = brickBoard.width() / brCols
        val rowH = brickBoard.height() * 0.055f
        val topY = brickBoard.top + brickBoard.height() * 0.08f

        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                val hp = bricks[r * brCols + c]
                if (hp <= 0) continue
                rect.set(
                    brickBoard.left + c * colW + dp(3f),
                    topY + r * rowH + dp(3f),
                    brickBoard.left + (c + 1) * colW - dp(3f),
                    topY + (r + 1) * rowH - dp(3f)
                )
                pFill.color = if (hp > 1) Color.WHITE else rowColors[r % rowColors.size]
                canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
            }
        }

        // Paddle lasers
        pFill.color = COL_RED
        for (l in 0 until maxBrickLasers) {
            if (!bLsrActive[l]) continue
            val lx = brickBoard.left + brickBoard.width() * bLsrX[l]
            val ly = brickBoard.top + brickBoard.height() * bLsrY[l]
            rect.set(lx - dp(2.2f), ly - dp(8f), lx + dp(2.2f), ly + dp(8f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
        }

        // Power-up capsules
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = dp(10.5f)
        for (k in 0 until maxCapsules) {
            if (!capActive[k]) continue
            val cx = brickBoard.left + brickBoard.width() * capX[k]
            val cy = brickBoard.top + brickBoard.height() * capY[k]
            val col = when (capType[k]) { 1 -> COL_GREEN; 2 -> COL_CYAN; 3 -> COL_RED; 4 -> COL_ORANGE; else -> COL_GOLD_LIGHT }
            val lbl = when (capType[k]) { 1 -> "W"; 2 -> "M"; 3 -> "L"; 4 -> "F"; else -> "+" }
            rect.set(cx - dp(12f), cy - dp(7f), cx + dp(12f), cy + dp(7f))
            pFill.color = col
            canvas.drawRoundRect(rect, dp(7f), dp(7f), pFill)
            pText.color = Color.BLACK
            canvas.drawText(lbl, cx, cy + dp(3.5f), pText)
        }

        val pw = brickBoard.width() * brickPaddleW
        val px = brickBoard.left + brickBoard.width() * brickPaddleX
        val py = brickBoard.top + brickBoard.height() * 0.87f
        rect.set(px - pw * 0.5f, py, px + pw * 0.5f, py + dp(11f))
        pFill.color = if (brickLaserTimer > 0f) COL_RED else if (brickWideTimer > 0f) COL_GREEN else COL_GOLD_LIGHT
        canvas.drawRoundRect(rect, dp(6f), dp(6f), pFill)

        for (b in 0 until maxBalls) {
            if (!ballActive[b]) continue
            val bx = brickBoard.left + brickBoard.width() * ballX[b]
            val by = brickBoard.top + brickBoard.height() * ballY[b]
            pFill.color = if (brickFireTimer > 0f) 0x44FF9800 else 0x33FFFFFF
            canvas.drawCircle(bx, by, dp(12f), pFill)
            pFill.color = if (brickFireTimer > 0f) COL_ORANGE else Color.WHITE
            canvas.drawCircle(bx, by, dp(6.5f), pFill)
        }

        pText.typeface = Typeface.SANS_SERIF
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_MUTE
        pText.textSize = dp(11.5f)
        canvas.drawText("Nyawa: $brickLives · Kapsul: W Lebar · M 3-Bola · L Laser · F Api", brickBoard.left + dp(10f), brickBoard.bottom - dp(8f), pText)
    }

    // =========================================================================
    // GAME 3: GOLD SNAKE (GIANT GOLD ORB, COMBO x4 & HARD-MODE OBSTACLES)
    // =========================================================================
    private val sCols = 22
    private val sRows = 14
    private val snakeX = IntArray(sCols * sRows)
    private val snakeY = IntArray(sCols * sRows)
    private var snakeLen = 4
    private var sDirX = 1
    private var sDirY = 0
    private var nextDirX = 1
    private var nextDirY = 0
    private var sStepTimer = 0f
    private var foodX = 12
    private var foodY = 7
    private var bonusX = -1
    private var bonusY = -1
    private var bonusIsGiant = false
    private var bonusTimer = 0f
    private var snakeCombo = 1
    private var snakeComboTimer = 0f
    private var snakeCellPx = 24f
    private var snakeOx = 0f
    private var snakeOy = 0f

    private val maxSnakeRocks = 6
    private val rockX = IntArray(maxSnakeRocks)
    private val rockY = IntArray(maxSnakeRocks)
    private var rockCount = 0

    private fun initSnake() {
        snakeLen = 4
        sDirX = 1; sDirY = 0
        nextDirX = 1; nextDirY = 0
        sStepTimer = 0f
        snakeCombo = 1
        snakeComboTimer = 0f
        for (i in 0 until snakeLen) {
            snakeX[i] = 7 - i
            snakeY[i] = 7
        }
        rockCount = if (difficulty == DIFF_HARD) 6 else if (difficulty == DIFF_NORMAL) 3 else 0
        for (r in 0 until rockCount) {
            rockX[r] = 4 + (r * 3) % (sCols - 6)
            rockY[r] = if (r % 2 == 0) 3 else sRows - 4
        }
        bonusX = -1
        spawnSnakeFood()
    }

    private fun queueSnakeDir(dx: Int, dy: Int) {
        if (dx == -sDirX && dy == -sDirY) return
        nextDirX = dx
        nextDirY = dy
    }

    private fun spawnSnakeFood() {
        while (true) {
            val fx = Random.nextInt(sCols)
            val fy = Random.nextInt(sRows)
            var hit = false
            for (i in 0 until snakeLen) if (snakeX[i] == fx && snakeY[i] == fy) { hit = true; break }
            for (r in 0 until rockCount) if (rockX[r] == fx && rockY[r] == fy) { hit = true; break }
            if (!hit) {
                foodX = fx
                foodY = fy
                break
            }
        }
    }

    private fun updateSnake(dt: Float) {
        if (snakeComboTimer > 0f) {
            snakeComboTimer -= dt
            if (snakeComboTimer <= 0f) snakeCombo = 1
        }

        val baseInt = when (difficulty) { DIFF_EASY -> 0.195f; DIFF_HARD -> 0.145f; else -> 0.17f }
        val interval = if (btnA) 0.075f else max(0.085f, baseInt - snakeLen * 0.002f)
        sStepTimer += dt
        if (bonusX >= 0) {
            bonusTimer -= dt
            if (bonusTimer <= 0f) bonusX = -1
        }
        if (sStepTimer < interval) return
        sStepTimer = 0f

        sDirX = nextDirX
        sDirY = nextDirY
        val nx = (snakeX[0] + sDirX + sCols) % sCols
        val ny = (snakeY[0] + sDirY + sRows) % sRows

        var crashed = false
        for (i in 0 until snakeLen - 1) if (snakeX[i] == nx && snakeY[i] == ny) crashed = true
        for (r in 0 until rockCount) if (rockX[r] == nx && rockY[r] == ny) crashed = true

        if (crashed) {
            audio.play(RetroAudioEngine.SFX_OVER)
            triggerShake(0.2f, 6f)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = false
            saveHighScore(MODE_SNAKE, score)
            return
        }

        val ateFood = (nx == foodX && ny == foodY)
        val ateBonus = (nx == bonusX && ny == bonusY)
        if (ateFood && snakeLen < snakeX.size - 1) snakeLen++

        for (i in snakeLen - 1 downTo 1) {
            snakeX[i] = snakeX[i - 1]
            snakeY[i] = snakeY[i - 1]
        }
        snakeX[0] = nx
        snakeY[0] = ny

        if (ateFood) {
            snakeCombo = if (snakeComboTimer > 0f) min(4, snakeCombo + 1) else 1
            snakeComboTimer = 4.5f
            val pts = 20 * snakeCombo
            addPoints(pts)
            audio.play(RetroAudioEngine.SFX_COIN, 0.95f + snakeCombo * 0.1f)
            val fx = snakeOx + (nx + 0.5f) * snakeCellPx
            val fy = snakeOy + (ny + 0.5f) * snakeCellPx
            spawnParticles(fx, fy, 10, COL_GOLD_LIGHT, dp(25f), dp(95f), 3.5f)
            spawnFloatText(fx, fy, if (snakeCombo > 1) "+$pts (x$snakeCombo!)" else "+$pts", COL_GOLD_LIGHT)

            spawnSnakeFood()
            if (snakeLen % 4 == 0 && bonusX < 0) {
                bonusX = (foodX + 7) % sCols
                bonusY = (foodY + 5) % sRows
                bonusIsGiant = (snakeLen % 8 == 0)
                bonusTimer = 6.5f
            }
        }
        if (ateBonus) {
            val bPts = if (bonusIsGiant) 150 else 90
            addPoints(bPts)
            bonusX = -1
            audio.play(RetroAudioEngine.SFX_POWERUP)
            val bx = snakeOx + (nx + 0.5f) * snakeCellPx
            val by = snakeOy + (ny + 0.5f) * snakeCellPx
            spawnRing(bx, by, 48f, if (bonusIsGiant) COL_GOLD_LIGHT else COL_CYAN, 0.3f)
            spawnParticles(bx, by, 14, if (bonusIsGiant) COL_GOLD_LIGHT else COL_CYAN, dp(30f), dp(120f), 4f)
            spawnFloatText(bx, by, "+$bPts BONUS!", if (bonusIsGiant) COL_GOLD_LIGHT else COL_CYAN)
        }
    }

    private fun drawSnake(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(56f)
        val botOffset = (h - getControlDeckTop(h)) + dp(12f)
        val cell = min((w - dp(20f)) / sCols, (h - topOffset - botOffset) / sRows)
        val ox = (w - cell * sCols) * 0.5f
        val oy = topOffset + ((h - topOffset - botOffset) - cell * sRows) * 0.5f
        snakeCellPx = cell
        snakeOx = ox
        snakeOy = oy

        rect.set(ox, oy, ox + cell * sCols, oy + cell * sRows)
        pFill.color = 0xFF121216.toInt()
        canvas.drawRoundRect(rect, dp(12f), dp(12f), pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x33B8975A
        canvas.drawRoundRect(rect, dp(12f), dp(12f), pStroke)

        // Obstacle rocks
        pFill.color = 0xFF3A3A44.toInt()
        for (r in 0 until rockCount) {
            val rx = ox + rockX[r] * cell
            val ry = oy + rockY[r] * cell
            rect.set(rx + dp(2f), ry + dp(2f), rx + cell - dp(2f), ry + cell - dp(2f))
            canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
        }

        val pulse = 1f + 0.14f * sin(globalTime * 7f)
        val fx = ox + (foodX + 0.5f) * cell
        val fy = oy + (foodY + 0.5f) * cell
        pFill.color = 0x38E05252
        canvas.drawCircle(fx, fy, cell * 0.52f * pulse, pFill)
        pFill.color = COL_RED
        canvas.drawCircle(fx, fy, cell * 0.33f * pulse, pFill)

        if (bonusX >= 0) {
            val bx = ox + (bonusX + 0.5f) * cell
            val by = oy + (bonusY + 0.5f) * cell
            val bCol = if (bonusIsGiant) COL_GOLD_LIGHT else COL_CYAN
            pFill.color = (bCol and 0x00FFFFFF) or 0x44000000
            canvas.drawCircle(bx, by, cell * (if (bonusIsGiant) 0.72f else 0.60f) * pulse, pFill)
            pFill.color = bCol
            canvas.drawCircle(bx, by, cell * (if (bonusIsGiant) 0.46f else 0.38f) * pulse, pFill)
        }

        for (i in snakeLen - 1 downTo 0) {
            val sx = ox + snakeX[i] * cell
            val sy = oy + snakeY[i] * cell
            rect.set(sx + dp(1.5f), sy + dp(1.5f), sx + cell - dp(1.5f), sy + cell - dp(1.5f))
            pFill.color = if (i == 0) Color.WHITE else if (i % 2 == 0) COL_GOLD_LIGHT else COL_GOLD
            canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
        }
    }

    // =========================================================================
    // GAME 4: NEON TETRIS (HOLD PIECE, DAS/ARR SMOOTH HOLD, GHOST DROP & COMBO)
    // =========================================================================
    private val tCols = 10
    private val tRows = 20
    private val tBoard = IntArray(tCols * tRows)
    private var tCurType = 0
    private var tCurRot = 0
    private var tCurX = 3
    private var tCurY = 0
    private var tNextType = 1
    private var tHoldType = -1
    private var tCanHold = true
    private var tCombo = 0
    private var tDropTimer = 0f
    private var tLinesTotal = 0
    private var tetrisCellPx = 22f
    private var tetrisOx = 0f
    private var tetrisOy = 0f
    private val tetrisHoldRect = RectF()

    private val tetrisColors = intArrayOf(
        COL_CYAN, COL_GOLD_LIGHT, COL_PURPLE, COL_GREEN, COL_RED, 0xFF5C85E6.toInt(), COL_ORANGE
    )

    private val tetrisMasks = arrayOf(
        intArrayOf(0x0F00, 0x4444, 0x00F0, 0x2222),
        intArrayOf(0x0660, 0x0660, 0x0660, 0x0660),
        intArrayOf(0x0E40, 0x4C40, 0x4E00, 0x4640),
        intArrayOf(0x06C0, 0x8C40, 0x06C0, 0x8C40),
        intArrayOf(0x0C60, 0x4C80, 0x0C60, 0x4C80),
        intArrayOf(0x8E00, 0x6440, 0x0E20, 0x44C0),
        intArrayOf(0x2E00, 0x4460, 0x0E80, 0xC440)
    )

    private fun initTetris() {
        tBoard.fill(0)
        tLinesTotal = 0
        tDropTimer = 0f
        tHoldType = -1
        tCanHold = true
        tCombo = 0
        tNextType = Random.nextInt(7)
        spawnTetrisPiece()
    }

    private fun spawnTetrisPiece() {
        tCurType = tNextType
        tNextType = Random.nextInt(7)
        tCurRot = 0
        tCurX = 3
        tCurY = -1
        tCanHold = true
        if (!canPlaceTetris(tCurType, tCurRot, tCurX, tCurY + 1)) {
            audio.play(RetroAudioEngine.SFX_OVER)
            triggerShake(0.22f, 6f)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = false
            saveHighScore(MODE_TETRIS, score)
        } else {
            tCurY++
        }
    }

    private fun holdTetrisPiece() {
        if (!tCanHold) return
        tCanHold = false
        audio.play(RetroAudioEngine.SFX_POWERUP, 1.15f)
        if (tHoldType < 0) {
            tHoldType = tCurType
            spawnTetrisPiece()
            tCanHold = false
        } else {
            val swap = tHoldType
            tHoldType = tCurType
            tCurType = swap
            tCurRot = 0
            tCurX = 3
            tCurY = 0
        }
    }

    private fun canPlaceTetris(type: Int, rot: Int, px: Int, py: Int): Boolean {
        val mask = tetrisMasks[type][rot and 3]
        for (r in 0..3) {
            for (c in 0..3) {
                if ((mask and (1 shl (r * 4 + c))) != 0) {
                    val bx = px + c
                    val by = py + r
                    if (bx < 0 || bx >= tCols || by >= tRows) return false
                    if (by >= 0 && tBoard[by * tCols + bx] != 0) return false
                }
            }
        }
        return true
    }

    private fun moveTetrisHorizontal(dx: Int) {
        if (canPlaceTetris(tCurType, tCurRot, tCurX + dx, tCurY)) {
            tCurX += dx
            audio.play(RetroAudioEngine.SFX_BOUNCE, 0.85f)
        }
    }

    private fun rotateTetrisPiece() {
        val nextRot = (tCurRot + 1) and 3
        val kicks = intArrayOf(0, -1, 1, -2, 2)
        for (k in kicks) {
            if (canPlaceTetris(tCurType, nextRot, tCurX + k, tCurY)) {
                tCurX += k
                tCurRot = nextRot
                audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
                return
            }
        }
    }

    private fun hardDropTetrisPiece() {
        var dropped = 0
        while (canPlaceTetris(tCurType, tCurRot, tCurX, tCurY + 1)) {
            tCurY++
            dropped++
        }
        addPoints(dropped * 2)
        lockTetrisPiece()
    }

    private fun lockTetrisPiece() {
        val mask = tetrisMasks[tCurType][tCurRot and 3]
        for (r in 0..3) {
            for (c in 0..3) {
                if ((mask and (1 shl (r * 4 + c))) != 0) {
                    val bx = tCurX + c
                    val by = tCurY + r
                    if (by in 0 until tRows && bx in 0 until tCols) {
                        tBoard[by * tCols + bx] = tCurType + 1
                    }
                }
            }
        }
        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.65f)

        var cleared = 0
        var r = tRows - 1
        while (r >= 0) {
            var full = true
            for (c in 0 until tCols) if (tBoard[r * tCols + c] == 0) { full = false; break }
            if (full) {
                cleared++
                val rowPy = tetrisOy + (r + 0.5f) * tetrisCellPx
                for (c in 0 until tCols) {
                    spawnParticles(tetrisOx + (c + 0.5f) * tetrisCellPx, rowPy, 3, COL_GOLD_LIGHT, dp(30f), dp(120f), 3.5f)
                }
                for (moveR in r downTo 1) {
                    for (c in 0 until tCols) tBoard[moveR * tCols + c] = tBoard[(moveR - 1) * tCols + c]
                }
                for (c in 0 until tCols) tBoard[c] = 0
            } else {
                r--
            }
        }

        if (cleared > 0) {
            tCombo++
            val basePts = when (cleared) { 1 -> 100; 2 -> 300; 3 -> 500; else -> 800 } * stage + (tCombo - 1) * 50
            addPoints(basePts)
            tLinesTotal += cleared
            stage = 1 + tLinesTotal / 8
            audio.play(if (cleared >= 4) RetroAudioEngine.SFX_WIN else RetroAudioEngine.SFX_COIN, 1.1f)
            if (cleared >= 3) {
                triggerShake(0.18f, 5.5f)
                triggerFlash(COL_GOLD_LIGHT, 0.18f)
            }
            val cx = tetrisOx + tCols * tetrisCellPx * 0.5f
            val cy = tetrisOy + tCurY * tetrisCellPx
            spawnRing(cx, cy, 65f, COL_GOLD_LIGHT, 0.32f)
            spawnFloatText(cx, cy, if (tCombo > 1) "+$basePts (COMBO x$tCombo)" else "+$basePts!", COL_GOLD_LIGHT)
        } else {
            tCombo = 0
        }

        spawnTetrisPiece()
    }

    private fun updateTetris(dt: Float) {
        // DAS/ARR smooth horizontal repeat when holding Left/Right on TV remote or gamepad
        if (inLeft xor inRight) {
            dasTimer += dt
            if (dasTimer >= 0.18f) {
                arrTimer += dt
                if (arrTimer >= 0.065f) {
                    arrTimer = 0f
                    moveTetrisHorizontal(if (inLeft) -1 else 1)
                }
            }
        } else {
            dasTimer = 0f
            arrTimer = 0f
        }

        val diffStart = when (difficulty) { DIFF_EASY -> 0.78f; DIFF_HARD -> 0.48f; else -> 0.64f }
        val baseInterval = max(0.095f, diffStart - (stage - 1) * 0.055f)
        val interval = if (inDown) 0.05f else baseInterval
        tDropTimer += dt
        if (tDropTimer >= interval) {
            tDropTimer = 0f
            if (canPlaceTetris(tCurType, tCurRot, tCurX, tCurY + 1)) {
                tCurY++
                if (inDown) score += 1
            } else {
                lockTetrisPiece()
            }
        }
    }

    private fun drawTetris(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(56f)
        val botOffset = (h - getControlDeckTop(h)) + dp(12f)
        val availH = h - topOffset - botOffset
        val cell = min((w - dp(124f)) / tCols, availH / tRows)
        val boardW = cell * tCols
        val boardH = cell * tRows
        val ox = (w - boardW) * 0.5f - dp(24f)
        val oy = topOffset + (availH - boardH) * 0.5f
        tetrisCellPx = cell
        tetrisOx = ox
        tetrisOy = oy

        rect.set(ox - dp(2f), oy - dp(2f), ox + boardW + dp(2f), oy + boardH + dp(2f))
        pFill.color = 0xFF121216.toInt()
        canvas.drawRoundRect(rect, dp(10f), dp(10f), pFill)
        pStroke.strokeWidth = dp(1.6f)
        pStroke.color = 0x38B8975A
        canvas.drawRoundRect(rect, dp(10f), dp(10f), pStroke)

        pStroke.strokeWidth = dp(0.7f)
        pStroke.color = 0x10FFFFFF
        for (c in 1 until tCols) canvas.drawLine(ox + c * cell, oy, ox + c * cell, oy + boardH, pStroke)
        for (r in 1 until tRows) canvas.drawLine(ox, oy + r * cell, ox + boardW, oy + r * cell, pStroke)

        for (r in 0 until tRows) {
            for (c in 0 until tCols) {
                val v = tBoard[r * tCols + c]
                if (v > 0) drawTetrisCell(canvas, ox + c * cell, oy + r * cell, cell, tetrisColors[v - 1], ghost = false)
            }
        }

        var ghostY = tCurY
        while (canPlaceTetris(tCurType, tCurRot, tCurX, ghostY + 1)) ghostY++
        val mask = tetrisMasks[tCurType][tCurRot and 3]
        val curCol = tetrisColors[tCurType]
        for (r in 0..3) {
            for (c in 0..3) {
                if ((mask and (1 shl (r * 4 + c))) != 0) {
                    val bx = tCurX + c
                    val gy = ghostY + r
                    if (gy >= 0) drawTetrisCell(canvas, ox + bx * cell, oy + gy * cell, cell, curCol, ghost = true)
                    val cy = tCurY + r
                    if (cy >= 0) drawTetrisCell(canvas, ox + bx * cell, oy + cy * cell, cell, curCol, ghost = false)
                }
            }
        }

        // Right Sidebar: NEXT Piece + HOLD Piece (tappable!)
        val sideX = ox + boardW + dp(10f)
        val boxW = dp(70f)
        val pCell = dp(11.5f)

        // NEXT Box
        rect.set(sideX, oy, sideX + boxW, oy + dp(78f))
        pFill.color = COL_CARD
        canvas.drawRoundRect(rect, dp(10f), dp(10f), pFill)
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x2EFFFFFF
        canvas.drawRoundRect(rect, dp(10f), dp(10f), pStroke)
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.color = COL_MUTE
        pText.textSize = dp(9.5f)
        canvas.drawText("BERIKUT", rect.centerX(), oy + dp(14f), pText)
        drawMiniTetromino(canvas, tNextType, rect.centerX() - pCell * 2f, oy + dp(22f), pCell)

        // HOLD Box (Touch or L1/R1 to swap)
        val holdTop = oy + dp(86f)
        tetrisHoldRect.set(sideX, holdTop, sideX + boxW, holdTop + dp(78f))
        pFill.color = if (tCanHold) COL_CARD else 0xFF0E0E12.toInt()
        canvas.drawRoundRect(tetrisHoldRect, dp(10f), dp(10f), pFill)
        pStroke.strokeWidth = dp(1.2f)
        pStroke.color = if (tCanHold) COL_GOLD else 0x22FFFFFF
        canvas.drawRoundRect(tetrisHoldRect, dp(10f), dp(10f), pStroke)
        pText.color = if (tCanHold) COL_GOLD_LIGHT else COL_MUTE
        canvas.drawText("SIMPAN", tetrisHoldRect.centerX(), holdTop + dp(14f), pText)
        if (tHoldType >= 0) {
            drawMiniTetromino(canvas, tHoldType, tetrisHoldRect.centerX() - pCell * 2f, holdTop + dp(22f), pCell)
        }

        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(10.5f)
        canvas.drawText("BARIS: $tLinesTotal", sideX + boxW * 0.5f, holdTop + dp(96f), pText)
    }

    private fun drawMiniTetromino(canvas: Canvas, type: Int, x0: Float, y0: Float, pCell: Float) {
        val m = tetrisMasks[type][0]
        val col = tetrisColors[type]
        for (r in 0..3) {
            for (c in 0..3) {
                if ((m and (1 shl (r * 4 + c))) != 0) {
                    drawTetrisCell(canvas, x0 + c * pCell, y0 + r * pCell, pCell, col, ghost = false)
                }
            }
        }
    }

    private fun drawTetrisCell(canvas: Canvas, x: Float, y: Float, size: Float, color: Int, ghost: Boolean) {
        rect.set(x + dp(1.2f), y + dp(1.2f), x + size - dp(1.2f), y + size - dp(1.2f))
        if (ghost) {
            pStroke.strokeWidth = dp(1.3f)
            pStroke.color = (color and 0x00FFFFFF) or 0x66000000
            canvas.drawRoundRect(rect, dp(3.5f), dp(3.5f), pStroke)
        } else {
            pFill.color = color
            canvas.drawRoundRect(rect, dp(3.5f), dp(3.5f), pFill)
            pFill.color = 0x38FFFFFF
            canvas.drawRect(rect.left + dp(2f), rect.top + dp(2f), rect.right - dp(2f), rect.top + size * 0.3f, pFill)
        }
    }

    // =========================================================================
    // GAME 5: ASTRO ROCKET (MAGNET, RAPID LASER & ALIEN MOTHERSHIP BOSS)
    // =========================================================================
    private val spaceBoard = RectF()
    private var rkX = 0.22f
    private var rkY = 0.50f
    private var rkVy = 0f
    private var rkShield = false
    private var rkMagnetTimer = 0f
    private var rkRapidTimer = 0f
    private var rkShootCd = 0f
    private var rkDistance = 0f

    // Alien Boss Cruiser every 400m
    private var bossActive = false
    private var bossY = 0.5f
    private var bossDir = 1f
    private var bossHp = 0
    private var bossMaxHp = 100
    private var nextBossDist = 400f

    private val maxAsteroids = 8
    private val astActive = BooleanArray(maxAsteroids)
    private val astX = FloatArray(maxAsteroids)
    private val astY = FloatArray(maxAsteroids)
    private val astR = FloatArray(maxAsteroids)
    private val astSpd = FloatArray(maxAsteroids)
    private val astHp = IntArray(maxAsteroids)
    private var astSpawnCd = 0.6f

    private val maxSpaceOrbs = 7
    private val orbActive = BooleanArray(maxSpaceOrbs)
    private val orbType = IntArray(maxSpaceOrbs) // 0=Gold Star, 1=Shield, 2=Magnet, 3=RapidLaser
    private val orbX = FloatArray(maxSpaceOrbs)
    private val orbY = FloatArray(maxSpaceOrbs)
    private var orbSpawnCd = 0.9f

    private val maxLasers = 8
    private val lsrActive = BooleanArray(maxLasers)
    private val lsrX = FloatArray(maxLasers)
    private val lsrY = FloatArray(maxLasers)

    private fun initRocket() {
        rkX = 0.22f
        rkY = 0.50f
        rkVy = 0f
        rkShield = true
        rkMagnetTimer = 0f
        rkRapidTimer = 0f
        rkShootCd = 0f
        rkDistance = 0f
        bossActive = false
        nextBossDist = 400f
        astActive.fill(false)
        orbActive.fill(false)
        lsrActive.fill(false)
        astSpawnCd = 0.5f
        orbSpawnCd = 0.8f
    }

    private fun fireRocketLaser() {
        if (rkShootCd > 0f) return
        for (i in 0 until maxLasers) {
            if (!lsrActive[i]) {
                lsrActive[i] = true
                lsrX[i] = rkX + 0.04f
                lsrY[i] = rkY
                rkShootCd = if (rkRapidTimer > 0f) 0.11f else 0.22f
                audio.play(RetroAudioEngine.SFX_LASER, if (rkRapidTimer > 0f) 1.25f else 1f)
                return
            }
        }
    }

    private fun updateRocket(dt: Float) {
        if (rkShootCd > 0f) rkShootCd = max(0f, rkShootCd - dt)
        if (rkMagnetTimer > 0f) rkMagnetTimer = max(0f, rkMagnetTimer - dt)
        if (rkRapidTimer > 0f) rkRapidTimer = max(0f, rkRapidTimer - dt)

        if (btnB && rkRapidTimer > 0f) fireRocketLaser()

        if (inLeft) rkX -= 0.42f * dt
        if (inRight) rkX += 0.42f * dt
        rkX = rkX.coerceIn(0.10f, 0.44f)

        val thrusting = btnA || inUp || boardHolding
        if (thrusting) {
            rkVy -= 2.15f * dt
            if (Random.nextFloat() < 0.65f && spaceBoard.width() > 0f) {
                val fx = spaceBoard.left + spaceBoard.width() * (rkX - 0.035f)
                val fy = spaceBoard.top + spaceBoard.height() * rkY
                spawnParticles(fx, fy, 1, COL_ORANGE, dp(40f), dp(95f), 3.5f)
            }
        } else if (inDown) {
            rkVy += 1.45f * dt
        } else {
            rkVy += 0.92f * dt
        }
        rkVy = rkVy.coerceIn(-0.75f, 0.75f)
        rkY += rkVy * dt

        if (rkY < 0.06f) { rkY = 0.06f; rkVy = 0.1f }
        if (rkY > 0.94f) { rkY = 0.94f; rkVy = -0.15f }

        val diffSpd = 0.85f + difficulty * 0.18f
        rkDistance += (18f + stage * 3f) * diffSpd * dt
        val newStage = 1 + (rkDistance / 180f).toInt()
        if (newStage > stage) {
            stage = newStage
            audio.play(RetroAudioEngine.SFX_POWERUP, 1.25f)
            spawnFloatText(spaceBoard.centerX(), spaceBoard.top + dp(40f), "SECTOR $stage!", COL_CYAN)
        }

        // Trigger Alien Boss Cruiser every 400m
        if (!bossActive && rkDistance >= nextBossDist) {
            bossActive = true
            bossY = 0.5f
            bossMaxHp = 18 + stage * 6
            bossHp = bossMaxHp
            nextBossDist += 450f
            triggerShake(0.22f, 6f)
            audio.play(RetroAudioEngine.SFX_EXPLODE, 0.7f)
            spawnFloatText(spaceBoard.centerX(), spaceBoard.centerY(), "KAPAL INDUK ALIEN!", COL_PURPLE)
        }

        if (bossActive) {
            bossY += bossDir * (0.28f + stage * 0.03f) * dt
            if (bossY < 0.16f) { bossY = 0.16f; bossDir = 1f }
            if (bossY > 0.84f) { bossY = 0.84f; bossDir = -1f }
        }

        for (i in 0 until maxLasers) {
            if (!lsrActive[i]) continue
            lsrX[i] += 1.45f * dt
            if (lsrX[i] > 1.04f) {
                lsrActive[i] = false
                continue
            }
            if (bossActive && lsrX[i] >= 0.82f && abs(lsrY[i] - bossY) < 0.14f) {
                lsrActive[i] = false
                bossHp--
                val bx = spaceBoard.left + spaceBoard.width() * 0.86f
                val by = spaceBoard.top + spaceBoard.height() * bossY
                spawnParticles(bx, by, 5, COL_PURPLE, dp(30f), dp(110f), 3.5f)
                if (bossHp <= 0) {
                    bossActive = false
                    addPoints(500)
                    triggerShake(0.30f, 8f)
                    triggerFlash(COL_PURPLE, 0.25f)
                    audio.play(RetroAudioEngine.SFX_WIN)
                    spawnRing(bx, by, 90f, COL_PURPLE, 0.45f)
                    spawnParticles(bx, by, 24, COL_ORANGE, dp(45f), dp(190f), 5f)
                    spawnFloatText(bx - dp(30f), by, "+500 BOS HANCUR!", COL_GOLD_LIGHT)
                }
            }
        }

        astSpawnCd -= dt
        if (astSpawnCd <= 0f) {
            for (i in 0 until maxAsteroids) {
                if (!astActive[i]) {
                    astActive[i] = true
                    astX[i] = 1.06f
                    astY[i] = if (bossActive) bossY else (0.10f + Random.nextFloat() * 0.80f)
                    val big = Random.nextFloat() < 0.35f
                    astR[i] = if (big) 0.052f else 0.034f
                    astHp[i] = if (big) 2 else 1
                    astSpd[i] = (0.34f + stage * 0.045f) * diffSpd * (0.85f + Random.nextFloat() * 0.35f)
                    astSpawnCd = max(0.34f, (1.05f - difficulty * 0.15f) - stage * 0.06f)
                    break
                }
            }
        }

        orbSpawnCd -= dt
        if (orbSpawnCd <= 0f) {
            for (i in 0 until maxSpaceOrbs) {
                if (!orbActive[i]) {
                    orbActive[i] = true
                    val roll = Random.nextFloat()
                    orbType[i] = when {
                        !rkShield && roll < 0.14f -> 1
                        roll < 0.26f -> 2
                        roll < 0.38f -> 3
                        else -> 0
                    }
                    orbX[i] = 1.05f
                    orbY[i] = 0.12f + Random.nextFloat() * 0.76f
                    orbSpawnCd = 0.75f + Random.nextFloat() * 0.6f
                    break
                }
            }
        }

        for (i in 0 until maxSpaceOrbs) {
            if (!orbActive[i]) continue
            orbX[i] -= (0.34f + stage * 0.03f) * dt
            if (rkMagnetTimer > 0f && orbType[i] == 0) {
                orbX[i] += (rkX - orbX[i]) * 4.5f * dt
                orbY[i] += (rkY - orbY[i]) * 4.5f * dt
            }
            if (orbX[i] < -0.05f) {
                orbActive[i] = false
                continue
            }
            if (hypot((orbX[i] - rkX) * 1.4f, orbY[i] - rkY) < 0.068f) {
                orbActive[i] = false
                val px = spaceBoard.left + spaceBoard.width() * rkX
                val py = spaceBoard.top + spaceBoard.height() * rkY
                when (orbType[i]) {
                    1 -> {
                        rkShield = true
                        audio.play(RetroAudioEngine.SFX_POWERUP)
                        spawnRing(px, py, 44f, COL_CYAN, 0.3f)
                        spawnFloatText(px, py - dp(16f), "PERISAI AKTIF!", COL_CYAN)
                    }
                    2 -> {
                        rkMagnetTimer = 11f
                        audio.play(RetroAudioEngine.SFX_POWERUP, 1.15f)
                        spawnFloatText(px, py - dp(16f), "MAGNET BINTANG!", COL_GREEN)
                    }
                    3 -> {
                        rkRapidTimer = 10f
                        audio.play(RetroAudioEngine.SFX_POWERUP, 1.25f)
                        spawnFloatText(px, py - dp(16f), "RAPID LASER!", COL_PURPLE)
                    }
                    else -> {
                        addPoints(30)
                        audio.play(RetroAudioEngine.SFX_COIN, 1.15f)
                        spawnParticles(px, py, 8, COL_GOLD_LIGHT, dp(25f), dp(90f), 3f)
                        spawnFloatText(px, py - dp(14f), "+30", COL_GOLD_LIGHT)
                    }
                }
            }
        }

        for (i in 0 until maxAsteroids) {
            if (!astActive[i]) continue
            astX[i] -= astSpd[i] * dt
            if (astX[i] < -0.08f) {
                astActive[i] = false
                addPoints(5)
                continue
            }

            val axPx = spaceBoard.left + spaceBoard.width() * astX[i]
            val ayPx = spaceBoard.top + spaceBoard.height() * astY[i]

            for (l in 0 until maxLasers) {
                if (!lsrActive[l]) continue
                if (hypot((lsrX[l] - astX[i]) * 1.35f, lsrY[l] - astY[i]) < astR[i] + 0.025f) {
                    lsrActive[l] = false
                    astHp[i]--
                    spawnParticles(axPx, ayPx, 6, COL_GOLD_LIGHT, dp(25f), dp(90f), 3f)
                    if (astHp[i] <= 0) {
                        astActive[i] = false
                        addPoints(40)
                        audio.play(RetroAudioEngine.SFX_EXPLODE, 1.25f)
                        spawnRing(axPx, ayPx, 42f, COL_ORANGE, 0.28f)
                        spawnParticles(axPx, ayPx, 14, COL_ORANGE, dp(35f), dp(140f), 4f)
                        spawnFloatText(axPx, ayPx, "+40!", COL_ORANGE)
                    } else {
                        audio.play(RetroAudioEngine.SFX_BOUNCE, 0.7f)
                    }
                    break
                }
            }
            if (!astActive[i]) continue

            if (hypot((astX[i] - rkX) * 1.35f, astY[i] - rkY) < astR[i] + 0.032f) {
                astActive[i] = false
                if (rkShield) {
                    rkShield = false
                    triggerShake(0.18f, 5.5f)
                    audio.play(RetroAudioEngine.SFX_EXPLODE, 1.1f)
                    spawnRing(axPx, ayPx, 50f, COL_CYAN, 0.32f)
                    spawnFloatText(axPx, ayPx, "PERISAI PECAH!", COL_CYAN)
                } else {
                    audio.play(RetroAudioEngine.SFX_OVER)
                    triggerShake(0.25f, 7f)
                    clearInputs()
                    state = STATE_GAMEOVER
                    dialogIndex = 0
                    victory = false
                    saveHighScore(MODE_ROCKET, score)
                    return
                }
            }
        }
    }

    private fun drawRocket(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(56f)
        val botOffset = (h - getControlDeckTop(h)) + dp(12f)
        spaceBoard.set(dp(12f), topOffset, w - dp(12f), h - botOffset)

        pFill.color = 0xFF0E1016.toInt()
        canvas.drawRoundRect(spaceBoard, dp(14f), dp(14f), pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x2EFFFFFF
        canvas.drawRoundRect(spaceBoard, dp(14f), dp(14f), pStroke)

        canvas.save()
        canvas.clipRect(spaceBoard)

        val bw = spaceBoard.width()
        val bh = spaceBoard.height()
        for (s in 0 until 24) {
            val speedFactor = if (s % 2 == 0) 65f else 120f
            val sx = spaceBoard.left + ((s * 97f - globalTime * speedFactor) % bw + bw) % bw
            val sy = spaceBoard.top + ((s * 53f) % bh)
            pFill.color = if (s % 3 == 0) 0x88E6C88F.toInt() else 0x55FFFFFF
            canvas.drawCircle(sx, sy, dp(if (s % 2 == 0) 1.2f else 2f), pFill)
        }

        for (i in 0 until maxSpaceOrbs) {
            if (!orbActive[i]) continue
            val ox = spaceBoard.left + bw * orbX[i]
            val oy = spaceBoard.top + bh * orbY[i]
            val col = when (orbType[i]) { 1 -> COL_CYAN; 2 -> COL_GREEN; 3 -> COL_PURPLE; else -> COL_GOLD_LIGHT }
            pFill.color = (col and 0x00FFFFFF) or 0x3A000000
            canvas.drawCircle(ox, oy, dp(14f), pFill)
            pFill.color = col
            canvas.drawCircle(ox, oy, dp(7.5f), pFill)
        }

        pFill.color = if (rkRapidTimer > 0f) COL_PURPLE else COL_CYAN
        for (i in 0 until maxLasers) {
            if (!lsrActive[i]) continue
            val lx = spaceBoard.left + bw * lsrX[i]
            val ly = spaceBoard.top + bh * lsrY[i]
            rect.set(lx - dp(10f), ly - dp(2.5f), lx + dp(10f), ly + dp(2.5f))
            canvas.drawRoundRect(rect, dp(2f), dp(2f), pFill)
        }

        for (i in 0 until maxAsteroids) {
            if (!astActive[i]) continue
            val ax = spaceBoard.left + bw * astX[i]
            val ay = spaceBoard.top + bh * astY[i]
            val ar = min(bw, bh) * astR[i]
            pFill.color = if (astHp[i] > 1) 0xFF6B5344.toInt() else 0xFF4E4A47.toInt()
            canvas.drawCircle(ax, ay, ar, pFill)
            pStroke.strokeWidth = dp(1.8f)
            pStroke.color = if (astHp[i] > 1) COL_ORANGE else 0x66FFFFFF
            canvas.drawCircle(ax, ay, ar, pStroke)
        }

        // Alien Mothership Boss
        if (bossActive) {
            val bx = spaceBoard.left + bw * 0.88f
            val by = spaceBoard.top + bh * bossY
            val bRad = dp(32f)
            pFill.color = 0x339C6ADE
            canvas.drawCircle(bx, by, bRad * 1.35f, pFill)
            pFill.color = 0xFF282036.toInt()
            canvas.drawCircle(bx, by, bRad, pFill)
            pStroke.strokeWidth = dp(2.5f)
            pStroke.color = COL_PURPLE
            canvas.drawCircle(bx, by, bRad, pStroke)

            // Boss HP bar
            val barW = dp(140f)
            rect.set(spaceBoard.right - dp(16f) - barW, spaceBoard.top + dp(12f), spaceBoard.right - dp(16f), spaceBoard.top + dp(20f))
            pFill.color = 0x33FFFFFF
            canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
            rect.left = rect.right - barW * (bossHp.toFloat() / max(1, bossMaxHp))
            pFill.color = COL_PURPLE
            canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
        }

        val rx = spaceBoard.left + bw * rkX
        val ry = spaceBoard.top + bh * rkY
        val rLen = dp(22f)
        val rHalfH = dp(11f)

        if (btnA || inUp || boardHolding) {
            pFill.color = COL_ORANGE
            canvas.drawCircle(rx - rLen * 0.95f, ry, dp(7f + 2f * sin(globalTime * 35f)), pFill)
        }

        path.reset()
        path.moveTo(rx + rLen, ry)
        path.lineTo(rx - rLen * 0.7f, ry - rHalfH)
        path.lineTo(rx - rLen * 0.45f, ry)
        path.lineTo(rx - rLen * 0.7f, ry + rHalfH)
        path.close()
        pFill.color = COL_IVORY
        canvas.drawPath(path, pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = COL_GOLD_LIGHT
        canvas.drawPath(path, pStroke)

        pFill.color = COL_CYAN
        canvas.drawCircle(rx + dp(3f), ry, dp(4.5f), pFill)

        if (rkShield) {
            pStroke.strokeWidth = dp(2f)
            pStroke.color = 0xAA4CC3D9.toInt()
            canvas.drawCircle(rx, ry, dp(26f), pStroke)
        }
        if (rkMagnetTimer > 0f) {
            pStroke.strokeWidth = dp(1.5f)
            pStroke.color = 0x884CAF7D.toInt()
            canvas.drawCircle(rx, ry, dp(33f), pStroke)
        }

        canvas.restore()

        pText.typeface = Typeface.SANS_SERIF
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_MUTE
        pText.textSize = dp(11.5f)
        val shText = if (rkShield) "Perisai: AKTIF" else "Perisai: Kosong"
        canvas.drawText("Jarak: ${rkDistance.toInt()}m · $shText", spaceBoard.left + dp(10f), spaceBoard.bottom - dp(8f), pText)
    }
}
