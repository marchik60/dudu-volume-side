package com.dudu.volumeside

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class LauncherDetectService : AccessibilityService() {

    companion object {
        var isLauncherForeground: Boolean = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Игнорируем системные всплывашки и само приложение
            if (pkg == packageName || pkg == "com.android.systemui") return

            // Пакеты DUDU Launcher (основной и модификации)
            val isDudu = pkg.contains("dudu", ignoreCase = true) ||
                         pkg.contains("launcher", ignoreCase = true)

            if (isLauncherForeground != isDudu) {
                isLauncherForeground = isDudu
                sendBroadcast(Intent("com.dudu.volumeside.FOREGROUND_CHANGED"))
            }
        }
    }

    override fun onInterrupt() {}
}
