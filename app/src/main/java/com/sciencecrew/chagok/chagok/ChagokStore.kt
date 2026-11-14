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
)

/** 메모·할 일·현재 운전자를 기기에 저장 (앱을 다시 켜도 유지) */
class ChagokStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("chagok", Context.MODE_PRIVATE)

    /** 이 차를 쓰는 운전자 목록 (추가 가능, 기기에 저장) */
    private val _drivers = MutableStateFlow(loadDrivers())
    val drivers: StateFlow<List<String>> = _drivers.asStateFlow()

    private val _driver = MutableStateFlow(prefs.getString("driver", null)?.takeIf { it in _drivers.value } ?: _drivers.value.first())
    val driver: StateFlow<String> = _driver.asStateFlow()

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
            _drivers.value.size >= MAX_DRIVERS -> "운전자는 ${MAX_DRIVERS}명까지 추가할 수 있어요"
            else -> {
                _drivers.value = _drivers.value + name
                prefs.edit().putString("drivers", JSONArray(_drivers.value).toString()).apply()
                null
            }
        }
    }

    private fun loadDrivers(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("drivers", null) ?: return@runCatching DEFAULT_DRIVERS)
        (0 until arr.length()).map { arr.getString(it) }.ifEmpty { DEFAULT_DRIVERS }
    }.getOrDefault(DEFAULT_DRIVERS)

    private val _notes = MutableStateFlow(loadNotes())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _todos = MutableStateFlow(loadTodos())
    val todos: StateFlow<List<ArrivalTodo>> = _todos.asStateFlow()

    fun setDriver(name: String) {
        _driver.value = name
        prefs.edit().putString("driver", name).apply()
    }

    private companion object {
        const val MAX_DRIVERS = 6
        const val MAX_TRIPS = 200
    }

    fun addNote(text: String, recipient: String, odometerKm: Float?) {
        val n = Note(System.currentTimeMillis(), text, _driver.value, recipient, System.currentTimeMillis(), odometerKm)
        saveNotes(listOf(n) + _notes.value)
    }

    /** 지금 운전자가 아직 안 들은, 다른 사람이 남긴 메모 */
    fun unheardFor(driver: String): List<Note> = _notes.value.filter {
        it.author != driver && driver !in it.heardBy && (it.recipient == TO_ALL || it.recipient == driver)
    }

    fun markHeard(ids: Collection<Long>, driver: String) {
        saveNotes(_notes.value.map { if (it.id in ids) it.copy(heardBy = it.heardBy + driver) else it })
    }

    /** 메모 지우기: 이미 전달된(누군가 들은) 메모이거나 내가 쓴 메모만 */
    fun canDelete(n: Note, driver: String) = n.heardBy.isNotEmpty() || n.author == driver

    fun deleteNote(id: Long) = saveNotes(_notes.value.filterNot { it.id == id })

    fun note(id: Long): Note? = _notes.value.firstOrNull { it.id == id }

    fun addTodo(place: String, task: String, forNextDriver: Boolean) {
        val t = ArrivalTodo(System.currentTimeMillis(), place, task, _driver.value, forNextDriver, System.currentTimeMillis())
        saveTodos(listOf(t) + _todos.value)
    }

    fun completeTodo(id: Long) = saveTodos(_todos.value.map { if (it.id == id) it.copy(done = true) else it })

    fun pendingTodos(): List<ArrivalTodo> = _todos.value.filter { !it.done }

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
    fun todoFor(arrivedPlace: String): ArrivalTodo? {
        val a = arrivedPlace.replace(" ", "")
        return pendingTodos().firstOrNull { t ->
            val p = t.place.replace(" ", "")
            p.isNotEmpty() && (a.contains(p) || p.contains(a))
        }
    }

    // ---------------- 저장 ----------------

    private fun saveNotes(list: List<Note>) {
        _notes.value = list
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id); put("text", n.text); put("author", n.author); put("recipient", n.recipient)
                put("createdAt", n.createdAt); n.odometerKm?.let { put("odo", it.toDouble()) }
                put("heardBy", JSONArray(n.heardBy.toList()))
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
            )
        }
    }.getOrDefault(emptyList())

    private fun saveTodos(list: List<ArrivalTodo>) {
        _todos.value = list
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("place", t.place); put("task", t.task); put("author", t.author)
                put("next", t.forNextDriver); put("createdAt", t.createdAt); put("done", t.done)
            })
        }
        prefs.edit().putString("todos", arr.toString()).apply()
    }

    private fun loadTodos(): List<ArrivalTodo> = runCatching {
        val arr = JSONArray(prefs.getString("todos", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ArrivalTodo(o.getLong("id"), o.getString("place"), o.getString("task"), o.getString("author"),
                o.getBoolean("next"), o.getLong("createdAt"), o.getBoolean("done"))
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
