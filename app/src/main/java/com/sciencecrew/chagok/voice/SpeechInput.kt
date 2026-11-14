package com.sciencecrew.chagok.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 사용자가 말을 "완전히" 끝낼 때까지 듣고, 받아 적은 문장을 돌려준다.
 * Pleos SpeechToText SDK, ON_DEVICE 모드. 마이크는 SDK(Pleos 서비스)가 켠다.
 * 권한: pleos.car.permission.STT_SERVICE
 *
 * Pleos STT는 말이 잠깐 끊기면 스스로 한 문장을 끝내 버린다. 그래서
 *  - 한 문장이 끝나도 바로 다시 듣기를 이어 가고, 받아 적은 조각을 이어 붙인다.
 *  - 마지막으로 소리가 난 뒤 2초 동안 조용하면 그때 듣기를 끝낸다.
 *  - 소리 크기(RMS)로 말하는 중인지 판단한다. 에뮬레이터 측정값: 조용할 때 90~170, 말할 때 3000~5500.
 */
class SpeechInput(context: Context) {

    sealed interface Status {
        data object Preparing : Status
        data object Idle : Status
        data object Listening : Status
        data class Error(val message: String) : Status
    }

    private val main = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow<Status>(Status.Preparing)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 듣는 동안 실시간으로 받아 적는 문장 (앞 조각 + 지금 조각) */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** 지금 소리 크기 0..1 (화면의 움직이는 파형용) */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false

