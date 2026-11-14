package com.sciencecrew.chagok.chagok

import android.util.Log
import com.sciencecrew.chagok.driving.DrivingState
import com.sciencecrew.chagok.driving.DrivingStateMonitor
import com.sciencecrew.chagok.navi.NaviRepository
import com.sciencecrew.chagok.vehicle.Reading
import com.sciencecrew.chagok.vehicle.VehicleStateRepository
import com.sciencecrew.chagok.voice.GleoAssistant
import com.sciencecrew.chagok.voice.SpeechInput
import com.sciencecrew.chagok.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 지금 보여 줄 차곡 화면 (주행 중 단순 화면은 UiPolicy가 따로 정함) */
sealed interface Screen {
    data object Home : Screen
    data object Record : Screen
    data object AddTodo : Screen
    /** notes: 사람이 남긴 메모, report: 지난 주차 때 차곡이 자동으로 확인한 차량 상태 */
    data class Handover(val notes: List<Note>, val drivenKm: Float?, val report: ParkReport? = null) : Screen
    data object TripLog : Screen
    data class Arrive(val todo: ArrivalTodo, val placeName: String) : Screen
    data class NoteDetail(val noteId: Long) : Screen
}

/** 글레오 AI 도움 상태 */
sealed interface AiState {
    data object Idle : AiState
    data object Loading : AiState
    /** demo = 글레오가 응답하지 않아 미리 써 둔 시연용 예시 답변을 보여 주는 중 */
    data class Answer(val text: String, val done: Boolean, val demo: Boolean = false, val source: String? = null) : AiState
    data class Failed(val message: String) : AiState
}

/** 도착 할 일 입력 중인 내용 */
data class TodoDraft(val sentence: String = "", val place: String = "", val task: String = "", val forNextDriver: Boolean = false)

/**
 * 차곡의 흐름을 한곳에서 관리한다.
 * - 주차(P)로 바뀌면: 남김말 녹음 화면 + "남길 말씀이 있으신가요?"
 * - 주차에서 출발하면: 안 들은 메모가 있으면 전달 화면 + 읽어 주기
 * - 내비 도착 이벤트: 맞는 할 일이 있으면 도착 알림 화면 + 읽어 주기
 */
