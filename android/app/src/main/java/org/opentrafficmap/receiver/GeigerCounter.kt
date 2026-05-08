package org.opentrafficmap.receiver

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Audio + haptic feedback for received frames. Each frame produces a short
 * tick on the notification stream (so the user's media volume isn't hijacked);
 * DENMs get a longer, higher tone and a brief buzz to make hazards stand out.
 *
 * Designed for in-car use: gives the same kind of immediate "I just heard
 * something" feedback as a Geiger counter — useful while driving when the
 * driver can't take their eyes off the road to watch the map.
 */
class GeigerCounter(private val context: Context) {

    private var tone: ToneGenerator? = null
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    val isRunning: Boolean get() = tone != null

    fun start() {
        if (tone != null) return
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (_: RuntimeException) {
            // ToneGenerator throws when audio focus / output is not available.
            tone = null
        }
    }

    fun stop() {
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }

    fun click(msgType: ItsG5Decoder.MsgType) {
        val t = tone ?: return
        when (msgType) {
            ItsG5Decoder.MsgType.DENM -> {
                t.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                vibrate(60)
            }
            ItsG5Decoder.MsgType.SPATEM -> {
                t.startTone(ToneGenerator.TONE_DTMF_3, 25)
            }
            else -> {
                // Standard click; very short so 10 Hz CAM streams stay tolerable.
                t.startTone(ToneGenerator.TONE_PROP_PROMPT, 20)
            }
        }
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Exception) { /* permissions / hw missing */ }
    }
}
