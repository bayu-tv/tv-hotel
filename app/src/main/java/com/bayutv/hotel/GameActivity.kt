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

// =============================================================================
// SYNTHESIZED 8-BIT RETRO SOUND ENGINE (ZERO EXTERNAL ASSETS, < 35 KB RAM)
// =============================================================================
private class RetroAudioEngine(context: Context) {
    companion object {
        const val SFX_EXPLODE = 0
        const val SFX_BOUNCE = 1
        const val SFX_COIN = 2
        const val SFX_PUNCH = 3
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
                sfxIds[SFX_PUNCH] = loadTone(dir, "hit.wav", 0.09f, 240f, 75f, noise = true)
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

            // Standard 44-byte PCM WAV Header
            writeAscii(buf, 0, "RIFF")
            writeIntLE(buf, 4, 36 + dataLen)
            writeAscii(buf, 8, "WAVE")
            writeAscii(buf, 12, "fmt ")
            writeIntLE(buf, 16, 16)
            writeShortLE(buf, 20, 1) // PCM
            writeShortLE(buf, 22, 1) // Mono
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
        if (id != 0) {
            soundPool?.play(id, 0.65f, 0.65f, 1, 0, rate)
        }
    }

    fun release() {
        ready = false
        soundPool?.release()
        soundPool = null
    }
}

// =============================================================================
// MAIN ARCADE ENGINE & CANVAS VIEW
// =============================================================================
private class ArcadeCanvasView(context: Context) : View(context) {

    companion object {
        const val MODE_HUB = 0
        const val MODE_BOMBER = 1
        const val MODE_BRICK = 2
        const val MODE_SNAKE = 3
        const val MODE_STICKMAN = 4

        const val STATE_HUB = 0
        const val STATE_READY = 1
        const val STATE_PLAYING = 2
        const val STATE_PAUSED = 3
        const val STATE_GAMEOVER = 4

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
    }

    private val prefs = context.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
    private val audio = RetroAudioEngine(context)
    private var running = false
    private var lastFrameNs = 0L
    private var globalTime = 0f

    private var mode = MODE_HUB
    private var state = STATE_HUB
    private var hubIndex = 0
    private var dialogIndex = 0
    private var victory = false
    private var score = 0
    private var stage = 1

    // Screen shake effect (0% RAM overhead)
    private var shakeTimer = 0f
    private var shakeStrength = 0f

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

    // Static Floating Score Text Pool (8 slots, zero GC)
    private val maxFloatTexts = 8
    private val ftLife = FloatArray(maxFloatTexts)
    private val ftX = FloatArray(maxFloatTexts)
    private val ftY = FloatArray(maxFloatTexts)
    private val ftColor = IntArray(maxFloatTexts)
    private val ftText = Array(maxFloatTexts) { "" }

    // Input state
    private var inLeft = false
    private var inRight = false
    private var inUp = false
    private var inDown = false
    private var btnA = false
    private var btnB = false
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

    // Board swipe tracking
    private var boardPointerId = -1
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    // Pre-allocated Paints, Paths & Rects
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val arcPath = Path()
    private val rect = RectF()
    private val hubRects = Array(4) { RectF() }
    private val dialogBtnRects = Array(3) { RectF() }
    private val hudPauseRect = RectF()
    private val hudMuteRect = RectF()
    private val touchBtnARect = RectF()
    private val touchBtnBRect = RectF()

    private val gameTitles = arrayOf(
        "Bomber Arena",
        "Neon Brick",
        "Gold Snake",
        "Stickman Fighter"
    )
    private val gameDescs = arrayOf(
        "Ledakkan blok, ambil item & kalahkan bot",
        "Hancurkan dinding neon + Multi-Ball & Lebar",
        "Ular emas bercahaya + sistem Combo Cepat",
        "Duel dojo combo 3x, jurus emas & Bos Stage 3"
    )
    private val gameGuides = arrayOf(
        "Arahkan karakter, tekan BOM untuk meledakkan dinding. Kumpulkan item biru, emas & hijau!",
        "Pantulkan bola & tangkap kapsul power-up: W (Pemukul Lebar) dan M (Multi-Ball 3 Bola)!",
        "Makan orb merah & orb biru bonus secara beruntun untuk mengaktifkan pengali Combo x2/x3!",
        "Tekan PUKUL (combo 3x) & kumpulkan energi emas untuk JURUS. Waspadai Bos di setiap Stage 3!"
    )
    private val gameAccents = intArrayOf(COL_RED, COL_CYAN, COL_GOLD, COL_GREEN)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        audio.muted = prefs.getBoolean("arcade_mute", false)
        showTouchOverlay = context.resources.displayMetrics.widthPixels < context.resources.displayMetrics.heightPixels
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun getHighScore(gameMode: Int): Int = prefs.getInt("hs_game_$gameMode", 0)

    private fun saveHighScore(gameMode: Int, value: Int) {
        if (value > getHighScore(gameMode)) {
            prefs.edit().putInt("hs_game_$gameMode", value).apply()
        }
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
        ftLife.fill(0f)
        shakeTimer = 0f
        shakeStrength = 0f
    }

    fun resumeLoop() {
        if (running) return
        running = true
        lastFrameNs = System.nanoTime()
        postOnAnimation(frameRunnable)
    }

    fun pauseLoop() {
        running = false
        removeCallbacks(frameRunnable)
    }

