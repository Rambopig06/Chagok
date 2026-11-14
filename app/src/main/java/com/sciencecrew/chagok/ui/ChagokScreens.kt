package com.sciencecrew.chagok.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sciencecrew.chagok.chagok.AiState
import com.sciencecrew.chagok.chagok.ChagokController
import com.sciencecrew.chagok.chagok.Screen
import com.sciencecrew.chagok.chagok.TO_ALL
import com.sciencecrew.chagok.driving.DrivingStateMonitor
import com.sciencecrew.chagok.navi.NaviRepository
import com.sciencecrew.chagok.navi.formatDistance
import com.sciencecrew.chagok.navi.formatDuration
import com.sciencecrew.chagok.voice.SpeechInput
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// ------------------------------------------------------------------
// 시안(1280×720) 크기를 실제 화면 폭에 맞춰 비율대로 바꾸는 단위
// ------------------------------------------------------------------
private val LocalScale = staticCompositionLocalOf { 1f }

@Composable private fun px(v: Int): Dp = (v * LocalScale.current).dp
@Composable private fun fs(v: Int): TextUnit = (v * LocalScale.current).sp

@Composable
private fun T(
    text: String,
    size: Int,
    color: Color = Chagok.Ink,
    medium: Boolean = false,
    spacing: Float = 0f,
    align: TextAlign? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text, modifier = modifier, color = color, fontFamily = Chagok.Font,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Light,
    fontSize = fs(size), letterSpacing = (spacing * LocalScale.current).sp,
    lineHeight = fs((size * 1.5f).toInt()), textAlign = align,
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

@Composable
private fun Hairline(modifier: Modifier = Modifier, color: Color = Chagok.Line) =
    Box(modifier.fillMaxWidth().height(1.dp).background(color))

@Composable
private fun VLine(height: Int) = Box(Modifier.width(1.dp).height(px(height)).background(Chagok.Line))

/** 검정 바탕 + 흰 글씨 (주 버튼) */
@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, height: Int = 88, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(px(height))
            .background(if (enabled) Chagok.Ink else Chagok.Line, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, 24, Chagok.OnInk, spacing = 3f) }
}

/** 테두리만 (보조 버튼). weak = 회색 테두리·회색 글씨 */
@Composable
private fun LineButton(text: String, modifier: Modifier = Modifier, weak: Boolean = false, height: Int = 88, onClick: () -> Unit) {
    Box(
        modifier.height(px(height))
            .border(1.dp, if (weak) Chagok.WeakBorder else Chagok.Ink, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, 22, if (weak) Chagok.WeakText else Chagok.Ink, spacing = 2f) }
}

/** 밑줄 선택 버튼 (받는 사람 고르기) */
@Composable
private fun UnderlineChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(vertical = px(6)), horizontalAlignment = Alignment.CenterHorizontally) {
        T(text, 20, if (selected) Chagok.Ink else Chagok.Sub, medium = selected, spacing = 2f)
        Spacer(Modifier.height(px(6)))
        Box(Modifier.width(px(text.length * 22)).height(1.dp).background(if (selected) Chagok.Ink else Color.Transparent))
    }
}

/**
 * 듣는 중 파형: 검정 막대 7개.
 * 조용할 때는 잔잔하게 숨 쉬듯, 말하면 목소리 크기(level)만큼 크게 출렁인다.
 * 듣고 있지 않을 때는 낮은 점선처럼 멈춰 있다.
 */
@Composable
private fun VoiceWave(speech: SpeechInput, maxHeight: Int = 64) {
    val status by speech.status.collectAsStateWithLifecycle()
    val level by speech.level.collectAsStateWithLifecycle()
    val listening = status is SpeechInput.Status.Listening
    val smooth by animateFloatAsState(level, tween(140), label = "level")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase",
    )
    Row(Modifier.height(px(maxHeight)), horizontalArrangement = Arrangement.spacedBy(px(10)), verticalAlignment = Alignment.CenterVertically) {
        repeat(7) { i ->
            val wobble = 0.5f + 0.5f * sin(phase + i * 0.9f)
            val h = if (listening) {
                val amp = 0.18f + 0.82f * smooth                 // 조용해도 살짝은 움직임
                (8 + (maxHeight - 8) * amp * (0.35f + 0.65f * wobble)).toInt()
            } else 4
            Box(Modifier.width(px(6)).height(px(h)).background(Chagok.Ink, RoundedCornerShape(50)))
        }
    }
}

