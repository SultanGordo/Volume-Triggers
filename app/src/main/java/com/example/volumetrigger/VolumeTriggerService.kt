package com.example.volumetrigger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.random.Random

class VolumeTriggerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var btnVolUpTarget: TextView
    private lateinit var btnVolDownTarget: TextView
    private lateinit var hudContainer: LinearLayout
    private lateinit var btnMasterToggle: TextView
    private lateinit var btnUpMode: TextView
    private lateinit var btnDownMode: TextView
    private lateinit var btnCollapse: TextView

    private lateinit var paramsVolUp: WindowManager.LayoutParams
    private lateinit var paramsVolDown: WindowManager.LayoutParams
    private lateinit var paramsHud: WindowManager.LayoutParams

    private var isTriggerActive = false
    private var isHudCollapsed = false
    private var isVolUpHoldMode = true
    private var isVolDownHoldMode = false

    private var isHoldingVolUp = false
    private var isHoldingVolDown = false
    private var isGestureLoopRunning = false
    private var activeStrokeUp: GestureDescription.StrokeDescription? = null
    private var activeStrokeDown: GestureDescription.StrokeDescription? = null
    private var holdXUp = 0f
    private var holdYUp = 0f
    private var holdXDown = 0f
    private var holdYDown = 0f

    private val targetSizePx = 130
    private val sliceDurationMs = 55L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isTriggerActive) {
                setTriggerActiveState(false)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("VolumeTriggerPrefs", Context.MODE_PRIVATE)

        isVolUpHoldMode = prefs.getBoolean("up_hold_mode", true)
        isVolDownHoldMode = prefs.getBoolean("down_hold_mode", false)

        btnVolUpTarget = createTargetView("VOL +", Color.parseColor("#CCFF3B30"))
        btnVolDownTarget = createTargetView("VOL -", Color.parseColor("#CC007AFF"))
        buildHudBar()

        val prefix = getOrientationPrefix()
        paramsVolUp = createOverlayParams(prefs.getInt("${prefix}_up_x", 300), prefs.getInt("${prefix}_up_y", 400), false)
        paramsVolDown = createOverlayParams(prefs.getInt("${prefix}_down_x", 600), prefs.getInt("${prefix}_down_y", 400), false)
        paramsHud = createOverlayParams(prefs.getInt("${prefix}_hud_x", 80), prefs.getInt("${prefix}_hud_y", 120), true)

        makeTargetDraggable(btnVolUpTarget, paramsVolUp, "up")
        makeTargetDraggable(btnVolDownTarget, paramsVolDown, "down")

        windowManager.addView(btnVolUpTarget, paramsVolUp)
        windowManager.addView(btnVolDownTarget, paramsVolDown)
        windowManager.addView(hudContainer, paramsHud)

        clampAllToScreenBounds()
        updateHudUi()

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::windowManager.isInitialized) return

        val prefix = getOrientationPrefix()
        paramsVolUp.x = prefs.getInt("${prefix}_up_x", 300)
        paramsVolUp.y = prefs.getInt("${prefix}_up_y", 400)
        paramsVolDown.x = prefs.getInt("${prefix}_down_x", 600)
        paramsVolDown.y = prefs.getInt("${prefix}_down_y", 400)
        paramsHud.x = prefs.getInt("${prefix}_hud_x", 80)
        paramsHud.y = prefs.getInt("${prefix}_hud_y", 120)

        clampAllToScreenBounds()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isTriggerActive) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleTriggerKey(event, isVolUpHoldMode, btnVolUpTarget, true)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleTriggerKey(event, isVolDownHoldMode, btnVolDownTarget, false)
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun handleTriggerKey(event: KeyEvent, isHoldMode: Boolean, targetView: View, isUpButton: Boolean) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val (jitterX, jitterY) = getJitteredCenter(targetView)
            if (!isHoldMode) {
                dispatchSingleTap(jitterX, jitterY)
            } else {
                if (isUpButton) {
                    isHoldingVolUp = true
                    holdXUp = jitterX
                    holdYUp = jitterY
                } else {
                    isHoldingVolDown = true
                    holdXDown = jitterX
                    holdYDown = jitterY
                }
                startOrContinueHoldLoop()
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (isUpButton) isHoldingVolUp = false
            else isHoldingVolDown = false
        }
    }

    private fun getJitteredCenter(targetView: View): Pair<Float, Float> {
        val loc = IntArray(2)
        targetView.getLocationOnScreen(loc)
        val offsetX = Random.nextInt(-3, 4).toFloat()
        val offsetY = Random.nextInt(-3, 4).toFloat()
        val centerX = (loc[0] + (targetView.width / 2f) + offsetX).coerceAtLeast(1f)
        val centerY = (loc[1] + (targetView.height / 2f) + offsetY).coerceAtLeast(1f)
        return Pair(centerX, centerY)
    }

    private fun dispatchSingleTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val duration = Random.nextLong(42L, 56L)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun startOrContinueHoldLoop() {
        if (isGestureLoopRunning) return
        if (!isHoldingVolUp && !isHoldingVolDown) return

        isGestureLoopRunning = true
        stepHoldLoop()
    }

    private fun stepHoldLoop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (isHoldingVolUp) dispatchSingleTap(holdXUp, holdYUp)
            if (isHoldingVolDown) dispatchSingleTap(holdXDown, holdYDown)
            isGestureLoopRunning = false
            return
        }

        val builder = GestureDescription.Builder()
        var hasStrokes = false

        if (isHoldingVolUp || activeStrokeUp != null) {
            val pathUp = Path().apply { moveTo(holdXUp, holdYUp) }
            val keepGoing = isHoldingVolUp
            activeStrokeUp = if (activeStrokeUp == null) {
                GestureDescription.StrokeDescription(pathUp, 0, sliceDurationMs, keepGoing)
            } else {
                activeStrokeUp!!.continueStroke(pathUp, 0, sliceDurationMs, keepGoing)
            }
            builder.addStroke(activeStrokeUp!!)
            if (!keepGoing) activeStrokeUp = null
            hasStrokes = true
        }

        if (isHoldingVolDown || activeStrokeDown != null) {
            val pathDown = Path().apply { moveTo(holdXDown, holdYDown) }
            val keepGoing = isHoldingVolDown
            activeStrokeDown = if (activeStrokeDown == null) {
                GestureDescription.StrokeDescription(pathDown, 0, sliceDurationMs, keepGoing)
            } else {
                activeStrokeDown!!.continueStroke(pathDown, 0, sliceDurationMs, keepGoing)
            }
            builder.addStroke(activeStrokeDown!!)
            if (!keepGoing) activeStrokeDown = null
            hasStrokes = true
        }

        if (!hasStrokes) {
            isGestureLoopRunning = false
            return
        }

        val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isHoldingVolUp || isHoldingVolDown || activeStrokeUp != null || activeStrokeDown != null) {
                    stepHoldLoop()
                } else {
                    isGestureLoopRunning = false
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                activeStrokeUp = null
                activeStrokeDown = null
                isGestureLoopRunning = false
                if (isHoldingVolUp || isHoldingVolDown) {
                    mainHandler.postDelayed({ startOrContinueHoldLoop() }, 25L)
                }
            }
        }, null)

        if (!dispatched) {
            activeStrokeUp = null
            activeStrokeDown = null
            isGestureLoopRunning = false
        }
    }

    private fun buildHudBar() {
        hudContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#DD1C1C1E"))
                setStroke(3, Color.parseColor("#66FFFFFF"))
            }
        }

        btnMasterToggle = createHudChip("OFF", Color.parseColor("#FF453A"))
        btnUpMode = createHudChip("+ : HOLD", Color.parseColor("#3A3A3C"))
        btnDownMode = createHudChip("- : TAP", Color.parseColor("#3A3A3C"))
        btnCollapse = createHudChip("–", Color.parseColor("#2C2C2E"))

        attachDragAndClick(btnMasterToggle) { setTriggerActiveState(!isTriggerActive) }
        attachDragAndClick(btnUpMode) {
            isVolUpHoldMode = !isVolUpHoldMode
            prefs.edit().putBoolean("up_hold_mode", isVolUpHoldMode).apply()
            updateHudUi()
        }
        attachDragAndClick(btnDownMode) {
            isVolDownHoldMode = !isVolDownHoldMode
            prefs.edit().putBoolean("down_hold_mode", isVolDownHoldMode).apply()
            updateHudUi()
        }
        attachDragAndClick(btnCollapse) {
            isHudCollapsed = !isHudCollapsed
            updateHudUi()
        }

        hudContainer.addView(btnMasterToggle)
        hudContainer.addView(btnUpMode)
        hudContainer.addView(btnDownMode)
        hudContainer.addView(btnCollapse)
    }

    private fun createHudChip(label: String, bgColor: Int): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(22, 12, 22, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(6, 0, 6, 0) }
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(bgColor)
            }
        }
    }

    private fun setTriggerActiveState(active: Boolean) {
        isTriggerActive = active
        if (!active) {
            isHoldingVolUp = false
            isHoldingVolDown = false
        }
        setTargetsTouchPassThrough(active)
        updateHudUi()
    }

    private fun updateHudUi() {
        val masterBg = btnMasterToggle.background as GradientDrawable
        if (isTriggerActive) {
            btnMasterToggle.text = "LOCKED"
            masterBg.setColor(Color.parseColor("#30D158"))
        } else {
            btnMasterToggle.text = "OFF"
            masterBg.setColor(Color.parseColor("#FF453A"))
        }

        btnUpMode.text = if (isVolUpHoldMode) "+ : HOLD" else "+ : TAP"
        btnDownMode.text = if (isVolDownHoldMode) "- : HOLD" else "- : TAP"

        if (isHudCollapsed) {
            btnMasterToggle.visibility = View.GONE
            btnUpMode.visibility = View.GONE
            btnDownMode.visibility = View.GONE
            btnCollapse.text = if (isTriggerActive) "⚡ ON" else "⚡ OFF"
            hudContainer.alpha = 0.65f
        } else {
            btnMasterToggle.visibility = View.VISIBLE
            btnUpMode.visibility = View.VISIBLE
            btnDownMode.visibility = View.VISIBLE
            btnCollapse.text = "–"
            hudContainer.alpha = 0.95f
        }
    }

    private fun setTargetsTouchPassThrough(passThrough: Boolean) {
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val updatedFlags = if (passThrough) baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else baseFlags
        paramsVolUp.flags = updatedFlags
        paramsVolDown.flags = updatedFlags
        btnVolUpTarget.alpha = if (passThrough) 0.38f else 0.92f
        btnVolDownTarget.alpha = if (passThrough) 0.38f else 0.92f
        windowManager.updateViewLayout(btnVolUpTarget, paramsVolUp)
        windowManager.updateViewLayout(btnVolDownTarget, paramsVolDown)
    }

    private fun attachDragAndClick(view: View, onClickAction: () -> Unit) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startParamsX = 0
        var startParamsY = 0
        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startParamsX = paramsHud.x
                    startParamsY = paramsHud.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && hypot(dx, dy) > touchSlop) isDragging = true
                    if (isDragging) {
                        val (maxW, maxH) = getScreenDimensions()
                        paramsHud.x = (startParamsX + dx.toInt()).coerceIn(0, (maxW - hudContainer.width).coerceAtLeast(0))
                        paramsHud.y = (startParamsY + dy.toInt()).coerceIn(0, (maxH - hudContainer.height).coerceAtLeast(0))
                        windowManager.updateViewLayout(hudContainer, paramsHud)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) savePosition("hud", paramsHud.x, paramsHud.y)
                    else onClickAction()
                    true
                }
                else -> false
            }
        }
    }

    private fun makeTargetDraggable(view: View, params: WindowManager.LayoutParams, keyTag: String) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            if (isTriggerActive) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (maxW, maxH) = getScreenDimensions()
                    params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, (maxW - targetSizePx).coerceAtLeast(0))
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, (maxH - targetSizePx).coerceAtLeast(0))
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition(keyTag, params.x, params.y)
                    true
                }
                else -> false
            }
        }
    }

    private fun clampAllToScreenBounds() {
        val (maxW, maxH) = getScreenDimensions()
        paramsVolUp.x = paramsVolUp.x.coerceIn(0, (maxW - targetSizePx).coerceAtLeast(0))
        paramsVolUp.y = paramsVolUp.y.coerceIn(0, (maxH - targetSizePx).coerceAtLeast(0))
        paramsVolDown.x = paramsVolDown.x.coerceIn(0, (maxW - targetSizePx).coerceAtLeast(0))
        paramsVolDown.y = paramsVolDown.y.coerceIn(0, (maxH - targetSizePx).coerceAtLeast(0))
        paramsHud.x = paramsHud.x.coerceIn(0, (maxW - 200).coerceAtLeast(0))
        paramsHud.y = paramsHud.y.coerceIn(0, (maxH - 120).coerceAtLeast(0))

        windowManager.updateViewLayout(btnVolUpTarget, paramsVolUp)
        windowManager.updateViewLayout(btnVolDownTarget, paramsVolDown)
        windowManager.updateViewLayout(hudContainer, paramsHud)
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun getOrientationPrefix(): String {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
    }

    private fun savePosition(tag: String, x: Int, y: Int) {
        val prefix = getOrientationPrefix()
        prefs.edit().putInt("${prefix}_${tag}_x", x).putInt("${prefix}_${tag}_y", y).apply()
    }

    private fun createOverlayParams(xPos: Int, yPos: Int, isPill: Boolean): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val size = if (isPill) WindowManager.LayoutParams.WRAP_CONTENT else targetSizePx
        return WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPos
            y = yPos
        }
    }

    private fun createTargetView(label: String, bgColor: Int): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
                setStroke(4, Color.WHITE)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        isHoldingVolUp = false
        isHoldingVolDown = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        if (::windowManager.isInitialized) {
            windowManager.removeView(btnVolUpTarget)
            windowManager.removeView(btnVolDownTarget)
            windowManager.removeView(hudContainer)
        }
    }
}