    fun releaseResources() {
        pauseLoop()
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
        dpadPointerId = -1
        boardPointerId = -1
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
            MODE_STICKMAN -> initStickman()
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

            if (state == STATE_HUB) {
                if (inLeft && !prevL) { hubIndex = (hubIndex + 3) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inRight && !prevR) { hubIndex = (hubIndex + 1) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inUp && !prevU) { hubIndex = (hubIndex + 2) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                if (inDown && !prevD) { hubIndex = (hubIndex + 2) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
            } else if (state == STATE_READY || state == STATE_PAUSED || state == STATE_GAMEOVER) {
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
            } else if (state == STATE_PLAYING && mode == MODE_SNAKE) {
                if (inLeft && !prevL) queueSnakeDir(-1, 0)
                if (inRight && !prevR) queueSnakeDir(1, 0)
                if (inUp && !prevU) queueSnakeDir(0, -1)
                if (inDown && !prevD) queueSnakeDir(0, 1)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        showTouchOverlay = false

        if (state == STATE_READY || state == STATE_PAUSED || state == STATE_GAMEOVER) {
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
                KeyEvent.KEYCODE_BUTTON_B -> {
                    onBackAction()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                inLeft = true
                if (state == STATE_HUB) { hubIndex = (hubIndex + 3) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(-1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                inRight = true
                if (state == STATE_HUB) { hubIndex = (hubIndex + 1) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                inUp = true
                if (state == STATE_HUB) { hubIndex = (hubIndex + 2) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
                else if (mode == MODE_SNAKE) queueSnakeDir(0, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                inDown = true
                if (state == STATE_HUB) { hubIndex = (hubIndex + 2) % 4; audio.play(RetroAudioEngine.SFX_BOUNCE, 0.9f) }
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
                for (i in 0..3) {
                    if (hubRects[i].contains(x, y)) {
                        hubIndex = i
                        openReadyScreen(i + 1)
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
                boardPointerId = -1
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
                MODE_STICKMAN -> {
                    if (dx < -dead) inLeft = true
                    if (dx > dead) inRight = true
                    if (dy < -maxR * 0.55f) inUp = true
                }
            }
        }
    }

    private fun hasSecondaryButton(): Boolean = (mode == MODE_BOMBER || mode == MODE_STICKMAN)

    private fun handlePrimaryAction() {
        if (state == STATE_HUB) {
            openReadyScreen(hubIndex + 1)
            return
        }
        if (state != STATE_PLAYING) return
        when (mode) {
            MODE_BOMBER -> placePlayerBomb()
            MODE_BRICK -> launchAllInactiveBalls()
            MODE_STICKMAN -> stickPlayerAttack()
        }
    }

    private fun handleSecondaryAction() {
        if (state != STATE_PLAYING) return
        when (mode) {
            MODE_BOMBER -> detonateEarliestBomb()
            MODE_STICKMAN -> stickPlayerSpecialOrJump()
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
        if (shakeTimer > 0f) {
            shakeTimer = max(0f, shakeTimer - dt)
            if (shakeTimer <= 0f) shakeStrength = 0f
        }

        // Update particles
        for (i in 0 until maxParticles) {
            if (ptLife[i] > 0f) {
                ptLife[i] -= dt
                ptX[i] += ptVx[i] * dt
                ptY[i] += ptVy[i] * dt
                ptVy[i] += dp(140f) * dt // subtle gravity
            }
        }

        // Update floating score texts
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
            MODE_STICKMAN -> updateStickman(dt)
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
            MODE_STICKMAN -> drawStickman(canvas, w, h)
        }

        drawParticlesAndFloatTexts(canvas)
        canvas.restore()

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
        for (i in 0 until maxParticles) {
            val life = ptLife[i]
            if (life > 0f) {
                val ratio = (life / max(0.01f, ptMaxLife[i])).coerceIn(0f, 1f)
                val alpha = (ratio * 255).toInt()
                pFill.color = (ptColor[i] and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawCircle(ptX[i], ptY[i], ptSize[i] * ratio, pFill)
            }
        }

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
            0 -> { // Bomber Bomb + Fuse Spark
                pFill.color = 0x33E05252
                canvas.drawCircle(cx, cy, r * 1.15f, pFill)
                pFill.color = 0xFF1F1F26.toInt()
                canvas.drawCircle(cx, cy + r * 0.1f, r * 0.65f, pFill)
                pStroke.strokeWidth = dp(2f)
                pStroke.color = COL_RED
                canvas.drawCircle(cx, cy + r * 0.1f, r * 0.65f, pStroke)
                pStroke.color = COL_GOLD_LIGHT
                canvas.drawLine(cx + r * 0.25f, cy - r * 0.45f, cx + r * 0.55f, cy - r * 0.75f, pStroke)
                pFill.color = COL_GOLD_LIGHT
                canvas.drawCircle(cx + r * 0.58f, cy - r * 0.78f, dp(3.5f), pFill)
            }
            1 -> { // Neon Bricks + Glowing Ball
                val bw = r * 0.58f
                val bh = r * 0.24f
                pFill.color = COL_RED
                rect.set(cx - bw - dp(2f), cy - bh * 1.4f, cx - dp(2f), cy - bh * 0.4f)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)
                pFill.color = COL_CYAN
                rect.set(cx + dp(2f), cy - bh * 1.4f, cx + bw + dp(2f), cy - bh * 0.4f)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)
                pFill.color = COL_GOLD_LIGHT
                rect.set(cx - bw * 0.6f, cy - bh * 0.1f, cx + bw * 0.6f, cy + bh * 0.9f)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)
                pFill.color = Color.WHITE
                canvas.drawCircle(cx, cy + r * 0.65f, dp(4.5f), pFill)
            }
            2 -> { // Coiled Gold Snake + Orb
                pStroke.strokeWidth = dp(5f)
                pStroke.strokeCap = Paint.Cap.ROUND
                pStroke.color = COL_GOLD
                rect.set(cx - r * 0.65f, cy - r * 0.45f, cx + r * 0.55f, cy + r * 0.55f)
                canvas.drawArc(rect, 30f, 260f, false, pStroke)
                pFill.color = Color.WHITE
                canvas.drawCircle(cx + r * 0.45f, cy - r * 0.25f, dp(4f), pFill)
                pFill.color = COL_RED
                canvas.drawCircle(cx + r * 0.15f, cy + r * 0.08f, dp(4.5f), pFill)
            }
            3 -> { // Stickman Martial Arts Pose
                pStroke.strokeWidth = dp(2.8f)
                pStroke.strokeCap = Paint.Cap.ROUND
                pStroke.color = COL_GREEN
                canvas.drawCircle(cx, cy - r * 0.55f, dp(5.5f), pStroke)
                canvas.drawLine(cx, cy - r * 0.32f, cx, cy + r * 0.18f, pStroke)
                canvas.drawLine(cx, cy - r * 0.18f, cx + r * 0.52f, cy - r * 0.28f, pStroke)
                canvas.drawLine(cx, cy - r * 0.18f, cx - r * 0.35f, cy + r * 0.05f, pStroke)
                canvas.drawLine(cx, cy + r * 0.18f, cx - r * 0.38f, cy + r * 0.68f, pStroke)
                canvas.drawLine(cx, cy + r * 0.18f, cx + r * 0.48f, cy + r * 0.58f, pStroke)
            }
        }
    }

    private fun drawHub(canvas: Canvas, w: Float, h: Float) {
        val isPortrait = h > w
        val pad = dp(18f)

        // Mute button in Hub top-right
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
        pText.textSize = dp(if (isPortrait) 24f else 28f)
        canvas.drawText("B-BOX Arcade", pad, pad + dp(26f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_MUTE
        pText.textSize = dp(if (isPortrait) 12f else 13.5f)
        canvas.drawText("100% Offline · Efek Partikel & Audio Retro", pad, pad + dp(46f), pText)

        val topY = pad + dp(62f)
        val botY = h - dp(40f)
        val gap = dp(12f)

        if (isPortrait) {
            val cardH = (botY - topY - gap * 3f) / 4f
            for (i in 0..3) {
                val y = topY + i * (cardH + gap)
                hubRects[i].set(pad, y, w - pad, y + cardH)
            }
        } else {
            val cardW = (w - pad * 2f - gap) / 2f
            val cardH = (botY - topY - gap) / 2f
            for (i in 0..3) {
                val col = i % 2
                val row = i / 2
                val x = pad + col * (cardW + gap)
                val y = topY + row * (cardH + gap)
                hubRects[i].set(x, y, x + cardW, y + cardH)
            }
        }

        for (i in 0..3) {
            val r = hubRects[i]
            val focused = i == hubIndex
            val accent = gameAccents[i]

            pFill.color = if (focused) ((accent and 0x00FFFFFF) or 0x36000000) else COL_CARD
            canvas.drawRoundRect(r, dp(16f), dp(16f), pFill)

            pStroke.strokeWidth = dp(if (focused) 2.5f else 1f)
            pStroke.color = if (focused) COL_GOLD_LIGHT else 0x28FFFFFF
            canvas.drawRoundRect(r, dp(16f), dp(16f), pStroke)

            // Mini vector illustration on left of card
            val artR = min(r.height() * 0.34f, dp(26f))
            val artCx = r.left + dp(20f) + artR
            val artCy = r.centerY()
            drawHubMiniArt(canvas, i, artCx, artCy, artR)

            val tx = artCx + artR + dp(16f)
            pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            pText.textAlign = Paint.Align.LEFT
            pText.color = if (focused) COL_GOLD_LIGHT else Color.WHITE
            pText.textSize = dp(if (isPortrait) 17.5f else 20f)
            canvas.drawText(gameTitles[i], tx, r.top + dp(31f), pText)

            pText.typeface = Typeface.SANS_SERIF
            pText.color = COL_MUTE
            pText.textSize = dp(if (isPortrait) 12f else 13f)
            canvas.drawText(gameDescs[i], tx, r.top + dp(51f), pText)

            val hs = getHighScore(i + 1)
            pText.color = COL_GOLD_LIGHT
            pText.textSize = dp(11.5f)
            canvas.drawText("Rekor Tertinggi: $hs", tx, r.bottom - dp(13f), pText)
        }

        pText.textAlign = Paint.Align.CENTER
        pText.color = COL_MUTE
        pText.textSize = dp(11.5f)
        canvas.drawText("Sentuh Kartu / Tekan OK · Tombol BACK Kembali ke Beranda", w * 0.5f, h - dp(14f), pText)
    }

    private fun drawHud(canvas: Canvas, w: Float, isPortrait: Boolean) {
        val topH = dp(48f)
        pFill.color = 0xFF131317.toInt()
        canvas.drawRect(0f, 0f, w, topH, pFill)
        pStroke.strokeWidth = dp(1f)
        pStroke.color = 0x22FFFFFF
        canvas.drawLine(0f, topH, w, topH, pStroke)

        // Top-right Pause/Stop Button
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

        // Mute toggle button beside Pause
        val muteW = dp(34f)
        hudMuteRect.set(hudPauseRect.left - dp(8f) - muteW, hudPauseRect.top, hudPauseRect.left - dp(8f), hudPauseRect.bottom)
        pFill.color = 0x1EFFFFFF
        canvas.drawRoundRect(hudMuteRect, dp(10f), dp(10f), pFill)
        pText.color = if (audio.muted) COL_RED else COL_GOLD_LIGHT
        pText.textSize = dp(13f)
        canvas.drawText(if (audio.muted) "🔇" else "🔊", hudMuteRect.centerX(), hudMuteRect.centerY() + dp(5f), pText)

        // Left: Title & Stage
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(if (isPortrait) 13f else 14.5f)
        canvas.drawText("${gameTitles[mode - 1]} · S$stage", dp(12f), dp(29f), pText)

        // Score (compact on portrait so it never overlaps title)
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
            MODE_BRICK -> "LEPAS"
            MODE_SNAKE -> "CEPAT"
            MODE_STICKMAN -> "PUKUL"
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
            val labelB = if (mode == MODE_BOMBER) "LEDAK" else if (stEnergy >= 100) "JURUS" else "LOMPAT"
            pFill.color = if (btnB) 0x99E05252.toInt() else 0x33FFFFFF
            canvas.drawOval(touchBtnBRect, pFill)
            pStroke.strokeWidth = dp(1.8f)
            pStroke.color = if (mode == MODE_STICKMAN && stEnergy >= 100) COL_GOLD_LIGHT else 0x88FFFFFF.toInt()
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
        pText.textSize = dp(14f)
        canvas.drawText(label, r.centerX(), r.centerY() + dp(5f), pText)
    }

    private fun drawReadyOverlay(canvas: Canvas, w: Float, h: Float) {
        pFill.color = 0xDC09090B.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val boxW = min(w * 0.88f, dp(450f))
        val boxH = dp(235f)
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
        canvas.drawText(gameTitles[mode - 1], w * 0.5f, rect.top + dp(42f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_MUTE
        pText.textSize = dp(12.5f)
        canvas.drawText("Rekor Tertinggi: ${getHighScore(mode)}", w * 0.5f, rect.top + dp(66f), pText)

        val guide = gameGuides[mode - 1]
        val mid = guide.length / 2
        val splitAt = guide.indexOf(' ', mid).let { if (it == -1) guide.length else it }
        pText.color = COL_IVORY
        pText.textSize = dp(13f)
        canvas.drawText(guide.substring(0, splitAt).trim(), w * 0.5f, rect.top + dp(102f), pText)
        if (splitAt < guide.length) {
            canvas.drawText(guide.substring(splitAt).trim(), w * 0.5f, rect.top + dp(122f), pText)
        }

        val btnY1 = rect.bottom - dp(64f)
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

        val labels = arrayOf("▶ LANJUTKAN", "↻ ULANGI STAGE", "■ STOP / KELUAR")
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
    // GAME 1: BOMBER ARENA (WITH SPEED POWER-UP & EXPLOSION PARTICLES)
    // =========================================================================
    private val bCols = 13
    private val bRows = 9
    private val bGrid = IntArray(bCols * bRows) // 0=empty, 1=hard, 2=soft, 3=+bomb, 4=+range, 5=+speed
    private var bpX = 1.5f
    private var bpY = 1.5f
    private var bpMaxBombs = 1
    private var bpRange = 2
    private var bpSpeed = 4.4f
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
        bpSpeed = 4.4f
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
                bombTimer[i] = 2.3f
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
        spawnParticles(cx, cy, 14, 0xFFFF9800.toInt(), dp(40f), dp(160f), 4.5f)

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
                        roll < 0.14f -> 3
                        roll < 0.28f -> 4
                        roll < 0.40f -> 5
                        else -> 0
                    }
                    score += 15
                    spawnFloatText(fx, fy, "+15", COL_GOLD_LIGHT)
                    break
                }
            }
        }
    }

    private fun updateBomber(dt: Float) {
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
        if (item in 3..5) {
            bGrid[pCellIdx] = 0
            audio.play(RetroAudioEngine.SFX_POWERUP)
            val px = bomberOx + bpX * bomberCellPx
            val py = bomberOy + bpY * bomberCellPx
            when (item) {
                3 -> { bpMaxBombs = min(4, bpMaxBombs + 1); spawnFloatText(px, py, "+BOM!", COL_CYAN) }
                4 -> { bpRange = min(5, bpRange + 1); spawnFloatText(px, py, "+API!", COL_GOLD_LIGHT) }
                5 -> { bpSpeed = min(6.4f, bpSpeed + 0.65f); spawnFloatText(px, py, "+CEPAT!", COL_GREEN) }
            }
            score += 50
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

        if (fireGrid[bpY.toInt() * bCols + bpX.toInt()] > 0f) {
            bpAlive = false
            audio.play(RetroAudioEngine.SFX_OVER)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = false
            saveHighScore(MODE_BOMBER, score)
            return
        }

        var aliveBots = 0
        val dxs = floatArrayOf(1f, -1f, 0f, 0f)
        val dys = floatArrayOf(0f, 0f, 1f, -1f)
        for (b in 0..2) {
            if (!botAlive[b]) continue
            aliveBots++
            val bgx = botX[b].toInt()
            val bgy = botY[b].toInt()
            if (fireGrid[bgy * bCols + bgx] > 0f) {
                botAlive[b] = false
                score += 200
                audio.play(RetroAudioEngine.SFX_COIN, 1.1f)
                val bx = bomberOx + botX[b] * bomberCellPx
                val by = bomberOy + botY[b] * bomberCellPx
                spawnParticles(bx, by, 16, COL_RED, dp(30f), dp(140f), 4f)
                spawnFloatText(bx, by, "+200!", COL_RED)
                continue
            }

            val dir = botDir[b]
            val nx = botX[b] + dxs[dir] * (2.4f + stage * 0.15f) * dt
            val ny = botY[b] + dys[dir] * (2.4f + stage * 0.15f) * dt
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
                botBombCd[b] = 4.2f + Random.nextFloat() * 2f
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
            score += 300 * stage
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

                if (v in 3..5) {
                    val col = when (v) { 3 -> COL_CYAN; 4 -> COL_GOLD_LIGHT; else -> COL_GREEN }
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
            pFill.color = Color.WHITE
            canvas.drawCircle(bx - cell * 0.1f, by - cell * 0.06f, cell * 0.07f, pFill)
            canvas.drawCircle(bx + cell * 0.1f, by - cell * 0.06f, cell * 0.07f, pFill)
        }

        if (bpAlive) {
            val px = ox + bpX * cell
            val py = oy + bpY * cell
            pFill.color = COL_GOLD_LIGHT
            canvas.drawCircle(px, py, cell * 0.36f, pFill)
            pStroke.strokeWidth = dp(2f)
            pStroke.color = Color.WHITE
            canvas.drawCircle(px, py, cell * 0.36f, pStroke)
        }
    }

    // =========================================================================
    // GAME 2: NEON BRICK (WITH MULTI-BALL, WIDE PADDLE & SHATTER PARTICLES)
    // =========================================================================
    private val brickBoard = RectF()
    private val brCols = 9
    private val brRows = 6
    private val bricks = IntArray(brCols * brRows)
    private var brickPaddleX = 0.5f
    private var brickPaddleW = 0.24f
    private var brickWideTimer = 0f
    private var brickLives = 3

    // Up to 3 simultaneous balls (primitive arrays, zero GC)
    private val maxBalls = 3
    private val ballActive = BooleanArray(maxBalls)
    private val ballLaunched = BooleanArray(maxBalls)
    private val ballX = FloatArray(maxBalls)
    private val ballY = FloatArray(maxBalls)
    private val ballVx = FloatArray(maxBalls)
    private val ballVy = FloatArray(maxBalls)

    // Falling power-up capsules (4 slots)
    private val maxCapsules = 4
    private val capActive = BooleanArray(maxCapsules)
    private val capType = IntArray(maxCapsules) // 1=Wide, 2=MultiBall, 3=+100
    private val capX = FloatArray(maxCapsules)
    private val capY = FloatArray(maxCapsules)

    private fun initBrick() {
        brickPaddleX = 0.5f
        brickPaddleW = 0.24f
        brickWideTimer = 0f
        brickLives = 3
        capActive.fill(false)
        resetSingleBall()
        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                bricks[r * brCols + c] = if (r < 2 && stage > 1) 2 else 1
            }
        }
    }

    private fun resetSingleBall() {
        ballActive.fill(false)
        ballLaunched.fill(false)
        ballActive[0] = true
        ballLaunched[0] = false
        ballX[0] = brickPaddleX
        ballY[0] = 0.84f
        ballVx[0] = (if (Random.nextBoolean()) 1f else -1f) * (0.40f + stage * 0.03f)
        ballVy[0] = -(0.65f + stage * 0.04f)
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

    private fun spawnMultiBalls() {
        var src = 0
        for (i in 0 until maxBalls) {
            if (ballActive[i]) { src = i; break }
        }
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

        val pSpeed = 1.3f
        if (inLeft) brickPaddleX -= pSpeed * dt
        if (inRight) brickPaddleX += pSpeed * dt
        brickPaddleX = brickPaddleX.coerceIn(brickPaddleW * 0.5f, 1f - brickPaddleW * 0.5f)

        // Update falling power-up capsules
        for (k in 0 until maxCapsules) {
            if (!capActive[k]) continue
            capY[k] += 0.38f * dt
            if (capY[k] in 0.84f..0.91f && abs(capX[k] - brickPaddleX) <= brickPaddleW * 0.55f) {
                capActive[k] = false
                audio.play(RetroAudioEngine.SFX_POWERUP)
                val fx = brickBoard.left + brickBoard.width() * capX[k]
                val fy = brickBoard.top + brickBoard.height() * capY[k]
                when (capType[k]) {
                    1 -> { brickWideTimer = 14f; brickPaddleW = 0.36f; spawnFloatText(fx, fy, "LEBAR!", COL_GREEN) }
                    2 -> { spawnMultiBalls(); spawnFloatText(fx, fy, "MULTI-BOLA!", COL_CYAN) }
                    else -> { score += 100; spawnFloatText(fx, fy, "+100!", COL_GOLD_LIGHT) }
                }
            } else if (capY[k] > 1.03f) {
                capActive[k] = false
            }
        }

        val rowColors = intArrayOf(COL_RED, COL_GOLD, COL_GOLD_LIGHT, COL_GREEN, COL_CYAN, COL_PURPLE)
        val topArea = 0.08f
        val rowH = 0.055f
        val colW = 1f / brCols

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
                        bricks[idx]--
                        ballVy[b] = -ballVy[b]
                        score += 25
                        audio.play(RetroAudioEngine.SFX_COIN, 1f + (brRows - r) * 0.06f)

                        val px = brickBoard.left + brickBoard.width() * ((bx1 + bx2) * 0.5f)
                        val py = brickBoard.top + brickBoard.height() * ((by1 + by2) * 0.5f)
                        spawnParticles(px, py, 8, rowColors[r % rowColors.size], dp(30f), dp(110f), 3.5f)

                        if (bricks[idx] == 0 && Random.nextFloat() < 0.22f) {
                            for (k in 0 until maxCapsules) {
                                if (!capActive[k]) {
                                    capActive[k] = true
                                    capType[k] = Random.nextInt(1, 4)
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
            score += 250 * stage
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
                audio.play(RetroAudioEngine.SFX_PUNCH)
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

        // Power-up capsules
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = dp(10.5f)
        for (k in 0 until maxCapsules) {
            if (!capActive[k]) continue
            val cx = brickBoard.left + brickBoard.width() * capX[k]
            val cy = brickBoard.top + brickBoard.height() * capY[k]
            val col = when (capType[k]) { 1 -> COL_GREEN; 2 -> COL_CYAN; else -> COL_GOLD_LIGHT }
            val lbl = when (capType[k]) { 1 -> "W"; 2 -> "M"; else -> "+" }
            rect.set(cx - dp(12f), cy - dp(7f), cx + dp(12f), cy + dp(7f))
            pFill.color = col
            canvas.drawRoundRect(rect, dp(7f), dp(7f), pFill)
            pText.color = Color.BLACK
            canvas.drawText(lbl, cx, cy + dp(3.5f), pText)
        }

        // Paddle with fake glow
        val pw = brickBoard.width() * brickPaddleW
        val px = brickBoard.left + brickBoard.width() * brickPaddleX
        val py = brickBoard.top + brickBoard.height() * 0.87f
        rect.set(px - pw * 0.5f, py, px + pw * 0.5f, py + dp(11f))
        pFill.color = if (brickWideTimer > 0f) COL_GREEN else COL_GOLD_LIGHT
        canvas.drawRoundRect(rect, dp(6f), dp(6f), pFill)

        // Balls with fake neon halo
        for (b in 0 until maxBalls) {
            if (!ballActive[b]) continue
            val bx = brickBoard.left + brickBoard.width() * ballX[b]
            val by = brickBoard.top + brickBoard.height() * ballY[b]
            pFill.color = 0x33FFFFFF
            canvas.drawCircle(bx, by, dp(11f), pFill)
            pFill.color = Color.WHITE
            canvas.drawCircle(bx, by, dp(6.5f), pFill)
        }

        pText.typeface = Typeface.SANS_SERIF
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_MUTE
        pText.textSize = dp(11.5f)
        canvas.drawText("Nyawa: $brickLives · Tangkap W (Lebar) & M (Multi-Bola)", brickBoard.left + dp(10f), brickBoard.bottom - dp(8f), pText)
    }

    // =========================================================================
    // GAME 3: GOLD SNAKE (WITH COMBO MULTIPLIER & GLOWING ORBS)
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
    private var bonusTimer = 0f
    private var snakeCombo = 1
    private var snakeComboTimer = 0f
    private var snakeCellPx = 24f
    private var snakeOx = 0f
    private var snakeOy = 0f

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
            for (i in 0 until snakeLen) {
                if (snakeX[i] == fx && snakeY[i] == fy) { hit = true; break }
            }
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

        val interval = if (btnA) 0.08f else max(0.095f, 0.175f - snakeLen * 0.002f)
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

        for (i in 0 until snakeLen - 1) {
            if (snakeX[i] == nx && snakeY[i] == ny) {
                audio.play(RetroAudioEngine.SFX_OVER)
                triggerShake(0.2f, 6f)
                clearInputs()
                state = STATE_GAMEOVER
                dialogIndex = 0
                victory = false
                saveHighScore(MODE_SNAKE, score)
                return
            }
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
            if (snakeComboTimer > 0f) snakeCombo = min(4, snakeCombo + 1) else snakeCombo = 1
            snakeComboTimer = 4.5f
            val pts = 20 * snakeCombo
            score += pts
            audio.play(RetroAudioEngine.SFX_COIN, 0.95f + snakeCombo * 0.1f)
            val fx = snakeOx + (nx + 0.5f) * snakeCellPx
            val fy = snakeOy + (ny + 0.5f) * snakeCellPx
            spawnParticles(fx, fy, 10, COL_GOLD_LIGHT, dp(25f), dp(95f), 3.5f)
            spawnFloatText(fx, fy, if (snakeCombo > 1) "+$pts (x$snakeCombo!)" else "+$pts", COL_GOLD_LIGHT)

            spawnSnakeFood()
            if (snakeLen % 5 == 0 && bonusX < 0) {
                bonusX = (foodX + 7) % sCols
                bonusY = (foodY + 5) % sRows
                bonusTimer = 6.5f
            }
        }
        if (ateBonus) {
            score += 100
            bonusX = -1
            audio.play(RetroAudioEngine.SFX_POWERUP)
            val bx = snakeOx + (nx + 0.5f) * snakeCellPx
            val by = snakeOy + (ny + 0.5f) * snakeCellPx
            spawnParticles(bx, by, 14, COL_CYAN, dp(30f), dp(120f), 4f)
            spawnFloatText(bx, by, "+100 BONUS!", COL_CYAN)
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

        // Subtle arena grid dots
        pFill.color = 0x10FFFFFF
        for (r in 1 until sRows step 2) {
            for (c in 1 until sCols step 2) {
                canvas.drawCircle(ox + c * cell, oy + r * cell, dp(1.2f), pFill)
            }
        }

        // Pulsing Food Orb with Fake Glow
        val pulse = 1f + 0.14f * sin(globalTime * 7f)
        val fx = ox + (foodX + 0.5f) * cell
        val fy = oy + (foodY + 0.5f) * cell
        pFill.color = 0x38E05252
        canvas.drawCircle(fx, fy, cell * 0.52f * pulse, pFill)
        pFill.color = COL_RED
        canvas.drawCircle(fx, fy, cell * 0.33f * pulse, pFill)

        // Pulsing Cyan Bonus Orb
        if (bonusX >= 0) {
            val bx = ox + (bonusX + 0.5f) * cell
            val by = oy + (bonusY + 0.5f) * cell
            pFill.color = 0x444CC3D9
            canvas.drawCircle(bx, by, cell * 0.62f * pulse, pFill)
            pFill.color = COL_CYAN
            canvas.drawCircle(bx, by, cell * 0.40f * pulse, pFill)
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
    // GAME 4: STICKMAN FIGHTER (SLASH ARCS, NINJAS & STAGE-3 DOJO BOSS)
    // =========================================================================
    private var stPlayerX = 0.5f
    private var stPlayerY = 0f
    private var stPlayerVy = 0f
    private var stFacing = 1
    private var stHp = 100
    private var stEnergy = 40
    private var stAttackTimer = 0f
    private var stComboStep = 0
    private var stHurtTimer = 0f
    private var stWaveTimer = 0f
    private var stWaveX = 0f
    private var stWaveDir = 1
    private var stEnemiesLeft = 6
    private var stBossPending = false

    private val maxEnemies = 5
    private val enActive = BooleanArray(maxEnemies)
    private val enType = IntArray(maxEnemies) // 0=normal, 1=fast ninja, 2=giant boss
    private val enX = FloatArray(maxEnemies)
    private val enHp = IntArray(maxEnemies)
    private val enMaxHp = IntArray(maxEnemies)
    private val enAtkCd = FloatArray(maxEnemies)
    private val enAnim = FloatArray(maxEnemies)
    private var stSpawnCd = 0.8f

    private fun initStickman() {
        stPlayerX = 0.5f
        stPlayerY = 0f
        stPlayerVy = 0f
        stFacing = 1
        stHp = 100
        stEnergy = 50
        stAttackTimer = 0f
        stComboStep = 0
        stHurtTimer = 0f
        stWaveTimer = 0f
        stEnemiesLeft = 4 + stage * 2
        stBossPending = (stage % 3 == 0)
        stSpawnCd = 0.4f
        enActive.fill(false)
    }

    private fun stickPlayerAttack() {
        if (stAttackTimer > 0.08f) return
        stAttackTimer = 0.24f
        stComboStep = (stComboStep % 3) + 1
        val reach = if (stComboStep == 3) 0.16f else 0.13f
        val dmg = if (stComboStep == 3) 45 else 25
        audio.play(RetroAudioEngine.SFX_PUNCH, 0.9f + stComboStep * 0.15f)

        val deckTop = getControlDeckTop(height.toFloat())
        val groundY = deckTop - dp(26f)
        val hitPy = groundY - stPlayerY * dp(145f) - dp(38f)

        var hitAny = false
        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            val dx = enX[i] - stPlayerX
            if (dx * stFacing in -0.02f..reach) {
                hitAny = true
                enHp[i] -= dmg
                val knock = if (enType[i] == 2) 0.015f else 0.055f
                enX[i] = (enX[i] + stFacing * knock).coerceIn(0.05f, 0.95f)
                stEnergy = min(100, stEnergy + 15)

                val ex = width * enX[i]
                spawnParticles(ex, hitPy, if (stComboStep == 3) 12 else 6, COL_GOLD_LIGHT, dp(35f), dp(130f), 3.5f)
                if (stComboStep == 3) {
                    triggerShake(0.12f, 4f)
                    spawnFloatText(ex, hitPy - dp(16f), "COMBO x3!", COL_GOLD_LIGHT)
                }

                if (enHp[i] <= 0) {
                    enActive[i] = false
                    val pts = if (enType[i] == 2) 400 else 60
                    score += pts
                    audio.play(RetroAudioEngine.SFX_COIN, 1.15f)
                    spawnFloatText(ex, hitPy - dp(24f), "+$pts", if (enType[i] == 2) COL_PURPLE else COL_GREEN)
                }
            }
        }
        if (hitAny && stComboStep == 3) {
            audio.play(RetroAudioEngine.SFX_EXPLODE, 1.35f)
        }
    }

    private fun stickPlayerSpecialOrJump() {
        if (stEnergy >= 100 && stWaveTimer <= 0f) {
            stEnergy = 0
            stWaveTimer = 0.65f
            stWaveX = stPlayerX
            stWaveDir = stFacing
            audio.play(RetroAudioEngine.SFX_POWERUP, 1.3f)
            triggerShake(0.18f, 5.5f)
            return
        }
        if (stPlayerY <= 0.001f) {
            stPlayerVy = 1.65f
            audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
        }
    }

    private fun updateStickman(dt: Float) {
        val speed = 0.52f
        if (inLeft) { stPlayerX -= speed * dt; stFacing = -1 }
        if (inRight) { stPlayerX += speed * dt; stFacing = 1 }
        stPlayerX = stPlayerX.coerceIn(0.06f, 0.94f)

        if (inUp && stPlayerY <= 0.001f) {
            stPlayerVy = 1.65f
            audio.play(RetroAudioEngine.SFX_BOUNCE, 1.15f)
        }
        if (stPlayerY > 0f || stPlayerVy != 0f) {
            stPlayerY += stPlayerVy * dt
            stPlayerVy -= 4.2f * dt
            if (stPlayerY <= 0f) {
                stPlayerY = 0f
                stPlayerVy = 0f
            }
        }

        if (stAttackTimer > 0f) stAttackTimer = max(0f, stAttackTimer - dt)
        if (stHurtTimer > 0f) stHurtTimer = max(0f, stHurtTimer - dt)

        if (stWaveTimer > 0f) {
            stWaveTimer -= dt
            stWaveX += stWaveDir * 1.35f * dt
            for (i in 0 until maxEnemies) {
                if (enActive[i] && abs(enX[i] - stWaveX) < 0.075f) {
                    enHp[i] -= 110
                    if (enHp[i] <= 0) {
                        enActive[i] = false
                        score += if (enType[i] == 2) 400 else 80
                    }
                }
            }
        }

        if (stEnemiesLeft > 0) {
            stSpawnCd -= dt
            if (stSpawnCd <= 0f) {
                for (i in 0 until maxEnemies) {
                    if (!enActive[i]) {
                        enActive[i] = true
                        enX[i] = if (Random.nextBoolean()) 0.04f else 0.96f
                        val isNinja = stage >= 2 && Random.nextFloat() < 0.35f
                        enType[i] = if (isNinja) 1 else 0
                        enHp[i] = (if (isNinja) 35 else 45) + stage * 10
                        enMaxHp[i] = enHp[i]
                        enAtkCd[i] = 0.7f
                        enAnim[i] = 0f
                        stEnemiesLeft--
                        stSpawnCd = max(0.85f, 1.8f - stage * 0.15f)
                        break
                    }
                }
            }
        } else if (stBossPending) {
            // Spawn Giant Dojo Boss when regular minions finish
            var anyAlive = false
            for (i in 0 until maxEnemies) if (enActive[i]) anyAlive = true
            if (!anyAlive) {
                stBossPending = false
                enActive[0] = true
                enType[0] = 2
                enX[0] = 0.92f
                enHp[0] = 180 + stage * 35
                enMaxHp[0] = enHp[0]
                enAtkCd[0] = 1.0f
                enAnim[0] = 0f
                triggerShake(0.25f, 7f)
                audio.play(RetroAudioEngine.SFX_EXPLODE, 0.7f)
                spawnFloatText(width * 0.5f, height * 0.35f, "BOSS DOJO MUNCUL!", COL_PURPLE)
            }
        }

        var activeCount = 0
        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            activeCount++
            enAnim[i] += dt * 7f
            val dx = stPlayerX - enX[i]
            val dir = if (dx >= 0f) 1f else -1f
            val moveSpd = when (enType[i]) {
                1 -> 0.34f + stage * 0.02f // fast ninja
                2 -> 0.18f // heavy boss
                else -> 0.24f + stage * 0.02f
            }
            val hitRange = if (enType[i] == 2) 0.095f else 0.075f
            if (abs(dx) > hitRange) {
                enX[i] += dir * moveSpd * dt
            } else {
                enAtkCd[i] -= dt
                if (enAtkCd[i] <= 0f) {
                    enAtkCd[i] = if (enType[i] == 2) 1.25f else 1.05f
                    if (stPlayerY < 0.12f && stHurtTimer <= 0f) {
                        val dmg = if (enType[i] == 2) 20 else 12
                        stHp = max(0, stHp - dmg)
                        stHurtTimer = 0.35f
                        triggerShake(0.14f, 5f)
                        audio.play(RetroAudioEngine.SFX_PUNCH, 0.65f)
                    }
                }
            }
        }

        if (stHp <= 0) {
            audio.play(RetroAudioEngine.SFX_OVER)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = false
            saveHighScore(MODE_STICKMAN, score)
            return
        }

        if (stEnemiesLeft == 0 && !stBossPending && activeCount == 0) {
            score += 300 * stage
            stage++
            audio.play(RetroAudioEngine.SFX_WIN)
            clearInputs()
            state = STATE_GAMEOVER
            dialogIndex = 0
            victory = true
            saveHighScore(MODE_STICKMAN, score)
        }
    }

    private fun drawStickFigure(
        canvas: Canvas,
        cx: Float,
        footY: Float,
        scale: Float,
        facing: Int,
        color: Int,
        isAttacking: Boolean,
        combo: Int,
        phase: Float,
        isBoss: Boolean = false
    ) {
        pStroke.color = color
        pStroke.strokeWidth = dp(if (isBoss) 5f else 3.2f)
        pStroke.strokeCap = Paint.Cap.ROUND

        val headR = 11f * scale
        val hipY = footY - 34f * scale
        val shoulderY = hipY - 28f * scale
        val headY = shoulderY - headR - 3f * scale

        pFill.color = color
        canvas.drawCircle(cx, headY, headR, pFill)
        canvas.drawLine(cx, shoulderY, cx, hipY, pStroke)

        val stride = sin(phase) * 14f * scale
        if (isAttacking && combo == 3) {
            canvas.drawLine(cx, hipY, cx - facing * 8f * scale, footY, pStroke)
            canvas.drawLine(cx, hipY, cx + facing * 34f * scale, hipY - 12f * scale, pStroke)
        } else {
            canvas.drawLine(cx, hipY, cx - stride, footY, pStroke)
            canvas.drawLine(cx, hipY, cx + stride, footY, pStroke)
        }

        if (isAttacking && combo != 3) {
            canvas.drawLine(cx, shoulderY + 4f * scale, cx + facing * 34f * scale, shoulderY + 2f * scale, pStroke)
            canvas.drawLine(cx, shoulderY + 4f * scale, cx - facing * 10f * scale, shoulderY + 14f * scale, pStroke)
        } else {
            canvas.drawLine(cx, shoulderY + 4f * scale, cx + facing * 14f * scale, shoulderY + 12f * scale, pStroke)
            canvas.drawLine(cx, shoulderY + 4f * scale, cx - facing * 12f * scale, shoulderY + 14f * scale, pStroke)
        }

        // Slash motion arc when attacking
        if (isAttacking) {
            pStroke.strokeWidth = dp(2.5f)
            pStroke.color = 0xAAFFE082.toInt()
            val arcR = 32f * scale
            rect.set(cx - arcR, shoulderY - arcR * 0.7f, cx + arcR, shoulderY + arcR * 0.9f)
            arcPath.reset()
            arcPath.addArc(rect, if (facing > 0) -55f else 125f, 110f)
            canvas.drawPath(arcPath, pStroke)
        }
    }

    private fun drawStickman(canvas: Canvas, w: Float, h: Float) {
        val deckTop = getControlDeckTop(h)
        val groundY = deckTop - dp(26f)
        val scale = dp(1f) * (if (h > w) 1.05f else 1.25f)

        pFill.color = 0x1AE6C88F
        canvas.drawCircle(w * 0.5f, groundY - dp(115f), dp(82f), pFill)
        pFill.color = 0xFF1A1A20.toInt()
        canvas.drawRect(0f, groundY, w, deckTop, pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = COL_GOLD
        canvas.drawLine(0f, groundY, w, groundY, pStroke)

        // Player HP & Energy bars
        val barW = min(dp(180f), w * 0.38f)
        rect.set(dp(16f), dp(58f), dp(16f) + barW, dp(68f))
        pFill.color = 0x33FFFFFF
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
        rect.right = dp(16f) + barW * (stHp / 100f)
        pFill.color = COL_GREEN
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)

        rect.set(dp(16f), dp(72f), dp(16f) + barW, dp(79f))
        pFill.color = 0x33FFFFFF
        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
        rect.right = dp(16f) + barW * (stEnergy / 100f)
        pFill.color = COL_GOLD_LIGHT
        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)

        pText.typeface = Typeface.SANS_SERIF
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_MUTE
        pText.textSize = dp(11f)
        val spReady = if (stEnergy >= 100) " · JURUS SIAP!" else " · Energi $stEnergy%"
        canvas.drawText("HP $stHp%$spReady", dp(16f), dp(93f), pText)

        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            val isBoss = enType[i] == 2
            val enColor = when (enType[i]) {
                1 -> COL_CYAN
                2 -> COL_PURPLE
                else -> COL_RED
            }
            val eScale = if (isBoss) scale * 1.48f else scale
            drawStickFigure(
                canvas,
                w * enX[i],
                groundY,
                eScale,
                if (enX[i] < stPlayerX) 1 else -1,
                enColor,
                enAtkCd[i] > 0.85f,
                1,
                enAnim[i],
                isBoss
            )

            // Boss HP bar at top right
            if (isBoss) {
                val bBarW = min(dp(170f), w * 0.36f)
                rect.set(w - dp(16f) - bBarW, dp(58f), w - dp(16f), dp(68f))
                pFill.color = 0x33FFFFFF
                canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
                rect.left = rect.right - bBarW * (enHp[i].toFloat() / max(1, enMaxHp[i]))
                pFill.color = COL_PURPLE
                canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
                pText.textAlign = Paint.Align.RIGHT
                pText.color = COL_PURPLE
                canvas.drawText("BOSS DOJO: ${enHp[i]}", w - dp(16f), dp(82f), pText)
            }
        }

        val pColor = if (stHurtTimer > 0f) Color.WHITE else COL_GOLD_LIGHT
        val phase = if (inLeft || inRight) System.nanoTime() / 80_000_000f else 0f
        drawStickFigure(
            canvas,
            w * stPlayerX,
            groundY - stPlayerY * dp(145f),
            scale,
            stFacing,
            pColor,
            stAttackTimer > 0f,
            stComboStep,
            phase
        )

        if (stWaveTimer > 0f) {
            pFill.color = 0x44E6C88F
            rect.set(w * stWaveX - dp(22f), groundY - dp(82f), w * stWaveX + dp(22f), groundY)
            canvas.drawRoundRect(rect, dp(14f), dp(14f), pFill)
            pFill.color = 0xDDE6C88F.toInt()
            rect.set(w * stWaveX - dp(10f), groundY - dp(74f), w * stWaveX + dp(10f), groundY)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), pFill)
        }
    }
}
