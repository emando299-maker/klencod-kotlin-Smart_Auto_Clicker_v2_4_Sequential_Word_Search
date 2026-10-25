package com.smartautoclicker.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class AutoClickService : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_FLOATING_WINDOW = "com.smartautoclicker.app.SHOW_FLOATING_WINDOW"
    }
    private val showWindowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHOW_FLOATING_WINDOW) {
                prefs.edit().putBoolean("floating_hidden", false).apply()
                try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                collapsedView = null
                try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                overlayView = null
                showFloatingWindow()
            }
        }
    }
    private var receiverRegistered = false
    private var lastClickTime = 0L
    private var overlayView: View? = null
    private var speedLabel: TextView? = null
    private var collapsedView: View? = null
    private var statusLabel: TextView? = null
    private var windowManager: WindowManager? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val clickDelay: Long
        get() = prefs.getInt("click_delay_ms", 1000).toLong().coerceIn(1L, 5000L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_SHOW_FLOATING_WINDOW)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(showWindowReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(showWindowReceiver, filter)
        }
        receiverRegistered = true
        if (!prefs.getBoolean("floating_hidden", false)) showFloatingWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefs.getBoolean("running", false)) {
            updateStatus("متوقف")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickDelay) return

        val currentSlot = currentEnabledSlot() ?: run {
            updateStatus("فعّل خانة بحث واحدة على الأقل")
            return
        }
        val word = prefs.getString("slot_word_${currentSlot + 1}", "")?.trim().orEmpty()
        if (word.isBlank()) {
            updateStatus("اكتب كلمة الخانة ${currentSlot + 1}")
            return
        }

        // Keep waiting on this slot until its word appears and a click succeeds.
        val root = rootInActiveWindow ?: return
        if (searchAndClick(root, word)) {
            lastClickTime = now
            val nextSlot = nextEnabledSlot(currentSlot)
            prefs.edit().putInt("sequence_index", nextSlot).apply()
            updateStatus("تم النقر على الخانة ${currentSlot + 1} • التالية ${nextSlot + 1}")
        } else {
            updateStatus("في انتظار الكلمة ${currentSlot + 1}: $word")
        }
    }

    private fun currentEnabledSlot(): Int? {
        val start = prefs.getInt("sequence_index", 0).coerceIn(0, 4)
        for (offset in 0 until 5) {
            val slot = (start + offset) % 5
            val enabled = prefs.getBoolean("slot_enabled_${slot + 1}", slot == 0)
            val word = prefs.getString("slot_word_${slot + 1}", if (slot == 0) prefs.getString("word", "") else "")?.trim().orEmpty()
            if (enabled && word.isNotBlank()) {
                if (slot != start) prefs.edit().putInt("sequence_index", slot).apply()
                return slot
            }
        }
        return null
    }

    private fun nextEnabledSlot(current: Int): Int {
        for (offset in 1..5) {
            val slot = (current + offset) % 5
            val enabled = prefs.getBoolean("slot_enabled_${slot + 1}", slot == 0)
            val word = prefs.getString("slot_word_${slot + 1}", if (slot == 0) prefs.getString("word", "") else "")?.trim().orEmpty()
            if (enabled && word.isNotBlank()) return slot
        }
        return current
    }

    private fun searchAndClick(node: AccessibilityNodeInfo, word: String): Boolean {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if (text.contains(word, ignoreCase = true) || description.contains(word, ignoreCase = true)) {
            var target: AccessibilityNodeInfo? = node
            var depth = 0
            while (target != null && depth < 10) {
                if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                target = target.parent
                depth++
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchAndClick(child, word)) return true
        }
        return false
    }

    private fun showFloatingWindow() {
        if (overlayView != null || collapsedView != null || prefs.getBoolean("floating_hidden", false)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            dp(178), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(10); y = dp(100) }

        fun roundedBackground(color: Int, radiusDp: Int = 18): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
            }

        fun makeCollapsedBubble() {
            if (collapsedView != null) return
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlayView = null
            val bubble = TextView(this).apply {
                text = "▶"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(35, 125, 220))
                    shape = GradientDrawable.OVAL
                }
                contentDescription = "إظهار لوحة النقر"
                setOnClickListener {
                    try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                    collapsedView = null
                    showFloatingWindow()
                }
            }
            val bubbleParams = WindowManager.LayoutParams(
                dp(48), dp(48),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = params.gravity; x = params.x; y = params.y }
            bubble.setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var startX = 0
                private var startY = 0
                private var moved = false
                override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX; downY = event.rawY
                            startX = bubbleParams.x; startY = bubbleParams.y; moved = false
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downX).toInt()
                            val dy = (event.rawY - downY).toInt()
                            if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) moved = true
                            bubbleParams.x = startX - dx
                            bubbleParams.y = startY + dy
                            try { windowManager?.updateViewLayout(bubble, bubbleParams) } catch (_: Exception) {}
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (!moved) v.performClick()
                            return true
                        }
                    }
                    return false
                }
            })
            try {
                windowManager?.addView(bubble, bubbleParams)
                collapsedView = bubble
            } catch (_: Exception) { collapsedView = null }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = roundedBackground(Color.rgb(28, 35, 45), 18)
        }
        val headingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "النقر التلقائي"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        headingRow.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        headingRow.addView(TextView(this).apply {
            text = "−"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "إخفاء النافذة"
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { makeCollapsedBubble() }
        })
        headingRow.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 115, 125))
            contentDescription = "إغلاق النافذة العائمة نهائيًا"
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener {
                prefs.edit().putBoolean("floating_hidden", true).apply()
                try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
                overlayView = null
                collapsedView = null
                Toast.makeText(this@AutoClickService, "تم إغلاق النافذة العائمة. يمكنك إظهارها من التطبيق.", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(headingRow)

        statusLabel = TextView(this).apply {
            text = if (prefs.getBoolean("running", false)) "يعمل" else "متوقف"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 11f
        }
        panel.addView(statusLabel)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(Button(this).apply {
            text = "تشغيل"; isAllCaps = false; textSize = 11f
            setPadding(dp(2), 0, dp(2), 0)
            minHeight = dp(34)
            setOnClickListener {
                if (prefs.getString("word", "").isNullOrBlank()) {
                    Toast.makeText(this@AutoClickService, "افتح التطبيق واحفظ كلمة البحث أولًا", Toast.LENGTH_SHORT).show()
                    updateStatus("لا توجد كلمة بحث")
                } else {
                    prefs.edit().putBoolean("running", true).apply()
                    updateStatus("يعمل")
                }
            }
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        controls.addView(Button(this).apply {
            text = "إيقاف"; isAllCaps = false; textSize = 11f
            setPadding(dp(2), 0, dp(2), 0)
            minHeight = dp(34)
            setOnClickListener {
                prefs.edit().putBoolean("running", false).apply()
                updateStatus("متوقف")
            }
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        panel.addView(controls)

        val delay = clickDelay.toInt()
        speedLabel = TextView(this).apply {
            text = "الفاصل: ${delay} ms"
            setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        panel.addView(speedLabel)
        panel.addView(SeekBar(this).apply {
            max = 4999
            progress = delay - 1
            contentDescription = "الفاصل بين النقرات من 1 إلى 5000 ملي ثانية"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val ms = progress + 1
                    prefs.edit().putInt("click_delay_ms", ms).apply()
                    speedLabel?.text = "الفاصل: ${ms} ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        panel.addView(TextView(this).apply {
            text = "اسحب العنوان للتحريك • − للتصغير • × للإغلاق"
            setTextColor(Color.LTGRAY); textSize = 9f; gravity = Gravity.CENTER
        })

        heading.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = startX - (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try { windowManager?.updateViewLayout(panel, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
        try {
            windowManager?.addView(panel, params)
            overlayView = panel
        } catch (_: Exception) { overlayView = null }
    }

    private fun updateStatus(message: String) { statusLabel?.text = message }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() {
        prefs.edit().putBoolean("running", false).apply()
        updateStatus("متوقف")
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(showWindowReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { collapsedView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        collapsedView = null
        super.onDestroy()
    }
}
