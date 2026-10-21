package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Issue #119: 悬浮小白点。部分车机无法在桌面添加快捷方式，
 * 开启后屏幕上显示一个可拖动的小圆点，点击即打开 DiPlay。
 */
object FloatingDotOverlay {
    private const val TAG = "FloatingDot"
    private const val PREFS = "diplay_floating_dot"
    private const val KEY_X = "dot_x"
    private const val KEY_Y = "dot_y"

    private var dotView: View? = null
    private var windowManager: WindowManager? = null

    /** CarPlay is on screen: the dot must not sit on top of the projection. */
    private var suppressedByCarPlay = false
    /** Screen is off or the keyguard is up: the dot must not appear over the lock screen. */
    private var suppressedByScreen = false
    private var screenReceiver: BroadcastReceiver? = null

    /** The one visibility decision, kept pure so it can be tested. */
    internal fun shouldShowDot(
        enabled: Boolean,
        permitted: Boolean,
        carPlayVisible: Boolean,
        screenSuppressed: Boolean,
    ): Boolean = enabled && permitted && !carPlayVisible && !screenSuppressed

    fun permitted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isShowing(): Boolean = dotView != null

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        AirPlayPersistence.saveFloatingDot(app, enabled)
        if (enabled) {
            ensureScreenReceiver(app)
            refresh(app)
        } else {
            suppressedByScreen = false
            unregisterScreenReceiver(app)
            hide()
        }
    }

    fun isEnabled(context: Context): Boolean = AirPlayPersistence.loadFloatingDot(context)

    /**
     * CarPlay is in front (or no longer is). CarPlayHostActivity's lifecycle drives this so the dot
     * never draws over the projection — one of the reported complaints.
     */
    fun setCarPlayVisible(context: Context, visible: Boolean) {
        suppressedByCarPlay = visible
        refresh(context.applicationContext)
    }

    /** App 启动时调用，按用户设置恢复小白点。 */
    fun restoreIfEnabled(context: Context) {
        val app = context.applicationContext
        if (isEnabled(app)) {
            ensureScreenReceiver(app)
            refresh(app)
        }
    }

    /** Shows the dot only when it is enabled and neither CarPlay nor the lock screen owns it. */
    private fun refresh(context: Context) {
        if (shouldShowDot(isEnabled(context), permitted(context), suppressedByCarPlay, suppressedByScreen)) {
            show(context)
        } else {
            hide()
        }
    }

    private fun ensureScreenReceiver(context: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        suppressedByScreen = true
                        hide()
                    }
                    // Stay hidden while the keyguard is up; reappear at once when there is none.
                    Intent.ACTION_SCREEN_ON -> {
                        suppressedByScreen = keyguardLocked(ctx)
                        refresh(ctx.applicationContext)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        suppressedByScreen = false
                        refresh(ctx.applicationContext)
                    }
                }
            }
        }
        runCatching {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }.onSuccess { screenReceiver = receiver }
            .onFailure { e -> Log.w(TAG, "screen receiver not registered", e) }
    }

    private fun unregisterScreenReceiver(context: Context) {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { e -> Log.w(TAG, "screen receiver not unregistered", e) }
    }

    private fun keyguardLocked(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    }.getOrDefault(false)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (dotView != null) return
        if (!permitted(context)) {
            Log.w(TAG, "overlay permission not granted")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        val size = (48 * density).toInt()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            x = prefs.getInt(KEY_X, metrics.widthPixels - size - (16 * density).toInt())
                .coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (metrics.heightPixels / 3))
                .coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
            title = "DiPlay dot"
        }

        // 直接用桌面 round 图标，和项目 UI 完全一致
        val dot = android.widget.ImageView(context).apply {
            setImageResource(com.shilapi.xcertplay.shared.R.mipmap.ic_launcher_round)
        }

        var downX = 0f
        var downY = 0f
        var moved = false
        dot.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (dx * dx + dy * dy > (8 * density).toInt().let { it * it }) {
                        moved = true
                    }
                    if (moved) {
                        params.x = (params.x + dx).coerceIn(
                            0, (context.resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (params.y + dy).coerceIn(
                            0, (context.resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, params) }
                        downX = event.rawX
                        downY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        dot.setOnClickListener {
            val launch = Intent(context, CarPlayHostActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { context.startActivity(launch) }
                .onFailure { e -> Log.w(TAG, "launch failed", e) }
        }

        runCatching { wm.addView(dot, params) }
            .onSuccess {
                dotView = dot
                windowManager = wm
                Log.i(TAG, "floating dot shown")
            }
            .onFailure { e -> Log.w(TAG, "addView failed", e) }
    }

    fun hide() {
        val view = dotView ?: return
        runCatching { windowManager?.removeView(view) }
        dotView = null
        windowManager = null
    }
}
