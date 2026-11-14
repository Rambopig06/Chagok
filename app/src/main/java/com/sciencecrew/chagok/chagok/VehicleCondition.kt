package com.sciencecrew.chagok.chagok

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 주차 순간의 차량 상태 한 장.
 * 권한(모두 읽기 전용, 일반 권한): CAR_DOORS, CAR_WINDOWS, CAR_SAFETY, CAR_ENERGY
 * PRIVATE_* 권한이 필요한 API(경고등 실시간 구독, 타이어 압력 수치)는 쓰지 않음.
 */
data class VehicleSnapshot(
    val openDoors: List<String> = emptyList(),     // "트렁크", "뒷좌석 왼쪽 문" …
    val openWindows: List<String> = emptyList(),   // "운전석 창문", "선루프" …
    val warnings: List<String> = emptyList(),      // "타이어 공기압 경고" …
    val batteryPct: Int? = null,
    val chargePortOpen: Boolean = false,
)

/**
 * 차량 상태 읽기 + 뒷좌석 문 감지.
 * - snapshot(): 문·창문·경고등·배터리를 한 번에 읽음 (주차할 때)
 * - 뒷좌석 문이 열린 적이 있는지 계속 지켜봄 (하차 안전 체크용)
 */
class VehicleCondition(private val vehicleProvider: () -> Vehicle?) {

    /** 마지막 출발 이후(또는 탑승 때) 뒷좌석 문이 열린 적이 있는지 */
    @Volatile var rearDoorUsed = false
        private set
    /** 주차 후 "뒷좌석 확인" 안내를 한 상태에서 뒷좌석 문이 다시 열리면 확인한 것으로 봄 */
    @Volatile var rearCheckPending = false
        private set
    var onRearChecked: (() -> Unit)? = null