class ChagokController(
    val store: ChagokStore,
    private val vehicle: VehicleStateRepository,
    drivingMonitor: DrivingStateMonitor,
    private val navi: NaviRepository,
    private val voice: VoiceGuide,
    val speech: SpeechInput,
    private val gleo: GleoAssistant,
    private val condition: VehicleCondition,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** 남김말 녹음 중인 문장과 받는 사람 */
    private val _noteDraft = MutableStateFlow("")
    val noteDraft: StateFlow<String> = _noteDraft.asStateFlow()
    private val _recipient = MutableStateFlow(TO_ALL)
    val recipient: StateFlow<String> = _recipient.asStateFlow()

    private val _todoDraft = MutableStateFlow(TodoDraft())
    val todoDraft: StateFlow<TodoDraft> = _todoDraft.asStateFlow()

    /** 주행 중 "말로 남기기" 결과 안내 한 줄 */
    private val _drivingNotice = MutableStateFlow<String?>(null)
    val drivingNotice: StateFlow<String?> = _drivingNotice.asStateFlow()

    private var lastState: DrivingState? = null

    /** 주차할 때 안내한 하차 안전 체크 문장 (남김말 화면 위에 표시) */
    private val _parkAlerts = MutableStateFlow<List<String>>(emptyList())
    val parkAlerts: StateFlow<List<String>> = _parkAlerts.asStateFlow()

    // ---- 운행일지: 지금 운행 중인 기록 ----
    private var tripStartAt: Long? = null
    private var tripStartKm: Float? = null
    private var tripStartBattery: Int? = null
    private var tripIntegratedKm = 0f
    private var tripJob: Job? = null

    init {
        scope.launch {
            drivingMonitor.drivingState.collect { now ->
                val prev = lastState
                lastState = now
                if (prev == null) return@collect          // 앱 시작 직후 상태는 건너뜀
                onDrivingChanged(prev, now)
            }
        }
        scope.launch {
            navi.arrived.collect { name -> onArrived(name) }
        }
        // 뒷좌석 문 감지 (Vehicle SDK 준비 후)
        scope.launch {
            delay(1500)
            condition.startWatching()
        }
        condition.onRearChecked = {
            scope.launch {
                _parkAlerts.value = _parkAlerts.value.filterNot { it.startsWith("뒷좌석") }
                Log.d(TAG, "뒷좌석 확인됨")
            }
        }
    }

    private fun currentKm(): Float? = (vehicle.distanceKm.value as? Reading.Value)?.value

    private fun onDrivingChanged(prev: DrivingState, now: DrivingState) {
        Log.d(TAG, "운전 상태 $prev → $now")
        when {
            now == DrivingState.PARKED && prev != DrivingState.PARKED -> onParked()
            prev == DrivingState.PARKED && now != DrivingState.PARKED -> {
                condition.onDeparted()
                condition.startWatching()
                startTrip()
                startHandover()
            }
        }
    }

    // ---------------- 주차: 운행 마감 + 차량 상태 기록 + 하차 안전 체크 ----------------

    private fun onParked() {
        endTrip()
        scope.launch {
            val snap = condition.snapshot()
            val report = ParkReport(System.currentTimeMillis(), store.driver.value,
                snap.openDoors, snap.openWindows, snap.warnings, snap.batteryPct, snap.chargePortOpen)
            store.saveParkReport(report)
            // 하차 안전 체크: 지금 내리는 사람에게 바로 알려 줄 것
            val alerts = buildList {
                if (condition.beginRearCheck()) add("뒷좌석 문이 열렸었어요. 뒷좌석에 두고 내린 물건이나 아이는 없는지 확인해 주세요.")
                if (snap.openWindows.isNotEmpty()) add("${iGa(snap.openWindows.joinToString(", "))} 열려 있어요.")
                val openDoors = snap.openDoors.filterNot { it == "운전석 문" }
                if (openDoors.isNotEmpty()) add("${iGa(openDoors.joinToString(", "))} 열려 있어요.")
                if (snap.chargePortOpen) add("충전구가 열려 있어요.")
            }
            Log.d(TAG, "주차 안전 체크: $alerts")
            startRecord(auto = true, alerts = alerts)
        }
    }

    // ---------------- 운행일지 ----------------

    private fun startTrip() {
        tripStartAt = System.currentTimeMillis()
        tripStartKm = currentKm()
        tripIntegratedKm = 0f
        tripStartBattery = null
        scope.launch { tripStartBattery = condition.batteryPct() }
        tripJob?.cancel()
        // 계기판 주행 거리가 안 바뀌는 환경(에뮬레이터 등)을 대비해 속도로도 거리를 계산
        tripJob = scope.launch {
            while (true) {
                delay(1000)
                val kmh = (vehicle.speedKmh.value as? Reading.Value)?.value ?: 0f
                tripIntegratedKm += kmh / 3600f
            }
        }
    }

    private fun endTrip() {
        val start = tripStartAt ?: return
        tripStartAt = null
        tripJob?.cancel(); tripJob = null
        val driver = store.driver.value
        val odoKm = currentKm()?.let { end -> tripStartKm?.let { (end - it).coerceAtLeast(0f) } } ?: 0f
        val km = maxOf(odoKm, tripIntegratedKm)
        val end = System.currentTimeMillis()
        val startBat = tripStartBattery
        if (km < 0.05f && end - start < 60_000) return       // 잠깐 기어만 바꾼 경우는 기록하지 않음
        scope.launch {
            val used = startBat?.let { s -> condition.batteryPct()?.let { e -> (s - e).coerceAtLeast(0) } }
            store.addTrip(Trip(driver, start, end, km, used))
            Log.d(TAG, "운행 기록: $driver ${"%.1f".format(km)}km, ${(end - start) / 60000}분, 배터리 ${used}% 사용")
        }
    }

    fun openTripLog() { speech.cancel(); _screen.value = Screen.TripLog }
    fun closeTripLog() { _screen.value = Screen.Home }

    // ---------------- 남김말 ----------------

    fun startRecord(auto: Boolean = false, alerts: List<String> = emptyList()) {
        speech.cancel()
        _noteDraft.value = ""
        _recipient.value = TO_ALL
        if (!auto) _parkAlerts.value = emptyList() else _parkAlerts.value = alerts
        _screen.value = Screen.Record
        val ask = if (auto) "다음 운전자께 남길 말씀이 있으신가요?" else "남기실 말씀을 해 주세요."
        voice.speak((alerts + ask).joinToString(" ")) { listenNote() }
    }

    fun listenNote() {
        if (_screen.value != Screen.Record) return
        speech.listen { text -> _noteDraft.value = text }
    }

    /** 다시 말하기: 이전 내용을 지우고 "다시 말씀해 주세요" 안내 후 새로 듣기 */
    fun retryNote() {
        speech.cancel()
        voice.stop()
        _noteDraft.value = ""
        voice.speak("다시 말씀해 주세요.") { listenNote() }
    }

    fun editNote(text: String) { _noteDraft.value = text }
    fun pickRecipient(name: String) { _recipient.value = name }

    fun saveNote() {
        val text = _noteDraft.value.trim()
        if (text.isEmpty()) return
        speech.cancel()
        store.addNote(text, _recipient.value, currentKm())
        val to = if (_recipient.value == TO_ALL) "다음 운전자께" else "${_recipient.value}님께"
        voice.speak("남겨 두었어요. $to 전해 드릴게요.")
        _screen.value = Screen.Home
    }

    // ---------------- 출발 전 전달 ----------------

    private fun startHandover() {
        speech.cancel()
        val driver = store.driver.value
        val notes = store.unheardFor(driver)
        // 지난 주차 때 차곡이 확인한 차량 상태 (다른 사람이 주차했을 때만 전달)
        val report = store.parkReport.value?.takeIf { it.items().isNotEmpty() && it.driver != driver }
        _parkAlerts.value = emptyList()
        if (notes.isEmpty() && report == null) {
            if (_screen.value == Screen.Record || _screen.value == Screen.AddTodo) _screen.value = Screen.Home
            return
        }
        val now = currentKm()
        val since = notes.mapNotNull { it.odometerKm }.minOrNull()
        val driven = if (now != null && since != null) (now - since).coerceAtLeast(0f) else null
        _screen.value = Screen.Handover(notes, driven, report)
        readHandover()
    }

    fun readHandover() {
        val h = _screen.value as? Screen.Handover ?: return
        val body = h.notes.sortedBy { it.createdAt }.joinToString(" ") {
            "${spokenWhen(it.createdAt)}에 ${it.author}님이 남긴 메모입니다. ${it.text.trimEnd('.', ' ')}."
        }
        val km = h.drivenKm?.let { if (it >= 1f) " 그사이 ${"%.0f".format(it)}킬로미터를 주행했어요." else "" } ?: ""
        val todos = store.pendingTodos().size.let { if (it > 0) " 도착하면 알려 드릴 일이 ${it}개 있어요." else "" }
        val driver = store.driver.value
        val car = h.report?.let { r ->
            " 차곡이 ${spokenWhen(r.at)} 주차 때 확인한 차량 상태예요. " + r.items().joinToString(" ") { "$it." }
        } ?: ""
        val people = if (h.notes.isEmpty()) "" else "이전 운전자께서 남긴 말이 있습니다. $body"
        voice.speak("$people$car$km$todos".trim()) {
            store.markHeard(h.notes.map { it.id }, driver)
            if (h.report != null) store.saveParkReport(null)
        }
    }

    // ---------------- 운전자 전환 시 메모 읽어 주기 ----------------

    /**
     * 드롭다운에서 운전자를 바꾸면, 새 운전자가 아직 안 들은 메모를 읽어 준다.
     * 메모마다 "언제, 누가" 남겼는지를 먼저 말하고 내용을 읽는다 (오래된 것부터).
     * 다 읽으면 들은 것으로 처리 → 출발할 때 같은 메모를 또 읽지 않음.
     */
    fun switchDriver(name: String) {
        if (name == store.driver.value) return
        speech.cancel()
        voice.stop()
        store.setDriver(name)
        val notes = store.unheardFor(name).sortedBy { it.createdAt }
        val intro = "${name}님으로 운전자를 바꿨어요."
        if (notes.isEmpty()) {
            voice.speak("$intro 새로 남겨진 메모는 없어요.")
            return
        }
        val body = notes.joinToString(" ") { n ->
            "${spokenWhen(n.createdAt)}에 ${n.author}님이 남긴 메모입니다. ${n.text.trimEnd('.', ' ')}."
        }
        voice.speak("$intro 남겨진 메모가 ${notes.size}개 있어요. $body") {
            store.markHeard(notes.map { it.id }, name)
        }
    }

    fun closeHandover() {
        val h = _screen.value as? Screen.Handover
        if (h != null) {
            store.markHeard(h.notes.map { it.id }, store.driver.value)
            if (h.report != null) store.saveParkReport(null)
        }
        voice.stop()
        _screen.value = Screen.Home
    }

    // ---------------- 도착 할 일 ----------------

    fun startAddTodo() {
        speech.cancel()
        _todoDraft.value = TodoDraft()
        _screen.value = Screen.AddTodo
        voice.speak("어디에 도착하면 무엇을 알려 드릴까요?") { listenTodo() }
    }

    fun listenTodo() {
        if (_screen.value != Screen.AddTodo) return
        speech.listen { text -> applyTodoSentence(text) }
    }

    /** 다시 말하기: 이전 내용을 지우고 안내 후 새로 듣기 */
    fun retryTodo() {
        speech.cancel()
        voice.stop()
        _todoDraft.value = TodoDraft(forNextDriver = _todoDraft.value.forNextDriver)
        voice.speak("다시 말씀해 주세요.") { listenTodo() }
    }

    fun applyTodoSentence(text: String) {
        val parsed = parseArrivalTodo(text)
        _todoDraft.value = _todoDraft.value.copy(
            sentence = text,
            place = parsed?.first ?: "",
            task = parsed?.second ?: text,
        )
    }

    /** 장소를 지금 내비 목적지로 바꾸기 */
    fun useNaviDestination() {
        val dest = navi.info.value.destinationName ?: return
        _todoDraft.value = _todoDraft.value.copy(place = dest)
    }

    fun setTodoForNext(forNext: Boolean) { _todoDraft.value = _todoDraft.value.copy(forNextDriver = forNext) }

    fun saveTodo() {
        val d = _todoDraft.value
        if (d.place.isBlank() || d.task.isBlank()) return
        speech.cancel()
        store.addTodo(d.place, d.task, d.forNextDriver)
        voice.speak("${d.place}에 도착하면 알려 드릴게요.")
        _screen.value = Screen.Home
    }

    fun cancel() {
        speech.cancel()
        voice.stop()
        _screen.value = Screen.Home
    }

    // ---------------- 도착 알림 ----------------

    fun onArrived(placeName: String) {
        val todo = store.todoFor(placeName)
        Log.d(TAG, "도착: $placeName → 할 일 ${todo?.task}")
        if (todo == null) return
        speech.cancel()
        _screen.value = Screen.Arrive(todo, placeName)
        voice.speak("${placeName}에 도착했습니다. ${todo.task}, 잊지 마세요.")
    }

    fun finishArrive(done: Boolean) {
        val a = _screen.value as? Screen.Arrive ?: return
        if (done) store.completeTodo(a.todo.id)
        voice.stop()
        _screen.value = Screen.Home
    }

    // ---------------- 메모 상세: 삭제 · AI 도움 ----------------

    private val _ai = MutableStateFlow<AiState>(AiState.Idle)
    val ai: StateFlow<AiState> = _ai.asStateFlow()

    fun openNote(id: Long) {
        speech.cancel()
        gleo.cancel()
        _ai.value = AiState.Idle
        _screen.value = Screen.NoteDetail(id)
    }

    fun closeNote() {
        gleo.cancel()
        voice.stop()
        _ai.value = AiState.Idle
        _screen.value = Screen.Home
    }

    fun deleteNote(id: Long) {
        val n = store.note(id) ?: return
        if (!store.canDelete(n, store.driver.value)) return
        store.deleteNote(id)
        voice.speak("메모를 지웠어요.")
        closeNote()
    }

    /** 글레오에게 메모를 보여 주고 해결책 받기 (차량 상태도 함께 알려 줌) */
    fun askGleo(id: Long) {
        val n = store.note(id) ?: return
        val km = currentKm()?.let { "현재 누적 주행 거리는 ${"%.0f".format(it)}km입니다. " } ?: ""
        val prompt = """
            당신은 현대자동차그룹 차량의 AI 비서 '글레오'입니다.
            가족이 같은 차를 번갈아 쓰며 차 안에 남긴 메모를 보고, 다음 운전자가 무엇을 하면 좋을지 해결책을 제안하세요.
            정비·점검·서비스 예약이 필요해 보이면 그렇게 안내하고, 안전과 관련된 내용이면 운전 전에 확인할 점을 먼저 말하세요.
            한국어 존댓말로, 3문장 이내로, 운전 중에도 듣기 쉽게 짧게 답하세요.
            ${km}메모 작성자: ${n.author}님. 메모 내용: "${n.text}"
        """.trimIndent()
        _ai.value = AiState.Loading
        gleo.ask(prompt,
            onUpdate = { text, done, source ->
                val label = if (source == GleoAssistant.Source.SERVER) "글레오 서버 응답" else "글레오 기기 안 응답"
                _ai.value = AiState.Answer(text, done, source = label)
                if (done && text.isNotBlank()) voice.speak(text)
            },
            onError = { msg ->
                // 글레오 응답 실패 → 시연용 예시 답변 (화면에 "시연용 예시 답변"으로 표시)
                Log.d(TAG, "글레오 실패($msg) → 시연용 예시 답변")
                val demo = DemoAdvice.forNote(n.text)
                _ai.value = AiState.Answer(demo, done = true, demo = true)
                voice.speak(demo)
            },
        )
    }

    /** 시연용: 마이크 대신 "이 문장을 말했다"고 처리 (debug 빌드의 DemoReceiver에서만 호출) */
    fun demoSpeech(text: String) {
        speech.cancel()
        when (_screen.value) {
            Screen.Record -> _noteDraft.value = text
            Screen.AddTodo -> applyTodoSentence(text)
            else -> {
                store.addNote(text, TO_ALL, currentKm())
                _drivingNotice.value = "남겨 두었어요"
                voice.speak("남겨 두었어요.")
            }
        }
    }

    // ---------------- 주행 중 말로 남기기 ----------------

    fun driveNote() {
        _drivingNotice.value = "말씀하세요"
        voice.speak("말씀하세요.") {
            speech.listen { text ->
                store.addNote(text, TO_ALL, currentKm())
                _drivingNotice.value = "남겨 두었어요"
                voice.speak("남겨 두었어요.")
            }
        }
    }

    private companion object { const val TAG = "Chagok" }
}

