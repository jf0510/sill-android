// SPDX-License-Identifier: Apache-2.0
// Adapted from Sill by Noah Saffer; see NOTICE for provenance and modifications.

package io.github.jf0510.sill

import android.content.Context
import android.graphics.*
import android.view.*
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.*

/**
 * UIKit's direct touch: tap clicks, hold right-clicks, finger pan scrolls, pinch zooms. Portrait
 * trackpad uses relative pointer movement and two-finger scrolling. Input is emitted through the
 * same protocol seam future pen and physical keys will use.
 */
class TouchSurface(context: Context, val client: SillClient, val relative: Boolean = false) :
    FrameLayout(context) {
    private val videoSurface = if (!relative) SurfaceView(context) else null
    private var output: Surface? = null
    private var videoW = 16
    private var videoH = 10
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var px = .5
    private var py = .5
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var lastTap = 0L
    private var moved = false
    private var scrolling = false
    private var longPressed = false
    private var multi = false
    private var pinched = false
    private var dragging = false
    private var maxPointers = 1
    private var chordX = 0f
    private var chordY = 0f
    private var chordSpan = 0f
    private var chordFinalSpan = 0f
    private var chordDx = 0f
    private var chordDy = 0f
    private var penDown = false
    private var mouseButtons = 0
    private var penButton = "left"
    private var velocity: VelocityTracker? = null
    private var momentum: Choreographer.FrameCallback? = null
    private val choreographer = Choreographer.getInstance()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var cursor: Pair<Double, Double>? = null
    private val pointerObserver: (Pair<Double, Double>?) -> Unit = {
        cursor = it
        invalidate()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaler =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    if (relative) return false
                    pinched = true
                    endScroll()
                    return true
                }

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val previous = zoom
                    zoom = (zoom * d.scaleFactor).coerceIn(1f, 5f)
                    val ratio = zoom / previous
                    panX = d.focusX - width / 2f - (d.focusX - width / 2f - panX) * ratio
                    panY = d.focusY - height / 2f - (d.focusY - height / 2f - panY) * ratio
                    clampPan()
                    transform()
                    return true
                }
            },
        )
    private val hold = Runnable {
        if (!moved && !multi && !pinched) {
            if (relative && client.holdToDrag) {
                dragging = true
                pointer("leftDown", downX, downY)
            } else {
                longPressed = true
                click("right", downX, downY)
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        setWillNotDraw(false)
        isFocusableInTouchMode = true
        isFocusable = true
        contentDescription =
            if (relative) "Trackpad. Slide to move, tap to click, two fingers to scroll."
            else "Mac picture. Tap to click, hold to right-click, swipe to scroll, pinch to zoom."
        setBackgroundColor(if (relative) 0xff101215.toInt() else 0xff1f2126.toInt())
        videoSurface?.let { view ->
            addView(view, LayoutParams(-1, -1))
            view.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        output = holder.surface
                        client.video.attach(output)
                        transform()
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        w: Int,
                        h: Int,
                    ) {
                        transform()
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        client.video.attach(null)
                        // The holder owns this Surface; do not release it ourselves.
                        output = null
                    }
                }
            )
        }
    }

    fun dimensions(w: Int, h: Int) {
        if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
            videoW = w
            videoH = h
            videoSurface?.holder?.setFixedSize(w, h)
            zoom = 1f
            panX = 0f
            panY = 0f
            transform()
        }
    }

    private fun rect(): RectF {
        val s = min(width.toFloat() / videoW, height.toFloat() / videoH)
        val w = videoW * s
        val h = videoH * s
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun relativeVerticalSpan() =
        if (resources.configuration.smallestScreenWidthDp < 600) width / 1.6f else height.toFloat()

    private fun position(x: Float, y: Float, clamp: Boolean = true): Pair<Double, Double>? {
        if (relative) return px to py
        val r = rect()
        val ux = (x - width / 2f - panX) / zoom + width / 2f
        val uy = (y - height / 2f - panY) / zoom + height / 2f
        if (r.width() <= 0 || r.height() <= 0 || (!clamp && !r.contains(ux, uy))) return null
        return ((ux - r.left) / r.width()).toDouble().coerceIn(0.0, 1.0) to
            ((uy - r.top) / r.height()).toDouble().coerceIn(0.0, 1.0)
    }

    private fun clampPan() {
        val r = rect()
        val maxX = max(0f, (r.width() * zoom - width) / 2)
        val maxY = max(0f, (r.height() * zoom - height) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun transform() {
        if (width == 0 || height == 0) return
        val r = rect()
        val targetW = width
        val targetH = height
        videoSurface?.apply {
            pivotX = targetW / 2f
            pivotY = targetH / 2f
            scaleX = r.width() / targetW * zoom
            scaleY = r.height() / targetH * zoom
            translationX = panX
            translationY = panY
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
        transform()
        if (!relative)
            client.viewport(
                w / resources.displayMetrics.density.toDouble(),
                h / resources.displayMetrics.density.toDouble(),
            )
    }

    private fun pointer(action: String, x: Float = lastX, y: Float = lastY) {
        position(x, y)?.let { (a, b) ->
            client.showPointer(if (relative) a to b else null)
            client.input(Wire.pointer(action, a, b))
        }
    }

    private fun click(button: String, x: Float, y: Float) {
        if (position(x, y, false) == null) return
        pointer("${button}Down", x, y)
        pointer("${button}Up", x, y)
    }

    private fun endScroll() {
        if (scrolling) {
            client.input(Wire.phase("ended", px, py))
            scrolling = false
        }
    }

    private fun scroll(dx: Float, dy: Float) {
        val r = rect()
        val p = position(downX, downY) ?: return
        px = p.first
        py = p.second
        if (!scrolling) {
            client.input(Wire.phase("began", px, py))
            scrolling = true
        }
        val sign = if (client.reverseScroll) -1 else 1
        client.input(
            Wire.scroll(
                px,
                py,
                dx / max(1f, if (relative) width.toFloat() else r.width()) * sign.toDouble(),
                dy / max(1f, if (relative) relativeVerticalSpan() else r.height()) * sign.toDouble(),
            )
        )
    }

    override fun onInterceptTouchEvent(e: MotionEvent) = true

    override fun onTouchEvent(e: MotionEvent): Boolean {
        client.used()
        if (client.state.value.inPip) return true
        val tool = e.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopMomentum()
                    penButton =
                        if (e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)) "right"
                        else "left"
                    penDown = true
                    pointer("${penButton}Down", e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> if (penDown) pointer("move", e.x, e.y)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    if (penDown) {
                        pointer("${penButton}Up", e.x, e.y)
                        penDown = false
                    }
            }
            return true
        }
        if (tool == MotionEvent.TOOL_TYPE_MOUSE) {
            mouse(e)
            return true
        }
        if (!relative && e.pointerCount < 3) scaler.onTouchEvent(e)
        val x = (0 until e.pointerCount).sumOf { e.getX(it).toDouble() }.toFloat() / e.pointerCount
        val y = (0 until e.pointerCount).sumOf { e.getY(it).toDouble() }.toFloat() / e.pointerCount
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMomentum()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x
                downY = y
                lastX = x
                lastY = y
                downTime = e.eventTime
                moved = false
                longPressed = false
                multi = false
                pinched = false
                maxPointers = 1
                chordSpan = 0f
                chordDx = 0f
                chordDy = 0f
                dragging = relative && e.eventTime - lastTap < 300
                if (dragging) pointer("leftDown", x, y) else postDelayed(hold, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                maxPointers = max(maxPointers, e.pointerCount)
                if (e.pointerCount >= 3) {
                    endScroll()
                    chordX = x
                    chordY = y
                    chordSpan = span(e, x, y)
                    chordFinalSpan = chordSpan
                }
                removeCallbacks(hold)
                if (dragging) {
                    pointer("leftUp")
                    dragging = false
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = x - lastX
                val dy = y - lastY
                if (hypot(x - downX, y - downY) > slop) {
                    moved = true
                    removeCallbacks(hold)
                }
                if (maxPointers >= 3) {
                    if (e.pointerCount >= 3) {
                        chordDx = x - chordX
                        chordDy = y - chordY
                        chordFinalSpan = span(e, x, y)
                    }
                } else if (moved && !longPressed && !scaler.isInProgress) {
                    when {
                        relative && e.pointerCount == 1 -> {
                            px = (px + dx / width).coerceIn(0.0, 1.0)
                            py = (py + dy / max(1f, relativeVerticalSpan())).coerceIn(0.0, 1.0)
                            pointer("move")
                            invalidate()
                        }
                        !relative && zoom > 1.01f && e.pointerCount == 2 -> {
                            panX += dx
                            panY += dy
                            clampPan()
                            transform()
                        }
                        !pinched && e.pointerCount <= 2 -> scroll(dx, dy)
                    }
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(hold)
                val kept = (0 until e.pointerCount).filter { it != e.actionIndex }
                lastX = kept.sumOf { e.getX(it).toDouble() }.toFloat() / kept.size
                lastY = kept.sumOf { e.getY(it).toDouble() }.toFloat() / kept.size
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (maxPointers >= 3 && e.actionMasked == MotionEvent.ACTION_UP) {
                    val threshold = 45 * resources.displayMetrics.density
                    val name =
                        when {
                            chordSpan > 0 && chordFinalSpan / chordSpan < .72f -> "pinch"
                            chordSpan > 0 && chordFinalSpan / chordSpan > 1.35f -> "spread"
                            max(abs(chordDx), abs(chordDy)) < threshold -> null
                            abs(chordDx) > abs(chordDy) ->
                                if (chordDx > 0) "swipeRight" else "swipeLeft"
                            else -> if (chordDy > 0) "swipeDown" else "swipeUp"
                        }
                    name?.let { client.gesture(it, maxPointers) }
                } else if (dragging) {
                    pointer("leftUp")
                    dragging = false
                } else if (
                    e.actionMasked == MotionEvent.ACTION_UP &&
                        maxPointers < 3 &&
                        !moved &&
                        !longPressed &&
                        !pinched
                ) {
                    click(if (maxPointers == 2) "right" else "left", x, y)
                    lastTap = e.eventTime
                    performClick()
                }
                val wasScrolling = scrolling
                endScroll()
                if (wasScrolling && e.actionMasked == MotionEvent.ACTION_UP && !pinched)
                    startMomentum()
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun span(e: MotionEvent, x: Float, y: Float): Float =
        (0 until e.pointerCount)
            .sumOf { hypot(e.getX(it) - x, e.getY(it) - y).toDouble() }
            .toFloat() / e.pointerCount

    private fun mouse(e: MotionEvent) {
        val buttons =
            if (
                e.actionMasked == MotionEvent.ACTION_CANCEL ||
                    e.actionMasked == MotionEvent.ACTION_UP
            )
                0
            else e.buttonState
        for ((mask, name) in
            listOf(MotionEvent.BUTTON_PRIMARY to "left", MotionEvent.BUTTON_SECONDARY to "right")) {
            if ((buttons and mask) != (mouseButtons and mask))
                pointer(name + if (buttons and mask != 0) "Down" else "Up", e.x, e.y)
        }
        mouseButtons = buttons
        if (
            e.actionMasked == MotionEvent.ACTION_MOVE ||
                e.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        )
            pointer("move", e.x, e.y)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
                client.used()
                val p = position(e.x, e.y) ?: return true
                client.input(
                    Wire.scroll(
                        p.first,
                        p.second,
                        -e.getAxisValue(MotionEvent.AXIS_HSCROLL) * .04,
                        -e.getAxisValue(MotionEvent.AXIS_VSCROLL) * .04,
                    )
                )
                return true
            }
            if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
                mouse(e)
                return true
            }
            // Hover while the pen is down must not jump the stroke back to its previous hover.
            if (!penDown && e.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                pointer("move", e.x, e.y)
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private fun startMomentum() {
        val v = velocity ?: return
        v.computeCurrentVelocity(1000)
        var vx = v.xVelocity.coerceIn(-5000f, 5000f)
        var vy = v.yVelocity.coerceIn(-5000f, 5000f)
        if (hypot(vx, vy) < 100) return
        val r = rect()
        val w = max(1f, if (relative) width.toFloat() else r.width())
        val h = max(1f, if (relative) relativeVerticalSpan() else r.height())
        val sign = if (client.reverseScroll) -1 else 1
        client.input(Wire.phase("momentumBegan", px, py))
        var lastFrame = System.nanoTime()
        val decayRate = -ln(.9) / .016
        momentum =
            object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (momentum !== this) return
                        val dt = ((frameTimeNanos - lastFrame) / 1e9).coerceIn(0.0, .05)
                        lastFrame = frameTimeNanos
                        val decay = exp(-decayRate * dt)
                        val travel = (1 - decay) / decayRate
                        client.input(
                            Wire.scroll(px, py, vx * travel / w * sign, vy * travel / h * sign)
                        )
                        vx = (vx * decay).toFloat()
                        vy = (vy * decay).toFloat()
                        if (hypot(vx, vy) < 20) {
                            stopMomentum()
                            return
                        }
                        choreographer.postFrameCallback(this)
                    }
                }
                .also { choreographer.postFrameCallback(it) }
    }

    private fun stopMomentum() {
        momentum?.let {
            choreographer.removeFrameCallback(it)
            client.input(Wire.phase("momentumEnded", px, py))
        }
        momentum = null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!relative) {
            client.onPointer = pointerObserver
            // Advertise panel capability as iOS does; the host maxFPS setting remains the cap.
            display?.let { d ->
                val mode = d.mode
                val rate =
                    d.supportedModes
                        .filter {
                            it.physicalWidth == mode.physicalWidth &&
                                it.physicalHeight == mode.physicalHeight
                        }
                        .maxOfOrNull { it.refreshRate } ?: d.refreshRate
                client.displayRate(rate)
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (client.onPointer === pointerObserver) client.onPointer = null
        removeCallbacks(hold)
        stopMomentum()
        endScroll()
        if (dragging || penDown) pointer("leftUp")
        if (mouseButtons and MotionEvent.BUTTON_PRIMARY != 0) pointer("leftUp")
        if (mouseButtons and MotionEvent.BUTTON_SECONDARY != 0) pointer("rightUp")
        mouseButtons = 0
        penDown = false
        dragging = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (relative && resources.configuration.smallestScreenWidthDp >= 600) {
            paint.color = 0xff555a64.toInt()
            paint.textSize = 13 * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.CENTER
            c.drawText(
                "Slide to move · Tap to click · Two fingers to scroll",
                width / 2f,
                height - 24 * resources.displayMetrics.density,
                paint,
            )
        }
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (relative || client.state.value.inPip) return
        val p = cursor ?: return
        val r = rect()
        val x = ((r.left + p.first * r.width() - width / 2) * zoom + width / 2 + panX).toFloat()
        val y = ((r.top + p.second * r.height() - height / 2) * zoom + height / 2 + panY).toFloat()
        val d = resources.displayMetrics.density
        val state = client.state.value
        state.cursorImage?.let { bitmap ->
            paint.style = Paint.Style.FILL
            c.drawBitmap(
                bitmap,
                null,
                RectF(
                    x - state.cursorHotX * d,
                    y - state.cursorHotY * d,
                    x + (state.cursorWidth - state.cursorHotX) * d,
                    y + (state.cursorHeight - state.cursorHotY) * d,
                ),
                paint,
            )
            return
        }
        val path =
            Path().apply {
                moveTo(x, y)
                lineTo(x, y + 16.8f * d)
                lineTo(x + 4 * d, y + 12.8f * d)
                lineTo(x + 6.8f * d, y + 19.2f * d)
                lineTo(x + 9.6f * d, y + 18 * d)
                lineTo(x + 6.9f * d, y + 12 * d)
                lineTo(x + 12 * d, y + 12 * d)
                close()
            }
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        c.drawPath(path, paint)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    fun keyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            // Composition stays entirely local. Only committed text reaches the Mac.
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    if (
                        client.modifierKeys != 0L &&
                            it.length == 1 &&
                            it[0].lowercaseChar() in 'a'..'z'
                    ) {
                        client.key(4 + (it[0].lowercaseChar() - 'a'), client.modifierKeys)
                    } else client.input(Wire.text(it.toString()))
                }
                editable?.clear()
                return true
            }

            override fun finishComposingText(): Boolean {
                val text = editable?.toString().orEmpty()
                if (text.isNotEmpty()) client.input(Wire.text(text))
                editable?.clear()
                return super.finishComposingText()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (editable?.isNotEmpty() == true)
                    return super.deleteSurroundingText(beforeLength, afterLength)
                repeat(beforeLength.coerceAtMost(100)) { client.key(42) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN)
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> client.key(42)
                        KeyEvent.KEYCODE_ENTER -> client.key(40)
                        else -> return super.sendKeyEvent(event)
                    }
                return true
            }
        }
    }
}