    // ---- 한 번 듣기(세션) 상태 ----
    private var session = 0
    private var onFinal: ((String) -> Unit)? = null
    private var committed = ""          // 이미 끝난 문장 조각들
    private var current = ""            // 지금 받아 적는 조각
    private var hasSpoken = false
    private var startedAt = 0L
    private var lastVoiceAt = 0L
    private var maxRms = 0.0
    private var rmsCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (_status.value !is Status.Listening) return
            val now = SystemClock.elapsedRealtime()
            // 잡음이 "섹스" 같은 1~2글자로 잘못 인식되는 경우가 있어, 3글자 이상일 때만 말한 것으로 봄
            val hasText = join(committed, current).replace(" ", "").length >= MIN_TEXT_CHARS
            when {
                // 받아 적은 글자가 있고, 마지막 소리 뒤 2초 조용 → 말이 끝남
                hasText && now - lastVoiceAt >= SILENCE_END_MS -> finish("말이 끝남(2초 조용)")
                // 글자가 하나도 없으면 소리가 나도 끝내지 않음. 마지막 소리 뒤 8초 조용하면 포기
                !hasText && now - maxOf(startedAt, lastVoiceAt) >= NO_SPEECH_MS -> finish("말이 없음")
                now - startedAt >= MAX_LISTEN_MS -> finish("최대 시간")
                else -> main.postDelayed(this, 200)
            }
        }
    }

    private val listener = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() {}
        override fun onUpdated(text: String, isFinal: Boolean) {
            main.post {
                if (_status.value !is Status.Listening) return@post
                if (text.isNotBlank()) {
                    current = text.trim()
                    markVoice()
                }
                if (isFinal) {
                    // 한 조각이 끝남 → 이어 붙이고 계속 듣기
                    committed = join(committed, current)
                    current = ""
                }
                _partial.value = join(committed, current)
            }
        }
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}
        override fun onEndedRecognition() {
            main.post { restartIfStillListening() }
        }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "STT 오류 $errorType")
            main.post {
                if (_status.value !is Status.Listening) return@post
                if (errorType == ErrorType.MIC_ERROR || errorType == ErrorType.PERMISSION_DENIED) {
                    fail(errorText(errorType))
                } else {
                    restartIfStillListening()   // 무음 시간 초과 등은 계속 듣기
                }
            }
        }
        override fun onUpdatedRms(rms: Double) {
            main.post {
                if (_status.value !is Status.Listening) return@post
                _level.value = ((rms - 150.0) / 4000.0).coerceIn(0.0, 1.0).toFloat()
                if (rms > maxRms) maxRms = rms
                if (++rmsCount % 20 == 0) Log.d(TAG, "소리 크기 rms=%.0f (이번 듣기 최대 %.0f)".format(rms, maxRms))
                // 듣기 시작 직후는 안내 음성(스피커) 여운일 수 있어 무시
                val warm = SystemClock.elapsedRealtime() - startedAt > IGNORE_START_MS
                if (warm && rms > VOICE_RMS) markVoice()
            }
        }
    }

    init {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.d(TAG, "STT 초기화 성공")
                stt.setLocale(Locale.KOREA)
                stt.addListener(listener)
                ready = true
                _status.value = Status.Idle
            }
            override fun onFailure(errorType: ErrorType) {
                Log.e(TAG, "STT 초기화 실패 $errorType")
                _status.value = Status.Error("음성 인식 준비 실패")
            }
        })
    }

    /** 듣기 시작. 말을 마치고 2초 조용하면 onResult로 받아 적은 문장 전달 */
    fun listen(onResult: (String) -> Unit) {
        if (!ready) { _status.value = Status.Error("음성 인식 준비 중이에요"); return }
        // 이미 듣는 중이면 이전 듣기를 확실히 끊고 새로 시작 (다시 말하기)
        val wasListening = _status.value is Status.Listening
        if (wasListening) runCatching { stt.stop() }
        session++
        onFinal = onResult
        committed = ""; current = ""; hasSpoken = false; maxRms = 0.0; rmsCount = 0
        startedAt = SystemClock.elapsedRealtime(); lastVoiceAt = startedAt
        _partial.value = ""
        _level.value = 0f
        _status.value = Status.Listening
        Log.d(TAG, "듣기 시작 (session=$session)")
        val s = session
        if (wasListening) {
            main.postDelayed({ if (s == session) runCatching { stt.request() } }, 300)
        } else {
            stt.request()
        }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 200)
    }

    fun cancel() {
        main.removeCallbacks(ticker)
        onFinal = null
        runCatching { stt.stop() }
        _level.value = 0f
        if (_status.value is Status.Listening) _status.value = Status.Idle
    }

    private fun markVoice() {
        hasSpoken = true
        lastVoiceAt = SystemClock.elapsedRealtime()
    }

    /** SDK가 한 문장을 끝냈지만 사용자는 아직 말하는 중일 수 있음 → 다시 듣기 */
    private fun restartIfStillListening() {
        if (_status.value !is Status.Listening) return
        val s = session
        main.postDelayed({
            if (s == session && _status.value is Status.Listening) runCatching { stt.request() }
        }, 100)
    }

    private fun finish(reason: String) {
        main.removeCallbacks(ticker)
        val raw = join(committed, current)
        val text = if (raw.replace(" ", "").length >= MIN_TEXT_CHARS) raw else ""
        Log.d(TAG, "듣기 끝: $reason → \"$text\" (소리 크기 최대 %.0f)".format(maxRms))
        session++                       // 이후 늦게 오는 콜백 무시
        runCatching { stt.stop() }
        _level.value = 0f
        val cb = onFinal
        onFinal = null
        if (text.isBlank()) {
            _status.value = Status.Error("말소리를 못 들었어요")
        } else {
            _status.value = Status.Idle
            _partial.value = text
            cb?.invoke(text)
        }
    }

    private fun fail(message: String) {
        main.removeCallbacks(ticker)
        session++
        runCatching { stt.stop() }
        onFinal = null
        _level.value = 0f
        _status.value = Status.Error(message)
    }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

    private fun errorText(e: ErrorType) = when (e) {
        ErrorType.MIC_ERROR -> "마이크 입력 오류"
        ErrorType.PERMISSION_DENIED -> "음성 인식 권한이 없어요"
        else -> "음성 인식 오류"
    }

    private companion object {
        const val TAG = "SpeechInput"
        const val SILENCE_END_MS = 2_000L   // 말 끝난 뒤 이만큼 조용하면 끝
        const val NO_SPEECH_MS = 8_000L     // 아예 말이 없으면 이만큼 기다리고 끝
        const val MAX_LISTEN_MS = 30_000L   // 한 번에 최대 듣기 시간
        const val VOICE_RMS = 600.0         // 이보다 크면 말하는 중으로 봄
        const val IGNORE_START_MS = 600L    // 듣기 시작 직후 무시하는 시간 (안내 음성 여운)
        const val MIN_TEXT_CHARS = 3        // 이보다 짧은 인식 결과는 잡음으로 봄
    }
}
