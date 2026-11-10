package com.example.videodl

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase { IDLE, RUNNING, DONE, ERROR, CANCELLED }

data class DlState(
    val phase: Phase = Phase.IDLE,
    val percent: Int = 0,
    val text: String = "",
)

/** Общее состояние загрузки: сервис пишет, экран читает. */
object DownloadState {
    val state = MutableStateFlow(DlState())
}