/** 작은 영문 상태 라벨 (PARKED 등) */
@Composable
private fun Label(text: String) = T(text, 16, Chagok.Sub, medium = true, spacing = 4f)

private fun whenText(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val hm = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(t))
    val days = ((now.timeInMillis - t) / 86_400_000L).toInt()
    return when {
        now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && days < 1 -> "오늘 $hm"
        days <= 1 -> "어제 $hm"
        else -> SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(t))
    }
}

// ------------------------------------------------------------------
// 앱 루트
// ------------------------------------------------------------------
@Composable
fun ChagokApp(c: ChagokController, monitor: DrivingStateMonitor, navi: NaviRepository) {
    val policy by monitor.uiPolicy.collectAsStateWithLifecycle()
    val screen by c.screen.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.Bg)) {
        val scale = minOf(maxWidth.value / 1280f, maxHeight.value / 720f)
        CompositionLocalProvider(LocalScale provides scale) {
            val s = screen
            when {
                s is Screen.Arrive -> ArriveScreen(c, s)                 // 도착 알림은 주행 중에도 (최소 화면)
                policy.simpleUi -> DrivingScreen(c, navi)                // 주행 중: 단순 화면
                s is Screen.Record -> RecordScreen(c)
                s is Screen.AddTodo -> TodoScreen(c, navi)
                s is Screen.Handover -> HandoverScreen(c, s)
                s is Screen.NoteDetail -> NoteDetailScreen(c, s.noteId)
                s is Screen.TripLog -> TripLogScreen(c)
                else -> HomeScreen(c)
            }
        }
    }
}

// ------------------------------------------------------------------
// 1. 주차 중 홈
// ------------------------------------------------------------------
@Composable
private fun HomeScreen(c: ChagokController) {
    val driver by c.store.driver.collectAsStateWithLifecycle()
    val notes by c.store.notes.collectAsStateWithLifecycle()
    val todos by c.store.todos.collectAsStateWithLifecycle()
    val pending = todos.filter { !it.done }

    Row(Modifier.fillMaxSize()) {
    SideNav(c, driver)
    Column(Modifier.weight(1f).fillMaxSize().padding(start = px(56), end = px(64), top = px(44), bottom = px(48))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            T("${driver}님, 안녕하세요", 30, modifier = Modifier.weight(1f))
            Label("PARKED · 주차 중")
        }
        Spacer(Modifier.height(px(20)))
        Hairline()

        Row(Modifier.weight(1f).fillMaxWidth().padding(top = px(32)), horizontalArrangement = Arrangement.spacedBy(px(48))) {
            Column(Modifier.weight(1f)) {
                SectionTitle("남긴 말")
                if (notes.isEmpty()) {
                    T("아직 남긴 말이 없어요", 22, Chagok.Sub, modifier = Modifier.padding(vertical = px(20)))
                }
                notes.take(2).forEachIndexed { i, n ->
                    Column(Modifier.fillMaxWidth().clickable { c.openNote(n.id) }.padding(vertical = px(20))) {
                        T(n.text, 25, maxLines = 2)
                        Spacer(Modifier.height(px(8)))
                        val to = if (n.recipient == TO_ALL) "가족 모두에게" else "${n.recipient}님에게"
                        val heard = if (n.heardBy.isNotEmpty()) " · 전달 완료" else ""
                        T("${n.author}님 · ${whenText(n.createdAt)} · $to$heard", 17, Chagok.Sub, spacing = 1f)
                    }
                    if (i == 0 && notes.size > 1) Hairline(color = Chagok.LineSoft)
                }
            }
            Column(Modifier.weight(1f)) {
                SectionTitle("도착하면 알릴 일")
                if (pending.isEmpty()) {
                    T("등록된 할 일이 없어요", 22, Chagok.Sub, modifier = Modifier.padding(vertical = px(20)))
                }
                pending.take(2).forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = px(20)), verticalAlignment = Alignment.CenterVertically) {
                        T(t.task, 25, modifier = Modifier.weight(1f), maxLines = 1)
                        T("${t.place} · ${if (t.forNextDriver) "다음 운전자에게" else "${t.author}님"}", 17, Chagok.Sub, spacing = 1f, maxLines = 1)
                    }
                    if (i == 0 && pending.size > 1) Hairline(color = Chagok.LineSoft)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(px(48))) {
            PrimaryButton("남김말 녹음", Modifier.weight(1f)) { c.startRecord() }
            LineButton("도착 할 일 추가", Modifier.weight(1f)) { c.startAddTodo() }
        }
    }
    }
}

