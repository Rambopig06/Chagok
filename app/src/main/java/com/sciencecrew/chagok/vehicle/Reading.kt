package com.sciencecrew.chagok.vehicle

/** 차량 값 하나의 상태: 확인 중 / 미지원 / 값 있음 / 오류 */
sealed interface Reading<out T> {
    data object Loading : Reading<Nothing>
    data object Unsupported : Reading<Nothing>
    data class Value<T>(val value: T) : Reading<T>
    data class Error(val message: String) : Reading<Nothing>
}
