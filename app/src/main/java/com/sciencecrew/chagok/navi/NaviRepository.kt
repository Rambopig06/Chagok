package com.sciencecrew.chagok.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 화면에 보여 줄 내비 정보 (읽기 전용) */
data class NaviInfo(
    val connected: Boolean = false,
    val status: NavigationStatus? = null,
    val destinationName: String? = null,
    /** 남은 거리. 단위는 문서에 없음 → 미터로 가정 (에뮬레이터 내비 화면과 비교해 확인) */
    val remainDistanceM: Int? = null,
    /** 남은 시간. 단위는 문서에 없음 → 초로 가정 */
    val remainTimeSec: Int? = null,
    val error: String? = null,
) {
    /** 경로 안내 중인가: 목적지가 있고, 내비가 "안내 안 함/자유 주행"이라고 알려 오지 않았을 때 (상태 이벤트가 늦게 오거나 안 오는 경우 있음) */
    val guiding: Boolean get() = destinationName != null && (remainDistanceM ?: 0) > 0 &&
        status != NavigationStatus.INACTIVE && status != NavigationStatus.FREE_DRIVE
}

/**
 * Pleos NaviHelper SDK로 목적지·남은 거리·남은 시간을 읽는다.
 * 권한: pleos.car.permission.NAVI_ROUTE (get*Info 계열, SDK @RequiresPermission 기준)
 */
class NaviRepository(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val _info = MutableStateFlow(NaviInfo())
    val info: StateFlow<NaviInfo> = _info.asStateFlow()

    /** 목적지 도착 이벤트 (음성 안내 등에 사용) */
    private val _arrived = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val arrived: SharedFlow<String> = _arrived

    private val listener = object : NaviHelperEventListener {
        override fun onNavigationStatus(status: NavigationStatus) {
            Log.d(TAG, "status = $status")
            _info.update {
                if (status == NavigationStatus.ROUTE_DRIVE) it.copy(status = status)
                else it.copy(status = status, destinationName = null, remainDistanceM = null, remainTimeSec = null)
            }
            if (status == NavigationStatus.ROUTE_DRIVE) navi.getDestinationInfo()
        }
        override fun onDestinationInfo(info: DestinationInfo) {
            Log.d(TAG, "destination = ${info.name} 남은거리=${info.remainDistance} 남은시간=${info.remainTime}")
            _info.update {
                it.copy(destinationName = info.name.ifBlank { null }, remainDistanceM = info.remainDistance, remainTimeSec = info.remainTime, error = null)
            }
        }
        override fun onDrivingInfo(info: DrivingInfo) {
            val d = info.destination ?: return
            _info.update { it.copy(destinationName = d.name.ifBlank { it.destinationName }, remainDistanceM = d.distance, remainTimeSec = d.duration) }
        }
        override fun onRoutingCompleted() {
            Log.d(TAG, "경로 탐색 완료")
            navi.getDestinationInfo()
        }
        override fun onDestinationChanged() { navi.getDestinationInfo() }
        override fun onRouteCancelled() {
            Log.d(TAG, "경로 취소")
            _info.update { it.copy(destinationName = null, remainDistanceM = null, remainTimeSec = null) }
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            Log.d(TAG, "도착: ${info.name}")
            _arrived.tryEmit(info.name)
            _info.update { it.copy(destinationName = null, remainDistanceM = null, remainTimeSec = null) }
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류 $errorCode")
            _info.update { it.copy(error = errorCode.toString()) }
        }
    }

    fun start() {
        navi.initialize(
            {
                Log.d(TAG, "NaviHelper 연결됨")
                _info.update { it.copy(connected = true) }
                navi.addListener(listener)
                navi.getDestinationInfo()
                startPolling()
            },
            { t ->
                Log.e(TAG, "NaviHelper 연결 실패", t)
                _info.update { it.copy(connected = false, error = "내비 연결 실패") }
            },
        )
    }

    /** 이벤트가 안 오는 경우를 대비해 5초마다 목적지 정보를 다시 요청 (결과는 onDestinationInfo로 옴) */
    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                delay(5000)
                runCatching { navi.getDestinationInfo() }
            }
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        runCatching { navi.removeListener(listener) }
        navi.release()
    }

    private companion object { const val TAG = "NaviRepo" }
}

/** 12.3 km / 850 m */
fun formatDistance(m: Int?): String = when {
    m == null || m < 0 -> "-"
    m >= 1000 -> "%.1f km".format(m / 1000f)
    else -> "$m m"
}

/** 1시간 5분 / 12분 */
fun formatDuration(sec: Int?): String {
    if (sec == null || sec < 0) return "-"
    val min = (sec + 59) / 60
    return if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"
}