/**
 * 왼쪽 세로 내비게이션 바 (주차 중 홈).
 * 맨 위 운전자 버튼을 누르면 가족 목록이 아래로 펼쳐지는 드롭다운 → 고르면 지금 운전자가 바뀜.
 */
@Composable
private fun SideNav(c: ChagokController, driver: String) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    val drivers by c.store.drivers.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(px(260)).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(start = px(40), end = px(24), top = px(44), bottom = px(48)),
        ) {
            T("차곡", 36, spacing = 2f)
            T("CHAGOK", 13, Chagok.Sub, spacing = 6f)

            Spacer(Modifier.height(px(44)))
            T("지금 운전자", 15, Chagok.Sub, medium = true, spacing = 3f)
            Spacer(Modifier.height(px(10)))
            // 드롭다운 버튼
            Row(
                Modifier.fillMaxWidth().height(px(64))
                    .border(1.dp, Chagok.Ink, RoundedCornerShape(2.dp))
                    .clickable { open = !open }
                    .padding(horizontal = px(18)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("${driver}님", 24, medium = true, modifier = Modifier.weight(1f))
                T(if (open) "▲" else "▼", 16)
            }
            // 펼쳐지는 세로 목록
            if (open) {
                Column(Modifier.fillMaxWidth().border(1.dp, Chagok.Line).background(Color.White)) {
                    drivers.forEach { name ->
                        val selected = name == driver
                        Row(
                            Modifier.fillMaxWidth().height(px(54))
                                .background(if (selected) Chagok.Ink else Color.White)
                                .clickable { open = false; adding = false; c.switchDriver(name) }
                                .padding(horizontal = px(18)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T("${name}님", 22, if (selected) Chagok.OnInk else Chagok.Ink, medium = selected, modifier = Modifier.weight(1f))
                            if (selected) T("지금", 15, Chagok.OnInk, spacing = 2f)
                        }
                        Hairline(color = Chagok.LineSoft)
                    }
                    if (!adding) {
                        // + 운전자 추가
                        Row(
                            Modifier.fillMaxWidth().height(px(54)).clickable { adding = true; newName = ""; addError = null }
                                .padding(horizontal = px(18)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T("+", 24, medium = true)
                            Spacer(Modifier.width(px(10)))
                            T("운전자 추가", 20, Chagok.WeakText, spacing = 1f)
                        }
                    } else {
                        // 이름 입력 (주차 중이라 키보드 입력 허용)
                        Column(Modifier.fillMaxWidth().padding(px(12))) {
                            Box(Modifier.fillMaxWidth().border(1.dp, Chagok.Ink).padding(horizontal = px(12), vertical = px(10))) {
                                if (newName.isEmpty()) T("이름 (예: 할머니)", 20, Chagok.Sub)
                                BasicTextField(
                                    value = newName, onValueChange = { newName = it.take(10); addError = null }, singleLine = true,
                                    textStyle = TextStyle(fontFamily = Chagok.Font, fontWeight = FontWeight.Light, fontSize = fs(20), color = Chagok.Ink),
                                    cursorBrush = SolidColor(Chagok.Ink), modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            addError?.let { Spacer(Modifier.height(px(6))); T(it, 15, Chagok.Ink, medium = true) }
                            Spacer(Modifier.height(px(10)))
                            Row(horizontalArrangement = Arrangement.spacedBy(px(8))) {
                                PrimaryButton("추가", Modifier.weight(1f), height = 52) {
                                    val err = c.store.addDriver(newName)
                                    if (err == null) {
                                        c.switchDriver(newName.trim().removeSuffix("님").trim())
                                        adding = false; open = false
                                    } else addError = err
                                }
                                LineButton("취소", Modifier.weight(1f), weak = true, height = 52) { adding = false; addError = null }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(px(40)))
            NavItem("홈", selected = true) {}
            NavItem("남김말 녹음") { c.startRecord() }
            NavItem("도착 할 일 추가") { c.startAddTodo() }
            NavItem("운행일지") { c.openTripLog() }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Chagok.Line))
    }
}

@Composable
private fun NavItem(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(px(56)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(px(3)).height(px(24)).background(if (selected) Chagok.Ink else Color.Transparent))
        Spacer(Modifier.width(px(14)))
        T(text, 20, if (selected) Chagok.Ink else Chagok.WeakText, medium = selected, spacing = 1f)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = px(8)), verticalAlignment = Alignment.CenterVertically) {
        T(text, 18, medium = true, spacing = 3f)
        Spacer(Modifier.width(px(16)))
        Hairline(Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------
// 2. 남김말 녹음
// ------------------------------------------------------------------
@Composable
private fun RecordScreen(c: ChagokController) {
    val draft by c.noteDraft.collectAsStateWithLifecycle()
    val recipient by c.recipient.collectAsStateWithLifecycle()
    val driver by c.store.driver.collectAsStateWithLifecycle()
    val drivers by c.store.drivers.collectAsStateWithLifecycle()
    val status by c.speech.status.collectAsStateWithLifecycle()
    val partial by c.speech.partial.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }
    val listening = status is SpeechInput.Status.Listening
    val shown = if (listening && partial.isNotBlank()) partial else draft

    Column(
        Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(56), bottom = px(48)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label("PARKED · 남김말")
        val alerts by c.parkAlerts.collectAsStateWithLifecycle()
        if (alerts.isNotEmpty()) {
            Spacer(Modifier.height(px(14)))
            Column(Modifier.fillMaxWidth().border(1.dp, Chagok.Ink).padding(horizontal = px(20), vertical = px(12))) {
                T("SAFETY · 내리기 전에 확인해 주세요", 14, Chagok.Sub, medium = true, spacing = 3f)
                alerts.take(3).forEach { T(it, 20, medium = true, maxLines = 1, modifier = Modifier.padding(top = px(6))) }
            }
        }
        Spacer(Modifier.height(px(20)))
        T("다음 운전자께 남길 말씀이 있으신가요?", 40, spacing = 1f, align = TextAlign.Center)
        Spacer(Modifier.height(px(20)))
        VoiceWave(c.speech)
        Spacer(Modifier.height(px(20)))

        if (typing) {
            BasicTextField(
                value = draft, onValueChange = c::editNote,
                textStyle = TextStyle(fontFamily = Chagok.Font, fontWeight = FontWeight.Light, fontSize = fs(30), color = Chagok.Ink, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Chagok.Ink),
                modifier = Modifier.fillMaxWidth().border(1.dp, Chagok.Line).padding(px(16)),
            )
        } else {
            T(shown.ifBlank { " " }, 30, align = TextAlign.Center, maxLines = 3)
        }
        Spacer(Modifier.height(px(14)))
        val hint = when (val s = status) {
            SpeechInput.Status.Listening -> "듣고 있어요 · 말씀을 마치면 2초 뒤 자동으로 끝나요"
            is SpeechInput.Status.Error -> s.message
            else -> if (draft.isBlank()) "" else "이대로 남길까요?"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(hint, 17, Chagok.Sub, spacing = 3f)
            if (!typing) {
                Spacer(Modifier.width(px(24)))
                T("직접 입력", 17, Chagok.Sub, spacing = 3f, modifier = Modifier.clickable { c.speech.cancel(); typing = true })
            }
        }

        Spacer(Modifier.height(px(36)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(px(40))) {
            T("받는 사람", 20, Chagok.Sub, spacing = 2f)
            UnderlineChoice(TO_ALL, recipient == TO_ALL) { c.pickRecipient(TO_ALL) }
            drivers.filter { it != driver }.take(3).forEach { name ->
                UnderlineChoice("${name}님", recipient == name) { c.pickRecipient(name) }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(px(20))) {
            PrimaryButton("남기기", Modifier.weight(2f), enabled = draft.isNotBlank()) { c.saveNote() }
            LineButton("다시 말하기", Modifier.weight(1f)) { typing = false; c.retryNote() }
            LineButton("건너뛰기", Modifier.weight(1f), weak = true) { c.cancel() }
        }
    }
}

// ------------------------------------------------------------------
// 3. 도착 할 일 추가
// ------------------------------------------------------------------
@Composable
private fun TodoScreen(c: ChagokController, navi: NaviRepository) {
    val d by c.todoDraft.collectAsStateWithLifecycle()
    val status by c.speech.status.collectAsStateWithLifecycle()
    val partial by c.speech.partial.collectAsStateWithLifecycle()
    val nav by navi.info.collectAsStateWithLifecycle()
    val listening = status is SpeechInput.Status.Listening
    val heading = when {
        listening && partial.isNotBlank() -> partial
        d.sentence.isNotBlank() -> d.sentence
        else -> "어디에 도착하면 무엇을 알려 드릴까요?"
    }
    val sub = when (val s = status) {
        SpeechInput.Status.Listening -> "듣고 있어요 · 예) 마트 도착하면 우유 사라고 알려 줘 · 말을 마치면 2초 뒤 끝나요"
        is SpeechInput.Status.Error -> s.message
        else -> if (d.sentence.isNotBlank()) "이렇게 이해했어요" else ""
    }

    Column(Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(56), bottom = px(48))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("PARKED · 도착 할 일")
            Spacer(Modifier.weight(1f))
            VoiceWave(c.speech, maxHeight = 40)
        }
        Spacer(Modifier.height(px(16)))
        T(heading, 40, spacing = 1f, maxLines = 2)
        Spacer(Modifier.height(px(10)))
        T(sub, 18, Chagok.Sub, spacing = 1f)
        Spacer(Modifier.height(px(32)))

        Hairline()
        TodoRow("장소", d.place.ifBlank { "-" }, if (nav.destinationName != null) "현재 목적지로" else "", { c.useNaviDestination() })
        Hairline(color = Chagok.LineSoft)
        TodoRow("할 일", d.task.ifBlank { "-" }, "도착 시 음성 안내", null)
        Hairline(color = Chagok.LineSoft)
        Row(Modifier.fillMaxWidth().padding(vertical = px(14)), verticalAlignment = Alignment.CenterVertically) {
            T("받는 사람", 17, Chagok.Sub, medium = true, spacing = 4f, modifier = Modifier.width(px(200)))
            Row(horizontalArrangement = Arrangement.spacedBy(px(40))) {
                UnderlineChoice("나에게", !d.forNextDriver) { c.setTodoForNext(false) }
                UnderlineChoice("다음 운전자에게", d.forNextDriver) { c.setTodoForNext(true) }
            }
        }
        Hairline(color = Chagok.LineSoft)

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(px(20))) {
            PrimaryButton("저장", Modifier.weight(2f), enabled = d.place.isNotBlank() && d.task.isNotBlank()) { c.saveTodo() }
            LineButton("다시 말하기", Modifier.weight(1f)) { c.retryTodo() }
            LineButton("취소", Modifier.weight(1f), weak = true) { c.cancel() }
        }
    }
}

@Composable
private fun TodoRow(label: String, value: String, side: String, onSide: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = px(22)), verticalAlignment = Alignment.CenterVertically) {
        T(label, 17, Chagok.Sub, medium = true, spacing = 4f, modifier = Modifier.width(px(200)))
        T(value, 30, modifier = Modifier.weight(1f), maxLines = 1)
        if (side.isNotEmpty()) {
            T(side, 17, if (onSide != null) Chagok.Ink else Chagok.Sub,
                modifier = if (onSide != null) Modifier.clickable(onClick = onSide).padding(px(8)) else Modifier)
        }
    }
}

// ------------------------------------------------------------------
// 4. 출발 전 · 메모 전달
// ------------------------------------------------------------------
@Composable
private fun HandoverScreen(c: ChagokController, h: Screen.Handover) {
    val todos by c.store.todos.collectAsStateWithLifecycle()
    val first = h.notes.minByOrNull { it.createdAt }
    val carItems = h.report?.items().orEmpty()

    Column(Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(52), bottom = px(48))) {
        Row(Modifier.fillMaxWidth()) {
            Label("READY · 출발 전")
            Spacer(Modifier.weight(1f))
            val count = buildList {
                if (h.notes.isNotEmpty()) add("이전 운전자 메모 ${h.notes.size}개")
                if (carItems.isNotEmpty()) add("차량 상태 ${carItems.size}건")
            }.joinToString(" · ")
            T(count, 16, Chagok.Sub, spacing = 3f)
        }
        if (first != null) {
            Spacer(Modifier.height(px(30)))
            T("${first.author}님 · ${whenText(first.createdAt)}", 18, Chagok.Sub, spacing = 2f)
            Spacer(Modifier.height(px(10)))
            T(first.text, if (carItems.isEmpty()) 40 else 32, spacing = 0.5f, maxLines = 2)
            if (h.notes.size > 1) {
                Spacer(Modifier.height(px(6)))
                T("외 ${h.notes.size - 1}개 · 음성으로 모두 읽어 드려요", 17, Chagok.Sub)
            }
        }
        if (carItems.isNotEmpty() && h.report != null) {
            Spacer(Modifier.height(px(if (first != null) 24 else 30)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T("CAR · 차곡이 확인한 지난 주차 상태", 15, Chagok.Sub, medium = true, spacing = 3f)
                Spacer(Modifier.width(px(12)))
                T("${h.report.driver}님 · ${whenText(h.report.at)}", 15, Chagok.Sub)
                Spacer(Modifier.width(px(16)))
                Hairline(Modifier.weight(1f))
            }
            carItems.take(4).forEach { item ->
                Row(Modifier.padding(top = px(10)), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(px(6)).height(px(6)).background(Chagok.Ink))
                    Spacer(Modifier.width(px(14)))
                    T(item, 24, medium = true, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(px(24)))
        Hairline()
        Row(Modifier.fillMaxWidth()) {
            Stat("그사이 주행", h.drivenKm?.let { "%.0f km".format(it) } ?: "-", Modifier.weight(1f), first = true)
            Stat("주차한 때", (h.report?.at ?: first?.createdAt)?.let { whenText(it) } ?: "-", Modifier.weight(1f))
            Stat("도착 할 일", "${todos.count { !it.done }}개", Modifier.weight(1f))
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(px(20))) {
            PrimaryButton("확인했어요", Modifier.weight(2f)) { c.closeHandover() }
            LineButton("다시 듣기", Modifier.weight(1f)) { c.readHandover() }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, first: Boolean = false) {
    Row(modifier) {
        if (!first) Box(Modifier.width(1.dp).height(px(96)).background(Chagok.LineSoft))
        Column(Modifier.padding(start = if (first) 0.dp else px(32), top = px(22), bottom = px(22))) {
            T(label, 15, Chagok.Sub, medium = true, spacing = 4f)
            Spacer(Modifier.height(px(6)))
            T(value, 32)
        }
    }
}

// ------------------------------------------------------------------
// 메모 상세 (주차 중): AI 도움 · 삭제
// ------------------------------------------------------------------
@Composable
private fun NoteDetailScreen(c: ChagokController, noteId: Long) {
    val notes by c.store.notes.collectAsStateWithLifecycle()
    val driver by c.store.driver.collectAsStateWithLifecycle()
    val ai by c.ai.collectAsStateWithLifecycle()
    val n = notes.firstOrNull { it.id == noteId }
    if (n == null) { HomeScreen(c); return }
    val canDelete = c.store.canDelete(n, driver)
    val to = if (n.recipient == TO_ALL) "가족 모두에게" else "${n.recipient}님에게"
    val heard = if (n.heardBy.isNotEmpty()) "전달 완료 (${n.heardBy.joinToString { "${it}님" }})" else "아직 전달 전"

    Column(Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(56), bottom = px(48))) {
        Label("PARKED · 남긴 말")
        Spacer(Modifier.height(px(24)))
        T("${n.author}님 · ${whenText(n.createdAt)} · $to", 18, Chagok.Sub, spacing = 2f)
        Spacer(Modifier.height(px(12)))
        T(n.text, 36, spacing = 0.5f, maxLines = 3)
        Spacer(Modifier.height(px(8)))
        T(heard, 17, Chagok.Sub, spacing = 1f)

        Spacer(Modifier.height(px(28)))
        Row(Modifier.fillMaxWidth().padding(bottom = px(8)), verticalAlignment = Alignment.CenterVertically) {
            T("GLEO · AI 도움", 16, Chagok.Sub, medium = true, spacing = 4f)
            Spacer(Modifier.width(px(16)))
            Hairline(Modifier.weight(1f))
            val answer = ai as? AiState.Answer
            if (answer != null && !answer.demo && answer.source != null) {
                Spacer(Modifier.width(px(16)))
                T(answer.source, 15, Chagok.Sub, medium = true, spacing = 1f)
            }
            if (answer?.demo == true) {
                Spacer(Modifier.width(px(16)))
                Box(Modifier.border(1.dp, Chagok.Ink, RoundedCornerShape(2.dp)).padding(horizontal = px(12), vertical = px(4))) {
                    T("시연용 예시 답변 · 실제 글레오 응답 아님", 15, medium = true, spacing = 1f)
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = px(12))) {
            when (val a = ai) {
                AiState.Idle -> T("'AI 도움'을 누르면 글레오가 이 메모를 보고 해결책을 알려 드려요.", 22, Chagok.Sub)
                AiState.Loading -> T("글레오가 메모를 살펴보고 있어요…", 22, Chagok.Sub)
                is AiState.Answer -> T(a.text.ifBlank { "…" }, 26, maxLines = 6)
                is AiState.Failed -> T("${a.message}. 잠시 후 다시 눌러 주세요.", 22, Chagok.Sub)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(px(20))) {
            PrimaryButton(if (ai is AiState.Loading) "살펴보는 중" else "AI 도움", Modifier.weight(2f),
                enabled = ai !is AiState.Loading) { c.askGleo(n.id) }
            if (canDelete) {
                LineButton("삭제", Modifier.weight(1f)) { c.deleteNote(n.id) }
            } else {
                Box(Modifier.weight(1f).height(px(88)).border(1.dp, Chagok.LineSoft, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center) { T("전달 후 삭제 가능", 18, Chagok.Line, spacing = 1f) }
            }
            LineButton("닫기", Modifier.weight(1f), weak = true) { c.closeNote() }
        }
    }
}

// ------------------------------------------------------------------
// 5. 주행 중 (단순 화면: 큰 글씨 + 버튼 하나)
// ------------------------------------------------------------------
@Composable
private fun DrivingScreen(c: ChagokController, navi: NaviRepository) {
    val todos by c.store.todos.collectAsStateWithLifecycle()
    val nav by navi.info.collectAsStateWithLifecycle()
    val notice by c.drivingNotice.collectAsStateWithLifecycle()
    val status by c.speech.status.collectAsStateWithLifecycle()
    val next = todos.firstOrNull { !it.done }

    Column(
        Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(56), bottom = px(48)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("DRIVING", 16, Chagok.Sub, medium = true, spacing = 6f)
        Spacer(Modifier.weight(1f))
        if (next != null) {
            T("${next.place} 도착 시", 20, Chagok.Sub, spacing = 3f)
            Spacer(Modifier.height(px(18)))
            T(next.task, 84, spacing = 2f, maxLines = 1)
        } else {
            T("안전 운전하세요", 72, spacing = 2f)
        }
        if (nav.guiding) {
            Spacer(Modifier.height(px(18)))
            VLine(36)
            Spacer(Modifier.height(px(18)))
            T("${formatDistance(nav.remainDistanceM)} · ${formatDuration(nav.remainTimeSec)}", 26, spacing = 2f)
        }
        Spacer(Modifier.weight(1f))
        val listening = status is SpeechInput.Status.Listening
        if (listening) {
            VoiceWave(c.speech, maxHeight = 48)
            Spacer(Modifier.height(px(16)))
        }
        PrimaryButton(if (listening) "듣고 있어요" else (notice?.takeIf { it == "남겨 두었어요" } ?: "말로 남기기"),
            Modifier.width(px(480)), height = 96, enabled = !listening) { c.driveNote() }
    }
}

// ------------------------------------------------------------------
// 6. 도착 알림
// ------------------------------------------------------------------
@Composable
private fun ArriveScreen(c: ChagokController, a: Screen.Arrive) {
    Column(
        Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(56), bottom = px(48)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T("ARRIVED", 16, Chagok.Sub, medium = true, spacing = 6f)
        Spacer(Modifier.weight(1f))
        T("${a.placeName}에 도착했습니다", 22, Chagok.Sub, spacing = 3f, maxLines = 1)
        Spacer(Modifier.height(px(18)))
        T(a.todo.task, 96, spacing = 2f, maxLines = 1)
        Spacer(Modifier.height(px(18)))
        VLine(36)
        Spacer(Modifier.height(px(18)))
        T("${a.todo.author}님이 남기신 할 일", 20, Chagok.Sub, spacing = 2f)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(px(20))) {
            PrimaryButton("완료했어요", Modifier.weight(2f)) { c.finishArrive(done = true) }
            LineButton("다음 도착 때 다시", Modifier.weight(1f)) { c.finishArrive(done = false) }
        }
    }
}


// ------------------------------------------------------------------
// 운행일지 (주차 중): 운전자별 이번 주 주행·비용 나누기
// ------------------------------------------------------------------
@Composable
private fun TripLogScreen(c: ChagokController) {
    val trips by c.store.trips.collectAsStateWithLifecycle()
    val drivers by c.store.drivers.collectAsStateWithLifecycle()
    val weekAgo = System.currentTimeMillis() - 7L * 86_400_000L
    val week = trips.filter { it.endAt >= weekAgo }
    val totalKm = week.sumOf { it.km.toDouble() }.toFloat()
    var costText by remember { mutableStateOf("") }
    val cost = costText.filter { it.isDigit() }.toLongOrNull()

    Column(Modifier.fillMaxSize().padding(start = px(120), end = px(120), top = px(52), bottom = px(48))) {
        Label("PARKED · 운행일지")
        Spacer(Modifier.height(px(12)))
        Row(verticalAlignment = Alignment.Bottom) {
            T("최근 7일 운행", 36, spacing = 1f)
            Spacer(Modifier.width(px(20)))
            T("총 ${"%.1f".format(totalKm)} km · ${week.size}회", 20, Chagok.Sub)
        }
        Spacer(Modifier.height(px(20)))
        Hairline()
        // 운전자별 합계 + 비율 막대 + 비용 나누기
        val names = (drivers + week.map { it.driver }).distinct().filter { n -> week.any { it.driver == n } }
        if (names.isEmpty()) {
            T("아직 운행 기록이 없어요. 출발(D)부터 주차(P)까지 자동으로 기록돼요.", 20, Chagok.Sub, modifier = Modifier.padding(vertical = px(24)))
        }
        names.forEach { name ->
            val mine = week.filter { it.driver == name }
            val km = mine.sumOf { it.km.toDouble() }.toFloat()
            val min = mine.sumOf { it.minutes }
            val bat = mine.mapNotNull { it.batteryUsedPct }.sum()
            val ratio = if (totalKm > 0f) km / totalKm else 0f
            Row(Modifier.fillMaxWidth().padding(vertical = px(14)), verticalAlignment = Alignment.CenterVertically) {
                T("${name}님", 22, medium = true, modifier = Modifier.width(px(150)))
                T("${"%.1f".format(km)} km · ${min}분 · ${mine.size}회" + if (bat > 0) " · 배터리 ${bat}%" else "", 18, Chagok.Sub,
                    modifier = Modifier.width(px(360)))
                Box(Modifier.weight(1f).height(px(10)).background(Chagok.LineSoft)) {
                    Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).height(px(10)).background(Chagok.Ink))
                }
                T("%.0f%%".format(ratio * 100), 18, medium = true, align = TextAlign.End, modifier = Modifier.width(px(70)))
                if (cost != null && cost > 0) {
                    T("%,d원".format((cost * ratio).toLong()), 18, medium = true, align = TextAlign.End, modifier = Modifier.width(px(150)))
                }
            }
            Hairline(color = Chagok.LineSoft)
        }
        Spacer(Modifier.height(px(18)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("충전·주유비 나누기", 17, Chagok.Sub, medium = true, spacing = 2f, modifier = Modifier.width(px(220)))
            Box(Modifier.width(px(240)).border(1.dp, Chagok.Ink).padding(horizontal = px(12), vertical = px(8))) {
                if (costText.isEmpty()) T("총 금액(원)", 18, Chagok.Sub)
                BasicTextField(
                    value = costText, onValueChange = { costText = it.filter { ch -> ch.isDigit() }.take(9) }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Chagok.Font, fontWeight = FontWeight.Light, fontSize = fs(18), color = Chagok.Ink),
                    cursorBrush = SolidColor(Chagok.Ink), modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(px(16)))
            T("주행 거리 비율대로 나눠요", 15, Chagok.Sub)
        }
        Spacer(Modifier.height(px(16)))
        T("최근 운행", 15, Chagok.Sub, medium = true, spacing = 3f)
        trips.take(3).forEach { t ->
            T("${whenText(t.startAt)} · ${t.driver}님 · ${"%.1f".format(t.km)} km · ${t.minutes}분" +
                (t.batteryUsedPct?.let { " · 배터리 ${it}%" } ?: ""), 17, modifier = Modifier.padding(top = px(6)))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LineButton("닫기", Modifier.width(px(240)), weak = true) { c.closeTripLog() }
        }
    }
}
