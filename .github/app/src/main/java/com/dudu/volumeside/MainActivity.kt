package com.dudu.volumeside

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F141E"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
        }

        val header = TextView(this).apply {
            text = "DUDU Volume Panel — Настройки"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 32)
        }
        root.addView(header)

        // 1. Разрешение поверх окон
        val btnPerm = Button(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity))
                "✓ Разрешение поверх окон выдано"
            else
                "1. Выдать разрешение поверх всех окон"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
        root.addView(btnPerm)

        // 2. Режим работы
        root.addView(createLabel("Режим отображения панели:"))
        val modeGroup = RadioGroup(this).apply {
            val modes = listOf("Выключено", "Поверх всех окон (Навигатор, плеер, DUDU)", "Только поверх лаунчера DUDU")
            modes.forEachIndexed { index, title ->
                val rb = RadioButton(this@MainActivity).apply {
                    id = index + 100
                    text = title
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 15f
                    isChecked = (prefs.displayMode == index)
                }
                addView(rb)
            }
            setOnCheckedChangeListener { _, checkedId ->
                val selected = checkedId - 100
                prefs.displayMode = selected
                if (selected == 2) {
                    Toast.makeText(this@MainActivity, "Включите 'DUDU Volume' в Спецвозможностях Android для отслеживания лаунчера", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                restartOverlayService()
            }
        }
        root.addView(modeGroup)

        // 3. Тема оформления
        root.addView(createLabel("Тема оформления:"))
        val themeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val themes = listOf("Авто с DUDU", "Тёмная", "Светлая")
            themes.forEachIndexed { index, title ->
                val rb = RadioButton(this@MainActivity).apply {
                    id = index + 200
                    text = title
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setPadding(0, 0, 32, 0)
                    isChecked = (prefs.themeMode == index)
                }
                addView(rb)
            }
            setOnCheckedChangeListener { _, checkedId ->
                prefs.themeMode = checkedId - 200
                sendBroadcast(Intent("com.dudu.volumeside.UPDATE_STYLE"))
            }
        }
        root.addView(themeGroup)

        // 4. Прозрачность
        val alphaLabel = createLabel("Непрозрачность панели: ${prefs.alphaPercent}%")
        root.addView(alphaLabel)
        val alphaSeek = SeekBar(this).apply {
            max = 70 // от 30 до 100
            progress = prefs.alphaPercent - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val valPct = p + 30
                    prefs.alphaPercent = valPct
                    alphaLabel.text = "Непрозрачность панели: $valPct%"
                    sendBroadcast(Intent("com.dudu.volumeside.UPDATE_STYLE"))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(alphaSeek)

        // 5. Размер (масштаб)
        val scaleLabel = createLabel("Размер кнопок: ${prefs.scalePercent}%")
        root.addView(scaleLabel)
        val scaleSeek = SeekBar(this).apply {
            max = 70 // от 70 до 140
            progress = prefs.scalePercent - 70
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val valPct = p + 70
                    prefs.scalePercent = valPct
                    scaleLabel.text = "Размер кнопок: $valPct%"
                    sendBroadcast(Intent("com.dudu.volumeside.UPDATE_STYLE"))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(scaleSeek)

        // 6. Фиксация позиции и сброс координат
        val lockSwitch = Switch(this).apply {
            text = "Закрепить панель (запретить перетаскивание)"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 15f
            isChecked = prefs.lockPosition
            setPadding(0, 28, 0, 20)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.lockPosition = isChecked
            }
        }
        root.addView(lockSwitch)

        val btnResetPos = Button(this).apply {
            text = "Вернуть панель вправо по центру"
            setOnClickListener {
                prefs.isRightAligned = true
                prefs.posX = 18
                prefs.posY = 0
                sendBroadcast(Intent("com.dudu.volumeside.UPDATE_STYLE"))
            }
        }
        root.addView(btnResetPos)

        scroll.addView(root)
        setContentView(scroll)

        if (Settings.canDrawOverlays(this) && prefs.displayMode != 0) {
            restartOverlayService()
        }
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 28, 0, 10)
        }
    }

    private fun restartOverlayService() {
        val svc = Intent(this, VolumeOverlayService::class.java)
        stopService(svc)
        if (prefs.displayMode != 0 && Settings.canDrawOverlays(this)) {
            startForegroundService(svc)
        }
    }
}
