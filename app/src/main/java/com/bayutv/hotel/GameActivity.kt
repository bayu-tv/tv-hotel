package com.bayutv.hotel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
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
        gameView.pauseLoop()
        super.onDestroy()
    }
}

private class ArcadeCanvasView(context: Context) : View(context) {

    companion object {
        const val MODE_HUB = 0
        const val MODE_BOMBER = 1
        const val MODE_BRICK = 2
        const val MODE_SNAKE = 3
        const val MODE_STICKMAN = 4

        const val COL_BG = 0xFF0D0D0F.toInt()
        const val COL_CARD = 0xFF17171C.toInt()
        const val COL_GOLD = 0xFFB8975A.toInt()
        const val COL_GOLD_LIGHT = 0xFFE6C88F.toInt()
        const val COL_IVORY = 0xFFEFE8DA.toInt()
        const val COL_MUTE = 0xFFA79F90.toInt()
        const val COL_RED = 0xFFE05252.toInt()
        const val COL_GREEN = 0xFF4CAF7D.toInt()
        const val COL_CYAN = 0xFF4CC3D9.toInt()
    }

    private val prefs = context.getSharedPreferences("bayutv", Context.MODE_PRIVATE)
    private var running = false
    private var lastFrameNs = 0L

    private var mode = MODE_HUB
    private var hubIndex = 0
    private var paused = false
    private var gameOver = false
    private var victory = false
    private var score = 0
    private var stage = 1

    // Input state (Joystick + D-Pad + Touch)
    private var inLeft = false
    private var inRight = false
    private var inUp = false
    private var inDown = false
    private var btnA = false
    private var btnB = false
    private var btnAJustPressed = false
    private var btnBJustPressed = false
    private var showTouchOverlay = false

    // Pre-allocated Paints & Rects (Zero GC during gameplay)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val rect = RectF()
    private val hubRects = Array(4) { RectF() }

    // Touch virtual buttons
    private val touchDpadCenter = FloatArray(2)
    private val touchBtnARect = RectF()
    private val touchBtnBRect = RectF()
    private var touchDpadRadius = 0f

    private val gameTitles = arrayOf(
        "Bomber Arena",
        "Neon Brick",
        "Gold Snake",
        "Stickman Fighter"
    )
    private val gameDescs = arrayOf(
        "Ledakkan blok & kalahkan 3 bot penjaga",
        "Hancurkan dinding neon & multi-bola",
        "Ular emas klasik + orb bonus berwaktu",
        "Duel dojo stickman combo & jurus emas"
    )
    private val gameAccents = intArrayOf(COL_RED, COL_CYAN, COL_GOLD, COL_GREEN)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        showTouchOverlay = context.resources.displayMetrics.widthPixels < context.resources.displayMetrics.heightPixels
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun getHighScore(gameMode: Int): Int = prefs.getInt("hs_game_$gameMode", 0)

