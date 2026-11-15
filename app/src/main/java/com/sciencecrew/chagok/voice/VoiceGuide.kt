package com.sciencecrew.chagok.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 음성으로 읽어 주기.
 * Pleos TextToSpeech SDK, ON_DEVICE 모드 (네트워크·앱 등록 불필요, 공식 문서 기준).
 * 권한: pleos.car.permission.TTS_SERVICE
 */
class VoiceGuide(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private var onDoneCallback: (() -> Unit)? = null
    @Volatile private var speaking = false
    /** 말이 끝난 뒤 0.7초 뒤에 실행하려고 예약해 둔 다음 동작 (멈추면 취소) */
    private var scheduled: Runnable? = null

    /** 지금 말하는 중이거나, 말이 끝난 뒤 이어서 할 동작(듣기 등)이 남아 있음 → 다른 안내를 끼워 넣지 말 것 */
    val busy: Boolean get() = speaking || onDoneCallback != null || pending != null

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)

    private val events = object : EventListener {
        override fun onStart() { Log.d(TAG, "말하기 시작") }
        override fun onReady() {}
        override fun onDone() { Log.d(TAG, "말하기 끝"); finish() }
        override fun onError(errorType: ErrorType) { Log.w(TAG, "TTS 오류 $errorType"); finish() }
        // 멈춤 신호는 늦게 도착할 수 있어, 다음 안내의 "끝"으로 착각하지 않도록 아무것도 하지 않음
        override fun onStop() { Log.d(TAG, "말하기 멈춤") }
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.d(TAG, "TTS 초기화 성공")
                tts.setLocale(Locale.KOREA)
                // 안내 음성 용도: 음악은 잠깐 줄이고 안내를 들려줌
                tts.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts.addEventListener(events)
                ready = true
                pending?.let { (t, cb) -> pending = null; speak(t, cb) }
            }
            override fun onFailure(errorType: ErrorType) {
                Log.e(TAG, "TTS 초기화 실패 $errorType")
            }
        })
    }

    private fun finish() {
        speaking = false
        val cb = onDoneCallback ?: return
        onDoneCallback = null
        // 스피커 소리가 완전히 사라진 뒤에 다음 동작(듣기 등) 시작 → 안내 음성이 마이크로 다시 들어가는 것 방지
        val r = Runnable { scheduled = null; cb() }
        scheduled = r
        main.postDelayed(r, AFTER_SPEAK_GAP_MS)
    }

    /** 읽어 주고, 다 읽으면 onDone 호출 (다음 동작 이어가기용) */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) { pending = text to onDone; return }
        Log.d(TAG, "speak: $text")
        scheduled?.let { main.removeCallbacks(it) }; scheduled = null
        onDoneCallback = onDone
        speaking = true
        tts.speak(text, true)   // isFinal = true: 한 번에 완성된 문장
    }

    fun stop() {
        scheduled?.let { main.removeCallbacks(it) }; scheduled = null
        onDoneCallback = null
        speaking = false
        runCatching { tts.stop() }
    }

    private companion object {
        const val TAG = "VoiceGuide"
        const val AFTER_SPEAK_GAP_MS = 700L
    }
}
