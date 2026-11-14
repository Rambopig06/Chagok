package com.sciencecrew.chagok.driving

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.car.drivingstate.CarUxRestrictions
import android.util.Log
import com.sciencecrew.chagok.vehicle.Reading
import com.sciencecrew.chagok.vehicle.VehicleStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** 주차(P) / 정차(P 아님 + 멈춤) / 주행 */
enum class DrivingState { PARKED, STOPPED, DRIVING }

/**
 * 화면이 지켜야 할 규칙.
 * - simpleUi: 주행 중이거나 플랫폼이 콘텐츠 제한(LIMIT_CONTENT)을 걸면 큰 글씨 최소 화면
 * - allowParkedOnlyFeatures: 영상·게임 성격 기능. P이고 플랫폼이 영상 금지를 걸지 않았을 때만
 */
data class UiPolicy(
    val state: DrivingState,
    val simpleUi: Boolean,
    val allowParkedOnlyFeatures: Boolean,
)

class DrivingStateMonitor(repo: VehicleStateRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 출발/정지 순간 깜빡임 방지: 5 km/h 이상이면 주행, 1 km/h 이하로 내려가야 정차
    private var last = DrivingState.PARKED

    val drivingState: StateFlow<DrivingState> =
        combine(repo.gear, repo.speedKmh) { gear, speed -> decide(gear, speed) }
            .distinctUntilChanged()
            .onEach { Log.d(TAG, "drivingState = $it") }
            .stateIn(scope, SharingStarted.Eagerly, DrivingState.PARKED)

    val uiPolicy: StateFlow<UiPolicy> =
        combine(drivingState, repo.uxRestrictions) { state, uxr -> policy(state, uxr) }
            .distinctUntilChanged()
            .onEach { Log.d(TAG, "uiPolicy = $it") }
            .stateIn(scope, SharingStarted.Eagerly, UiPolicy(DrivingState.PARKED, simpleUi = false, allowParkedOnlyFeatures = true))

    private fun decide(gear: Reading<VehicleGear>, speed: Reading<Float>): DrivingState {
        val g = (gear as? Reading.Value)?.value
        val kmh = (speed as? Reading.Value)?.value
        val next = when {
            g == VehicleGear.GEAR_PARK -> DrivingState.PARKED
            g == null && kmh == null -> last               // 아직 모름 → 이전 상태 유지
            kmh == null -> if (last == DrivingState.PARKED) DrivingState.STOPPED else last
            kmh >= 5f -> DrivingState.DRIVING
            kmh <= 1f -> if (g == null && last == DrivingState.PARKED) last else DrivingState.STOPPED
            else -> if (last == DrivingState.PARKED) DrivingState.STOPPED else last
        }
        last = next
        return next
    }

    private fun policy(state: DrivingState, uxr: CarUxRestrictions?): UiPolicy {
        val requiresDo = uxr?.isRequiresDistractionOptimization == true
        val active = uxr?.activeRestrictions ?: 0
        val limitContent = requiresDo && (active and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT) != 0
        val noVideo = requiresDo && (active and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) != 0
        return UiPolicy(
            state = state,
            simpleUi = state == DrivingState.DRIVING || limitContent,
            allowParkedOnlyFeatures = state == DrivingState.PARKED && !noVideo,
        )
    }

    private companion object { const val TAG = "DrivingState" }
}
