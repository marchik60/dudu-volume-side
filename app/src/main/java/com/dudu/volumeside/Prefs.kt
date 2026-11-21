package com.dudu.volumeside

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dudu_vol_prefs", Context.MODE_PRIVATE)

    // 0 = выкл, 1 = поверх всех окон, 2 = только поверх лаунчера
    var displayMode: Int
        get() = sp.getInt("display_mode", 1)
        set(v) = sp.edit().putInt("display_mode", v).apply()

    // Прозрачность от 30 до 100 (%)
    var alphaPercent: Int
        get() = sp.getInt("alpha_percent", 88)
        set(v) = sp.edit().putInt("alpha_percent", v).apply()

    // Масштаб от 70 до 140 (%)
    var scalePercent: Int
        get() = sp.getInt("scale_percent", 100)
        set(v) = sp.edit().putInt("scale_percent", v).apply()

    // Закрепить позицию (запрет перетаскивания)
    var lockPosition: Boolean
        get() = sp.getBoolean("lock_position", false)
        set(v) = sp.edit().putBoolean("lock_position", v).apply()

    // Тема: 0 = Авто (как DUDU), 1 = Тёмная, 2 = Светлая
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(v) = sp.edit().putInt("theme_mode", v).apply()

    var posX: Int
        get() = sp.getInt("pos_x", 18) // отступ от правого края при Gravity.END
        set(v) = sp.edit().putInt("pos_x", v).apply()

    var posY: Int
        get() = sp.getInt("pos_y", 0)
        set(v) = sp.edit().putInt("pos_y", v).apply()

    var isRightAligned: Boolean
        get() = sp.getBoolean("right_aligned", true)
        set(v) = sp.edit().putBoolean("right_aligned", v).apply()
}
