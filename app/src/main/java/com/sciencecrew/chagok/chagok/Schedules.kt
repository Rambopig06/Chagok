package com.sciencecrew.chagok.chagok

import java.util.Calendar

/** 운전자 개인 일정 (임박하면 음성으로 알려 줌) */
data class Schedule(
    val id: Long,
    val owner: String,
    val title: String,
    val place: String,
    val at: Long,
    /** 이미 알린 단계: 1 = 30분 전, 2 = 10분 전 (비트) */
    val notified: Int = 0,
)

/** 일정 알림 시점 (분) */
val SCHEDULE_ALERT_MINUTES = listOf(30 to 1, 10 to 2)

/** 말로 입력한 일정 문장을 나눈 결과. 못 찾은 칸은 null */
data class ScheduleParse(val title: String?, val place: String?, val dayMillis: Long?, val hour: Int?, val minute: Int?)

/**
 * "내일 오후 3시 반에 강남역에서 치과 예약" → 내일 / 15:30 / 강남역 / 치과 예약
 * "11월 20일 10시 회사 회의" → 11월 20일 / 10:00 / (장소 없음) / 회사 회의
 * 말 그대로 받아 적은 문장에서 날짜·시간·장소·일정 이름을 최대한 찾아낸다.
 */
fun parseSchedule(sentence: String, now: Long = System.currentTimeMillis()): ScheduleParse {
    var s = " " + sentence.trim().trimEnd('.', '!', '?') + " "
    val today = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    // ---- 날짜 ----
    var day: Calendar? = null
    fun plus(d: Int) = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, d) }
    val rel = mapOf("오늘" to 0, "내일" to 1, "모레" to 2, "글피" to 3)
    for ((w, d) in rel) if (s.contains(" $w") || s.startsWith(w)) { day = plus(d); s = s.replaceFirst(w, " "); break }
    Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""").find(s)?.let { m ->
        val c = (today.clone() as Calendar).apply {
            set(Calendar.MONTH, m.groupValues[1].toInt() - 1); set(Calendar.DAY_OF_MONTH, m.groupValues[2].toInt())
        }
        if (c.before(today)) c.add(Calendar.YEAR, 1)
        day = c; s = s.replace(m.value, " ")
    }
    if (day == null) {
        val dows = listOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")
        Regex("""(다음\s*주\s*)?([일월화수목금토])요일""").find(s)?.let { m ->
            val target = dows.indexOf(m.groupValues[2] + "요일") + 1           // Calendar.SUNDAY = 1
            val cur = today.get(Calendar.DAY_OF_WEEK)
            var diff = (target - cur + 7) % 7
            if (m.groupValues[1].isNotBlank()) diff += 7 else if (diff == 0) diff = 7
            day = plus(diff); s = s.replace(m.value, " ")
        }
    }

    // ---- 시간 ----
    var hour: Int? = null
    var minute: Int? = null
    Regex("""(오전|오후|아침|낮|저녁|밤|새벽)?\s*(\d{1,2})\s*시\s*(반|(\d{1,2})\s*분)?""").find(s)?.let { m ->
        var h = m.groupValues[2].toInt()
        when (m.groupValues[1]) {
            "오후", "저녁", "밤" -> if (h < 12) h += 12
            "낮" -> if (h < 6) h += 12
            "오전", "아침", "새벽" -> if (h == 12) h = 0
            else -> if (h in 1..6) h += 12             // "3시"처럼 오전/오후가 없으면 낮 시간으로 봄
        }
        hour = h.coerceIn(0, 23)
        minute = when {
            m.groupValues[3] == "반" -> 30
            m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt().coerceIn(0, 59)
            else -> 0
        }
        s = s.replace(m.value, " ")
    }

    // ---- 장소: "○○에서" ----
    var place: String? = null
    Regex("""(\S+)\s*에서""").find(s)?.let { m ->
        place = m.groupValues[1].trim()
        s = s.replace(m.value, " ")
    }

    // ---- 나머지 = 일정 이름 ----
    val title = s.replace(Regex("""\s*(에|에는|까지)\s+"""), " ")
        .replace(Regex("""(일정|약속)?\s*(있어|있음|잡아\s*줘|추가해\s*줘|등록해\s*줘|알려\s*줘|넣어\s*줘)\s*$"""), "")
        .replace(Regex("""\s+"""), " ").trim().trim(',', ' ')
        .ifBlank { null }
    return ScheduleParse(title, place, day?.timeInMillis, hour, minute)
}
