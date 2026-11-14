package com.sciencecrew.chagok.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차량 상태(읽기 전용)를 한곳에서 모아 StateFlow로 내보낸다.
 * - 기어, 주행 거리: Pleos Vehicle SDK (권한 pleos.car.permission.CAR_DRIVE)
 * - 속도, 주차 브레이크: Pleos SDK에 없어서 AAOS 표준 Car API 사용
 */
class VehicleStateRepository(context: Context) {

    private val appContext = context.applicationContext

    private fun <T> MutableStateFlow<Reading<T>>.set(name: String, r: Reading<T>) {
        if (value != r) Log.d(TAG, "$name = $r")
        value = r
    }

    private val _gear = MutableStateFlow<Reading<VehicleGear>>(Reading.Loading)
    val gear: StateFlow<Reading<VehicleGear>> = _gear.asStateFlow()

    private val _distanceKm = MutableStateFlow<Reading<Float>>(Reading.Loading)
    val distanceKm: StateFlow<Reading<Float>> = _distanceKm.asStateFlow()

    private val _speedKmh = MutableStateFlow<Reading<Float>>(Reading.Loading)
    val speedKmh: StateFlow<Reading<Float>> = _speedKmh.asStateFlow()

    private val _parkingBrakeOn = MutableStateFlow<Reading<Boolean>>(Reading.Loading)
    val parkingBrakeOn: StateFlow<Reading<Boolean>> = _parkingBrakeOn.asStateFlow()

    /** 플랫폼이 알려 주는 주행 중 UI 제한 (AAOS CarUxRestrictions). null = 아직 모름 */
    private val _uxRestrictions = MutableStateFlow<CarUxRestrictions?>(null)
    val uxRestrictions: StateFlow<CarUxRestrictions?> = _uxRestrictions.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var speedPollJob: Job? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private var vehicle: Vehicle? = null

    /** 다른 모듈(차량 상태 점검 등)이 같은 Vehicle SDK 연결을 쓰도록 공유 */
    val sdk: Vehicle? get() = vehicle
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var started = false

