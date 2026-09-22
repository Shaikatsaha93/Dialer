package com.example.sip

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

class DtmfTonePlayer {
    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 80)
        } catch (e: Exception) {
            Log.w("DtmfTonePlayer", "Failed to create ToneGenerator: ${e.message}")
        }
    }

    fun playTone(char: Char, durationMs: Int = 120) {
        val tone = when (char) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            'A', 'a' -> ToneGenerator.TONE_DTMF_A
            'B', 'b' -> ToneGenerator.TONE_DTMF_B
            'C', 'c' -> ToneGenerator.TONE_DTMF_C
            'D', 'd' -> ToneGenerator.TONE_DTMF_D
            else -> null
        }
        tone?.let {
            try {
                toneGenerator?.startTone(it, durationMs)
            } catch (e: Exception) {
                Log.w("DtmfTonePlayer", "Failed to play tone: ${e.message}")
            }
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.w("DtmfTonePlayer", "Error releasing ToneGenerator: ${e.message}")
        }
    }
}
