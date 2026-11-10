package com.example.videodl

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : Activity() {

    private class Choice(val label: String, val value: String)

    private class VideoMeta(
        val title: String,
        val duration: Long,
        val choices: List<Choice>,
        val defaultIndex: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val noProgress: (Float, Long, String) -> Unit = { _, _, _ -> }

    private lateinit var urlEdit: EditText
    private lateinit var pasteBtn: Button
    private lateinit var findBtn: Button
    private lateinit var infoText: TextView
    private lateinit var spinner: Spinner
    private lateinit var actionBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var footer: TextView
    private lateinit var proxyEdit: EditText
    private lateinit var byeBtn: Button

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var currentUrl = ""
    private var hasInfo = false
    private var finding = false
    private var running = false
    private var choiceValues: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEdit = findViewById(R.id.urlEdit)
        pasteBtn = findViewById(R.id.pasteBtn)
        findBtn = findViewById(R.id.findBtn)
        infoText = findViewById(R.id.infoText)
        spinner = findViewById(R.id.qualitySpinner)
        actionBtn = findViewById(R.id.actionBtn)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        footer = findViewById(R.id.footer)
        proxyEdit = findViewById(R.id.proxyEdit)
        byeBtn = findViewById(R.id.byeBtn)

        statusText.text = "Вставьте ссылку и нажмите «Найти»"
        updateButtons()

        // Прокси запоминается между запусками
        proxyEdit.setText(prefs.getString("proxy", ""))
        proxyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("proxy", s?.toString().orEmpty()).apply()
            }
        })
        byeBtn.setOnClickListener {
            proxyEdit.setText(BYEBYEDPI_PROXY)
            statusText.text = "Указан прокси ByeByeDPI. В приложении ByeByeDPI выберите режим " +
                "«Прокси» и нажмите «Подключиться»."
        }

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        findBtn.setOnClickListener { find() }
        actionBtn.setOnClickListener { onAction() }
        urlEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                find()
                true
            } else {
                false
            }
        }
        urlEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                resetInfo()
            }
        })

        scope.launch { DownloadState.state.collect { render(it) } }
        scope.launch { Engine.status.collect { footer.text = it } }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Ссылка, отправленная из браузера или другого приложения через «Поделиться». */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        urlEdit.setText(text.trim())
        find()
    }

    private fun extractUrl(text: String): String? = Regex("https?://\\S+").find(text)?.value

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }
        if (text.isBlank()) {
            statusText.text = "Буфер обмена пуст"
            return
        }
        urlEdit.setText(text.trim())
        find()
    }

    private fun resetInfo() {
        if (running || finding) return
        hasInfo = false
        infoText.text = ""
        spinner.adapter = null
        updateButtons()
    }

    private fun updateButtons() {
        val busy = running || finding
        actionBtn.text = if (running) "Отмена" else "Скачать"
        actionBtn.isEnabled = running || (hasInfo && !finding)
        findBtn.isEnabled = !busy
        pasteBtn.isEnabled = !busy
        spinner.isEnabled = hasInfo && !busy
    }

    private fun render(s: DlState) {
        running = s.phase == Phase.RUNNING
        progress.progress = if (s.phase == Phase.IDLE) 0 else s.percent
        if (s.text.isNotEmpty()) statusText.text = s.text
        updateButtons()
    }

    private fun find() {
        if (running || finding) return
        val url = extractUrl(urlEdit.text.toString())
        if (url == null) {
            statusText.text = "Вставьте ссылку, начинающуюся с http:// или https://"
            return
        }
        currentUrl = url
        val proxy = currentProxy()
        finding = true
        hasInfo = false
        infoText.text = ""
        statusText.text = "Получаю информацию о видео…"
        updateButtons()

        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchInfo(url, proxy) } }
            finding = false
            result.onSuccess { showInfo(it) }
            result.onFailure {
                hasInfo = false
                var text = "Не удалось получить видео: ${cleanMsg(it.message)}"
                if (proxy.isEmpty() && looksLikeNetworkError(it.message)) {
                    text += "\n\nЕсли сайт без обхода блокировок не открывается, включите ByeByeDPI: " +
                        "в режиме VPN он подойдёт сразу, в режиме «Прокси» укажите его адрес в поле " +
                        "«Прокси» внизу (кнопка ByeByeDPI)."
                }
                statusText.text = text
            }
            updateButtons()
        }
    }

    /** Прокси из поля ввода в виде, понятном yt-dlp (пусто, если не задан). */
    private fun currentProxy(): String = normalizeProxy(proxyEdit.text.toString())

    private suspend fun fetchInfo(url: String, proxy: String): VideoMeta {
        Engine.awaitReady()
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-playlist")
        if (proxy.isNotEmpty()) req.addOption("--proxy", proxy)
        val out = YoutubeDL.getInstance().execute(req, "info", noProgress).out

        val start = out.indexOf('{')
        if (start < 0) throw IllegalStateException("yt-dlp не вернул данные о видео")
        val json = JSONObject(out.substring(start))

        val title = json.optString("title", "Видео")
        val duration = json.optDouble("duration", 0.0).toLong()

        val heightSize = HashMap<Int, Long>()
        var audioSize = 0L
        val formats = json.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val h = f.optInt("height", 0)
                val vcodec = f.optString("vcodec", "none")
                var size = f.optLong("filesize", 0L)
                if (size == 0L) size = f.optLong("filesize_approx", 0L)
                if (h > 0 && vcodec != "none") {
                    heightSize[h] = maxOf(heightSize[h] ?: 0L, size)
                } else if (vcodec == "none") {
                    audioSize = maxOf(audioSize, size)
                }
            }
        }

        val choices = ArrayList<Choice>()
        var defaultIndex = -1
        for (h in heightSize.keys.sortedDescending().take(6)) {
            val size = heightSize[h] ?: 0L
            val sizeText = if (size > 0) " · ~${human(size + audioSize)}" else ""
            if (defaultIndex < 0 && h <= 1080) defaultIndex = choices.size
            choices.add(Choice("${h}p$sizeText", h.toString()))
        }
        choices.add(Choice("Лучшее доступное", "best"))
        choices.add(Choice("Только аудио (mp3)", "audio"))
        if (defaultIndex < 0) defaultIndex = 0
        return VideoMeta(title, duration, choices, defaultIndex)
    }

    private fun showInfo(meta: VideoMeta) {
        val dur = if (meta.duration > 0) " · ${fmtTime(meta.duration)}" else ""
        infoText.text = meta.title + dur
        choiceValues = meta.choices.map { it.value }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            meta.choices.map { it.label },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(meta.defaultIndex)
        hasInfo = true
        statusText.text = "Выберите качество и нажмите «Скачать»"
    }

    private fun onAction() {
        if (running) {
            // Кнопка работает как «Отмена», пока идёт загрузка
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
            statusText.text = "Отменяю…"
            return
        }
        if (!hasInfo) return
        val choice = choiceValues.getOrNull(spinner.selectedItemPosition) ?: "best"
        DownloadState.state.value = DlState(Phase.RUNNING, 0, "Запускаю загрузку…")
        val intent = Intent(this, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_URL, currentUrl)
            .putExtra(DownloadService.EXTRA_CHOICE, choice)
            .putExtra(DownloadService.EXTRA_PROXY, currentProxy())
        startForegroundService(intent)
    }
}
