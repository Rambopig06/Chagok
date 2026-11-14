package com.sciencecrew.chagok

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 시연용 (debug 빌드에만 포함, release에는 없음).
 *
 * 1) 에뮬레이터 내비는 실제로 도착하지 않으므로 "도착" 이벤트를 흉내 낸다.
 *    adb shell am broadcast -a com.sciencecrew.chagok.DEMO_ARRIVE -p com.sciencecrew.chagok --es place "이마트 성수점"
 *
 * 2) 마이크가 안 될 때 "이 문장을 말했다"고 처리한다 (지금 화면에 맞게 들어감).
 *    adb shell am broadcast -a com.sciencecrew.chagok.DEMO_SAY -p com.sciencecrew.chagok --es text "뒷좌석에 우산 두고 내렸어요"
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chagok = (context.applicationContext as PleosApplication).chagok
        when (intent.action) {
            "com.sciencecrew.chagok.DEMO_ARRIVE" -> intent.getStringExtra("place")?.let {
                Log.d("Chagok", "시연용 도착: $it")
                chagok.onArrived(it)
            }
            "com.sciencecrew.chagok.DEMO_SAY" -> intent.getStringExtra("text")?.let {
                Log.d("Chagok", "시연용 음성 입력: $it")
                chagok.demoSpeech(it)
            }
        }
    }
}