/**
 * 귀로 듣기 좋은 시각 표현.
 * 예) "오늘 오후 3시 20분", "어제 오전 9시", "그저께 저녁…" 대신 "11월 9일 오후 6시 5분"
 */
fun spokenWhen(t: Long, now: Long = System.currentTimeMillis()): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val today = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayDiff = Math.floorDiv(today - t, 86_400_000L) + 1     // 오늘 0, 어제 1, …
    val day = when {
        t >= today -> "오늘"
        dayDiff == 1L -> "어제"
        dayDiff == 2L -> "그저께"
        else -> "${c.get(java.util.Calendar.MONTH) + 1}월 ${c.get(java.util.Calendar.DAY_OF_MONTH)}일"
    }
    val h24 = c.get(java.util.Calendar.HOUR_OF_DAY)
    val m = c.get(java.util.Calendar.MINUTE)
    // 귀로 들었을 때 자연스러운 시간대 이름
    val part = when (h24) {
        in 0..5 -> "새벽"
        in 6..11 -> "오전"
        12 -> "낮"
        in 13..17 -> "오후"
        else -> "저녁"
    }
    val h12 = (h24 % 12).let { if (it == 0) 12 else it }
    return "$day $part ${h12}시" + if (m > 0) " ${m}분" else ""
}
