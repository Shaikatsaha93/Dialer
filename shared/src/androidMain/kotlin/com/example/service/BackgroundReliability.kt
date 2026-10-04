package com.example.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What the phone must allow so a registered SIP account keeps ringing while the screen is off,
 * in Doze or in battery saver: the app must be off the battery-optimization list (otherwise
 * Doze cuts its network and ignores its wake lock) and allowed to show a full-screen call UI.
 */
object BackgroundReliability {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** System dialog "Let app always run in background?" (falls back to the full list). */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        return if (direct.resolveActivity(context.packageManager) != null) direct
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    fun fullScreenIntentSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            appDetailsSettings(context)
        }

    /**
     * Phone makers' own "Auto launch / background activity" screens. On Oppo, Realme, OnePlus,
     * Xiaomi, Vivo and Huawei the app is killed in the background unless it is allowed there,
     * whatever the battery setting says. The first screen that exists on this phone, or null.
     */
    fun oemAutostartIntent(context: Context): Intent? = OEM_AUTOSTART_SCREENS
        .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

    /** Package + activity of each maker's auto-launch screen (package names are listed in <queries>). */
    private val OEM_AUTOSTART_SCREENS = listOf(
        // Oppo / Realme / OnePlus (ColorOS)
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Xiaomi / Redmi / Poco
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Samsung (battery / sleeping apps)
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        // Asus
        "com.asus.mobilemanager" to "com.asus.mobilemanager.MainActivity"
    )

    fun appDetailsSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
}
