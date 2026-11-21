package com.dudu.volumeside

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.*
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class VolumeOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: Prefs

    private var pillView: LinearLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var tvVolume: TextView? = null
    private var btnMute: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnPlus: TextView? = null
    private var btnMinus: TextView? = null

    private var sliderOverlay: FrameLayout? = null
    private var bigVolumeText: TextView? = null
    private var volumeSeekBar: SeekBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var hideSliderRunnable = Runnable { hideCenterSlider() }

    private var isMuted = false
    private var savedVolumeBeforeMute = 10

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.media.VOLUME_CHANGED_ACTION" -> updateVolumeUI()
                "com.dudu.volumeside.UPDATE_STYLE" -> rebuildPill()
                "com.dudu.volumeside.FOREGROUND_CHANGED" -> updateVisibilityByMode()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = Prefs(this)

        startForegroundNotification()
        createPillOverlay()

        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("com.dudu.volumeside.UPDATE_STYLE")
            addAction("com.dudu.volumeside.FOREGROUND_CHANGED")
        }
        registerReceiver(volumeReceiver, filter)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Автоматическая смена темы при переключении день/ночь в DUDU
        if (prefs.themeMode == 0) {
            applyDuduTheme()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "dudu_vol_channel"
        val channel = NotificationChannel(channelId, "DUDU Volume Service", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DUDU Volume")
            .setContentText("Боковая панель громкости активна")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .build()
        startForeground(101, notification)
    }

    private fun isDarkTheme(): Boolean {
        return when (prefs.themeMode) {
            1 -> true
            2 -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun dp(value: Float): Int {
        val scale = prefs.scalePercent / 100f
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value * scale,
            resources.displayMetrics
        ).roundToInt()
    }

    private fun rawDp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPillOverlay() {
        if (prefs.displayMode == 0) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        }

        // 1. Кнопка настроек (шестерёнка)
        btnSettings = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(40f))
            setOnClickListener {
                val intent = Intent(this@VolumeOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        // 2. Кнопка +
        btnPlus = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 24f * (prefs.scalePercent / 100f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(50f))
        }
        setupHoldButton(btnPlus!!, isIncrease = true)

        // 3. Цифра текущей громкости
        tvVolume = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f * (prefs.scalePercent / 100f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(42f))
            setOnClickListener { showCenterSlider() }
        }

        // 4. Кнопка -
        btnMinus = TextView(this).apply {
            text = "−"
            gravity = Gravity.CENTER
            textSize = 24f * (prefs.scalePercent / 100f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(50f))
        }
        setupHoldButton(btnMinus!!, isIncrease = false)

        // 5. Кнопка Mute
        btnMute = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
            setOnClickListener { toggleMute() }
        }

        container.addView(btnSettings)
        container.addView(createDivider())
        container.addView(btnPlus)
        container.addView(tvVolume)
        container.addView(btnMinus)
        container.addView(createDivider())
        container.addView(btnMute)

        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (prefs.isRightAligned) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            x = prefs.posX
            y = prefs.posY
        }

        setupDragGesture(container)

        pillView = container
        applyDuduTheme()
        updateVolumeUI()
        wm.addView(pillView, pillParams)
        updateVisibilityByMode()
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26f), rawDp(1f)).apply {
                topMargin = dp(2f)
                bottomMargin = dp(2f)
            }
            setBackgroundColor(0x338899AA)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldButton(view: View, isIncrease: Boolean) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    stepVolume(isIncrease)
                    repeatRunnable = object : Runnable {
                        override fun run() {
                            stepVolume(isIncrease)
                            handler.postDelayed(this, 120L)
                        }
                    }
                    handler.postDelayed(repeatRunnable!!, 380L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1.0f
                    repeatRunnable?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragGesture(container: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            if (prefs.lockPosition) return@setOnTouchListener false
            val params = pillParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                        params.x = if (prefs.isRightAligned) (initialX - dx).coerceAtLeast(0) else (initialX + dx).coerceAtLeast(0)
                        params.y = initialY + dy
                        wm.updateViewLayout(pillView, params)
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        prefs.posX = params.x
                        prefs.posY = params.y
                    }
                    isDragging
                }
                else -> false
            }
        }
    }

    private fun stepVolume(increase: Boolean) {
        if (isMuted) {
            isMuted = false
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = if (increase) (current + 1).coerceAtMost(max) else (current - 1).coerceAtLeast(0)

        // Флаг 0 — меняем тихо без всплывающей системной шторки Teyes
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        updateVolumeUI()
    }

    private fun toggleMute() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (!isMuted && current > 0) {
            savedVolumeBeforeMute = current
            isMuted = true
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else {
            isMuted = false
            val restore = if (savedVolumeBeforeMute > 0) savedVolumeBeforeMute else 10
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
        }
        updateVolumeUI()
    }

    private fun updateVolumeUI() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        isMuted = (current == 0)
        tvVolume?.text = current.toString()
        bigVolumeText?.text = current.toString()
        volumeSeekBar?.progress = current

        val dark = isDarkTheme()
        val accentColor = if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        val normalIconColor = if (dark) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")

        tvVolume?.setTextColor(if (isMuted) Color.parseColor("#EF4444") else accentColor)
        btnMute?.setImageResource(
            if (isMuted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
        btnMute?.setColorFilter(if (isMuted) Color.parseColor("#EF4444") else normalIconColor)
    }

    private fun applyDuduTheme() {
        val view = pillView ?: return
        val dark = isDarkTheme()
        val alphaInt = ((prefs.alphaPercent / 100f) * 255).roundToInt().coerceIn(40, 255)

        val bgColor = if (dark) {
            Color.argb(alphaInt, 18, 22, 31) // Глубокий графит DUDU Dark
        } else {
            Color.argb(alphaInt, 244, 247, 251) // Светлое стекло DUDU Light
        }
        val strokeColor = if (dark) Color.parseColor("#4438BDF8") else Color.parseColor("#330284C7")
        val textColor = if (dark) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18f).toFloat()
            setColor(bgColor)
            setStroke(rawDp(1.2f), strokeColor)
        }
        view.background = bg

        btnPlus?.setTextColor(textColor)
        btnMinus?.setTextColor(textColor)
        btnSettings?.setColorFilter(if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
        updateVolumeUI()
    }

    private fun showCenterSlider() {
        if (sliderOverlay != null) {
            resetSliderTimer()
            return
        }

        val dark = isDarkTheme()
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55000000"))
            setOnClickListener { hideCenterSlider() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(rawDp(32f), rawDp(24f), rawDp(32f), rawDp(28f))
            background = GradientDrawable().apply {
                cornerRadius = rawDp(22f).toFloat()
                setColor(if (dark) Color.parseColor("#EE141923") else Color.parseColor("#F5FFFFFF"))
                setStroke(rawDp(1.5f), if (dark) Color.parseColor("#6638BDF8") else Color.parseColor("#440284C7"))
            }
            setOnClickListener { /* перехват клика внутри карточки */ }
        }

        val titleLabel = TextView(this).apply {
            text = "ГРОМКОСТЬ"
            textSize = 12f
            letterSpacing = 0.15f
            setTextColor(if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
        }

        bigVolumeText = TextView(this).apply {
            text = curVol.toString()
            textSize = 46f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            gravity = Gravity.CENTER
            setPadding(0, rawDp(4f), 0, rawDp(16f))
        }

        volumeSeekBar = SeekBar(this).apply {
            max = maxVol
            progress = curVol
            layoutParams = LinearLayout.LayoutParams(rawDp(360f), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        isMuted = (progress == 0)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                        bigVolumeText?.text = progress.toString()
                        tvVolume?.text = progress.toString()
                        resetSliderTimer()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    handler.removeCallbacks(hideSliderRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    resetSliderTimer()
                }
            })
        }

        card.addView(titleLabel)
        card.addView(bigVolumeText)
        card.addView(volumeSeekBar)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(card, cardParams)

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        sliderOverlay = root
        wm.addView(root, overlayParams)
        resetSliderTimer()
    }

    private fun resetSliderTimer() {
        handler.removeCallbacks(hideSliderRunnable)
        handler.postDelayed(hideSliderRunnable, 2500L)
    }

    private fun hideCenterSlider() {
        handler.removeCallbacks(hideSliderRunnable)
        sliderOverlay?.let {
            wm.removeView(it)
            sliderOverlay = null
            bigVolumeText = null
            volumeSeekBar = null
        }
    }

    private fun updateVisibilityByMode() {
        val view = pillView ?: return
        view.visibility = when (prefs.displayMode) {
            0 -> View.GONE
            2 -> if (LauncherDetectService.isLauncherForeground) View.VISIBLE else View.GONE
            else -> View.VISIBLE
        }
    }

    private fun rebuildPill() {
        pillView?.let {
            wm.removeView(it)
            pillView = null
        }
        createPillOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(volumeReceiver)
        hideCenterSlider()
        pillView?.let { wm.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