    private var watching = false
    private val rearDoors = listOf(DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    private val rearListeners = rearDoors.associateWith { door ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) {
                if ((position ?: 0) > 0) onRearDoorOpened(door)
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "뒷좌석 문 감지 실패 $door", e) }
        }
    }

    /** Vehicle SDK가 준비된 뒤 한 번 호출 */
    fun startWatching() {
        if (watching) return
        val v = vehicleProvider() ?: return
        runCatching {
            rearListeners.forEach { (door, l) -> v.getDoor().registerDoorPosition(DoorArea(door), l) }
            watching = true
            Log.d(TAG, "뒷좌석 문 감지 시작")
        }.onFailure { Log.w(TAG, "뒷좌석 문 감지 등록 실패", it) }
    }

    private fun onRearDoorOpened(door: DoorValue) {
        Log.d(TAG, "뒷좌석 문 열림: $door")
        if (rearCheckPending) {
            rearCheckPending = false
            rearDoorUsed = false
            onRearChecked?.invoke()
        } else {
            rearDoorUsed = true
        }
    }

    /** 주차할 때: 뒷좌석을 썼으면 확인 안내 대기 상태로 */
    fun beginRearCheck(): Boolean {
        val need = rearDoorUsed
        rearCheckPending = need
        return need
    }

    /** 출발할 때: 확인 대기는 끝 (이번 출발 전 탑승으로 뒷좌석 문을 열었으면 rearDoorUsed는 유지) */
    fun onDeparted() { rearCheckPending = false }

    // ---------------- 한 번 읽기 ----------------

    suspend fun snapshot(): VehicleSnapshot {
        val v = vehicleProvider() ?: return VehicleSnapshot()
        val doors = mutableListOf<String>()
        for ((value, name) in DOOR_NAMES) {
            val pos = read<Int> { ok, fail -> v.getDoor().getCurrentDoorPosition(DoorArea(value), ok, fail) }
            if ((pos ?: 0) > 0) doors += name
        }
        val windows = mutableListOf<String>()
        for ((value, name) in WINDOW_NAMES) {
            val pos = read<Int> { ok, fail -> v.getWindow().getCurrentWindowPosition(WindowArea(value), ok, fail) }
            if ((pos ?: 0) > 0) windows += name
        }
        val tell = read<ai.pleos.playground.vehicle.api.model.telltale.Telltales> { ok, fail -> v.getSafety().getTelltalesInfo(ok, fail) }
        val warnings = tell?.telltales.orEmpty()
            .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
            .mapNotNull { WARNING_NAMES[it.type] }
            .distinct()
            // 문/트렁크 경고등은 위의 "열린 문"과 겹치므로 문 정보가 있으면 생략
            .filterNot { (it == "문 열림 경고" || it == "트렁크 열림 경고") && doors.isNotEmpty() }
        val battery = read<Int> { ok, fail -> v.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }
            ?: read<Int> { ok, fail -> v.getFuel().getFuelPercent(ok, fail) }
        val port = read<Boolean> { ok, fail -> v.getEvBattery().isEvChargePortOpen(ok, fail) } ?: false
        return VehicleSnapshot(doors, windows, warnings, battery, port).also { Log.d(TAG, "주차 상태: $it") }
    }

    /** 지금 배터리(또는 연료) % */
    suspend fun batteryPct(): Int? {
        val v = vehicleProvider() ?: return null
        return read<Int> { ok, fail -> v.getEvBattery().getEvBatteryStateOfCharge(ok, fail) }
            ?: read<Int> { ok, fail -> v.getFuel().getFuelPercent(ok, fail) }
    }

    /** 콜백형 SDK 호출을 기다렸다가 값으로 (1.5초 안에 답이 없거나 실패하면 null) */
    private suspend fun <T> read(call: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    call({ value -> if (cont.isActive) cont.resume(value) },
                        { e -> Log.d(TAG, "읽기 실패: ${e.message}"); if (cont.isActive) cont.resume(null) })
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    companion object {
        private const val TAG = "Condition"

        val DOOR_NAMES = linkedMapOf(
            DoorValue.ROW_1_LEFT to "운전석 문", DoorValue.ROW_1_RIGHT to "조수석 문",
            DoorValue.ROW_2_LEFT to "뒷좌석 왼쪽 문", DoorValue.ROW_2_RIGHT to "뒷좌석 오른쪽 문",
            DoorValue.REAR to "트렁크", DoorValue.HOOD to "보닛",
        )
        val WINDOW_NAMES = linkedMapOf(
            WindowValue.ROW_1_LEFT to "운전석 창문", WindowValue.ROW_1_RIGHT to "조수석 창문",
            WindowValue.ROW_2_LEFT to "뒷좌석 왼쪽 창문", WindowValue.ROW_2_RIGHT to "뒷좌석 오른쪽 창문",
            WindowValue.ROOF_TOP_1 to "선루프",
        )
        /** 다음 운전자가 알아야 할 경고등만 골라 우리말 이름으로 */
        val WARNING_NAMES = mapOf(
            TelltaleType.TPMS to "타이어 공기압 경고",
            TelltaleType.MASTER_WARNING to "차량 종합 경고",
            TelltaleType.ENGINE to "엔진 경고",
            TelltaleType.ENGINE_OIL_PRESSURE to "엔진 오일 압력 경고",
            TelltaleType.ENGINE_TEMPERATURE to "엔진 과열 경고",
            TelltaleType.EV_SYSTEM to "전기차 시스템 경고",
            TelltaleType.LOW_EV_BATTERY to "배터리 부족 경고",
            TelltaleType.LOW_FUEL to "연료 부족 경고",
            TelltaleType.LOW_VOLT_BATTERY to "보조 배터리 경고",
            TelltaleType.ABS to "ABS 경고",
            TelltaleType.ESC to "차체 자세 제어 경고",
            TelltaleType.AIRBAG to "에어백 경고",
            TelltaleType.MDPS to "조향 장치 경고",
            TelltaleType.TRUNK_OPEN to "트렁크 열림 경고",
            TelltaleType.DOOR_OPEN to "문 열림 경고",
        )
    }
}

/** 주차 보고서: 주차할 때 차곡이 자동으로 남기는 기록 (다음 운전자에게 전달) */
data class ParkReport(
    val at: Long,
    val driver: String,
    val openDoors: List<String>,
    val openWindows: List<String>,
    val warnings: List<String>,
    val batteryPct: Int?,
    val chargePortOpen: Boolean,
) {
    /** 다음 운전자에게 알려 줄 문장들 */
    fun items(): List<String> = buildList {
        if (openDoors.isNotEmpty()) add("${iGa(openDoors.joinToString(", "))} 열린 채 주차됐어요")
        if (openWindows.isNotEmpty()) add("${iGa(openWindows.joinToString(", "))} 열린 채 주차됐어요")
        warnings.forEach { add("주차할 때 ${iGa(it)} 켜져 있었어요") }
        if (chargePortOpen) add("충전구가 열린 채 주차됐어요")
        batteryPct?.let { if (it <= 20) add("배터리가 ${it}% 남아 있어요. 충전이 필요해요") }
    }
}

/** 받침에 맞춰 "이/가" 붙이기: 문 → 문이, 트렁크 → 트렁크가 */
fun iGa(word: String): String {
    val last = word.trimEnd().lastOrNull() ?: return word
    val hasBatchim = last in '가'..'힣' && (last - '가') % 28 != 0
    return word + if (hasBatchim) "이" else "가"
}
