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
    data object Schedules : Screen
    data object Settings : Screen
}

/** 화면 위에 띄우는 팝업 */
sealed interface Popup {
    /** 비밀번호 입력: login = 이 운전자로 바꾸기, set = 내 비밀번호 새로 정하기 */
    data class Pin(val name: String, val forSetting: Boolean, val error: String? = null) : Popup
    /** 운전자를 바꿨을 때 안 읽은 메시지: 확인 / 읽지 않음으로 표시 */
    data class Unread(val notes: List<Note>) : Popup
    /** 차량 관련 메모 → 가까운 서비스센터 안내 여부 */
    data class Service(val tags: List<String>, val note: String) : Popup
    /** 안내 한 줄 (일정 알림 등) */
    data class Notice(val title: String, val body: String) : Popup
}

/** 일정 입력 중인 내용 */
data class ScheduleDraft(
    val title: String = "", val place: String = "",
    val dayMillis: Long = startOfDay(System.currentTimeMillis()),
    val hour: Int = 9, val minute: Int = 0,
    val sentence: String = "",
)

fun startOfDay(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

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

    private val _popup = MutableStateFlow<Popup?>(null)
    val popup: StateFlow<Popup?> = _popup.asStateFlow()

    private val _scheduleDraft = MutableStateFlow(ScheduleDraft())
    val scheduleDraft: StateFlow<ScheduleDraft> = _scheduleDraft.asStateFlow()

    /** 받는 사람·화면에 쓰는 '모두' 이름 (렌터카면 '다음 운전자') */
    fun toAllLabel(): String = if (store.carType.value == CarType.RENTAL) "다음 운전자" else TO_ALL

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
        scope.launch {
            navi.routeFailed.collect { code ->
                Log.d(TAG, "서비스센터 경로 탐색 실패: $code")
                voice.speak("내비게이션에서 경로를 찾지 못했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
        // 뒷좌석 문 감지 (Vehicle SDK 준비 후)
        scope.launch {
            delay(1500)
            condition.startWatching()
        }
        // 일정 임박 알림: 30초마다 지금 운전자의 일정 확인
        scope.launch {
            while (true) {
                checkSchedules()
                delay(30_000)
            }
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
            offerService(h.notes)
        }
    }

    /** 출발 전 전달 화면: 읽지 않음으로 표시 → 다음에 다시 읽어 줌 */
    fun markHandoverUnread() {
        val h = _screen.value as? Screen.Handover ?: return
        store.markUnheard(h.notes.map { it.id }, store.driver.value)
        voice.stop()
        _screen.value = Screen.Home
    }

    // ---------------- 운전자 전환 시 메모 읽어 주기 ----------------

    /**
     * 드롭다운에서 운전자를 바꾸면, 새 운전자가 아직 안 들은 메모를 읽어 준다.
     * 메모마다 "언제, 누가" 남겼는지를 먼저 말하고 내용을 읽는다 (오래된 것부터).
     * 다 읽으면 들은 것으로 처리 → 출발할 때 같은 메모를 또 읽지 않음.
     */
    /** 드롭다운에서 운전자를 고름: 비공개 운전자면 비밀번호부터 묻는다 */
    fun requestSwitch(name: String) {
        if (name == store.driver.value) return
        val p = store.profile(name)
        if (p.private && p.pin != null) {
            speech.cancel(); voice.stop()
            _popup.value = Popup.Pin(name, forSetting = false)
            voice.speak("${name}님의 비밀번호 네 자리를 눌러 주세요.")
        } else switchDriver(name)
    }

    /** 비밀번호 팝업에서 4자리를 다 눌렀을 때 */
    fun submitPin(pin: String) {
        val p = _popup.value as? Popup.Pin ?: return
        if (p.forSetting) {
            store.setPin(p.name, pin)
            _popup.value = null
            voice.speak("비밀번호를 정했어요.")
            return
        }
        if (store.checkPin(p.name, pin)) {
            _popup.value = null
            switchDriver(p.name)
        } else {
            _popup.value = p.copy(error = "비밀번호가 맞지 않아요")
            voice.speak("비밀번호가 맞지 않아요.")
        }
    }

    fun startSetPin() { _popup.value = Popup.Pin(store.driver.value, forSetting = true) }

    fun closePopup() { voice.stop(); speech.cancel(); _popup.value = null }

    private fun switchDriver(name: String) {
        if (name == store.driver.value) return
        speech.cancel()
        voice.stop()
        store.setDriver(name)
        announceUnread(name, "${name}님으로 운전자를 바꿨어요.")
    }

    /** 렌터카: 새 운전자로 대여 시작 */
    fun startRental() {
        speech.cancel(); voice.stop()
        val name = store.startRentalDriver()
        announceUnread(name, "${name}님, 대여를 시작했어요.")
    }

    /**
     * 안 읽은 메시지만 읽어 주고 '확인 / 읽지 않음으로 표시' 팝업을 띄운다.
     * 확인을 누르기 전까지는 읽음 처리하지 않음 → 이미 확인한 메시지는 다시 읽지 않음.
     */
    private fun announceUnread(name: String, intro: String) {
        val notes = store.unheardFor(name).sortedBy { it.createdAt }
        if (notes.isEmpty()) {
            voice.speak("$intro 새로 온 메시지는 없어요.")
            checkSchedules()
            return
        }
        _popup.value = Popup.Unread(notes)
        val body = notes.joinToString(" ") { n ->
            "${spokenWhen(n.createdAt)}에 ${n.author}님이 남긴 메모입니다. ${n.text.trimEnd('.', ' ')}."
        }
        voice.speak("$intro 읽지 않은 메시지가 ${notes.size}개 있어요. $body")
    }

    /** 안 읽은 메시지 팝업: 확인 → 읽음 처리 / 읽지 않음으로 표시 → 다음에 다시 읽어 줌 */
    fun answerUnread(confirm: Boolean) {
        val u = _popup.value as? Popup.Unread ?: return
        voice.stop()
        _popup.value = null
        if (confirm) {
            store.markHeard(u.notes.map { it.id }, store.driver.value)
            offerService(u.notes)
        } else {
            voice.speak("읽지 않은 메시지로 남겨 둘게요.")
        }
    }

    // ---------------- 차량 관련 메모 → 서비스센터 안내 ----------------

    /** 들은 메모 중 차량 키워드가 있으면 서비스센터 안내 여부를 묻는다 (음성으로 '네/아니요' 답해도 됨) */
    private val offeredNotes = mutableSetOf<Long>()

    private fun offerService(notes: List<Note>) {
        val fresh = notes.filter { offeredNotes.add(it.id) }
        val hit = fresh.firstNotNullOfOrNull { n -> carTags(n.text).takeIf { it.isNotEmpty() }?.let { n to it } } ?: return
        navi.requestLocation()
        _popup.value = Popup.Service(hit.second, hit.first.text)
        voice.speak("차량 점검이 필요해 보이는 메시지예요. 가까운 서비스센터를 안내해 드릴까요? 네 또는 아니요로 말씀해 주세요.") {
            if (_popup.value !is Popup.Service) return@speak
            speech.listen { answer ->
                val a = answer.replace(" ", "")
                when {
                    listOf("아니", "괜찮", "됐어", "안해", "필요없").any { a.contains(it) } -> answerService(false)
                    listOf("네", "응", "예", "좋아", "안내", "그래", "해줘", "부탁").any { a.contains(it) } -> answerService(true)
                }
            }
        }
    }

    fun answerService(yes: Boolean) {
        if (_popup.value !is Popup.Service) return
        speech.cancel(); voice.stop()
        _popup.value = null
        if (!yes) { voice.speak("알겠어요."); return }
        val (center, km) = nearestServiceCenter(navi.location.value)
        val ok = navi.routeTo(center.lat, center.lon, center.name, center.address)
        val dist = km?.let { " 약 ${"%.1f".format(it)}킬로미터 거리예요." } ?: ""
        voice.speak(if (ok) "${center.name.substringBefore(" (")}로 길 안내를 시작할게요.$dist" else "내비게이션에 연결하지 못했어요.")
    }

    // ---------------- 일정 ----------------

    fun openSchedules() {
        speech.cancel(); voice.stop()
        _scheduleDraft.value = ScheduleDraft()
        _screen.value = Screen.Schedules
    }

    fun editSchedule(d: ScheduleDraft) { _scheduleDraft.value = d }

    /** 말로 일정 입력: "내일 오후 3시 강남역에서 치과 예약" */
    fun listenSchedule() {
        speech.cancel(); voice.stop()
        voice.speak("일정 이름과 장소, 날짜와 시간을 말씀해 주세요.") {
            if (_screen.value != Screen.Schedules) return@speak
            speech.listen { text -> applyScheduleSentence(text) }
        }
    }

    fun applyScheduleSentence(text: String) {
        val p = parseSchedule(text)
        val d = _scheduleDraft.value
        _scheduleDraft.value = d.copy(
            sentence = text,
            title = p.title ?: d.title, place = p.place ?: d.place,
            dayMillis = p.dayMillis ?: d.dayMillis, hour = p.hour ?: d.hour, minute = p.minute ?: d.minute,
        )
    }

    fun saveSchedule() {
        val d = _scheduleDraft.value
        if (d.title.isBlank()) return
        val at = d.dayMillis + d.hour * 3_600_000L + d.minute * 60_000L
        store.addSchedule(d.title.trim(), d.place.trim(), at)
        _scheduleDraft.value = ScheduleDraft(dayMillis = d.dayMillis)
        // 저장 안내를 끝까지 들려준 뒤에 임박 알림 확인 (곧 시작하는 일정이면 바로 알림)
        voice.speak("${spokenWhen(at)}, ${d.title.trim()} 일정을 저장했어요. 30분 전과 10분 전에 알려 드릴게요.") { checkSchedules() }
    }

    fun deleteSchedule(id: Long) = store.deleteSchedule(id)

    /** 지금 운전자의 일정이 30분·10분 안으로 다가오면 한 번씩 읽어 준다 */
    private fun checkSchedules() {
        val now = System.currentTimeMillis()
        val me = store.driver.value
        store.schedules.value.filter { it.owner == me && it.at > now }.forEach { sc ->
            val left = sc.at - now
            val step = SCHEDULE_ALERT_MINUTES.firstOrNull { (min, bit) -> left <= min * 60_000L && sc.notified and bit == 0 } ?: return@forEach
            // 이번 단계와 그보다 앞선 단계를 모두 알린 것으로 처리 (10분 전에 처음 확인했으면 30분 전 알림은 건너뜀)
            SCHEDULE_ALERT_MINUTES.filter { it.first >= step.first }.forEach { store.markScheduleNotified(sc.id, it.second) }
            val mins = ((left + 59_999) / 60_000).toInt()
            val where = if (sc.place.isNotBlank()) " 장소는 ${sc.place}예요." else ""
            voice.speak("${mins}분 뒤에 ${sc.title} 일정이 있어요.$where")
            if (_popup.value == null) _popup.value = Popup.Notice("${mins}분 뒤 일정", "${sc.title}" + if (sc.place.isNotBlank()) " · ${sc.place}" else "")
        }
    }

    fun openSettings() { speech.cancel(); voice.stop(); _screen.value = Screen.Settings }

    fun closeHandover() {
        val h = _screen.value as? Screen.Handover
        if (h != null) {
            store.markHeard(h.notes.map { it.id }, store.driver.value)
            if (h.report != null) store.saveParkReport(null)
        }
        voice.stop()
        _screen.value = Screen.Home
        if (h != null && _popup.value == null) offerService(h.notes)
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
            Screen.Schedules -> applyScheduleSentence(text)
            else -> if (_popup.value is Popup.Service) {
                val a = text.replace(" ", "")
                answerService(!listOf("아니", "괜찮").any { a.contains(it) })
            } else {
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
