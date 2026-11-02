package com.remotecontrolplus.core

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Button feedback through the vibrator directly.
 *
 * Predefined click effects are classed as "touch" haptics, which Android
 * silences when the user's Touch feedback setting is off. A remote with no
 * feedback feels dead, so these are requested as media haptics instead,
 * which follow the separate "Media vibration" setting.
 */
object Haptics {
    private var vibrator: Vibrator? = null
    private var click: VibrationEffect? = null
    private var tick: VibrationEffect? = null
    private val attributes: VibrationAttributes by lazy {
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
    }

    fun init(context: Context) {
        val manager = context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        val v = manager?.defaultVibrator
        vibrator = v
        if (v == null || !v.hasVibrator()) {
            Diagnostics.log("haptics", "no vibrator on this device")
            return
        }
        val predefined = v.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK) ==
            Vibrator.VIBRATION_EFFECT_SUPPORT_YES
        click = if (predefined) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else VibrationEffect.createOneShot(18, 180)
        tick = if (predefined) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else VibrationEffect.createOneShot(10, 120)
        val touchSetting = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        }.getOrDefault(-1)
        Diagnostics.log("haptics", "vibrator ready; predefined effects=$predefined; system touch feedback setting=$touchSetting")
    }

    /** A key press. */
    fun click() = play(click)

    /** A swipe or slider nudge. */
    fun tick() = play(tick)

    private fun play(effect: VibrationEffect?) {
        val v = vibrator ?: return
        val e = effect ?: return
        runCatching { v.vibrate(e, attributes) }
            .onFailure { Diagnostics.log("haptics", "vibrate failed", it) }
    }
}