    // ---- Pleos SDK 리스너 (등록할 때와 같은 객체로 해제해야 함) ----
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            if (gear != null) _gear.set("gear", Reading.Value(gear))
        }
        override fun onFailed(e: Exception) {
            _gear.set("gear", Reading.Error(e.message ?: "기어 구독 실패"))
        }
    }
    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) {
            if (distance != null) _distanceKm.set("distanceKm", Reading.Value(distance))
        }
        override fun onFailed(e: Exception) {
            _distanceKm.set("distanceKm", Reading.Error(e.message ?: "주행 거리 구독 실패"))
        }
    }

    // ---- AAOS 속성 콜백 ----
    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            Log.v(TAG, "raw prop=${value.propertyId} status=${value.status} value=${value.value}")
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    (value.value as? Float)?.let { ms -> _speedKmh.set("speedKmh", Reading.Value(ms * 3.6f)) }
                VehiclePropertyIds.PARKING_BRAKE_ON ->
                    (value.value as? Boolean)?.let { _parkingBrakeOn.set("parkingBrake", Reading.Value(it)) }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "속성 오류 propId=$propertyId area=$areaId")
        }
    }

    /** Pleos SDK 부분 시작 (일반 권한만 필요) */
    fun start() {
        if (started) return
        started = true
        val v = Vehicle(appContext).also { it.initialize() }
        vehicle = v
        startGear(v)
        startDistance(v)
        startParkingBrake()
        startUxRestrictions()
    }

    private fun startUxRestrictions() {
        val c = car ?: runCatching { Car.createCar(appContext) }.getOrNull()?.also { car = it } ?: return
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = m
            _uxRestrictions.value = m.currentCarUxRestrictions
            Log.d(TAG, "uxr = ${m.currentCarUxRestrictions}")
            m.registerListener { r ->
                Log.d(TAG, "uxr = $r")
                _uxRestrictions.value = r
            }
        } catch (e: Exception) {
            Log.e(TAG, "UX 제한 서비스 연결 실패", e)
        }
    }

    private fun startGear(v: Vehicle) {
        // Gear 그룹에는 checkXXXCapabilities가 없음 → 바로 읽고, 실패하면 미지원/오류로 표시
        val g = v.getGear()
        g.getCurrentGearSelectionState(
            { gear -> _gear.set("gear", if (gear != null) Reading.Value(gear) else Reading.Unsupported) },
            { e -> _gear.set("gear", Reading.Error(e.message ?: "기어 읽기 실패")) },
        )
        g.registerGearSelection(gearListener)
    }

    private fun startDistance(v: Vehicle) {
        val o = v.getOdometer()
        o.checkCurrentDistanceDrivenCapabilities(
            { config ->
                if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) {
                    o.getCurrentDistanceDriven(
                        { d -> _distanceKm.set("distanceKm", if (d != null) Reading.Value(d) else Reading.Unsupported) },
                        { e -> _distanceKm.set("distanceKm", Reading.Error(e.message ?: "주행 거리 읽기 실패")) },
                    )
                    o.registerDistanceDriven(distanceListener)
                } else {
                    _distanceKm.set("distanceKm", Reading.Unsupported)
                }
            },
            { e -> _distanceKm.set("distanceKm", Reading.Error(e.message ?: "주행 거리 지원 확인 실패")) },
        )
    }

    private fun carPropertyManager(): CarPropertyManager? {
        propertyManager?.let { return it }
        return try {
            val c = car ?: Car.createCar(appContext).also { car = it }
            (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).also { propertyManager = it }
        } catch (e: Exception) {
            Log.e(TAG, "Car 서비스 연결 실패", e)
            null
        }
    }

    /** AAOS 속성 하나를 지원 확인 → 현재값 읽기 → 변화 구독 */
    private fun <T> subscribeProperty(propId: Int, rate: Float, target: MutableStateFlow<Reading<T>>, convert: (Any?) -> T?) {
        val name = "prop$propId"
        val pm = carPropertyManager() ?: run { target.set(name, Reading.Error("Car 서비스 없음")); return }
        try {
            if (pm.getCarPropertyConfig(propId) == null) {
                target.set(name, Reading.Unsupported)
                return
            }
            val current = pm.getProperty<Any>(propId, 0)
            convert(current?.value)?.let { target.set(name, Reading.Value(it)) }
            pm.registerCallback(propertyCallback, propId, rate)
        } catch (e: SecurityException) {
            target.set(name, Reading.Error("권한 없음"))
        } catch (e: Exception) {
            target.set(name, Reading.Error(e.message ?: "읽기 실패"))
        }
    }

    private fun startParkingBrake() {
        subscribeProperty(VehiclePropertyIds.PARKING_BRAKE_ON, CarPropertyManager.SENSOR_RATE_ONCHANGE, _parkingBrakeOn) { it as? Boolean }
    }

    /** 속도: 위험 권한(CAR_SPEED)이 허용된 뒤에 호출 */
    fun startSpeed() {
        subscribeProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL, _speedKmh) {
            (it as? Float)?.times(3.6f)
        }
        // 보강: 이벤트가 안 들어오는 경우가 있었음(2단계) → 1초마다 직접 읽어서 덮어쓰기
        if (speedPollJob == null && _speedKmh.value !is Reading.Unsupported) {
            speedPollJob = scope.launch {
                while (true) {
                    delay(1000)
                    val pm = propertyManager ?: continue
                    runCatching { pm.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0) }
                        .getOrNull()?.value?.let { ms -> _speedKmh.set("speedKmh(poll)", Reading.Value(ms * 3.6f)) }
                }
            }
        }
    }

    fun markSpeedPermissionDenied() {
        _speedKmh.set("speedKmh", Reading.Error("속도 권한이 거부됨"))
    }

    fun stop() {
        vehicle?.let { v ->
            runCatching { v.getGear().unregisterGearSelection(gearListener) }
            runCatching { v.getOdometer().unregisterDistanceDriven(distanceListener) }
            v.release()
        }
        vehicle = null
        speedPollJob?.cancel(); speedPollJob = null
        runCatching { uxManager?.unregisterListener() }
        uxManager = null
        runCatching { propertyManager?.unregisterCallback(propertyCallback) }
        propertyManager = null
        car?.disconnect()
        car = null
        started = false
    }

    private companion object { const val TAG = "VehicleState" }
}
