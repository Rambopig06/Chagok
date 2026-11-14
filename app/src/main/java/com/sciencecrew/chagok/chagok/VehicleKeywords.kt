package com.sciencecrew.chagok.chagok

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 차량 상태 관련 해시태그.
 * 메모에 이 단어(또는 함께 적은 다른 표현)가 들어 있으면 "차량 점검이 필요해 보이는 메모"로 보고
 * 다음 운전자에게 가까운 서비스센터 안내 여부를 묻는다.
 *
 * tag: 화면에 보여 줄 해시태그, words: 메모에서 찾을 표현 (띄어쓰기 무시)
 */
data class CarKeyword(val tag: String, val words: List<String>)

val CAR_KEYWORDS: List<CarKeyword> = listOf(
    CarKeyword("타이어", listOf("타이어", "바퀴")),
    CarKeyword("공기압", listOf("공기압", "TPMS", "바람빠", "바람이빠")),
    CarKeyword("펑크", listOf("펑크", "빵꾸", "빵구")),
    CarKeyword("경고등", listOf("경고등", "경고램프", "체크등", "엔진등", "불이들어", "불들어")),
    CarKeyword("엔진", listOf("엔진")),
    CarKeyword("엔진오일", listOf("엔진오일", "오일교환", "오일갈", "오일부족")),
    CarKeyword("누유", listOf("누유", "오일샘", "기름샘", "기름이새", "오일이새")),
    CarKeyword("냉각수", listOf("냉각수", "부동액", "라디에이터")),
    CarKeyword("과열", listOf("과열", "오버히트", "온도계")),
    CarKeyword("브레이크", listOf("브레이크", "제동", "브레이크패드")),
    CarKeyword("ABS", listOf("ABS")),
    CarKeyword("배터리", listOf("배터리", "방전", "밧데리")),
    CarKeyword("고전압배터리", listOf("고전압", "구동배터리")),
    CarKeyword("충전", listOf("충전이안", "충전안", "충전불량", "충전오류", "충전구")),
    CarKeyword("시동", listOf("시동")),
    CarKeyword("변속기", listOf("변속", "미션", "기어가", "기어변경")),
    CarKeyword("핸들", listOf("핸들", "스티어링", "조향", "쏠림", "쏠려")),
    CarKeyword("진동", listOf("진동", "떨림", "덜덜")),
    CarKeyword("소음", listOf("소음", "이상한소리", "끼익", "덜컹", "딸깍", "삐걱", "웅웅", "소리가나")),
    CarKeyword("냄새", listOf("타는냄새", "탄내", "냄새가나", "이상한냄새")),
    CarKeyword("연기", listOf("연기", "매연", "흰연기", "검은연기")),
    CarKeyword("서스펜션", listOf("서스펜션", "쇼바", "충격흡수")),
    CarKeyword("얼라인먼트", listOf("얼라인먼트", "휠정렬")),
    CarKeyword("휠", listOf("휠")),
    CarKeyword("에어컨", listOf("에어컨", "냉방", "찬바람")),
    CarKeyword("히터", listOf("히터", "난방", "더운바람")),
    CarKeyword("와이퍼", listOf("와이퍼")),
    CarKeyword("워셔액", listOf("워셔액")),
    CarKeyword("전조등", listOf("전조등", "헤드램프", "헤드라이트")),
    CarKeyword("깜빡이", listOf("깜빡이", "방향지시등", "비상등")),
    CarKeyword("브레이크등", listOf("브레이크등", "후미등", "미등")),
    CarKeyword("유리", listOf("유리", "앞유리", "금이", "크랙")),
    CarKeyword("사이드미러", listOf("사이드미러", "백미러")),
    CarKeyword("문", listOf("문이안", "문안닫", "도어")),
    CarKeyword("창문", listOf("창문", "윈도우")),
    CarKeyword("범퍼", listOf("범퍼")),
    CarKeyword("긁힘", listOf("긁힘", "긁혔", "스크래치", "기스")),
    CarKeyword("찌그러짐", listOf("찌그러", "찍힘", "덴트")),
    CarKeyword("사고", listOf("사고", "접촉", "박았", "부딪")),
    CarKeyword("에어백", listOf("에어백")),
    CarKeyword("안전벨트", listOf("안전벨트")),
    CarKeyword("센서", listOf("센서", "카메라", "후방카메라", "주차보조")),
    CarKeyword("계기판", listOf("계기판", "클러스터")),
    CarKeyword("화면", listOf("화면이", "디스플레이", "먹통")),
    CarKeyword("벨트", listOf("벨트소리", "타이밍벨트", "팬벨트")),
    CarKeyword("배기", listOf("배기", "머플러")),
    CarKeyword("필터", listOf("필터", "에어필터", "에어컨필터")),
    CarKeyword("점검", listOf("점검", "정비", "수리", "고장", "이상해", "이상있", "이상이")),
    CarKeyword("리콜", listOf("리콜", "무상수리")),
    CarKeyword("소모품", listOf("소모품", "교체주기", "교환주기")),
)

/** 메모에서 찾은 차량 해시태그들 (없으면 빈 목록) */
fun carTags(text: String): List<String> {
    val t = text.replace(" ", "").uppercase()
    return CAR_KEYWORDS.filter { k -> k.words.any { t.contains(it.replace(" ", "").uppercase()) } }.map { it.tag }
}

// ------------------------------------------------------------------
// 서비스센터 안내
// ------------------------------------------------------------------

/**
 * 서비스센터 위치.
 * NaviHelper SDK에는 장소 검색 API가 없어서(좌표로 경로 요청만 가능) 앱 안의 목록에서 가장 가까운 곳을 고른다.
 * 아래 목록은 시연용 예시 위치이며 실제 매장 정보가 아니다. 출시 시에는 서비스센터 검색 API로 바꾼다.
 */
data class ServiceCenter(val name: String, val address: String, val lat: Double, val lon: Double)

val DEMO_SERVICE_CENTERS = listOf(
    ServiceCenter("서비스센터 성수 (시연용 예시)", "서울 성동구 성수동 일대", 37.5446, 127.0557),
    ServiceCenter("서비스센터 강남 (시연용 예시)", "서울 강남구 역삼동 일대", 37.5006, 127.0364),
    ServiceCenter("서비스센터 여의도 (시연용 예시)", "서울 영등포구 여의도동 일대", 37.5219, 126.9245),
    ServiceCenter("서비스센터 판교 (시연용 예시)", "경기 성남시 분당구 삼평동 일대", 37.4020, 127.1086),
)

/** 두 좌표 사이 거리(km) */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** 현재 위치에서 가장 가까운 서비스센터 (위치를 모르면 목록 첫 번째) */
fun nearestServiceCenter(here: Pair<Double, Double>?): Pair<ServiceCenter, Double?> {
    if (here == null) return DEMO_SERVICE_CENTERS.first() to null
    return DEMO_SERVICE_CENTERS.map { it to distanceKm(here.first, here.second, it.lat, it.lon) }.minBy { it.second }
}
