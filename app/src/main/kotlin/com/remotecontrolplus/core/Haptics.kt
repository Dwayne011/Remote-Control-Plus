package com.remotecontrolplus.core

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Button feedback through the vibrator directly. Compose's haptic feedback
 * is tied to the system "Touch feedback" setting and is silent when that
 * is off, which makes a remote feel dead.
 */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        val manager = context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibrator = manager?.defaultVibrator
    }

    /** A key press. */
    fun click() = play(VibrationEffect.EFFECT_CLICK)

    /** A swipe or slider nudge. */
    fun tick() = play(VibrationEffect.EFFECT_TICK)

    private fun play(effect: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }
}
