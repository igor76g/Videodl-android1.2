package com.example.videodl

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.lang.reflect.Modifier

/**
 * Запуск и обновление движка (Python + yt-dlp + ffmpeg).
 * Первый запуск распаковывает файлы и занимает несколько секунд, поэтому всё в фоне.
 */
object Engine {
    private val state = MutableStateFlow(0) // 0 - запускается, 1 - готов, 2 - ошибка
    private val _status = MutableStateFlow("Подготовка движка…")
    val status: StateFlow<String> = _status

    @Volatile private var error: String? = null
    @Volatile private var started = false

    fun start(app: Context) {
        if (started) return
        started = true
        Thread {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _status.value = "yt-dlp ${versionOf(app)}".trim()
                state.value = 1
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                _status.value = "Ошибка запуска движка: $error"
                state.value = 2
            }
            if (state.value == 1) updateYtDlp(app)
        }.start()
    }

    /** Ждёт, пока движок будет готов; бросает исключение, если он не запустился. */
    suspend fun awaitReady() {
        state.first { it != 0 }
        if (state.value == 2) throw IllegalStateException(error ?: "Движок не запустился")
    }

    // Обновление yt-dlp при каждом запуске приложения.
    // Вызов сделан через рефлексию, чтобы сборка не зависела от точного имени
    // константы канала обновлений в конкретной версии библиотеки.
    private fun updateYtDlp(app: Context) {
        val before = versionOf(app)
        _status.value = "yt-dlp $before · проверяю обновления…".trim()
        val result = try {
            val yd = YoutubeDL.getInstance()
            val method = yd.javaClass.methods.firstOrNull {
                it.name == "updateYoutubeDL" && it.parameterTypes.size == 2
            }
            if (method == null) {
                "обновление недоступно"
            } else {
                val channel = findStableChannel(method.parameterTypes[1])
                describe(method.invoke(yd, app, channel))
            }
        } catch (e: Throwable) {
            "не удалось проверить обновления"
        }
        _status.value = "yt-dlp ${versionOf(app)} · $result".trim()
    }

    private fun describe(r: Any?): String {
        val s = r?.toString() ?: return "проверено"
        return when {
            s.contains("DONE", true) -> "обновлён"
            s.contains("ALREADY", true) -> "версия актуальна"
            else -> s
        }
    }

    private fun findStableChannel(type: Class<*>): Any? {
        // Вариант 1: enum
        type.enumConstants?.firstOrNull { it.toString().trim('_').equals("STABLE", true) }
            ?.let { return it }
        // Вариант 2: статическое поле STABLE или _STABLE
        for (name in listOf("STABLE", "_STABLE")) {
            try {
                val f = type.getField(name)
                if (Modifier.isStatic(f.modifiers)) return f.get(null)
            } catch (_: Exception) {
            }
        }
        // Вариант 3: вложенный Kotlin-объект STABLE
        for (nested in type.declaredClasses) {
            if (nested.simpleName.trim('_').equals("STABLE", true)) {
                try {
                    return nested.getField("INSTANCE").get(null)
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun versionOf(app: Context): String = try {
        val yd = YoutubeDL.getInstance()
        val m = yd.javaClass.methods.firstOrNull { it.name == "version" && it.parameterTypes.size == 1 }
        m?.invoke(yd, app)?.toString() ?: ""
    } catch (e: Throwable) {
        ""
    }
}