    private fun saveHighScore(gameMode: Int, value: Int) {
        if (value > getHighScore(gameMode)) {
            prefs.edit().putInt("hs_game_$gameMode", value).apply()
        }
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

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNs = now
            update(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    fun onBackAction(): Boolean {
        if (mode != MODE_HUB) {
            mode = MODE_HUB
            paused = false
            gameOver = false
            return true
        }
        return false
    }

    private fun startGame(targetMode: Int, keepProgress: Boolean = false) {
        mode = targetMode
        paused = false
        gameOver = false
        victory = false
        if (!keepProgress) {
            score = 0
            stage = 1
        }
        when (targetMode) {
            MODE_BOMBER -> initBomber()
            MODE_BRICK -> initBrick()
            MODE_SNAKE -> initSnake()
            MODE_STICKMAN -> initStickman()
        }
    }

    // =========================================================================
    // INPUT: JOYSTICK ANALOG, GAMEPAD BUTTONS, REMOTE D-PAD, TOUCHSCREEN
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

            if (mode == MODE_HUB) {
                if (inLeft && !prevL) hubIndex = (hubIndex + 3) % 4
                if (inRight && !prevR) hubIndex = (hubIndex + 1) % 4
                if (inUp && !prevU) hubIndex = (hubIndex + 2) % 4
                if (inDown && !prevD) hubIndex = (hubIndex + 2) % 4
            } else if (mode == MODE_SNAKE) {
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

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                inLeft = true
                if (mode == MODE_HUB) hubIndex = (hubIndex + 3) % 4
                else if (mode == MODE_SNAKE) queueSnakeDir(-1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                inRight = true
                if (mode == MODE_HUB) hubIndex = (hubIndex + 1) % 4
                else if (mode == MODE_SNAKE) queueSnakeDir(1, 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                inUp = true
                if (mode == MODE_HUB) hubIndex = (hubIndex + 2) % 4
                else if (mode == MODE_SNAKE) queueSnakeDir(0, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                inDown = true
                if (mode == MODE_HUB) hubIndex = (hubIndex + 2) % 4
                else if (mode == MODE_SNAKE) queueSnakeDir(0, 1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE -> {
                if (!btnA) btnAJustPressed = true
                btnA = true
                handlePrimaryAction()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (!btnB) btnBJustPressed = true
                btnB = true
                handleSecondaryAction()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                if (mode != MODE_HUB && !gameOver) paused = !paused
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        showTouchOverlay = true
        val w = width.toFloat()
        val h = height.toFloat()

        if (mode == MODE_HUB) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                for (i in 0..3) {
                    if (hubRects[i].contains(x, y)) {
                        hubIndex = i
                        startGame(i + 1)
                        return true
                    }
                }
            }
            return true
        }

        if (gameOver && event.action == MotionEvent.ACTION_DOWN) {
            if (event.y > h * 0.68f && event.x > w * 0.52f) {
                mode = MODE_HUB
            } else {
                startGame(mode, keepProgress = victory)
            }
            return true
        }

        // Multi-touch virtual gamepad handling
        inLeft = false; inRight = false; inUp = false; inDown = false
        var newA = false
        var newB = false

        val count = event.pointerCount
        for (i in 0 until count) {
            val actionMasked = event.actionMasked
            if ((actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP || actionMasked == MotionEvent.ACTION_CANCEL) &&
                i == event.actionIndex
            ) continue

            val px = event.getX(i)
            val py = event.getY(i)

            val dx = px - touchDpadCenter[0]
            val dy = py - touchDpadCenter[1]
            if (dx * dx + dy * dy <= touchDpadRadius * touchDpadRadius * 2.2f) {
                if (abs(dx) > touchDpadRadius * 0.22f) {
                    if (dx < 0) inLeft = true else inRight = true
                }
                if (abs(dy) > touchDpadRadius * 0.22f) {
                    if (dy < 0) inUp = true else inDown = true
                }
                if (mode == MODE_SNAKE && (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
                    if (abs(dx) > abs(dy)) queueSnakeDir(if (dx < 0) -1 else 1, 0)
                    else queueSnakeDir(0, if (dy < 0) -1 else 1)
                }
            } else if (touchBtnARect.contains(px, py)) {
                newA = true
            } else if (touchBtnBRect.contains(px, py)) {
                newB = true
            } else if (mode == MODE_BRICK && py < h * 0.72f) {
                brickPaddleX = ((px - brickBoard.left) / brickBoard.width()).coerceIn(0.08f, 0.92f)
            }
        }

        if (newA && !btnA) {
            btnAJustPressed = true
            handlePrimaryAction()
        }
        if (newB && !btnB) {
            btnBJustPressed = true
            handleSecondaryAction()
        }
        btnA = newA
        btnB = newB
        return true
    }

    private fun handlePrimaryAction() {
        if (mode == MODE_HUB) {
            startGame(hubIndex + 1)
            return
        }
        if (gameOver) {
            startGame(mode, keepProgress = victory)
            return
        }
        if (paused) {
            paused = false
            return
        }
        when (mode) {
            MODE_BOMBER -> placePlayerBomb()
            MODE_BRICK -> brickLaunched = true
            MODE_STICKMAN -> stickPlayerAttack()
        }
    }

    private fun handleSecondaryAction() {
        if (mode == MODE_HUB) return
        if (gameOver) {
            mode = MODE_HUB
            return
        }
        when (mode) {
            MODE_BOMBER -> detonateEarliestBomb()
            MODE_STICKMAN -> stickPlayerSpecialOrJump()
        }
    }

    // =========================================================================
    // MASTER UPDATE & DRAW
    // =========================================================================
    private fun update(dt: Float) {
        if (mode == MODE_HUB || paused || gameOver) {
            btnAJustPressed = false
            btnBJustPressed = false
            return
        }
        when (mode) {
            MODE_BOMBER -> updateBomber(dt)
            MODE_BRICK -> updateBrick(dt)
            MODE_SNAKE -> updateSnake(dt)
            MODE_STICKMAN -> updateStickman(dt)
        }
        btnAJustPressed = false
        btnBJustPressed = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(COL_BG)

        touchDpadRadius = min(w, h) * 0.13f
        touchDpadCenter[0] = dp(24f) + touchDpadRadius
        touchDpadCenter[1] = h - dp(24f) - touchDpadRadius
        val bSize = touchDpadRadius * 0.88f
        touchBtnARect.set(w - dp(24f) - bSize * 2f, h - dp(24f) - bSize * 2f, w - dp(24f), h - dp(24f))
        touchBtnBRect.set(
            touchBtnARect.left - bSize * 2.2f,
            touchBtnARect.top + bSize * 0.35f,
            touchBtnARect.left - dp(12f),
            touchBtnARect.bottom + bSize * 0.35f
        )

        if (mode == MODE_HUB) {
            drawHub(canvas, w, h)
            return
        }

        drawHud(canvas, w)

        when (mode) {
            MODE_BOMBER -> drawBomber(canvas, w, h)
            MODE_BRICK -> drawBrick(canvas, w, h)
            MODE_SNAKE -> drawSnake(canvas, w, h)
            MODE_STICKMAN -> drawStickman(canvas, w, h)
        }

        if (showTouchOverlay && !gameOver) {
            drawTouchControls(canvas)
        }

        if (paused) {
            drawOverlayDialog(canvas, w, h, "JEDA", "Tekan START / A untuk lanjut · BACK untuk keluar", COL_GOLD_LIGHT)
        } else if (gameOver) {
            val title = if (victory) "STAGE SELESAI!" else "GAME OVER"
            val sub = if (victory) {
                "Skor: $score · Tekan A untuk Stage ${stage + 1} · B untuk Menu"
            } else {
                "Skor Akhir: $score · Tekan A untuk Ulang · B untuk Menu"
            }
            drawOverlayDialog(canvas, w, h, title, sub, if (victory) COL_GREEN else COL_RED)
        }
    }

    private fun drawHub(canvas: Canvas, w: Float, h: Float) {
        val isPortrait = h > w
        val pad = dp(20f)

        pText.typeface = Typeface.SERIF
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(if (isPortrait) 24f else 28f)
        canvas.drawText("B-BOX Arcade", pad, pad + dp(26f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_MUTE
        pText.textSize = dp(if (isPortrait) 12.5f else 14f)
        canvas.drawText("Offline · Pilih dengan Joystick / Remote / Sentuh", pad, pad + dp(46f), pText)

        val topY = pad + dp(64f)
        val botY = h - dp(44f)
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

            pFill.color = if (focused) ((accent and 0x00FFFFFF) or 0x33000000) else COL_CARD
            canvas.drawRoundRect(r, dp(16f), dp(16f), pFill)

            pStroke.strokeWidth = dp(if (focused) 2.5f else 1f)
            pStroke.color = if (focused) Color.WHITE else 0x2EFFFFFF
            canvas.drawRoundRect(r, dp(16f), dp(16f), pStroke)

            pFill.color = accent
            rect.set(r.left + dp(12f), r.top + dp(14f), r.left + dp(17f), r.bottom - dp(14f))
            canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)

            val tx = r.left + dp(28f)
            pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            pText.textAlign = Paint.Align.LEFT
            pText.color = Color.WHITE
            pText.textSize = dp(if (isPortrait) 18f else 20f)
            canvas.drawText(gameTitles[i], tx, r.top + dp(32f), pText)

            pText.typeface = Typeface.SANS_SERIF
            pText.color = COL_MUTE
            pText.textSize = dp(if (isPortrait) 12.5f else 13.5f)
            canvas.drawText(gameDescs[i], tx, r.top + dp(52f), pText)

            val hs = getHighScore(i + 1)
            pText.color = COL_GOLD_LIGHT
            pText.textSize = dp(12f)
            canvas.drawText("Rekor Tertinggi: $hs", tx, r.bottom - dp(14f), pText)
        }

        pText.textAlign = Paint.Align.CENTER
        pText.color = COL_MUTE
        pText.textSize = dp(12f)
        canvas.drawText("Tekan A / OK untuk Mulai · Tombol BACK untuk Kembali ke Beranda", w * 0.5f, h - dp(16f), pText)
    }

    private fun drawHud(canvas: Canvas, w: Float) {
        val topH = dp(44f)
        pFill.color = 0xFF141418.toInt()
        canvas.drawRect(0f, 0f, w, topH, pFill)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_GOLD_LIGHT
        pText.textSize = dp(15f)
        canvas.drawText("${gameTitles[mode - 1]}  ·  Stage $stage", dp(16f), dp(28f), pText)

        pText.textAlign = Paint.Align.RIGHT
        pText.color = Color.WHITE
        canvas.drawText("SKOR: $score   (REKOR: ${max(score, getHighScore(mode))})", w - dp(16f), dp(28f), pText)
    }

    private fun drawTouchControls(canvas: Canvas) {
        pFill.color = 0x26FFFFFF
        canvas.drawCircle(touchDpadCenter[0], touchDpadCenter[1], touchDpadRadius, pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x55FFFFFF
        canvas.drawCircle(touchDpadCenter[0], touchDpadCenter[1], touchDpadRadius, pStroke)

        pFill.color = 0x66E6C88F
        val knobX = touchDpadCenter[0] + (if (inRight) 1f else if (inLeft) -1f else 0f) * touchDpadRadius * 0.45f
        val knobY = touchDpadCenter[1] + (if (inDown) 1f else if (inUp) -1f else 0f) * touchDpadRadius * 0.45f
        canvas.drawCircle(knobX, knobY, touchDpadRadius * 0.36f, pFill)

        pFill.color = if (btnA) 0x88E6C88F.toInt() else 0x38E6C88F
        canvas.drawOval(touchBtnARect, pFill)
        pStroke.color = COL_GOLD_LIGHT
        canvas.drawOval(touchBtnARect, pStroke)
        pText.textAlign = Paint.Align.CENTER
        pText.color = Color.WHITE
        pText.textSize = dp(16f)
        canvas.drawText("A", touchBtnARect.centerX(), touchBtnARect.centerY() + dp(6f), pText)

        pFill.color = if (btnB) 0x88E05252.toInt() else 0x33FFFFFF
        canvas.drawOval(touchBtnBRect, pFill)
        pStroke.color = 0x77FFFFFF
        canvas.drawOval(touchBtnBRect, pStroke)
        canvas.drawText("B", touchBtnBRect.centerX(), touchBtnBRect.centerY() + dp(6f), pText)
    }

    private fun drawOverlayDialog(canvas: Canvas, w: Float, h: Float, title: String, subtitle: String, titleColor: Int) {
        pFill.color = 0xCC09090B.toInt()
        canvas.drawRect(0f, 0f, w, h, pFill)

        val boxW = min(w * 0.86f, dp(420f))
        val boxH = dp(150f)
        rect.set((w - boxW) * 0.5f, (h - boxH) * 0.5f, (w + boxW) * 0.5f, (h + boxH) * 0.5f)
        pFill.color = COL_CARD
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = titleColor
        canvas.drawRoundRect(rect, dp(18f), dp(18f), pStroke)

        pText.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pText.textAlign = Paint.Align.CENTER
        pText.color = titleColor
        pText.textSize = dp(24f)
        canvas.drawText(title, w * 0.5f, rect.top + dp(54f), pText)

        pText.typeface = Typeface.SANS_SERIF
        pText.color = COL_IVORY
        pText.textSize = dp(13.5f)
        canvas.drawText(subtitle, w * 0.5f, rect.top + dp(94f), pText)
    }

    // =========================================================================
    // GAME 1: BOMBER ARENA
    // =========================================================================
    private val bCols = 13
    private val bRows = 9
    private val bGrid = IntArray(bCols * bRows)
    private var bpX = 1.5f
    private var bpY = 1.5f
    private var bpMaxBombs = 1
    private var bpRange = 2
    private var bpAlive = true

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
                if (cell == 2) {
                    val roll = Random.nextFloat()
                    bGrid[cellIdx] = when {
                        roll < 0.16f -> 3
                        roll < 0.32f -> 4
                        else -> 0
                    }
                    score += 15
                    break
                }
            }
        }
    }

    private fun updateBomber(dt: Float) {
        val speed = 4.2f
        var vx = 0f
        var vy = 0f
        if (inLeft) vx -= 1f
        if (inRight) vx += 1f
        if (inUp) vy -= 1f
        if (inDown) vy += 1f

        val curGx = bpX.toInt()
        val curGy = bpY.toInt()
        if (vx != 0f) {
            val nextX = bpX + vx * speed * dt
            val checkGx = (nextX + (if (vx > 0) 0.34f else -0.34f)).toInt()
            if (checkGx == curGx || isBomberWalkable(checkGx, curGy)) {
                bpX = nextX
                bpY += ((curGy + 0.5f) - bpY) * 10f * dt
            }
        }
        if (vy != 0f) {
            val nextY = bpY + vy * speed * dt
            val checkGy = (nextY + (if (vy > 0) 0.34f else -0.34f)).toInt()
            if (checkGy == curGy || isBomberWalkable(curGx, checkGy)) {
                bpY = nextY
                bpX += ((curGx + 0.5f) - bpX) * 10f * dt
            }
        }

        val pCellIdx = bpY.toInt() * bCols + bpX.toInt()
        if (bGrid[pCellIdx] == 3) {
            bGrid[pCellIdx] = 0
            bpMaxBombs = min(4, bpMaxBombs + 1)
            score += 50
        } else if (bGrid[pCellIdx] == 4) {
            bGrid[pCellIdx] = 0
            bpRange = min(5, bpRange + 1)
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
            gameOver = true
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
                continue
            }

            val dir = botDir[b]
            val nx = botX[b] + dxs[dir] * 2.4f * dt
            val ny = botY[b] + dys[dir] * 2.4f * dt
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
            gameOver = true
            victory = true
            saveHighScore(MODE_BOMBER, score)
        }
    }

    private fun drawBomber(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(52f)
        val botOffset = if (showTouchOverlay) touchDpadRadius * 2.2f + dp(24f) else dp(18f)
        val availW = w - dp(24f)
        val availH = h - topOffset - botOffset
        val cell = min(availW / bCols, availH / bRows)
        val ox = (w - cell * bCols) * 0.5f
        val oy = topOffset + (availH - cell * bRows) * 0.5f

        for (r in 0 until bRows) {
            for (c in 0 until bCols) {
                val x = ox + c * cell
                val y = oy + r * cell
                rect.set(x + 1f, y + 1f, x + cell - 1f, y + cell - 1f)
                val v = bGrid[r * bCols + c]
                pFill.color = when (v) {
                    1 -> 0xFF2B2B33.toInt()
                    2 -> 0xFF7A5636.toInt()
                    else -> 0xFF15171A.toInt()
                }
                canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)

                if (v == 3 || v == 4) {
                    pFill.color = if (v == 3) COL_CYAN else COL_GOLD_LIGHT
                    canvas.drawCircle(x + cell * 0.5f, y + cell * 0.5f, cell * 0.26f, pFill)
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
            pFill.color = COL_RED
            canvas.drawCircle(ox + botX[b] * cell, oy + botY[b] * cell, cell * 0.34f, pFill)
        }

        if (bpAlive) {
            pFill.color = COL_GOLD_LIGHT
            canvas.drawCircle(ox + bpX * cell, oy + bpY * cell, cell * 0.36f, pFill)
            pStroke.strokeWidth = dp(2f)
            pStroke.color = Color.WHITE
            canvas.drawCircle(ox + bpX * cell, oy + bpY * cell, cell * 0.36f, pStroke)
        }
    }

    // =========================================================================
    // GAME 2: NEON BRICK
    // =========================================================================
    private val brickBoard = RectF()
    private val brCols = 9
    private val brRows = 6
    private val bricks = IntArray(brCols * brRows)
    private var brickPaddleX = 0.5f
    private var brickPaddleW = 0.22f
    private var brickBallX = 0.5f
    private var brickBallY = 0.80f
    private var brickVx = 0.42f
    private var brickVy = -0.68f
    private var brickLaunched = false
    private var brickLives = 3

    private fun initBrick() {
        brickPaddleX = 0.5f
        brickPaddleW = 0.22f
        brickLives = 3
        resetBrickBall()
        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                bricks[r * brCols + c] = if (r < 2 && stage > 1) 2 else 1
            }
        }
    }

    private fun resetBrickBall() {
        brickLaunched = false
        brickBallX = brickPaddleX
        brickBallY = 0.84f
        brickVx = (if (Random.nextBoolean()) 1f else -1f) * (0.40f + stage * 0.04f)
        brickVy = -(0.65f + stage * 0.05f)
    }

    private fun updateBrick(dt: Float) {
        val pSpeed = 1.15f
        if (inLeft) brickPaddleX -= pSpeed * dt
        if (inRight) brickPaddleX += pSpeed * dt
        brickPaddleX = brickPaddleX.coerceIn(brickPaddleW * 0.5f, 1f - brickPaddleW * 0.5f)

        if (!brickLaunched) {
            brickBallX = brickPaddleX
            brickBallY = 0.84f
            return
        }

        brickBallX += brickVx * dt
        brickBallY += brickVy * dt

        if (brickBallX < 0.02f) { brickBallX = 0.02f; brickVx = abs(brickVx) }
        if (brickBallX > 0.98f) { brickBallX = 0.98f; brickVx = -abs(brickVx) }
        if (brickBallY < 0.02f) { brickBallY = 0.02f; brickVy = abs(brickVy) }

        if (brickVy > 0f && brickBallY in 0.85f..0.90f &&
            abs(brickBallX - brickPaddleX) <= brickPaddleW * 0.54f
        ) {
            val hitOffset = (brickBallX - brickPaddleX) / (brickPaddleW * 0.5f)
            brickVx = hitOffset * 0.72f
            brickVy = -abs(brickVy)
        }

        val topArea = 0.08f
        val rowH = 0.055f
        val colW = 1f / brCols
        var remaining = 0
        for (r in 0 until brRows) {
            for (c in 0 until brCols) {
                val idx = r * brCols + c
                if (bricks[idx] <= 0) continue
                remaining++
                val bx1 = c * colW + 0.008f
                val bx2 = (c + 1) * colW - 0.008f
                val by1 = topArea + r * rowH + 0.006f
                val by2 = topArea + (r + 1) * rowH - 0.006f
                if (brickBallX in (bx1 - 0.015f)..(bx2 + 0.015f) &&
                    brickBallY in (by1 - 0.015f)..(by2 + 0.015f)
                ) {
                    bricks[idx]--
                    brickVy = -brickVy
                    score += 25
                    if (bricks[idx] == 0) remaining--
                    break
                }
            }
        }

        if (remaining == 0) {
            score += 250 * stage
            stage++
            gameOver = true
            victory = true
            saveHighScore(MODE_BRICK, score)
            return
        }

        if (brickBallY > 1.02f) {
            brickLives--
            if (brickLives <= 0) {
                gameOver = true
                victory = false
                saveHighScore(MODE_BRICK, score)
            } else {
                resetBrickBall()
            }
        }
    }

    private fun drawBrick(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(52f)
        val botOffset = if (showTouchOverlay) touchDpadRadius * 2.2f + dp(20f) else dp(18f)
        val boardW = min(w - dp(24f), dp(680f))
        val boardH = h - topOffset - botOffset
        brickBoard.set((w - boardW) * 0.5f, topOffset, (w + boardW) * 0.5f, topOffset + boardH)

        pFill.color = 0xFF131318.toInt()
        canvas.drawRoundRect(brickBoard, dp(14f), dp(14f), pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x2EFFFFFF
        canvas.drawRoundRect(brickBoard, dp(14f), dp(14f), pStroke)

        val rowColors = intArrayOf(COL_RED, COL_GOLD, COL_GOLD_LIGHT, COL_GREEN, COL_CYAN, 0xFF9C6ADE.toInt())
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

        val pw = brickBoard.width() * brickPaddleW
        val px = brickBoard.left + brickBoard.width() * brickPaddleX
        val py = brickBoard.top + brickBoard.height() * 0.87f
        rect.set(px - pw * 0.5f, py, px + pw * 0.5f, py + dp(11f))
        pFill.color = COL_GOLD_LIGHT
        canvas.drawRoundRect(rect, dp(6f), dp(6f), pFill)

        val bx = brickBoard.left + brickBoard.width() * brickBallX
        val by = brickBoard.top + brickBoard.height() * brickBallY
        pFill.color = Color.WHITE
        canvas.drawCircle(bx, by, dp(7f), pFill)

        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_MUTE
        pText.textSize = dp(12f)
        canvas.drawText("Nyawa: $brickLives  ·  Tekan A untuk Lepas Bola", brickBoard.left + dp(10f), brickBoard.bottom - dp(10f), pText)
    }

    // =========================================================================
    // GAME 3: GOLD SNAKE
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

    private fun initSnake() {
        snakeLen = 4
        sDirX = 1; sDirY = 0
        nextDirX = 1; nextDirY = 0
        sStepTimer = 0f
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
        val interval = if (btnA) 0.07f else max(0.085f, 0.16f - snakeLen * 0.002f)
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
                gameOver = true
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
            score += 20
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
        }
    }

    private fun drawSnake(canvas: Canvas, w: Float, h: Float) {
        val topOffset = dp(52f)
        val botOffset = if (showTouchOverlay) touchDpadRadius * 2.2f + dp(24f) else dp(18f)
        val cell = min((w - dp(24f)) / sCols, (h - topOffset - botOffset) / sRows)
        val ox = (w - cell * sCols) * 0.5f
        val oy = topOffset + ((h - topOffset - botOffset) - cell * sRows) * 0.5f

        rect.set(ox, oy, ox + cell * sCols, oy + cell * sRows)
        pFill.color = 0xFF131318.toInt()
        canvas.drawRoundRect(rect, dp(12f), dp(12f), pFill)
        pStroke.strokeWidth = dp(1.5f)
        pStroke.color = 0x33B8975A
        canvas.drawRoundRect(rect, dp(12f), dp(12f), pStroke)

        pFill.color = COL_RED
        canvas.drawCircle(ox + (foodX + 0.5f) * cell, oy + (foodY + 0.5f) * cell, cell * 0.34f, pFill)

        if (bonusX >= 0) {
            pFill.color = COL_CYAN
            canvas.drawCircle(ox + (bonusX + 0.5f) * cell, oy + (bonusY + 0.5f) * cell, cell * 0.42f, pFill)
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
    // GAME 4: STICKMAN FIGHTER
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

    private val maxEnemies = 5
    private val enActive = BooleanArray(maxEnemies)
    private val enX = FloatArray(maxEnemies)
    private val enHp = IntArray(maxEnemies)
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
        stSpawnCd = 0.4f
        enActive.fill(false)
    }

    private fun stickPlayerAttack() {
        if (stAttackTimer > 0.08f) return
        stAttackTimer = 0.24f
        stComboStep = (stComboStep % 3) + 1
        val reach = if (stComboStep == 3) 0.14f else 0.11f
        val dmg = if (stComboStep == 3) 45 else 25

        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            val dx = enX[i] - stPlayerX
            if (dx * stFacing in -0.02f..reach) {
                enHp[i] -= dmg
                enX[i] = (enX[i] + stFacing * 0.05f).coerceIn(0.05f, 0.95f)
                stEnergy = min(100, stEnergy + 15)
                if (enHp[i] <= 0) {
                    enActive[i] = false
                    score += 60
                }
            }
        }
    }

    private fun stickPlayerSpecialOrJump() {
        if (stEnergy >= 100 && stWaveTimer <= 0f) {
            stEnergy = 0
            stWaveTimer = 0.65f
            stWaveX = stPlayerX
            stWaveDir = stFacing
            return
        }
        if (stPlayerY <= 0.001f) {
            stPlayerVy = 1.65f
        }
    }

    private fun updateStickman(dt: Float) {
        val speed = 0.48f
        if (inLeft) { stPlayerX -= speed * dt; stFacing = -1 }
        if (inRight) { stPlayerX += speed * dt; stFacing = 1 }
        stPlayerX = stPlayerX.coerceIn(0.06f, 0.94f)

        if (inUp && stPlayerY <= 0.001f) stPlayerVy = 1.65f
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
                if (enActive[i] && abs(enX[i] - stWaveX) < 0.07f) {
                    enHp[i] -= 100
                    if (enHp[i] <= 0) {
                        enActive[i] = false
                        score += 80
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
                        enHp[i] = 45 + stage * 10
                        enAtkCd[i] = 0.7f
                        enAnim[i] = 0f
                        stEnemiesLeft--
                        stSpawnCd = max(0.85f, 1.8f - stage * 0.15f)
                        break
                    }
                }
            }
        }

        var activeCount = 0
        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            activeCount++
            enAnim[i] += dt * 7f
            val dx = stPlayerX - enX[i]
            val dir = if (dx >= 0f) 1f else -1f
            if (abs(dx) > 0.075f) {
                enX[i] += dir * (0.24f + stage * 0.02f) * dt
            } else {
                enAtkCd[i] -= dt
                if (enAtkCd[i] <= 0f) {
                    enAtkCd[i] = 1.05f
                    if (stPlayerY < 0.12f && stHurtTimer <= 0f) {
                        stHp = max(0, stHp - 12)
                        stHurtTimer = 0.35f
                    }
                }
            }
        }

