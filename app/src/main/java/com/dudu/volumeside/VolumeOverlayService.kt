package com.dudu.volumeside

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.*
import android.provider.Settings
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
    private var savedVolumeBeforeMute = 15
    private var currentFytVolume = 15
    private val maxFytVolume = 36

    // Прямой мост к FYT MCU (com.syu.ms) на Teyes CC3 2K
    private var syuMainModule: IBinder? = null

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.media.VOLUME_CHANGED_ACTION",
                "com.syu.ms.action.VOLUME_CHANGED",
                "com.dudu.action.VOLUME_CHANGED" -> {
                    syncVolumeFromSystem()
                }
                "com.dudu.volumeside.UPDATE_STYLE" -> rebuildPill()
                "com.dudu.volumeside.FOREGROUND_CHANGED" -> updateVisibilityByMode()
            }
        }
    }

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            syncVolumeFromSystem()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = Prefs(this)

        connectFytToolkit()
        syncVolumeFromSystem()

        startForegroundNotification()
        createPillOverlay()

        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("com.syu.ms.action.VOLUME_CHANGED")
            addAction("com.dudu.action.VOLUME_CHANGED")
            addAction("com.dudu.volumeside.UPDATE_STYLE")
            addAction("com.dudu.volumeside.FOREGROUND_CHANGED")
        }
        registerReceiver(volumeReceiver, filter)
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)

        // Периодическая лёгкая сверка с MCU Teyes (каждые 700 мс), чтобы цифра всегда совпадала со статус-баром
        handler.post(object : Runnable {
            override fun run() {
                syncVolumeFromSystem()
                handler.postDelayed(this, 700L)
            }
        })
    }

    @SuppressLint("PrivateApi")
    private fun connectFytToolkit() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val toolkitBinder = getService.invoke(null, "syu_toolkit") as? IBinder
                ?: getService.invoke(null, "ms") as? IBinder

            if (toolkitBinder != null) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken("com.syu.ipc.IRemoteToolkit")
                    data.writeInt(0) // MODULE_CODE_MAIN = 0 (управление громкостью и MCU)
                    toolkitBinder.transact(1, data, reply, 0) // getRemoteModule(0)
                    reply.readException()
                    syuMainModule = reply.readStrongBinder()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        } catch (_: Throwable) {
            syuMainModule = null
        }
    }

    // Отправка прямой команды в FYT MODULE_CODE_MAIN (код 1 = C_VOLUME: -1 вниз, -2 вверх, -3 mute, 0..36 точное значение)
    private fun sendFytVolumeCommand(param: Int) {
        if (syuMainModule == null) connectFytToolkit()
        val module = syuMainModule
        if (module != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.syu.ipc.IRemoteModule")
                data.writeInt(1) // C_VOLUME = 1 в FYT MODULE_MAIN
                data.writeIntArray(intArrayOf(param))
                data.writeFloatArray(null)
                data.writeStringArray(null)
                module.transact(1, data, reply, 0) // cmd(...)
                reply.readException()
            } catch (_: Throwable) {
                syuMainModule = null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun readFytVolume(): Int {
        // 1. Чтение напрямую из FYT MODULE_CODE_MAIN (U_VOLUME = 2)
        if (syuMainModule == null) connectFytToolkit()
        val module = syuMainModule
        if (module != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.syu.ipc.IRemoteModule")
                data.writeInt(2) // U_VOLUME = 2
                module.transact(2, data, reply, 0) // get(...)
                reply.readException()
                if (reply.readInt() != 0) {
                    val ints = reply.createIntArray()
                    if (ints != null && ints.isNotEmpty() && ints[0] in 0..36) {
                        return ints[0]
                    }
                }
            } catch (_: Throwable) {
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        // 2. Чтение из системных ключей прошивки Teyes / DUDU
        val keys = listOf("syu_volume", "fyt_volume", "mcu_volume", "volume_music_speaker")
        for (k in keys) {
            val v = Settings.System.getInt(contentResolver, k, -1)
            if (v in 0..36) return v
        }

        // 3. Масштабирование стандартного Android потока в 0..36 как запасной вариант
        val androidCur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val androidMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ((androidCur.toFloat() / androidMax) * maxFytVolume).roundToInt()
    }

    private fun syncVolumeFromSystem() {
        val sysVol = readFytVolume()
        if (sysVol != currentFytVolume) {
            currentFytVolume = sysVol
            updateVolumeUI()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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

        // 1. Кнопка настроек (шестерёнка + перетаскивание)
        btnSettings = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(42f))
        }
        setupDragAndClickOnSettings(btnSettings!!)

        // 2. Кнопка +
        btnPlus = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 24f * (prefs.scalePercent / 100f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(50f), dp(52f))
        }
        setupHoldButton(btnPlus!!, isIncrease = true)

        // 3. Цифра текущей громкости
        tvVolume = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f * (prefs.scalePercent / 100f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(50f), dp(44f))
            setOnClickListener { showCenterSlider() }
        }

        // 4. Кнопка -
        btnMinus = TextView(this).apply {
            text = "−"
            gravity = Gravity.CENTER
            textSize = 24f * (prefs.scalePercent / 100f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(50f), dp(52f))
        }
        setupHoldButton(btnMinus!!, isIncrease = false)

        // 5. Кнопка Mute
        btnMute = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(46f), dp(46f))
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
                            handler.postDelayed(this, 130L)
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
    private fun setupDragAndClickOnSettings(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            val params = pillParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!prefs.lockPosition) {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 15 || abs(dy) > 15) {
                            isDragging = true
                            params.x = if (prefs.isRightAligned) (initialX - dx).coerceAtLeast(0) else (initialX + dx).coerceAtLeast(0)
                            params.y = initialY + dy
                            wm.updateViewLayout(pillView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1.0f
                    if (isDragging) {
                        prefs.posX = params.x
                        prefs.posY = params.y
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        val intent = Intent(this@VolumeOverlayService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun stepVolume(increase: Boolean) {
        val next = if (increase) (currentFytVolume + 1).coerceAtMost(maxFytVolume)
                   else (currentFytVolume - 1).coerceAtLeast(0)
        currentFytVolume = next
        isMuted = (next == 0)

        // В FYT MODULE_MAIN: -2 = шаг вверх, -1 = шаг вниз, либо прямое число 0..36
        sendFytVolumeCommand(if (increase) -2 else -1)
        sendFytVolumeCommand(next)

        // Дублируем системным интентом DUDU / Teyes
        sendBroadcast(Intent("com.syu.ms.action.VOLUME").apply {
            putExtra("volume", next)
            putExtra("cmd", if (increase) 1 else -1)
        })

        updateVolumeUI()
    }

    private fun setExactVolume(target: Int) {
        val clamped = target.coerceIn(0, maxFytVolume)
        currentFytVolume = clamped
        isMuted = (clamped == 0)

        sendFytVolumeCommand(clamped)
        sendBroadcast(Intent("com.syu.ms.action.VOLUME").apply {
            putExtra("volume", clamped)
        })

        updateVolumeUI()
    }

    private fun toggleMute() {
        if (!isMuted && currentFytVolume > 0) {
            savedVolumeBeforeMute = currentFytVolume
            isMuted = true
            sendFytVolumeCommand(-3) // FYT C_VOLUME(-3) = MUTE toggle
            setExactVolume(0)
        } else {
            isMuted = false
            sendFytVolumeCommand(-3)
            val restore = if (savedVolumeBeforeMute > 0) savedVolumeBeforeMute else 15
            setExactVolume(restore)
        }
    }

    private fun updateVolumeUI() {
        isMuted = (currentFytVolume == 0)
        tvVolume?.text = currentFytVolume.toString()
        bigVolumeText?.text = currentFytVolume.toString()
        volumeSeekBar?.progress = currentFytVolume

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
            Color.argb(alphaInt, 18, 22, 31)
        } else {
            Color.argb(alphaInt, 244, 247, 251)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun showCenterSlider() {
        if (sliderOverlay != null) {
            resetSliderTimer()
            return
        }

        val dark = isDarkTheme()
        syncVolumeFromSystem()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#55000000"))
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
        }

        val titleLabel = TextView(this).apply {
            text = "ГРОМКОСТЬ"
            textSize = 12f
            letterSpacing = 0.15f
            setTextColor(if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
        }

        bigVolumeText = TextView(this).apply {
            text = currentFytVolume.toString()
            textSize = 46f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            gravity = Gravity.CENTER
            setPadding(0, rawDp(4f), 0, rawDp(16f))
        }

        volumeSeekBar = SeekBar(this).apply {
            max = maxFytVolume // 36, как в системе Teyes
            progress = currentFytVolume
            layoutParams = LinearLayout.LayoutParams(rawDp(420f), rawDp(52f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        setExactVolume(progress)
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

        // Закрываем только при нажатии за пределами карточки, не мешая SeekBar внутри
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val rect = Rect()
                card.getGlobalVisibleRect(rect)
                if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    hideCenterSlider()
                    return@setOnTouchListener true
                }
            }
            false
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        sliderOverlay = root
        wm.addView(root, overlayParams)
        resetSliderTimer()
    }

    private fun resetSliderTimer() {
        handler.removeCallbacks(hideSliderRunnable)
        handler.postDelayed(hideSliderRunnable, 2800L)
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
        contentResolver.unregisterContentObserver(settingsObserver)
        hideCenterSlider()
        pillView?.let { wm.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
