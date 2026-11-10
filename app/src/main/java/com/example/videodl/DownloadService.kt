package com.example.videodl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Скачивает видео в фоне (с уведомлением), чтобы загрузка на гигабайт и больше
 * не прерывалась, когда экран выключен или приложение свёрнуто.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.example.videodl.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_PROXY = "proxy"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        private const val PROCESS_ID = "download"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var cancelled = false
    @Volatile private var lastNotify = 0L
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelDownload()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        val choice = intent?.getStringExtra(EXTRA_CHOICE)
        val proxy = intent?.getStringExtra(EXTRA_PROXY).orEmpty()
        if (url == null || choice == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_ID,
            buildNotification("Подготовка…", 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        cancelled = false
        job = scope.launch { runDownload(url, choice, proxy) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelDownload() {
        cancelled = true
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (_: Exception) {
        }
        if (job?.isActive != true) {
            publish(Phase.CANCELLED, 0, "Загрузка отменена.")
            stopSelf()
        }
    }

    private fun checkCancel() {
        if (cancelled) throw IllegalStateException("cancelled")
    }

    private suspend fun runDownload(url: String, choice: String, proxy: String) {
        val dir = File(getExternalFilesDir(null) ?: cacheDir, "tmp")
        try {
            publish(Phase.RUNNING, 0, "Подготовка…")
            dir.deleteRecursively()
            dir.mkdirs()
            Engine.awaitReady()
            checkCancel()

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("-N", "4")
            if (proxy.isNotEmpty()) request.addOption("--proxy", proxy)
            request.addOption("-o", "${dir.absolutePath}/%(title).100s [%(id)s].%(ext)s")
            when (choice) {
                "audio" -> {
                    request.addOption("-f", "ba/b")
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "0")
                }
                "best" -> {
                    request.addOption("-f", "bv*+ba/b")
                    request.addOption("--merge-output-format", "mp4")
                }
                else -> {
                    val h = choice.toInt()
                    request.addOption("-f", "bv*[height<=$h]+ba/b[height<=$h]")
                    request.addOption("--merge-output-format", "mp4")
                }
            }

            val callback: (Float, Long, String) -> Unit = { progress, eta, line ->
                when {
                    line.contains("[Merger]") || line.contains("Merging formats") ->
                        publish(Phase.RUNNING, 100, "Склейка видео и звука…")
                    line.contains("[ExtractAudio]") ->
                        publish(Phase.RUNNING, 100, "Конвертация в mp3…")
                    progress >= 0f -> {
                        val pct = progress.toInt().coerceIn(0, 100)
                        val left = if (eta > 0) " · осталось ${fmtTime(eta)}" else ""
                        publish(Phase.RUNNING, pct, "Скачивание: $pct%$left")
                    }
                    else -> Unit
                }
            }

            YoutubeDL.getInstance().execute(request, PROCESS_ID, callback)
            checkCancel()

            val file = dir.listFiles()
                ?.filter {
                    it.isFile && !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
                }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("Файл не найден после загрузки")

            val name = file.name
            val size = file.length()
            publish(Phase.RUNNING, 0, "Сохраняю в память телефона…")
            val where = saveToGallery(file)
            file.delete()

            publish(Phase.DONE, 100, "Готово: $name · ${human(size)}\nСохранено в: $where")
            notifyDone("Загрузка завершена", name)
        } catch (e: Exception) {
            if (cancelled) {
                publish(Phase.CANCELLED, 0, "Загрузка отменена.")
                notifyDone("Загрузка отменена", "")
            } else {
                val msg = cleanMsg(e.message ?: e.toString())
                publish(Phase.ERROR, 0, "Ошибка: $msg")
                notifyDone("Ошибка загрузки", msg)
            }
        } finally {
            dir.deleteRecursively()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Копирует готовый файл в общую папку Movies/VideoDownloader (или Music/...) через MediaStore. */
    private fun saveToGallery(file: File): String {
        val ext = file.extension.lowercase()
        val isAudio = ext in setOf("mp3", "m4a", "opus", "ogg", "aac", "flac", "wav", "weba")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (isAudio) "audio/mpeg" else "video/mp4"
        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) +
            "/VideoDownloader"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Не удалось создать файл в памяти телефона")

        try {
            val total = file.length().coerceAtLeast(1L)
            var copied = 0L
            var lastPct = -1
            val out = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Не удалось открыть файл для записи")
            out.use { output ->
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        checkCancel()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        val pct = (copied * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            publish(Phase.RUNNING, pct, "Сохраняю в память телефона: $pct%")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return folder
    }

    private fun publish(phase: Phase, percent: Int, text: String) {
        DownloadState.state.value = DlState(phase, percent, text)
        val now = System.currentTimeMillis()
        if (phase == Phase.RUNNING && now - lastNotify > 800) {
            lastNotify = now
            nm.notify(NOTIF_ID, buildNotification(text, percent))
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(text: String, percent: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Отмена",
            cancelIntent,
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузка видео")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(cancelAction)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(DONE_ID, n)
    }
}
