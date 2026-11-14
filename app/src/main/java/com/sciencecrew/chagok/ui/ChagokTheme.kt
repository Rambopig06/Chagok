package com.sciencecrew.chagok.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sciencecrew.chagok.R

/**
 * 차곡 디자인 토큰 (화면 시안 기준).
 * 서체: 지금은 앱 내장이 허용된 IBM Plex Sans KR(OFL).
 * 열린고딕은 앱 임베딩에 윤디자인 별도 허락이 필요 → 허락받으면 res/font 파일 두 개만 교체.
 */
object Chagok {
    val Bg = Color(0xFFF6F6F6)        // 아주 밝은 회색 배경
    val Ink = Color(0xFF111111)       // 대표색: 검정 (글씨·버튼)
    val Sub = Color(0xFF666666)       // 보조 글씨
    val Line = Color(0xFFD2D2D2)      // 구역 구분선
    val LineSoft = Color(0xFFE5E5E5)  // 목록 사이 구분선
    val OnInk = Color(0xFFFFFFFF)     // 버튼 글씨
    val WeakBorder = Color(0xFF8C8C8C) // 취소·건너뛰기 버튼 테두리 (쓸 수 있는 버튼으로 보이게 진하게)
    val WeakText = Color(0xFF333333)   // 취소·건너뛰기 버튼 글씨

    val Font = FontFamily(
        Font(R.font.plex_kr_light, FontWeight.Light),
        Font(R.font.plex_kr_medium, FontWeight.Medium),
    )
}
