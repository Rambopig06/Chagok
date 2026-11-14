package com.sciencecrew.chagok.voice

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.OnServerConnectionListener
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sciencecrew.chagok.BuildConfig

/**
 * 글레오(Pleos LLM)에게 메모를 보여 주고 해결책을 받는다.
 * Pleos LLM SDK 2.1.3.2. 공식 문서 페이지가 없어 클래스·메서드 이름은 SDK 파일(AAR)에서 확인.
 * 권한: pleos.car.permission.LLM_SERVICE
 *
 * 순서: ① 서버 모드 (Playground 클라이언트 ID·시크릿으로 앱 등록 성공 시)
 *       ② 기기 안 모드 (실차. 에뮬레이터는 "on-device LLM is disabled")
 *       ③ 둘 다 실패하면 호출한 쪽에서 시연용 예시 답변
 */
class GleoAssistant(context: Context) {

    /** 지금 어떤 글레오로 묻는지 (화면 표시·로그용) */
    enum class Source { SERVER, ON_DEVICE }

    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var requestId = 0

    private val clientId = BuildConfig.PLEOS_CLIENT_ID
    private val clientSecret = BuildConfig.PLEOS_CLIENT_SECRET

    // ① 서버 모드: 키가 있을 때만 만들고 앱 등록
    @Volatile private var serverReady = false
    @Volatile var serverStatus: String = if (clientId.isBlank()) "키 없음 (local.properties에 pleos.clientId/clientSecret 필요)" else "연결 중"
        private set
    private val server: LLM? = if (clientId.isBlank() || clientSecret.isBlank()) null else
        runCatching {
            LLM(appContext, Mode.SERVER).also { llm ->
                llm.initialize()
                llm.registerApp(clientId, clientSecret, object : OnServerConnectionListener {
                    override fun onConnected() {
                        serverReady = true
                        serverStatus = "연결됨"
                        Log.d(TAG, "글레오 서버 연결 성공")
                    }
                    override fun onFailed(message: String) {
                        serverReady = false
                        serverStatus = "연결 실패: $message"
                        Log.w(TAG, "글레오 서버 연결 실패: $message")
                    }
                })
            }
        }.onFailure { Log.e(TAG, "서버 모드 초기화 실패", it); serverStatus = "초기화 실패" }.getOrNull()

    // ② 기기 안 모드
    private val onDevice: LLM? = runCatching {
        LLM(appContext, Mode.ON_DEVICE).also { it.initialize() }
    }.onFailure { Log.e(TAG, "기기 안 모드 초기화 실패", it) }.getOrNull()

    /**
     * 질문을 보내고 답을 받는다. onUpdate(지금까지 답, 끝났는지, 어느 글레오인지), 실패 시 onError.
     * 응답이 일정 시간 없으면 실패로 처리 (답이 들어오는 중이면 연장).
     */
    fun ask(prompt: String, onUpdate: (String, Boolean, Source) -> Unit, onError: (String) -> Unit) {
        val id = ++requestId
        val useServer = server != null && serverReady
        val llm = if (useServer) server else onDevice
        val source = if (useServer) Source.SERVER else Source.ON_DEVICE
        val timeoutMs = if (useServer) SERVER_TIMEOUT_MS else ON_DEVICE_TIMEOUT_MS
        Log.d(TAG, "질문 보냄: $source (서버 상태: $serverStatus)")
        if (llm == null) { onError("글레오를 준비하지 못했어요"); return }

        var finished = false
        val timeout = Runnable {
            if (!finished && id == requestId) { finished = true; onError("글레오가 응답하지 않아요") }
        }
        main.postDelayed(timeout, timeoutMs)
        try {
            llm.generateContent(prompt, object : ResultListener {
                override fun onResponse(text: String, isFinal: Boolean) {
                    Log.d(TAG, "응답($source, final=$isFinal): ${text.take(80)}")
                    main.post {
                        if (finished || id != requestId) return@post
                        main.removeCallbacks(timeout)
                        if (isFinal) finished = true else main.postDelayed(timeout, timeoutMs)
                        onUpdate(text.trim(), isFinal, source)
                    }
                }
                override fun onError(message: String) {
                    Log.w(TAG, "LLM 오류($source): $message")
                    main.post {
                        if (finished || id != requestId) return@post
                        finished = true
                        main.removeCallbacks(timeout)
                        onError("글레오 오류: $message")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "LLM 요청 실패", e)
            finished = true
            main.removeCallbacks(timeout)
            onError("글레오에 연결하지 못했어요")
        }
    }

    fun cancel() { requestId++ }

    private companion object {
        const val TAG = "Gleo"
        const val SERVER_TIMEOUT_MS = 20_000L     // 서버는 네트워크라 조금 더 기다림
        const val ON_DEVICE_TIMEOUT_MS = 10_000L
    }
}
