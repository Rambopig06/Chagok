package com.sciencecrew.chagok

import android.car.Car
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sciencecrew.chagok.ui.ChagokApp

class MainActivity : ComponentActivity() {

    private val app get() = application as PleosApplication

    // 차량 속도는 위험(dangerous) 권한 → 주행 상태 판단에 필요해서 처음 한 번 허락 받음
    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) app.vehicleRepo.startSpeed() else app.vehicleRepo.markSpeedPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            app.vehicleRepo.startSpeed()
        } else {
            speedPermission.launch(Car.PERMISSION_SPEED)
        }
        setContent { ChagokApp(app.chagok, app.drivingMonitor, app.naviRepo) }
    }
}