        if (stHp <= 0) {
            gameOver = true
            victory = false
            saveHighScore(MODE_STICKMAN, score)
            return
        }

        if (stEnemiesLeft == 0 && activeCount == 0) {
            score += 300 * stage
            stage++
            gameOver = true
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
        phase: Float
    ) {
        pStroke.color = color
        pStroke.strokeWidth = dp(3.2f)
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
    }

    private fun drawStickman(canvas: Canvas, w: Float, h: Float) {
        val groundY = h - (if (showTouchOverlay) touchDpadRadius * 2.2f + dp(34f) else dp(44f))
        val scale = dp(1f) * (if (h > w) 1.05f else 1.25f)

        pFill.color = 0x1AE6C88F
        canvas.drawCircle(w * 0.5f, groundY - dp(120f), dp(86f), pFill)
        pFill.color = 0xFF1A1A20.toInt()
        canvas.drawRect(0f, groundY, w, h, pFill)
        pStroke.strokeWidth = dp(2f)
        pStroke.color = COL_GOLD
        canvas.drawLine(0f, groundY, w, groundY, pStroke)

        val barW = min(dp(180f), w * 0.38f)
        rect.set(dp(16f), dp(54f), dp(16f) + barW, dp(64f))
        pFill.color = 0x33FFFFFF
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
        rect.right = dp(16f) + barW * (stHp / 100f)
        pFill.color = COL_GREEN
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)

