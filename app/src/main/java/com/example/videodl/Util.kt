package com.example.videodl

import java.util.Locale

private val ANSI = Regex("\u001B\\[[0-9;]*m")

/** Убирает цветовые коды и префикс ERROR: из сообщения yt-dlp. */
fun cleanMsg(msg: String?): String =
    ANSI.replace(msg ?: "", "").trim().removePrefix("ERROR:").trim().take(300)

/** Адрес локального SOCKS5-прокси ByeByeDPI в режиме «Прокси» (порт по умолчанию 1080). */
const val BYEBYEDPI_PROXY = "socks5://127.0.0.1:1080"

/** Адрес из поля прокси: пусто = без прокси; без схемы считаем SOCKS5 (так работает ByeByeDPI). */
fun normalizeProxy(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return ""
    return if (s.contains("://")) s else "socks5://$s"
}

/** Похоже ли сообщение на проблему с доступом к сайту (таймаут, обрыв, блокировка). */
fun looksLikeNetworkError(msg: String?): Boolean {
    val m = msg?.lowercase().orEmpty()
    return listOf(
        "timed out", "timeout", "unable to connect", "connection", "network is unreachable",
        "unable to download", "ssl", "eof occurred", "reset by peer", "name or service not known",
    ).any { it in m }
}

fun human(bytes: Long): String {
    var n = bytes.toDouble()
    val units = arrayOf("Б", "КБ", "МБ", "ГБ")
    for (u in units) {
        if (n < 1024) {
            return if (u == "Б") "${n.toInt()} $u" else String.format(Locale.getDefault(), "%.1f %s", n, u)
        }
        n /= 1024
    }
    return String.format(Locale.getDefault(), "%.1f ТБ", n)
}

fun fmtTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
