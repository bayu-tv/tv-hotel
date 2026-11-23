package com.bayutv.hotel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class BBoxQuestEngine(private val context: Context) {

    interface Callbacks {
        fun onAddScore(basePoints: Int)
        fun onPlaySfx(sfxType: Int, rate: Float = 1f)
        fun onShake(duration: Float, strengthDp: Float)
        fun onParticles(x: Float, y: Float, count: Int, color: Int, speedMin: Float, speedMax: Float, sizeDp: Float)
        fun onFloatText(x: Float, y: Float, text: String, color: Int)
        fun onStageFinished(victory: Boolean, nextStage: Int)
    }

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

        private const val ROWS = 13
        private const val COLS = 132
    }

    var callbacks: Callbacks? = null

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()

    private val grid = CharArray(ROWS * COLS)
    private val tileBounce = FloatArray(ROWS * COLS)

    private var stage = 1
    private var difficulty = 1
    private var lives = 3
    private var coins = 0
    private var animTime = 0f

    // Player physics (in tile units)
    private var px = 3f
    private var py = 8f
    private var vx = 0f
    private var vy = 0f
    private var facing = 1
    private var onGround = false
    private var coyoteTimer = 0f
    private var jumpBufferTimer = 0f
    private var jumpHoldTimer = 0f
    private var powerLevel = 0 // 0=Normal, 1=Super (Big), 2=Fire
    private var invulnTimer = 0f
    private var fireCooldown = 0f
    private var landSquash = 0f
    private var checkpointX = 3f
    private var checkpointY = 8f
    private var checkpointReached = false
    private var goalX = 124f
    private var cameraX = 0f

    // Screen metrics cache
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardW = 0f
    private var boardH = 0f
    private var tilePx = 36f
    private var cachedSpriteSize = 0
    private var cachedSpriteStage = 0

    // Pre-rendered tile & sprite bitmaps (zero per-frame allocation)
    private var bmpGround: Bitmap? = null
    private var bmpBrick: Bitmap? = null
    private var bmpQuestion: Bitmap? = null
    private var bmpUsed: Bitmap? = null
    private var bmpPipeTop: Bitmap? = null
    private var bmpPipeBody: Bitmap? = null
    private var bmpCoin: Bitmap? = null
    private var bmpSlime: Bitmap? = null
    private var bmpTurtle: Bitmap? = null
    private var bmpShell: Bitmap? = null
    private var bmpBat: Bitmap? = null
    private var skyShader: Shader? = null
    private var cachedSkyH = 0f
    private var cachedSkyStage = 0

    // Enemies pool (24 slots)
    private val maxEnemies = 24
    private val enActive = BooleanArray(maxEnemies)
    private val enType = IntArray(maxEnemies) // 0=Slime, 1=Turtle, 2=Bat
    private val enState = IntArray(maxEnemies) // For Turtle: 0=walk, 1=shell idle, 2=shell slide
    private val enX = FloatArray(maxEnemies)
    private val enY = FloatArray(maxEnemies)
    private val enVx = FloatArray(maxEnemies)
    private val enVy = FloatArray(maxEnemies)
    private val enBaseY = FloatArray(maxEnemies)

    // Moving platforms pool (8 slots)
    private val maxPlats = 8
    private val plActive = BooleanArray(maxPlats)
    private val plX = FloatArray(maxPlats)
    private val plY = FloatArray(maxPlats)
    private val plW = FloatArray(maxPlats)
    private val plMinX = FloatArray(maxPlats)
    private val plMaxX = FloatArray(maxPlats)
    private val plVx = FloatArray(maxPlats)

    // Power-up items pool (4 slots)
    private val maxItems = 4
    private val itActive = BooleanArray(maxItems)
    private val itType = IntArray(maxItems) // 1=Super Gem, 2=Fire Crystal
    private val itX = FloatArray(maxItems)
    private val itY = FloatArray(maxItems)
    private val itVx = FloatArray(maxItems)
    private val itVy = FloatArray(maxItems)

    // Player fireballs pool (4 slots)
    private val maxFireballs = 4
    private val fbActive = BooleanArray(maxFireballs)
    private val fbX = FloatArray(maxFireballs)
    private val fbY = FloatArray(maxFireballs)
    private val fbVx = FloatArray(maxFireballs)
    private val fbVy = FloatArray(maxFireballs)

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density

    fun initStage(targetStage: Int, diff: Int, resetStats: Boolean) {
        stage = ((targetStage - 1) % 3) + 1
        difficulty = diff
        if (resetStats) {
            lives = if (diff == 2) 2 else 3
            coins = 0
            powerLevel = 0
        }
        checkpointReached = false
        checkpointX = 3f
        checkpointY = 8f
        buildLevelMap(stage)
        respawnAtCheckpoint()
    }

    private fun respawnAtCheckpoint() {
        px = checkpointX
        py = checkpointY
        vx = 0f
        vy = 0f
        facing = 1
        onGround = false
        coyoteTimer = 0f
        jumpBufferTimer = 0f
        jumpHoldTimer = 0f
        invulnTimer = 1.2f
        fbActive.fill(false)
        itActive.fill(false)
        cameraX = max(0f, px - 5f)
    }

    private fun setTile(c: Int, r: Int, ch: Char) {
        if (c in 0 until COLS && r in 0 until ROWS) {
            grid[r * COLS + c] = ch
        }
    }

    private fun getTile(c: Int, r: Int): Char {
        if (c < 0 || c >= COLS) return '#'
        if (r < 0) return '.'
        if (r >= ROWS) return '.'
        return grid[r * COLS + c]
    }

    private fun isSolidTile(ch: Char): Boolean {
        return ch == '#' || ch == 'B' || ch == '?' || ch == 'U' || ch == 'X' || ch == 'P' || ch == 'p'
    }

    private fun addPipe(col: Int, heightTiles: Int) {
        val topRow = ROWS - 2 - heightTiles
        setTile(col, topRow, 'P')
        for (r in (topRow + 1) until (ROWS - 2)) {
            setTile(col, r, 'p')
        }
    }

    private fun spawnEnemy(type: Int, col: Int, row: Int) {
        for (i in 0 until maxEnemies) {
            if (!enActive[i]) {
                enActive[i] = true
                enType[i] = type
                enState[i] = 0
                enX[i] = col + 0.1f
                enY[i] = row.toFloat()
                enBaseY[i] = row.toFloat()
                val spd = (2.1f + difficulty * 0.45f)
                enVx[i] = -spd
                enVy[i] = 0f
                return
            }
        }
    }

    private fun spawnMovingPlatform(x: Float, y: Float, widthTiles: Float, minX: Float, maxX: Float, speed: Float) {
        for (i in 0 until maxPlats) {
            if (!plActive[i]) {
                plActive[i] = true
                plX[i] = x
                plY[i] = y
                plW[i] = widthTiles
                plMinX[i] = minX
                plMaxX[i] = maxX
                plVx[i] = speed
                return
            }
        }
    }

    private fun buildLevelMap(st: Int) {
        grid.fill('.')
        tileBounce.fill(0f)
        enActive.fill(false)
        plActive.fill(false)

        // Base ground (rows 11 and 12)
        for (c in 0 until COLS) {
            setTile(c, ROWS - 2, '#')
            setTile(c, ROWS - 1, '#')
        }

        // Carve pits according to stage
        val pits = when (st) {
            1 -> listOf(18..19, 38..40, 58..60, 82..84, 104..106)
            2 -> listOf(15..18, 32..36, 52..57, 74..79, 96..101)
            else -> listOf(14..17, 29..33, 46..51, 68..73, 88..93, 106..110)
        }
        for (pit in pits) {
            for (c in pit) {
                setTile(c, ROWS - 2, '.')
                setTile(c, ROWS - 1, '.')
            }
        }

        // Intro blocks & Power-up
        setTile(10, 7, '?')
        setTile(13, 7, 'B')
        setTile(14, 7, 'U') // Power-up block
        setTile(15, 7, 'B')
        setTile(14, 4, '?')
        setTile(11, 6, 'O')
        setTile(12, 6, 'O')

        // Pipes & mid-level structures
        addPipe(23, 2)
        addPipe(30, 3)
        spawnEnemy(0, 26, ROWS - 3)
        spawnEnemy(1, 35, ROWS - 3)

        // Coin arcs & elevated bricks
        for (c in 43..48) {
            setTile(c, 7, if (c == 45 || c == 46) '?' else 'B')
            setTile(c, 5, 'O')
        }
        spawnEnemy(0, 46, 6)
        spawnEnemy(0, 50, ROWS - 3)

        // Moving platforms over wider gaps in Stage 2 & 3
        if (st >= 2) {
            spawnMovingPlatform(32.5f, 8.5f, 2.5f, 32.2f, 35.2f, 2.0f)
            spawnMovingPlatform(52.5f, 8.0f, 2.5f, 52.2f, 55.5f, 2.3f)
            spawnMovingPlatform(74.5f, 8.2f, 2.5f, 74.2f, 77.5f, 2.5f)
            spawnEnemy(2, 34, 5)
            spawnEnemy(2, 76, 5)
        }
        if (st == 3) {
            spawnMovingPlatform(88.5f, 7.8f, 2.3f, 88.2f, 91.5f, 2.8f)
            spawnEnemy(2, 90, 4)
        }

        // Mid-stage Checkpoint Flag at column 64
        setTile(64, ROWS - 3, 'C')

        // Second half challenges
        setTile(70, 7, 'U')
        setTile(71, 7, '?')
        setTile(72, 7, 'B')
        for (c in 70..72) setTile(c, 5, 'O')

        addPipe(86, 2)
        spawnEnemy(1, 89, ROWS - 3)
        spawnEnemy(0, 93, ROWS - 3)
        if (st >= 2) spawnEnemy(2, 98, 6)

        // Staircase before goal flagpole
        val stairStart = 113
        for (step in 0..4) {
            for (h in 0..step) {
                setTile(stairStart + step, ROWS - 3 - h, '#')
            }
        }
        spawnEnemy(1, 111, ROWS - 3)

        // Goal flagpole & Castle at column 123..128
        goalX = 123f
        setTile(123, ROWS - 3, 'G')
        setTile(127, ROWS - 3, 'K')
    }

    fun onJumpPressed() {
        jumpBufferTimer = 0.14f
    }

    fun onFirePressed() {
        if (powerLevel < 2 || fireCooldown > 0f) return
        for (i in 0 until maxFireballs) {
            if (!fbActive[i]) {
                fbActive[i] = true
                fbX[i] = px + (if (facing > 0) 0.75f else -0.15f)
                fbY[i] = py + 0.55f
                fbVx[i] = facing * 12.5f
                fbVy[i] = 2.5f
                fireCooldown = 0.28f
                callbacks?.onPlaySfx(SFX_LASER, 1.3f)
                return
            }
        }
    }

    fun update(dt: Float, left: Boolean, right: Boolean, jumpHeld: Boolean, fireHeld: Boolean) {
        animTime += dt
        if (invulnTimer > 0f) invulnTimer = max(0f, invulnTimer - dt)
        if (fireCooldown > 0f) fireCooldown = max(0f, fireCooldown - dt)
        if (landSquash > 0f) landSquash = max(0f, landSquash - dt * 4.5f)
        if (jumpBufferTimer > 0f) jumpBufferTimer = max(0f, jumpBufferTimer - dt)
        if (fireHeld && powerLevel >= 2 && fireCooldown <= 0f) onFirePressed()

        for (i in tileBounce.indices) {
            if (tileBounce[i] > 0f) tileBounce[i] = max(0f, tileBounce[i] - dt * 4f)
        }

        // Update moving platforms
        for (i in 0 until maxPlats) {
            if (!plActive[i]) continue
            val oldX = plX[i]
            plX[i] += plVx[i] * dt
            if (plX[i] < plMinX[i]) { plX[i] = plMinX[i]; plVx[i] = abs(plVx[i]) }
            if (plX[i] > plMaxX[i]) { plX[i] = plMaxX[i]; plVx[i] = -abs(plVx[i]) }
            val deltaX = plX[i] - oldX

            // Carry player if standing on this platform
            val pH = if (powerLevel > 0) 1.38f else 0.92f
            val pFeet = py + pH
            if (onGround && abs(pFeet - plY[i]) < 0.16f && (px + 0.65f) > plX[i] && (px + 0.15f) < (plX[i] + plW[i])) {
                px += deltaX
            }
        }

        // Horizontal movement with momentum
        val maxRun = 7.4f
        val accel = if (onGround) 34f else 22f
        val friction = if (onGround) 28f else 8f
        if (left && !right) {
            facing = -1
            vx = max(-maxRun, vx - accel * dt)
        } else if (right && !left) {
            facing = 1
            vx = min(maxRun, vx + accel * dt)
        } else {
            if (vx > 0f) vx = max(0f, vx - friction * dt)
            else if (vx < 0f) vx = min(0f, vx + friction * dt)
        }

        // Coyote time & Variable Jump
        if (onGround) {
            coyoteTimer = 0.10f
        } else {
            coyoteTimer = max(0f, coyoteTimer - dt)
        }

        if (jumpBufferTimer > 0f && coyoteTimer > 0f) {
            vy = -12.6f
            onGround = false
            coyoteTimer = 0f
            jumpBufferTimer = 0f
            jumpHoldTimer = 0.18f
            callbacks?.onPlaySfx(SFX_JUMP)
            val fx = boardLeft + (px + 0.4f - cameraX) * tilePx
            val fy = boardTop + (py + (if (powerLevel > 0) 1.35f else 0.9f)) * tilePx
            callbacks?.onParticles(fx, fy, 6, 0xFFEFE8DA.toInt(), dp(20f), dp(75f), 3f)
        }

        if (jumpHeld && jumpHoldTimer > 0f && vy < 0f) {
            vy -= 18f * dt
            jumpHoldTimer -= dt
        } else if (!jumpHeld && vy < -4.5f) {
            vy *= 0.55f
            jumpHoldTimer = 0f
        }

        // Gravity
        vy = min(17f, vy + 33f * dt)

        val pW = 0.72f
        val pH = if (powerLevel > 0) 1.38f else 0.92f
        val marginX = 0.14f

        // Move X & resolve tile collisions
        px += vx * dt
        if (px < 0.2f) { px = 0.2f; vx = 0f }
        val leftCol = floorTile(px + marginX)
        val rightCol = floorTile(px + marginX + pW)
        val topRowX = floorTile(py + 0.08f)
        val botRowX = floorTile(py + pH - 0.08f)
        for (r in topRowX..botRowX) {
            if (vx > 0f && isSolidTile(getTile(rightCol, r))) {
                px = rightCol - marginX - pW - 0.001f
                vx = 0f
            } else if (vx < 0f && isSolidTile(getTile(leftCol, r))) {
                px = (leftCol + 1) - marginX + 0.001f
                vx = 0f
            }
        }

        // Move Y & resolve tile + moving platform collisions
        val wasOnGround = onGround
        onGround = false
        py += vy * dt

        val lColY = floorTile(px + marginX + 0.04f)
        val rColY = floorTile(px + marginX + pW - 0.04f)

        if (vy >= 0f) {
            val footRow = floorTile(py + pH)
            var hitFloor = false
            for (c in lColY..rColY) {
                if (isSolidTile(getTile(c, footRow))) {
                    hitFloor = true
                    break
                }
            }
            if (hitFloor) {
                py = footRow - pH - 0.001f
                if (!wasOnGround && vy > 4f) landSquash = 1f
                vy = 0f
                onGround = true
            } else {
                // Check moving platforms
                val feetY = py + pH
                for (i in 0 until maxPlats) {
                    if (!plActive[i]) continue
                    if (feetY >= plY[i] && feetY <= plY[i] + 0.35f &&
                        (px + marginX + pW) > plX[i] && (px + marginX) < (plX[i] + plW[i])
                    ) {
                        py = plY[i] - pH - 0.001f
                        if (!wasOnGround && vy > 4f) landSquash = 1f
                        vy = 0f
                        onGround = true
                        break
                    }
                }
            }
        } else {
            val headRow = floorTile(py)
            for (c in lColY..rColY) {
                val ch = getTile(c, headRow)
                if (isSolidTile(ch)) {
                    py = (headRow + 1) + 0.001f
                    vy = 1.5f
                    handleBlockHeadHit(c, headRow, ch)
                    break
                }
            }
        }

        // Collect floating coins & checkpoint
        val cMin = floorTile(px + marginX)
        val cMax = floorTile(px + marginX + pW)
        val rMin = floorTile(py)
        val rMax = floorTile(py + pH)
        for (r in rMin..rMax) {
            for (c in cMin..cMax) {
                val ch = getTile(c, r)
                if (ch == 'O') {
                    setTile(c, r, '.')
                    collectCoin(c + 0.5f, r + 0.5f)
                } else if (ch == 'C' && !checkpointReached) {
                    checkpointReached = true
                    checkpointX = c.toFloat()
                    checkpointY = (r - 1).toFloat()
                    callbacks?.onPlaySfx(SFX_POWERUP, 1.15f)
                    val cx = boardLeft + (c + 0.5f - cameraX) * tilePx
                    val cy = boardTop + r * tilePx
                    callbacks?.onFloatText(cx, cy, "CHECKPOINT!", 0xFF4CAF7D.toInt())
                }
            }
        }

        // Check Goal Flagpole
        if (px + pW >= goalX) {
            val heightBonus = ((ROWS - 2 - py).coerceIn(1f, 8f) * 100).toInt()
            callbacks?.onAddScore(500 + heightBonus)
            callbacks?.onPlaySfx(SFX_WIN)
            val gx = boardLeft + (goalX - cameraX) * tilePx
            val gy = boardTop + py * tilePx
            callbacks?.onFloatText(gx, gy, "STAGE SELESAI! +${500 + heightBonus}", 0xFFE6C88F.toInt())
            callbacks?.onStageFinished(true, stage + 1)
            return
        }

        // Pit fall check
        if (py > ROWS + 0.5f) {
            loseLife()
            return
        }

        updateItems(dt, pW, pH)
        updateFireballs(dt)
        updateEnemies(dt, pW, pH)

        // Smooth camera follow
        val targetCam = (px - 5.5f).coerceIn(0f, (COLS - 20).toFloat())
        cameraX += (targetCam - cameraX) * min(1f, dt * 8f)
    }

    private fun floorTile(v: Float): Int = kotlin.math.floor(v).toInt()

    private fun handleBlockHeadHit(c: Int, r: Int, ch: Char) {
        val sx = boardLeft + (c + 0.5f - cameraX) * tilePx
        val sy = boardTop + r * tilePx
        when (ch) {
            '?' -> {
                setTile(c, r, 'X')
                tileBounce[r * COLS + c] = 1f
                collectCoin(c + 0.5f, r - 0.5f)
            }
            'U' -> {
                setTile(c, r, 'X')
                tileBounce[r * COLS + c] = 1f
                callbacks?.onPlaySfx(SFX_POWERUP, 1.05f)
                spawnPowerUpItem(c + 0.15f, r - 1f, if (powerLevel == 0) 1 else 2)
            }
            'B' -> {
                if (powerLevel > 0) {
                    setTile(c, r, '.')
                    callbacks?.onAddScore(25)
                    callbacks?.onPlaySfx(SFX_EXPLODE, 1.25f)
                    callbacks?.onShake(0.10f, 3.5f)
                    callbacks?.onParticles(sx, sy, 10, 0xFFD96B43.toInt(), dp(35f), dp(130f), 4f)
                } else {
                    tileBounce[r * COLS + c] = 0.8f
                    callbacks?.onPlaySfx(SFX_BOUNCE, 0.75f)
                }
            }
            else -> callbacks?.onPlaySfx(SFX_BOUNCE, 0.7f)
        }
    }

    private fun collectCoin(tx: Float, ty: Float) {
        coins++
        callbacks?.onAddScore(50)
        callbacks?.onPlaySfx(SFX_COIN, 1.1f)
        val sx = boardLeft + (tx - cameraX) * tilePx
        val sy = boardTop + ty * tilePx
        callbacks?.onParticles(sx, sy, 6, 0xFFE6C88F.toInt(), dp(20f), dp(80f), 3f)
        callbacks?.onFloatText(sx, sy, "+50", 0xFFE6C88F.toInt())
        if (coins >= 50) {
            coins -= 50
            lives++
            callbacks?.onFloatText(sx, sy - dp(18f), "+1 NYAWA!", 0xFF4CAF7D.toInt())
        }
    }

    private fun spawnPowerUpItem(x: Float, y: Float, type: Int) {
        for (i in 0 until maxItems) {
            if (!itActive[i]) {
                itActive[i] = true
                itType[i] = type
                itX[i] = x
                itY[i] = y
                itVx[i] = 2.6f
                itVy[i] = -3.5f
                return
            }
        }
    }

    private fun updateItems(dt: Float, pW: Float, pH: Float) {
        for (i in 0 until maxItems) {
            if (!itActive[i]) continue
            itVy[i] = min(14f, itVy[i] + 26f * dt)
            itX[i] += itVx[i] * dt
            val checkC = floorTile(itX[i] + (if (itVx[i] > 0) 0.7f else 0f))
            if (isSolidTile(getTile(checkC, floorTile(itY[i] + 0.4f)))) {
                itVx[i] = -itVx[i]
            }
            itY[i] += itVy[i] * dt
            val footR = floorTile(itY[i] + 0.75f)
            if (isSolidTile(getTile(floorTile(itX[i] + 0.35f), footR))) {
                itY[i] = footR - 0.75f
                itVy[i] = 0f
            }
            if (itY[i] > ROWS) {
                itActive[i] = false
                continue
            }

            if (abs((itX[i] + 0.35f) - (px + 0.5f)) < 0.72f && abs((itY[i] + 0.35f) - (py + pH * 0.5f)) < (pH * 0.5f + 0.35f)) {
                val t = itType[i]
                itActive[i] = false
                if (t == 2) {
                    powerLevel = 2
                } else if (powerLevel == 0) {
                    powerLevel = 1
                    py -= 0.46f
                }
                callbacks?.onAddScore(200)
                callbacks?.onPlaySfx(SFX_POWERUP, 1.2f)
                val sx = boardLeft + (px + 0.5f - cameraX) * tilePx
                val sy = boardTop + py * tilePx
                callbacks?.onParticles(sx, sy, 14, 0xFFE6C88F.toInt(), dp(30f), dp(120f), 4f)
                callbacks?.onFloatText(sx, sy, if (t == 2) "MODE API!" else "SUPER B-BOX!", 0xFFFF9800.toInt())
            }
        }
    }

    private fun updateFireballs(dt: Float) {
        for (i in 0 until maxFireballs) {
            if (!fbActive[i]) continue
            fbVy[i] = min(15f, fbVy[i] + 32f * dt)
            fbX[i] += fbVx[i] * dt
            fbY[i] += fbVy[i] * dt

            val c = floorTile(fbX[i])
            val r = floorTile(fbY[i])
            if (fbX[i] < cameraX - 1f || fbX[i] > cameraX + 24f || fbY[i] > ROWS) {
                fbActive[i] = false
                continue
            }
            if (isSolidTile(getTile(c, r))) {
                // Bounce off ground, explode on wall
                if (fbVy[i] > 0f && !isSolidTile(getTile(c, r - 1))) {
                    fbY[i] = r - 0.15f
                    fbVy[i] = -7.8f
                } else {
                    fbActive[i] = false
                    val sx = boardLeft + (fbX[i] - cameraX) * tilePx
                    val sy = boardTop + fbY[i] * tilePx
                    callbacks?.onParticles(sx, sy, 6, 0xFFFF9800.toInt(), dp(25f), dp(90f), 3f)
                    continue
                }
            }

            // Check enemy hit
            for (e in 0 until maxEnemies) {
                if (!enActive[e]) continue
                if (abs(fbX[i] - (enX[e] + 0.4f)) < 0.65f && abs(fbY[i] - (enY[e] + 0.4f)) < 0.65f) {
                    fbActive[i] = false
                    enActive[e] = false
                    callbacks?.onAddScore(150)
                    callbacks?.onPlaySfx(SFX_EXPLODE, 1.3f)
                    val sx = boardLeft + (enX[e] + 0.4f - cameraX) * tilePx
                    val sy = boardTop + (enY[e] + 0.4f) * tilePx
                    callbacks?.onParticles(sx, sy, 12, 0xFFFF9800.toInt(), dp(30f), dp(130f), 4f)
                    callbacks?.onFloatText(sx, sy, "+150!", 0xFFFF9800.toInt())
                    break
                }
            }
        }
    }

    private fun updateEnemies(dt: Float, pW: Float, pH: Float) {
        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            if (abs(enX[i] - px) > 22f) continue

            val type = enType[i]
            if (type == 2) {
                // Bat flyer sine wave
                enX[i] += enVx[i] * dt
                enY[i] = enBaseY[i] + sin(animTime * 4f + i) * 1.1f
                val frontC = floorTile(enX[i] + (if (enVx[i] > 0) 0.85f else -0.05f))
                if (isSolidTile(getTile(frontC, floorTile(enY[i] + 0.4f)))) enVx[i] = -enVx[i]
            } else {
                // Ground walker / turtle
                if (type == 1 && enState[i] == 1) {
                    enVx[i] = 0f
                }
                enVy[i] = min(16f, enVy[i] + 30f * dt)
                enX[i] += enVx[i] * dt

                val frontC = floorTile(enX[i] + (if (enVx[i] > 0) 0.82f else 0.02f))
                val bodyR = floorTile(enY[i] + 0.45f)
                val groundBelowFront = isSolidTile(getTile(frontC, floorTile(enY[i] + 1.15f)))
                if (isSolidTile(getTile(frontC, bodyR)) || (enState[i] == 0 && !groundBelowFront)) {
                    enVx[i] = -enVx[i]
                }

                enY[i] += enVy[i] * dt
                val footR = floorTile(enY[i] + 0.85f)
                if (isSolidTile(getTile(floorTile(enX[i] + 0.42f), footR))) {
                    enY[i] = footR - 0.85f
                    enVy[i] = 0f
                }
                if (enY[i] > ROWS) {
                    enActive[i] = false
                    continue
                }

                // Sliding shell defeats other enemies
                if (type == 1 && enState[i] == 2) {
                    for (other in 0 until maxEnemies) {
                        if (other != i && enActive[other] && abs(enX[i] - enX[other]) < 0.75f && abs(enY[i] - enY[other]) < 0.75f) {
                            enActive[other] = false
                            callbacks?.onAddScore(200)
                            callbacks?.onPlaySfx(SFX_STOMP, 1.2f)
                            val sx = boardLeft + (enX[other] + 0.4f - cameraX) * tilePx
                            val sy = boardTop + (enY[other] + 0.4f) * tilePx
                            callbacks?.onParticles(sx, sy, 10, 0xFF4CAF7D.toInt(), dp(30f), dp(120f), 4f)
                            callbacks?.onFloatText(sx, sy, "+200!", 0xFF4CAF7D.toInt())
                        }
                    }
                }
            }

            // Player vs Enemy collision
            val pCenterX = px + 0.5f
            val eCenterX = enX[i] + 0.42f
            val pFeet = py + pH
            if (abs(pCenterX - eCenterX) < 0.68f && pFeet > enY[i] + 0.08f && py < enY[i] + 0.82f) {
                val isStomp = vy > 0f && (pFeet - vy * dt) <= (enY[i] + 0.48f)
                if (isStomp) {
                    vy = -10.5f
                    callbacks?.onPlaySfx(SFX_STOMP)
                    val sx = boardLeft + (eCenterX - cameraX) * tilePx
                    val sy = boardTop + (enY[i] + 0.4f) * tilePx
                    if (type == 1 && enState[i] == 0) {
                        enState[i] = 1
                        enVx[i] = 0f
                        callbacks?.onAddScore(100)
                        callbacks?.onFloatText(sx, sy, "CANGKANG!", 0xFF4CAF7D.toInt())
                    } else if (type == 1 && enState[i] == 1) {
                        enState[i] = 2
                        enVx[i] = if (pCenterX < eCenterX) 11.5f else -11.5f
                        callbacks?.onAddScore(150)
                        callbacks?.onFloatText(sx, sy, "TENDANG!", 0xFF4CAF7D.toInt())
                    } else {
                        enActive[i] = false
                        callbacks?.onAddScore(100)
                        callbacks?.onParticles(sx, sy, 12, 0xFF9C6ADE.toInt(), dp(30f), dp(120f), 4f)
                        callbacks?.onFloatText(sx, sy, "+100!", 0xFFE6C88F.toInt())
                    }
                } else if (type == 1 && enState[i] == 1) {
                    // Kick idle shell from the side safely
                    enState[i] = 2
                    enVx[i] = if (pCenterX < eCenterX) 11.5f else -11.5f
                    callbacks?.onPlaySfx(SFX_STOMP, 1.15f)
                } else if (invulnTimer <= 0f) {
                    if (powerLevel > 0) {
                        powerLevel = 0
                        invulnTimer = 1.6f
                        callbacks?.onShake(0.16f, 5f)
                        callbacks?.onPlaySfx(SFX_EXPLODE, 1.1f)
                        val sx = boardLeft + (pCenterX - cameraX) * tilePx
                        val sy = boardTop + py * tilePx
                        callbacks?.onFloatText(sx, sy, "PERISAI LEPAS!", 0xFFE05252.toInt())
                    } else {
                        loseLife()
                        return
                    }
                }
            }
        }
    }

    private fun loseLife() {
        lives--
        powerLevel = 0
        callbacks?.onShake(0.24f, 7f)
        if (lives <= 0) {
            callbacks?.onPlaySfx(SFX_OVER)
            callbacks?.onStageFinished(false, stage)
        } else {
            callbacks?.onPlaySfx(SFX_EXPLODE, 0.85f)
            respawnAtCheckpoint()
        }
    }

    // =========================================================================
    // RENDERING (CACHED TILE SPRITES + PARALLAX + VECTOR KAPTEN B-BOX)
    // =========================================================================
    fun draw(canvas: Canvas, w: Float, h: Float, deckTop: Float) {
        val topHud = dp(48f)
        boardLeft = 0f
        boardTop = topHud
        boardW = w
        boardH = max(dp(180f), deckTop - topHud)
        tilePx = boardH / ROWS.toFloat()

        ensureCachedSprites(tilePx.toInt().coerceAtLeast(16), stage)

        canvas.save()
        canvas.clipRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH)

        drawParallaxBackground(canvas)

        val firstCol = max(0, floorTile(cameraX) - 1)
        val lastCol = min(COLS - 1, floorTile(cameraX + boardW / tilePx) + 2)

        for (r in 0 until ROWS) {
            for (c in firstCol..lastCol) {
                val ch = grid[r * COLS + c]
                if (ch == '.') continue
                val sx = boardLeft + (c - cameraX) * tilePx
                val bounceOff = sin(tileBounce[r * COLS + c] * Math.PI.toFloat()) * tilePx * 0.25f
                val sy = boardTop + r * tilePx - bounceOff

                when (ch) {
                    '#' -> bmpGround?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                    'B' -> bmpBrick?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                    '?', 'U' -> bmpQuestion?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                    'X' -> bmpUsed?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                    'P' -> bmpPipeTop?.let { canvas.drawBitmap(it, sx - tilePx * 0.06f, sy, pBmp) }
                    'p' -> bmpPipeBody?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                    'O' -> {
                        val bob = sin(animTime * 6f + c) * tilePx * 0.08f
                        bmpCoin?.let { canvas.drawBitmap(it, sx, sy + bob, pBmp) }
                    }
                    'C' -> drawCheckpointFlag(canvas, sx, sy)
                    'G' -> drawGoalFlagAndCastle(canvas, sx, sy)
                }
            }
        }

        // Moving platforms
        for (i in 0 until maxPlats) {
            if (!plActive[i]) continue
            val sx = boardLeft + (plX[i] - cameraX) * tilePx
            val sy = boardTop + plY[i] * tilePx
            val pw = plW[i] * tilePx
            rect.set(sx, sy, sx + pw, sy + tilePx * 0.36f)
            pFill.color = 0xFFE6C88F.toInt()
            canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)
            pStroke.strokeWidth = dp(1.5f)
            pStroke.color = 0xFF7A5636.toInt()
            canvas.drawRoundRect(rect, dp(5f), dp(5f), pStroke)
        }

        // Power-up items
        for (i in 0 until maxItems) {
            if (!itActive[i]) continue
            val sx = boardLeft + (itX[i] + 0.35f - cameraX) * tilePx
            val sy = boardTop + (itY[i] + 0.35f) * tilePx
            val col = if (itType[i] == 2) 0xFFFF9800.toInt() else 0xFFE6C88F.toInt()
            pFill.color = (col and 0x00FFFFFF) or 0x44000000
            canvas.drawCircle(sx, sy, tilePx * 0.44f, pFill)
            pFill.color = col
            canvas.drawCircle(sx, sy, tilePx * 0.28f, pFill)
            pStroke.strokeWidth = dp(1.5f)
            pStroke.color = Color.WHITE
            canvas.drawCircle(sx, sy, tilePx * 0.28f, pStroke)
        }

        // Enemies
        for (i in 0 until maxEnemies) {
            if (!enActive[i]) continue
            val sx = boardLeft + (enX[i] - cameraX) * tilePx
            val sy = boardTop + enY[i] * tilePx
            if (sx < -tilePx * 2 || sx > boardW + tilePx * 2) continue
            when (enType[i]) {
                0 -> bmpSlime?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                1 -> {
                    val bmp = if (enState[i] == 0) bmpTurtle else bmpShell
                    bmp?.let { canvas.drawBitmap(it, sx, sy, pBmp) }
                }
                2 -> {
                    val flap = sin(animTime * 14f + i) * tilePx * 0.08f
                    bmpBat?.let { canvas.drawBitmap(it, sx, sy + flap, pBmp) }
                }
            }
        }

        // Fireballs
        for (i in 0 until maxFireballs) {
            if (!fbActive[i]) continue
            val sx = boardLeft + (fbX[i] - cameraX) * tilePx
            val sy = boardTop + fbY[i] * tilePx
            pFill.color = 0x55FF9800
            canvas.drawCircle(sx, sy, tilePx * 0.26f, pFill)
            pFill.color = 0xFFFF9800.toInt()
            canvas.drawCircle(sx, sy, tilePx * 0.16f, pFill)
        }

        // Player: Kapten B-BOX
        drawKaptenBBox(canvas)

        // Sub-HUD pill (Hearts, Coins, Power Status)
        drawQuestSubHud(canvas)

        canvas.restore()
    }

    private fun drawParallaxBackground(canvas: Canvas) {
        if (skyShader == null || cachedSkyH != boardH || cachedSkyStage != stage) {
            cachedSkyH = boardH
            cachedSkyStage = stage
            val colors = when (stage) {
                1 -> intArrayOf(0xFF4CA6E8.toInt(), 0xFFAEE0FF.toInt())
                2 -> intArrayOf(0xFF101326.toInt(), 0xFF222A4A.toInt())
                else -> intArrayOf(0xFF240B0E.toInt(), 0xFF4A161B.toInt())
            }
            skyShader = LinearGradient(0f, boardTop, 0f, boardTop + boardH, colors[0], colors[1], Shader.TileMode.CLAMP)
        }
        pFill.shader = skyShader
        canvas.drawRect(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH, pFill)
        pFill.shader = null

        val camPx = cameraX * tilePx
        if (stage == 1) {
            // Fluffy white clouds (0.2x parallax)
            pFill.color = 0xDDF7FBFF.toInt()
            val cloudSpan = dp(260f)
            for (i in -1..6) {
                val cx = boardLeft + i * cloudSpan - ((camPx * 0.2f) % cloudSpan)
                val cy = boardTop + boardH * (0.18f + (i and 1) * 0.08f)
                canvas.drawCircle(cx, cy, dp(18f), pFill)
                canvas.drawCircle(cx + dp(16f), cy - dp(5f), dp(22f), pFill)
                canvas.drawCircle(cx + dp(34f), cy, dp(17f), pFill)
            }

            // Rolling green hills (0.45x parallax)
            val hillSpan = dp(320f)
            for (i in -1..5) {
                val hx = boardLeft + i * hillSpan - ((camPx * 0.45f) % hillSpan)
                val base = boardTop + boardH - tilePx * 1.9f
                pFill.color = 0xFF56A85A.toInt()
                canvas.drawCircle(hx + dp(80f), base + dp(35f), dp(115f), pFill)
                pFill.color = 0xFF428E46.toInt()
                canvas.drawCircle(hx + dp(220f), base + dp(45f), dp(95f), pFill)
            }
        } else {
            // Cave crystals or castle pillars in Stage 2 & 3
            val span = dp(240f)
            pFill.color = if (stage == 2) 0x334CC3D9 else 0x33FF9800
            for (i in -1..6) {
                val px0 = boardLeft + i * span - ((camPx * 0.35f) % span)
                path.reset()
                path.moveTo(px0, boardTop)
                path.lineTo(px0 + dp(24f), boardTop + dp(68f))
                path.lineTo(px0 + dp(48f), boardTop)
                path.close()
                canvas.drawPath(path, pFill)
            }
            if (stage == 3) {
                // Lava glow at bottom of pits
                pFill.color = 0xEEFF5722.toInt()
                canvas.drawRect(boardLeft, boardTop + boardH - tilePx * 0.45f, boardLeft + boardW, boardTop + boardH, pFill)
            }
        }
    }

    private fun drawKaptenBBox(canvas: Canvas) {
        if (invulnTimer > 0f && ((animTime * 20f).toInt() and 1) == 1) return

        val isBig = powerLevel > 0
        val isFire = powerLevel >= 2
        val pH = (if (isBig) 1.38f else 0.92f) * tilePx
        val pW = 0.78f * tilePx
        val cx = boardLeft + (px + 0.5f - cameraX) * tilePx
        val bottomY = boardTop + (py + (if (isBig) 1.38f else 0.92f)) * tilePx

        val squashY = 1f - landSquash * 0.16f
        val squashX = 1f + landSquash * 0.14f

        canvas.save()
        canvas.translate(cx, bottomY)
        canvas.scale(facing * squashX, squashY)

        val runCycle = if (onGround && abs(vx) > 0.3f) sin(animTime * 18f) else 0f
        val jumpPose = if (!onGround) 1f else 0f

        // Ground shadow
        pFill.color = 0x44000000
        rect.set(-pW * 0.42f, -dp(3f), pW * 0.42f, dp(3f))
        canvas.drawOval(rect, pFill)

        // Flowing golden scarf behind neck
        val neckY = -pH * 0.48f
        val scarfWave = sin(animTime * 14f) * dp(4f) + abs(vx) * dp(1.2f)
        path.reset()
        path.moveTo(-pW * 0.12f, neckY)
        path.quadTo(-pW * 0.65f, neckY - dp(4f) + scarfWave * 0.4f, -pW * 0.95f - scarfWave * 0.4f, neckY + dp(6f) + scarfWave)
        path.lineTo(-pW * 0.85f - scarfWave * 0.4f, neckY + dp(13f) + scarfWave)
        path.quadTo(-pW * 0.50f, neckY + dp(5f), -pW * 0.10f, neckY + dp(5f))
        path.close()
        pFill.color = 0xFFE6C88F.toInt()
        canvas.drawPath(path, pFill)

        // Boots (animated stride)
        val bootW = pW * 0.28f
        val bootH = pH * 0.18f
        val leftBootX = -pW * 0.22f - runCycle * pW * 0.16f
        val rightBootX = pW * 0.06f + runCycle * pW * 0.16f
        val rightBootY = -bootH - jumpPose * pH * 0.08f
        pFill.color = 0xFF9C6938.toInt()
        rect.set(leftBootX, -bootH, leftBootX + bootW, 0f)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)
        rect.set(rightBootX, rightBootY, rightBootX + bootW, rightBootY + bootH)
        canvas.drawRoundRect(rect, dp(3f), dp(3f), pFill)

        // Jacket torso (Maroon or Ivory in Fire mode)
        val torsoTop = -pH * 0.52f
        val torsoBot = -bootH * 0.85f
        rect.set(-pW * 0.28f, torsoTop, pW * 0.28f, torsoBot)
        pFill.color = if (isFire) 0xFFF5F0E6.toInt() else 0xFF9E2A2B.toInt()
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)

        // Gold belt & buckle
        pFill.color = 0xFF4A2E1B.toInt()
        canvas.drawRect(-pW * 0.28f, torsoBot - pH * 0.09f, pW * 0.28f, torsoBot - pH * 0.03f, pFill)
        pFill.color = 0xFFE6C88F.toInt()
        canvas.drawRect(-pW * 0.07f, torsoBot - pH * 0.10f, pW * 0.07f, torsoBot - pH * 0.02f, pFill)

        // Scarf collar
        rect.set(-pW * 0.30f, torsoTop - dp(2f), pW * 0.30f, torsoTop + pH * 0.08f)
        pFill.color = 0xFFE6C88F.toInt()
        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)

        // White gloved hands
        val handR = pW * 0.13f
        val frontHandY = if (jumpPose > 0f) torsoTop - pH * 0.12f else torsoTop + pH * 0.14f - runCycle * pH * 0.08f
        pFill.color = if (isFire) 0xFFFF9800.toInt() else Color.WHITE
        canvas.drawCircle(pW * 0.32f, frontHandY, handR, pFill)
        canvas.drawCircle(-pW * 0.30f, torsoTop + pH * 0.16f + runCycle * pH * 0.08f, handR * 0.9f, pFill)

        // Chibi Head
        val headR = pW * 0.36f
        val headCy = torsoTop - headR * 0.78f
        pFill.color = 0xFFFFDBB8.toInt()
        canvas.drawCircle(0f, headCy, headR, pFill)

        // Hair back
        pFill.color = 0xFF3E2723.toInt()
        rect.set(-headR * 1.02f, headCy - headR * 0.4f, -headR * 0.45f, headCy + headR * 0.55f)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), pFill)

        // Eye & smile
        pFill.color = 0xFF1A1614.toInt()
        canvas.drawCircle(headR * 0.42f, headCy + headR * 0.04f, headR * 0.14f, pFill)
        pFill.color = Color.WHITE
        canvas.drawCircle(headR * 0.46f, headCy - headR * 0.01f, headR * 0.05f, pFill)

        // Explorer Hat (Gold with 'B' badge)
        val hatY = headCy - headR * 0.55f
        pFill.color = if (isFire) 0xFFE05252.toInt() else 0xFFD4A857.toInt()
        rect.set(-headR * 1.38f, hatY, headR * 1.42f, hatY + headR * 0.28f)
        canvas.drawRoundRect(rect, dp(5f), dp(5f), pFill)

        // Hat crown
        rect.set(-headR * 0.88f, hatY - headR * 0.65f, headR * 0.88f, hatY + headR * 0.12f)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), pFill)

        // 'B' emblem on hat
        pFill.color = 0xFFFFF3D1.toInt()
        canvas.drawCircle(headR * 0.25f, hatY - headR * 0.26f, headR * 0.24f, pFill)
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.color = 0xFF4A2E1B.toInt()
        pText.textSize = headR * 0.36f
        canvas.drawText("B", headR * 0.25f, hatY - headR * 0.14f, pText)

        canvas.restore()
    }

    private fun drawCheckpointFlag(canvas: Canvas, sx: Float, sy: Float) {
        val cx = sx + tilePx * 0.5f
        pFill.color = 0xFFEFE8DA.toInt()
        canvas.drawRect(cx - dp(2f), sy - tilePx * 1.2f, cx + dp(2f), sy + tilePx, pFill)
        path.reset()
        path.moveTo(cx + dp(2f), sy - tilePx * 1.15f)
        path.lineTo(cx + tilePx * 0.65f, sy - tilePx * 0.85f)
        path.lineTo(cx + dp(2f), sy - tilePx * 0.55f)
        path.close()
        pFill.color = if (checkpointReached) 0xFF4CAF7D.toInt() else 0xFFE05252.toInt()
        canvas.drawPath(path, pFill)
    }

    private fun drawGoalFlagAndCastle(canvas: Canvas, sx: Float, sy: Float) {
        // Tall flagpole
        val poleX = sx + tilePx * 0.4f
        pFill.color = Color.WHITE
        canvas.drawRect(poleX - dp(2.5f), sy - tilePx * 5.5f, poleX + dp(2.5f), sy + tilePx, pFill)
        pFill.color = 0xFFE6C88F.toInt()
        canvas.drawCircle(poleX, sy - tilePx * 5.6f, dp(5f), pFill)

        // Golden flag
        path.reset()
        path.moveTo(poleX + dp(2.5f), sy - tilePx * 5.3f)
        path.lineTo(poleX + tilePx * 1.1f, sy - tilePx * 4.8f)
        path.lineTo(poleX + dp(2.5f), sy - tilePx * 4.3f)
        path.close()
        pFill.color = 0xFFE6C88F.toInt()
        canvas.drawPath(path, pFill)

        // Mini Castle behind flagpole
        val cx = sx + tilePx * 2.4f
        val cw = tilePx * 2.6f
        val ch = tilePx * 2.8f
        rect.set(cx, sy + tilePx - ch, cx + cw, sy + tilePx)
        pFill.color = 0xFF8C4A32.toInt()
        canvas.drawRect(rect, pFill)
        // Castle gate
        pFill.color = 0xFF141210.toInt()
        rect.set(cx + cw * 0.34f, sy + tilePx - ch * 0.45f, cx + cw * 0.66f, sy + tilePx)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), pFill)
    }

    private fun drawQuestSubHud(canvas: Canvas) {
        val pillY = boardTop + dp(8f)
        val pillH = dp(26f)

        // Hearts pill (left)
        rect.set(dp(12f), pillY, dp(104f), pillY + pillH)
        pFill.color = 0xAA121216.toInt()
        canvas.drawRoundRect(rect, dp(13f), dp(13f), pFill)
        pText.typeface = Typeface.DEFAULT_BOLD
        pText.textAlign = Paint.Align.CENTER
        pText.textSize = dp(13f)
        pText.color = 0xFFE05252.toInt()
        val heartsStr = "♥".repeat(lives.coerceIn(0, 5))
        canvas.drawText(heartsStr, rect.centerX(), rect.centerY() + dp(4.5f), pText)

        // Coins & Power pill (right)
        rect.set(boardW - dp(176f), pillY, boardW - dp(12f), pillY + pillH)
        pFill.color = 0xAA121216.toInt()
        canvas.drawRoundRect(rect, dp(13f), dp(13f), pFill)
        pText.textSize = dp(11.5f)
        pText.color = 0xFFE6C88F.toInt()
        val pwrTag = when (powerLevel) { 2 -> "API"; 1 -> "SUPER"; else -> "NORMAL" }
        canvas.drawText("KOIN: $coins  ·  $pwrTag", rect.centerX(), rect.centerY() + dp(4f), pText)
    }

    private fun ensureCachedSprites(sz: Int, st: Int) {
        if (sz == cachedSpriteSize && st == cachedSpriteStage && bmpGround != null) return
        cachedSpriteSize = sz
        cachedSpriteStage = st
        val f = sz.toFloat()

        // 1. Ground block (Brown stone + vibrant grass top)
        bmpGround = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = if (st == 1) 0xFF6B472A.toInt() else if (st == 2) 0xFF2D334D.toInt() else 0xFF3D2222.toInt()
            c.drawRect(0f, 0f, f, f, pFill)
            pStroke.strokeWidth = max(1.5f, f * 0.04f)
            pStroke.color = 0x44000000
            c.drawRect(f * 0.08f, f * 0.34f, f * 0.52f, f * 0.66f, pStroke)
            c.drawRect(f * 0.52f, f * 0.38f, f * 0.94f, f * 0.72f, pStroke)
            c.drawRect(f * 0.22f, f * 0.68f, f * 0.78f, f * 0.96f, pStroke)
            // Top grass / crystal / lava rim
            pFill.color = if (st == 1) 0xFF5EC243.toInt() else if (st == 2) 0xFF4CC3D9.toInt() else 0xFFFF6D00.toInt()
            c.drawRect(0f, 0f, f, f * 0.28f, pFill)
            pFill.color = 0x33FFFFFF
            c.drawRect(0f, 0f, f, f * 0.09f, pFill)
        }

        // 2. Red Brick block
        bmpBrick = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFFC85A32.toInt()
            rect.set(1f, 1f, f - 1f, f - 1f)
            c.drawRoundRect(rect, f * 0.1f, f * 0.1f, pFill)
            pStroke.strokeWidth = max(1.5f, f * 0.05f)
            pStroke.color = 0xFF5A2210.toInt()
            c.drawLine(0f, f * 0.5f, f, f * 0.5f, pStroke)
            c.drawLine(f * 0.5f, 0f, f * 0.5f, f * 0.5f, pStroke)
            c.drawLine(f * 0.25f, f * 0.5f, f * 0.25f, f, pStroke)
            c.drawLine(f * 0.75f, f * 0.5f, f * 0.75f, f, pStroke)
        }

        // 3. Mystery '?' block
        bmpQuestion = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFFF5B82E.toInt()
            rect.set(1f, 1f, f - 1f, f - 1f)
            c.drawRoundRect(rect, f * 0.14f, f * 0.14f, pFill)
            pStroke.strokeWidth = max(1.5f, f * 0.05f)
            pStroke.color = 0xFF8C5808.toInt()
            c.drawRoundRect(rect, f * 0.14f, f * 0.14f, pStroke)
            pText.typeface = Typeface.DEFAULT_BOLD
            pText.textAlign = Paint.Align.CENTER
            pText.color = Color.WHITE
            pText.textSize = f * 0.64f
            c.drawText("?", f * 0.5f, f * 0.73f, pText)
        }

        // 4. Used block
        bmpUsed = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF4A443D.toInt()
            rect.set(1f, 1f, f - 1f, f - 1f)
            c.drawRoundRect(rect, f * 0.12f, f * 0.12f, pFill)
        }

        // 5. Green Pipe Top & Body
        val topW = (sz * 1.12f).toInt()
        bmpPipeTop = Bitmap.createBitmap(topW, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF2EA843.toInt()
            rect.set(1f, 1f, topW - 1f, f * 0.45f)
            c.drawRoundRect(rect, f * 0.1f, f * 0.1f, pFill)
            rect.set(topW * 0.07f, f * 0.42f, topW * 0.93f, f)
            c.drawRect(rect, pFill)
            pFill.color = 0x44FFFFFF
            c.drawRect(topW * 0.18f, 2f, topW * 0.32f, f, pFill)
        }
        bmpPipeBody = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF2EA843.toInt()
            c.drawRect(f * 0.04f, 0f, f * 0.96f, f, pFill)
            pFill.color = 0x44FFFFFF
            c.drawRect(f * 0.16f, 0f, f * 0.30f, f, pFill)
        }

        // 6. Gold Coin
        bmpCoin = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFFF5C542.toInt()
            rect.set(f * 0.24f, f * 0.12f, f * 0.76f, f * 0.88f)
            c.drawOval(rect, pFill)
            pStroke.strokeWidth = max(1.5f, f * 0.05f)
            pStroke.color = 0xFF9E6E10.toInt()
            c.drawOval(rect, pStroke)
        }

        // 7. Purple Slime Enemy
        bmpSlime = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF9C6ADE.toInt()
            rect.set(f * 0.08f, f * 0.22f, f * 0.92f, f * 0.96f)
            c.drawRoundRect(rect, f * 0.42f, f * 0.42f, pFill)
            pFill.color = 0xFFFFEB3B.toInt()
            c.drawCircle(f * 0.34f, f * 0.56f, f * 0.10f, pFill)
            c.drawCircle(f * 0.66f, f * 0.56f, f * 0.10f, pFill)
            pFill.color = Color.BLACK
            c.drawCircle(f * 0.34f, f * 0.56f, f * 0.05f, pFill)
            c.drawCircle(f * 0.66f, f * 0.56f, f * 0.05f, pFill)
        }

        // 8. Green Shell Turtle
        bmpTurtle = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF4CAF7D.toInt()
            rect.set(f * 0.18f, f * 0.20f, f * 0.92f, f * 0.82f)
            c.drawRoundRect(rect, f * 0.35f, f * 0.35f, pFill)
            pStroke.strokeWidth = max(1.5f, f * 0.05f)
            pStroke.color = 0xFFE6C88F.toInt()
            c.drawLine(f * 0.18f, f * 0.72f, f * 0.92f, f * 0.72f, pStroke)
            pFill.color = 0xFF81C784.toInt()
            c.drawCircle(f * 0.18f, f * 0.56f, f * 0.14f, pFill)
            c.drawCircle(f * 0.34f, f * 0.86f, f * 0.09f, pFill)
            c.drawCircle(f * 0.74f, f * 0.86f, f * 0.09f, pFill)
        }

        // 9. Turtle Shell
        bmpShell = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF4CAF7D.toInt()
            rect.set(f * 0.14f, f * 0.34f, f * 0.86f, f * 0.92f)
            c.drawRoundRect(rect, f * 0.32f, f * 0.32f, pFill)
            pStroke.strokeWidth = max(1.5f, f * 0.05f)
            pStroke.color = 0xFFE6C88F.toInt()
            c.drawRoundRect(rect, f * 0.32f, f * 0.32f, pStroke)
        }

        // 10. Bat Flyer
        bmpBat = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            pFill.color = 0xFF2B2438.toInt()
            path.reset()
            path.moveTo(f * 0.04f, f * 0.32f)
            path.lineTo(f * 0.50f, f * 0.50f)
            path.lineTo(f * 0.96f, f * 0.32f)
            path.lineTo(f * 0.75f, f * 0.64f)
            path.lineTo(f * 0.25f, f * 0.64f)
            path.close()
            c.drawPath(path, pFill)
            c.drawCircle(f * 0.50f, f * 0.52f, f * 0.22f, pFill)
            pFill.color = 0xFFE05252.toInt()
            c.drawCircle(f * 0.42f, f * 0.50f, f * 0.06f, pFill)
            c.drawCircle(f * 0.58f, f * 0.50f, f * 0.06f, pFill)
        }
    }
}