        rect.set(dp(16f), dp(68f), dp(16f) + barW, dp(75f))
        pFill.color = 0x33FFFFFF
        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)
        rect.right = dp(16f) + barW * (stEnergy / 100f)
        pFill.color = COL_GOLD_LIGHT
        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)

        pText.textAlign = Paint.Align.LEFT
        pText.color = COL_MUTE
        pText.textSize = dp(11f)
        val spReady = if (stEnergy >= 100) " · JURUS B SIAP!" else " · A: Serang · B: Lompat/Jurus"
        canvas.drawText("HP $stHp%$spReady", dp(16f), dp(89f), pText)

        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            drawStickFigure(
                canvas,
                w * enX[i],
                groundY,
                scale,
                if (enX[i] < stPlayerX) 1 else -1,
                COL_RED,
                enAtkCd[i] > 0.85f,
                1,
                enAnim[i]
            )
        }

        val pColor = if (stHurtTimer > 0f) Color.WHITE else COL_GOLD_LIGHT
        val phase = if (inLeft || inRight) System.nanoTime() / 80_000_000f else 0f
        drawStickFigure(
            canvas,
            w * stPlayerX,
            groundY - stPlayerY * dp(150f),
            scale,
            stFacing,
            pColor,
            stAttackTimer > 0f,
            stComboStep,
            phase
        )

        if (stWaveTimer > 0f) {
            pFill.color = 0xCCE6C88F.toInt()
            rect.set(w * stWaveX - dp(12f), groundY - dp(75f), w * stWaveX + dp(12f), groundY)
            canvas.drawRoundRect(rect, dp(10f), dp(10f), pFill)
        }
    }
}
