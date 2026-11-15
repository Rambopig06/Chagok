package com.sciencecrew.chagok.chagok

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 처음 설치했을 때의 기본 운전자 목록 (이후에는 추가한 목록이 저장됨) */
val DEFAULT_DRIVERS = listOf("아빠", "엄마")
const val TO_ALL = "가족 모두"

/** 다음 운전자에게 남긴 말 */
data class Note(
    val id: Long,
    val text: String,
    val author: String,
    val recipient: String,          // TO_ALL 또는 가족 이름
    val createdAt: Long,
    val odometerKm: Float?,         // 남길 때 주행 거리 → 다음 출발 때 "그사이 주행" 계산
    val heardBy: Set<String> = emptySet(),
    /** 렌터카 모드에서 남긴 메모 (가정용·법인 메모와 섞이지 않게 나눠 보여 줌) */
    val rental: Boolean = false,
)

/** 운행 한 번 (출발 → 주차): 운전자별 운행일지·비용 나누기에 사용 */
data class Trip(
    val driver: String,
    val startAt: Long,
    val endAt: Long,
    val km: Float,
    val batteryUsedPct: Int?,
) {
    val minutes: Int get() = ((endAt - startAt) / 60_000L).toInt()
}

/** 도착하면 알려 줄 일 */
data class ArrivalTodo(
    val id: Long,
    val place: String,
    val task: String,
    val author: String,
    val forNextDriver: Boolean,
    val createdAt: Long,
    val done: Boolean = false,
    val rental: Boolean = false,
)

/** 차량 종류: 렌터카는 운전자를 일회용(운전자1, 운전자2 …)으로 만들고, 나머지는 최대 10명까지 등록 */
enum class CarType(val label: String) { FAMILY("가정용"), BUSINESS("법인 차량"), RENTAL("렌터카") }

/**
 * 운전자 계정 설정.
 * pin: 숫자 4자리 (없으면 null), private: 비공개면 이 운전자로 바꿀 때 비밀번호를 묻고, 이 사람에게 온 메모 내용을 다른 사람에게 가림
 * temp: 렌터카 일회용 운전자 (새 운전자가 대여를 시작하면 지워짐)
 */
data class DriverProfile(
    val pin: String? = null, val private: Boolean = false, val temp: Boolean = false,
    /** 이 운전자가 등록된 때. 그 전에 남겨진 메모는 '안 읽은 메시지'로 쏟아지지 않게 함 */
    val since: Long = 0L,
)

