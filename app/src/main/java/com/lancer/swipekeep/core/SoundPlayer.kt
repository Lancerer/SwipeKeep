package com.lancer.swipekeep.core

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * 滑卡音效。**阶段 3 的零资源起步版**：用系统自带的 [ToneGenerator]，不往 `res/raw` 塞音频。
 * 阶段 8 会换成两个 ~2KB 单声道 WAV + `SoundPool`（`USAGE_ASSISTANCE_SONIFICATION`：
 * 与音乐混音而不是压低它、尊重静音/震动模式）—— 那时这个类的接口不用变。
 *
 * **只建一次，挂在 `AppContainer` 上**：构造 `ToneGenerator` 要几毫秒（要开音频输出通道），
 * 每次滑动都建一次会把这点开销压在关键帧上。同理**不要在 Fragment 的 `onDestroyView` 里 release**
 * —— 那会在导航离开的瞬间掐断正在响的音。
 */
class SoundPlayer {

    /**
     * `ToneGenerator` 的构造在个别设备上会抛 `RuntimeException`（音频服务不可用）。
     * 音效是锦上添花，拿不到就静音，**不能因为它崩掉滑卡**。
     */
    private val tone: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    }.onFailure { Log.w(TAG, "ToneGenerator unavailable, sound disabled", it) }
        .getOrNull()

    /** 右滑保留 */
    fun playKeep() = play(ToneGenerator.TONE_PROP_ACK)

    /** 左滑删除 */
    fun playDelete() = play(ToneGenerator.TONE_PROP_NACK)

    /**
     * 发声。`startTone` 在音频通道不可用时会抛，同样吞掉 —— 理由和构造一样。
     * 调用点都在主线程的手势回调里（`ToneGenerator` 对调用线程有 Looper 要求）。
     */
    private fun play(toneType: Int) {
        runCatching { tone?.startTone(toneType, TONE_MS) }
            .onFailure { Log.w(TAG, "startTone failed", it) }
    }

    private companion object {
        const val TAG = "SoundPlayer"

        /** 0–100。系统 UI 音一般在这个量级；再大会盖过用户正在放的音乐。 */
        const val VOLUME = 60

        /** 一次发声时长。TONE_PROP_ACK/NACK 本身很短，120ms 够它响完。 */
        const val TONE_MS = 120
    }
}
