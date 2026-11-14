package com.sciencecrew.chagok

import android.app.Application
import com.sciencecrew.chagok.chagok.ChagokController
import com.sciencecrew.chagok.chagok.ChagokStore
import com.sciencecrew.chagok.chagok.VehicleCondition
import com.sciencecrew.chagok.driving.DrivingStateMonitor
import com.sciencecrew.chagok.navi.NaviRepository
import com.sciencecrew.chagok.vehicle.VehicleStateRepository
import com.sciencecrew.chagok.voice.GleoAssistant
import com.sciencecrew.chagok.voice.SpeechInput
import com.sciencecrew.chagok.voice.VoiceGuide

/** 앱 전체가 같은 상태를 구독하도록 한곳에서 만든다 */
class PleosApplication : Application() {
    lateinit var vehicleRepo: VehicleStateRepository
        private set
    lateinit var drivingMonitor: DrivingStateMonitor
        private set
    lateinit var naviRepo: NaviRepository
        private set
    lateinit var chagok: ChagokController
        private set

    override fun onCreate() {
        super.onCreate()
        vehicleRepo = VehicleStateRepository(this).also { it.start() }
        drivingMonitor = DrivingStateMonitor(vehicleRepo)
        naviRepo = NaviRepository(this).also { it.start() }
        chagok = ChagokController(
            store = ChagokStore(this),
            vehicle = vehicleRepo,
            drivingMonitor = drivingMonitor,
            navi = naviRepo,
            voice = VoiceGuide(this),
            speech = SpeechInput(this),
            gleo = GleoAssistant(this),
            condition = VehicleCondition { vehicleRepo.sdk },
        )
    }
}
