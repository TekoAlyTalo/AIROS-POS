package com.airos.pos.core.common

sealed interface PosResult<out T> {
    data class Success<T>(val value: T) : PosResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : PosResult<Nothing>
}

fun <T> PosResult<T>.getOrNull(): T? = when (this) {
    is PosResult.Success -> value
    is PosResult.Failure -> null
}