/** 메모·할 일·현재 운전자를 기기에 저장 (앱을 다시 켜도 유지) */
class ChagokStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("chagok", Context.MODE_PRIVATE)

    private val _carType = MutableStateFlow(
        runCatching { CarType.valueOf(prefs.getString("carType", null) ?: "FAMILY") }.getOrDefault(CarType.FAMILY))
    val carType: StateFlow<CarType> = _carType.asStateFlow()

    /** 운전자별 비밀번호·공개 여부 */
    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<Map<String, DriverProfile>> = _profiles.asStateFlow()
    fun profile(name: String): DriverProfile = _profiles.value[name] ?: DriverProfile()

    /** 이 차를 쓰는 운전자 목록 (추가 가능, 기기에 저장) */
    private val _drivers = MutableStateFlow(loadDrivers())
    val drivers: StateFlow<List<String>> = _drivers.asStateFlow()

    private val _driver = MutableStateFlow(prefs.getString("driver", null)?.takeIf { it in _drivers.value } ?: _drivers.value.first())
    val driver: StateFlow<String> = _driver.asStateFlow()

    /** 지금 차량 종류에서 고를 수 있는 운전자 (렌터카면 일회용 운전자만) */
    fun visibleDrivers(list: List<String> = _drivers.value): List<String> {
        val rental = _carType.value == CarType.RENTAL
        return list.filter { profile(it).temp == rental }
    }

    /**
     * 운전자 추가. 성공하면 null, 안 되면 이유를 돌려줌.
     * 이름 끝의 "님"은 떼고 저장 (화면에서 "○○님"으로 붙여 보여 줌).
     */
    fun addDriver(raw: String): String? {
        val name = raw.trim().removeSuffix("님").trim()
        return when {
            name.isEmpty() -> "이름을 입력해 주세요"
            name.length > 8 -> "이름은 8글자까지 쓸 수 있어요"
            name == TO_ALL || name in _drivers.value -> "이미 있는 이름이에요"
            _carType.value == CarType.RENTAL -> "렌터카는 '새 운전자'로 대여를 시작해 주세요"
            visibleDrivers().size >= MAX_DRIVERS -> "운전자는 ${MAX_DRIVERS}명까지 추가할 수 있어요"
            else -> {
                saveDrivers(_drivers.value + name)
                saveProfiles(_profiles.value + (name to DriverProfile(since = System.currentTimeMillis())))
                null
            }
        }
    }

    /** 운전자 지우기 (지금 운전자는 지울 수 없음). 그 사람이 남긴 메모는 남겨 둠 */
    fun removeDriver(name: String) {
        if (name == _driver.value) return
        saveDrivers(_drivers.value - name)
        saveProfiles(_profiles.value - name)
        saveSchedules(_schedules.value.filterNot { it.owner == name })
        // 그 사람에게만 온 메모도 지움 (같은 이름으로 다시 만들어 비공개 메모를 보는 것 방지)
        saveNotes(allNotes.filterNot { it.recipient == name })
    }

    /**
     * 렌터카: 새 운전자로 대여 시작 → "운전자N"을 만들고 지금 운전자로 바꿈.
     * 이전 일회용 운전자는 계정(비밀번호·일정)째 지움. 다음 운전자에게 남긴 메모는 그대로 전달됨.
     */
    fun startRentalDriver(): String {
        var seq = prefs.getInt("rentalSeq", 0) + 1
        while ("운전자$seq" in _drivers.value) seq++          // 가족 운전자와 이름이 겹치지 않게
        prefs.edit().putInt("rentalSeq", seq).apply()
        val name = "운전자$seq"
        val old = _drivers.value.filter { profile(it).temp }
        // 새 손님은 바로 앞 손님이 대여한 뒤에 남긴 메모만 받음 (더 오래된 손님들의 메모는 전달하지 않음)
        val since = old.maxOfOrNull { profile(it).since } ?: System.currentTimeMillis()
        saveDrivers(_drivers.value - old.toSet() + name)
        saveProfiles(_profiles.value - old.toSet() + (name to DriverProfile(temp = true, since = since)))
        saveSchedules(_schedules.value.filterNot { it.owner in old })
        setDriver(name)
        return name
    }

    /** 차량 종류 바꾸기. 렌터카로 바꾸면 일회용 운전자를 새로 만들고, 렌터카에서 나오면 일회용 운전자를 지움 */
    fun setCarType(t: CarType) {
        if (t == _carType.value) return
        _carType.value = t
        prefs.edit().putString("carType", t.name).apply()
        refreshMode()
        if (t == CarType.RENTAL) {
            startRentalDriver()
        } else {
            val temps = _drivers.value.filter { profile(it).temp }
            val keep = (_drivers.value - temps.toSet()).ifEmpty { DEFAULT_DRIVERS }
            saveDrivers(keep)
            saveProfiles(_profiles.value - temps.toSet())
            // 비공개 운전자는 비밀번호 없이 고르지 않도록 공개 운전자 먼저
            if (_driver.value !in keep) setDriver(keep.firstOrNull { !profile(it).private } ?: keep.first())
        }
    }

    /** 비밀번호 설정 (null이면 지움 → 공개로 바뀜) */
    fun setPin(name: String, pin: String?) {
        val p = profile(name)
        saveProfiles(_profiles.value + (name to p.copy(pin = pin, private = if (pin == null) false else p.private)))
    }

    /** 공개/비공개. 비공개는 비밀번호가 있어야 함 */
    fun setPrivate(name: String, private: Boolean) {
        val p = profile(name)
        if (private && p.pin == null) return
        saveProfiles(_profiles.value + (name to p.copy(private = private)))
    }

    fun checkPin(name: String, pin: String) = profile(name).pin == pin

    /** 이 메모 내용을 지금 보는 사람에게 가려야 하나 (비공개 운전자에게 온 메모) */
    fun hiddenFrom(n: Note, viewer: String): Boolean =
        n.recipient != TO_ALL && profile(n.recipient).private && viewer != n.recipient && viewer != n.author

    private fun saveDrivers(list: List<String>) {
        _drivers.value = list
        prefs.edit().putString("drivers", JSONArray(list).toString()).apply()
    }

    private fun saveProfiles(map: Map<String, DriverProfile>) {
        _profiles.value = map
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONObject().apply { v.pin?.let { put("pin", it) }; put("private", v.private); put("temp", v.temp); put("since", v.since) }) }
        prefs.edit().putString("profiles", o.toString()).apply()
    }

    private fun loadProfiles(): Map<String, DriverProfile> = runCatching {
        val o = JSONObject(prefs.getString("profiles", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            DriverProfile(if (v.has("pin")) v.getString("pin") else null, v.optBoolean("private"), v.optBoolean("temp"), v.optLong("since"))
        }
    }.getOrDefault(emptyMap())

    private fun loadDrivers(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("drivers", null) ?: return@runCatching DEFAULT_DRIVERS)
        (0 until arr.length()).map { arr.getString(it) }.ifEmpty { DEFAULT_DRIVERS }
    }.getOrDefault(DEFAULT_DRIVERS)

    /** 저장된 전체 메모·할 일. 화면에는 지금 차량 종류(렌터카 / 그 외)에 맞는 것만 보여 줌 */
    private var allNotes: List<Note> = loadNotes()
    private var allTodos: List<ArrivalTodo> = loadTodos()
    private fun rentalNow() = _carType.value == CarType.RENTAL

    private val _notes = MutableStateFlow(allNotes.filter { it.rental == rentalNow() })
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _todos = MutableStateFlow(allTodos.filter { it.rental == rentalNow() })
    val todos: StateFlow<List<ArrivalTodo>> = _todos.asStateFlow()

    private fun refreshMode() {
        _notes.value = allNotes.filter { it.rental == rentalNow() }
        _todos.value = allTodos.filter { it.rental == rentalNow() }
    }

    fun setDriver(name: String) {
        _driver.value = name
        prefs.edit().putString("driver", name).apply()
    }

    private companion object {
        /** 가정용·법인 차량 운전자 최대 수 (렌터카는 일회용이라 제한 없음) */
        const val MAX_DRIVERS = 10
        const val MAX_TRIPS = 200
    }

    fun addNote(text: String, recipient: String, odometerKm: Float?) {
        val n = Note(System.currentTimeMillis(), text, _driver.value, recipient, System.currentTimeMillis(), odometerKm, rental = rentalNow())
        saveNotes(listOf(n) + allNotes)
    }

    /** 지금 운전자가 아직 안 들은, 다른 사람이 남긴 메모 */
    fun unheardFor(driver: String): List<Note> = _notes.value.filter { it.createdAt >= profile(driver).since }.filter {
        it.author != driver && driver !in it.heardBy && (it.recipient == TO_ALL || it.recipient == driver)
    }

    fun markHeard(ids: Collection<Long>, driver: String) {
        saveNotes(allNotes.map { if (it.id in ids) it.copy(heardBy = it.heardBy + driver) else it })
    }

    /** '읽지 않음으로 표시': 다음에 다시 읽어 줌 */
    fun markUnheard(ids: Collection<Long>, driver: String) {
        saveNotes(allNotes.map { if (it.id in ids) it.copy(heardBy = it.heardBy - driver) else it })
    }

    /** 이 운전자에게 온 메모인가 (본인이 쓴 것 제외) */
    fun isFor(n: Note, driver: String) = n.author != driver && (n.recipient == TO_ALL || n.recipient == driver)

    /** 메모 지우기: 이미 전달된(누군가 들은) 메모이거나 내가 쓴 메모만 */
    fun canDelete(n: Note, driver: String) = n.heardBy.isNotEmpty() || n.author == driver

    fun deleteNote(id: Long) = saveNotes(allNotes.filterNot { it.id == id })

    fun note(id: Long): Note? = _notes.value.firstOrNull { it.id == id }

    fun addTodo(place: String, task: String, forNextDriver: Boolean) {
        val t = ArrivalTodo(System.currentTimeMillis(), place, task, _driver.value, forNextDriver, System.currentTimeMillis(), rental = rentalNow())
        saveTodos(listOf(t) + allTodos)
    }

    fun completeTodo(id: Long) = saveTodos(allTodos.map { if (it.id == id) it.copy(done = true) else it })

    fun pendingTodos(): List<ArrivalTodo> = _todos.value.filter { !it.done }

    /** 이 운전자에게 알려 줄 할 일: 내가 나에게 남긴 것 + 다른 사람이 '다음 운전자에게' 남긴 것 */
    fun todosFor(driver: String, list: List<ArrivalTodo> = _todos.value): List<ArrivalTodo> =
        list.filter { !it.done && ((!it.forNextDriver && it.author == driver) || (it.forNextDriver && it.author != driver)) }

    // ---------------- 운행일지 ----------------

    private val _trips = MutableStateFlow(loadTrips())
    /** 최근 운행 기록 (새것이 앞) */
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()

    fun addTrip(t: Trip) {
        val list = (listOf(t) + _trips.value).take(MAX_TRIPS)
        _trips.value = list
        val arr = JSONArray()
        list.forEach { x ->
            arr.put(JSONObject().apply {
                put("driver", x.driver); put("start", x.startAt); put("end", x.endAt); put("km", x.km.toDouble())
                x.batteryUsedPct?.let { put("bat", it) }
            })
        }
        prefs.edit().putString("trips", arr.toString()).apply()
    }

    private fun loadTrips(): List<Trip> = runCatching {
        val arr = JSONArray(prefs.getString("trips", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(o.getString("driver"), o.getLong("start"), o.getLong("end"), o.getDouble("km").toFloat(),
                if (o.has("bat")) o.getInt("bat") else null)
        }
    }.getOrDefault(emptyList())

    // ---------------- 주차 보고서 (차가 자동으로 남기는 기록) ----------------

    private val _parkReport = MutableStateFlow(loadParkReport())
    val parkReport: StateFlow<ParkReport?> = _parkReport.asStateFlow()

    fun saveParkReport(r: ParkReport?) {
        _parkReport.value = r
        if (r == null) { prefs.edit().remove("parkReport").apply(); return }
        prefs.edit().putString("parkReport", JSONObject().apply {
            put("at", r.at); put("driver", r.driver)
            put("doors", JSONArray(r.openDoors)); put("windows", JSONArray(r.openWindows)); put("warnings", JSONArray(r.warnings))
            r.batteryPct?.let { put("bat", it) }; put("port", r.chargePortOpen)
        }.toString()).apply()
    }

    private fun loadParkReport(): ParkReport? = runCatching {
        val o = JSONObject(prefs.getString("parkReport", null) ?: return null)
        fun list(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        ParkReport(o.getLong("at"), o.getString("driver"), list("doors"), list("windows"), list("warnings"),
            if (o.has("bat")) o.getInt("bat") else null, o.optBoolean("port"))
    }.getOrNull()

    /** 도착한 장소 이름과 맞는 할 일 찾기 (서로 포함 관계면 같은 곳으로 봄) */
    fun todoFor(arrivedPlace: String, driver: String): ArrivalTodo? {
        val a = arrivedPlace.replace(" ", "")
        if (a.length < 2) return null                      // 이름 없는 도착 이벤트는 무시
        return todosFor(driver).firstOrNull { t ->
            val p = t.place.replace(" ", "")
            p.length >= 2 && (a.contains(p) || p.contains(a))
        }
    }

    // ---------------- 일정 ----------------

    private val _schedules = MutableStateFlow(loadSchedules())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    fun addSchedule(title: String, place: String, at: Long) {
        val s = Schedule(System.currentTimeMillis(), _driver.value, title, place, at)
        saveSchedules((_schedules.value + s).sortedBy { it.at })
    }

    fun deleteSchedule(id: Long) = saveSchedules(_schedules.value.filterNot { it.id == id })

    fun markScheduleNotified(id: Long, bit: Int) =
        saveSchedules(_schedules.value.map { if (it.id == id) it.copy(notified = it.notified or bit) else it })

    private fun saveSchedules(list: List<Schedule>) {
        _schedules.value = list
        val arr = JSONArray()
        list.forEach { x ->
            arr.put(JSONObject().apply {
                put("id", x.id); put("owner", x.owner); put("title", x.title); put("place", x.place); put("at", x.at); put("n", x.notified)
            })
        }
        prefs.edit().putString("schedules", arr.toString()).apply()
    }

    private fun loadSchedules(): List<Schedule> = runCatching {
        val arr = JSONArray(prefs.getString("schedules", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Schedule(o.getLong("id"), o.getString("owner"), o.getString("title"), o.optString("place"), o.getLong("at"), o.optInt("n"))
        }
    }.getOrDefault(emptyList())

    // ---------------- 시연용 예시 데이터 (설정 화면에서 넣고 지움) ----------------

    /** 예시 데이터 id는 이 값 이상 → 지울 때 예시만 정확히 지움 (실제 데이터 id는 현재 시각 ms라 겹치지 않음) */
    private val DEMO_ID = 9_000_000_000_000L

    fun hasDemo(): Boolean = allNotes.any { it.id >= DEMO_ID } || allTodos.any { it.id >= DEMO_ID } ||
        _schedules.value.any { it.id >= DEMO_ID }

    /**
     * 가족 차 시나리오: 아빠가 주차하며 남긴 메모 2개 + 다음 운전자에게 남긴 도착 할 일 + 엄마·아빠 내일 일정.
     * 지금 운전자를 아빠로 두어, 드롭다운에서 엄마로 바꾸거나 출발(D)하면 바로 전달 장면을 볼 수 있게 함.
     */
    fun seedDemo() {
        clearDemo()
        val now = System.currentTimeMillis()
        listOf("아빠", "엄마").forEach { n -> if (n !in _drivers.value) saveDrivers(_drivers.value + n) }
        val dad = "아빠"; val mom = "엄마"
        val notes = listOf(       // 최신 메모가 위로
            Note(DEMO_ID + 2, "뒷좌석에 아이 우산 두고 내렸어, 어린이집 갈 때 챙겨 줘", dad, mom, now - 4 * 60_000L, null),
            Note(DEMO_ID + 1, "앞 타이어 공기압 경고등이 한 번 켜졌어, 점검 부탁해", dad, TO_ALL, now - 5 * 60_000L, null),
        )
        saveNotes(notes + allNotes)
        saveTodos(listOf(ArrivalTodo(DEMO_ID + 3, "이마트 성수점", "우유 사기", dad, true, now - 3 * 60_000L)) + allTodos)
        val tomorrow = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        fun at(h: Int, m: Int) = (tomorrow.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
        }.timeInMillis
        saveSchedules((_schedules.value + listOf(
            Schedule(DEMO_ID + 4, mom, "치과 예약", "강남역", at(10, 0)),
            Schedule(DEMO_ID + 5, dad, "고객 미팅", "성수역", at(15, 30)),
        )).sortedBy { it.at })
        setDriver(dad)
    }

    fun clearDemo() {
        saveNotes(allNotes.filter { it.id < DEMO_ID })
        saveTodos(allTodos.filter { it.id < DEMO_ID })
        saveSchedules(_schedules.value.filter { it.id < DEMO_ID })
    }

    // ---------------- 저장 ----------------

    private fun saveNotes(list: List<Note>) {
        allNotes = list
        _notes.value = list.filter { it.rental == rentalNow() }
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id); put("text", n.text); put("author", n.author); put("recipient", n.recipient)
                put("createdAt", n.createdAt); n.odometerKm?.let { put("odo", it.toDouble()) }
                put("heardBy", JSONArray(n.heardBy.toList())); put("rental", n.rental)
            })
        }
        prefs.edit().putString("notes", arr.toString()).apply()
    }

    private fun loadNotes(): List<Note> = runCatching {
        val arr = JSONArray(prefs.getString("notes", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val heard = o.optJSONArray("heardBy") ?: JSONArray()
            Note(
                o.getLong("id"), o.getString("text"), o.getString("author"), o.getString("recipient"),
                o.getLong("createdAt"), if (o.has("odo")) o.getDouble("odo").toFloat() else null,
                (0 until heard.length()).map { heard.getString(it) }.toSet(),
                o.optBoolean("rental"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveTodos(list: List<ArrivalTodo>) {
        allTodos = list
        _todos.value = list.filter { it.rental == rentalNow() }
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("place", t.place); put("task", t.task); put("author", t.author)
                put("next", t.forNextDriver); put("createdAt", t.createdAt); put("done", t.done); put("rental", t.rental)
            })
        }
        prefs.edit().putString("todos", arr.toString()).apply()
    }

    private fun loadTodos(): List<ArrivalTodo> = runCatching {
        val arr = JSONArray(prefs.getString("todos", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ArrivalTodo(o.getLong("id"), o.getString("place"), o.getString("task"), o.getString("author"),
                o.getBoolean("next"), o.getLong("createdAt"), o.getBoolean("done"), o.optBoolean("rental"))
        }
    }.getOrDefault(emptyList())
}

/**
 * "마트 도착하면 우유 사라고 알려 줘" → (마트, 우유 사기)
 * 말 그대로 받아 적은 문장을 장소와 할 일로 나눈다. 못 나누면 null.
 */
fun parseArrivalTodo(sentence: String): Pair<String, String>? {
    val s = sentence.trim().trimEnd('.', '!', '?')
    val m = Regex("""^(.+?)\s*(?:에\s*|에서\s*)?도착(?:하면|하거든|하면은)\s*(.+)$""").find(s) ?: return null
    val place = m.groupValues[1].trim().removeSuffix("에").trim()
    var task = m.groupValues[2].trim()
        .replace(Regex("""\s*(?:알려\s*(?:줘|주세요|줄래|줘요)?|말해\s*(?:줘|주세요)?)$"""), "")
        .trim()
        .replace(Regex("""(?:으라고|라고|고)$"""), "")
        .trim()
    if (place.isEmpty() || task.isEmpty()) return null
    if (!task.endsWith("기")) task += "기"
    return place to task
}
