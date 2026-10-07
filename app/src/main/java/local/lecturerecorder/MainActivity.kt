package local.lecturerecorder

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var level: ProgressBar
    private lateinit var transcript: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var manualButton: Button
    private lateinit var stopButton: Button
    private lateinit var checks: LinearLayout
    private lateinit var lectureList: LinearLayout
    private lateinit var pendingText: TextView
    private lateinit var baseFolderText: TextView

    private var modelState = "確認中…"
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }
    private val serviceListener: () -> Unit = { runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        RecorderService.createChannels(this)
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_RESUME && store.autoEnabled) ensureServiceRunning()
    }

    override fun onResume() {
        super.onResume()
        RecorderService.listeners += serviceListener
        if (store.autoEnabled) ensureServiceRunning()
        // 「アラームとリマインダー」を許可して戻ってきた場合などに、アラームを設定し直す
        RecorderService.instance?.evaluate()
        checkModel()
        refreshAll()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        RecorderService.listeners -= serviceListener
        handler.removeCallbacks(refresher)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasNotif() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun canExactAlarm() = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** 必要な実行時の権限（通知の権限は Android 13 から） */
    private fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    /** 「使用されていないアプリ」の自動削除（休止状態）の対象外になっているか */
    private fun unusedAppRestrictionsOff(): Boolean =
        runCatching { packageManager.isAutoRevokeWhitelisted }.getOrDefault(false)

    /** 「使用されていないアプリ」の設定画面（アプリ情報）を開く */
    private fun openUnusedAppSettings() {
        AlertDialog.Builder(this)
            .setTitle("「使用されていないアプリ」をオフにしてください")
            .setMessage(
                "この設定が有効なままだと、しばらくアプリを開かなかったときに Android が権限と保存先の許可を取り消し、" +
                    "録音が保存できなくなります（実際に取り消された例があります）。\n\n" +
                    "設定画面で「アプリが使用されていない場合に権限を削除」や「使用されていないアプリを一時停止する」をオフにしてください。",
            )
            .setPositiveButton("設定を開く") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.onFailure { toast("設定画面を開けませんでした：${it.message}") }
            }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun ensureServiceRunning() {
        if (!hasMic()) {
            requestPermissions(runtimePermissions(), REQ_PERM)
            return
        }
        if (RecorderService.instance == null) RecorderService.startFromUi(this)
    }

    // ---------------- 画面の組み立て ----------------

    private fun buildUi(): View {
        val root = vbox().apply { setPadding(dp(16), dp(16), dp(16), dp(32)) }

        statusTitle = text("", 22f, bold = true)
        statusDetail = text("", 14f)
        level = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        transcript = text("", 13f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(card(statusTitle, statusDetail, level, transcript))

        autoSwitch = Switch(this).apply {
            text = "時間割どおりに自動録音する"
            textSize = 17f
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, checked -> onAutoToggled(checked) }
        }
        root.addView(autoSwitch)

        manualButton = button("今すぐ録音（科目を選ぶ）", weight = true) { pickManualLecture() }
        stopButton = button("この回の録音を停止", weight = true) {
            RecorderService.startFromUi(this, RecorderService.ACTION_STOP_CURRENT)
        }
        root.addView(hbox(manualButton, stopButton))

        root.addView(header("事前チェック"))
        checks = vbox()
        root.addView(checks)

        root.addView(header("保存"))
        baseFolderText = text("", 14f)
        root.addView(baseFolderText)
        root.addView(text(
            "親フォルダーを1つ選ぶと、その下に科目名のフォルダーを自動で作って保存します（同じ名前のフォルダーが既にあればそれを使います）。" +
                "科目ごとに別の場所にしたいときは、その科目の編集画面で個別に指定できます（個別の指定が優先されます）。", 13f,
        ))
        root.addView(hbox(
            button("親フォルダーを選ぶ", weight = true) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    )
                }, REQ_BASE_FOLDER)
            },
            button("端末に保存", weight = true) {
                store.baseFolderUri = null
                store.baseFolderLabel = null
                refreshAll()
            },
        ))
        pendingText = text("", 14f)
        root.addView(pendingText)
        root.addView(button("保存待ちを今すぐ書き出す") {
            Thread {
                val left = Exporter.flush(this)
                runOnUiThread { refreshAll(); toast(if (left == 0) "書き出しました" else "${left}件が保存待ちのままです") }
            }.start()
        })

        root.addView(header("時間割"))
        lectureList = vbox()
        root.addView(lectureList)
        root.addView(button("＋ 授業を追加") {
            startActivity(Intent(this, LectureEditActivity::class.java))
        })

        root.addView(header("時間割ファイル（JSON）"))
        root.addView(text(
            "時間割を JSON ファイルでまとめて読み込めます。書き方はサンプルの中に説明があります。" +
                "「今の時間割を保存」は、アプリを入れ直す前のバックアップにも使えます（保存先フォルダーは含みません）。", 13f,
        ))
        root.addView(button("JSON から時間割を読み込む") { openTimetableJson() })
        root.addView(hbox(
            button("サンプルを保存", weight = true) { createJsonFile(REQ_SAVE_SAMPLE, "講義録音_時間割サンプル.json") },
            button("今の時間割を保存", weight = true) {
                if (store.lectures.isEmpty()) toast("時間割が空です")
                else createJsonFile(REQ_SAVE_EXPORT, "講義録音_時間割_${java.time.LocalDate.now()}.json")
            },
        ))

        root.addView(header("録音の前後の余裕"))
        root.addView(numberRow("開始を早める（分）", store.startEarlyMin) { store.startEarlyMin = it; afterScheduleChange() })
        root.addView(numberRow("終了を遅らせる（分）", store.endLateMin) { store.endLateMin = it; afterScheduleChange() })

        return screen(getString(R.string.app_name), root)
    }

    private fun onAutoToggled(checked: Boolean) {
        if (checked == store.autoEnabled) return
        if (checked) {
            store.autoEnabled = true
            ensureServiceRunning()
            if (!unusedAppRestrictionsOff()) openUnusedAppSettings()
        } else {
            if (RecorderService.instance != null) {
                RecorderService.startFromUi(this, RecorderService.ACTION_DISABLE)
            } else {
                store.autoEnabled = false
                RecorderService.scheduleAlarm(this, store)
            }
        }
        refreshAll()
    }

    private fun afterScheduleChange() {
        RecorderService.instance?.evaluate()
        refreshAll()
    }

    private fun pickManualLecture() {
        val lectures = store.lectures.distinctBy { it.name }
        if (lectures.isEmpty()) {
            toast("先に時間割へ授業を追加してください")
            return
        }
        if (!hasMic()) {
            ensureServiceRunning()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("どの授業として録音しますか")
            .setItems(lectures.map { it.name }.toTypedArray()) { _, which ->
                RecorderService.startFromUi(this, RecorderService.ACTION_MANUAL_START, lectures[which].id)
            }
            .show()
    }

    // ---------------- 表示の更新 ----------------

    private fun refreshAll() {
        autoSwitch.isChecked = store.autoEnabled
        refreshChecks()
        refreshLectures()
        refreshStatus()
    }

    private fun refreshStatus() {
        val fmt = DateTimeFormatter.ofPattern("M/d(E) H:mm", Locale.JAPAN)
        val hm = DateTimeFormatter.ofPattern("H:mm")
        val st = RecorderService.instance?.snapshot()
        if (st?.recordingName != null) {
            val sec = java.time.Duration.between(st.recordingSince, LocalDateTime.now()).seconds
            statusTitle.text = "● 録音中：${st.recordingName}"
            statusTitle.setTextColor(0xFFC62828.toInt())
            statusDetail.text = buildString {
                append("経過 %d:%02d:%02d／%s に停止".format(sec / 3600, sec / 60 % 60, sec % 60, st.recordingUntil?.format(hm)))
                if (st.manual) append("（手動）")
                st.transcriberStatus?.let { append("\n$it") }
            }
            level.visibility = View.VISIBLE
            level.progress = (st.level * 100).toInt()
            val t = listOfNotNull(st.transcriptTail?.takeIf { it.isNotEmpty() }, st.partial?.let { "… $it" }).joinToString("\n")
            transcript.text = t.lines().takeLast(8).joinToString("\n")
            transcript.visibility = View.VISIBLE
            stopButton.isEnabled = true
        } else {
            statusTitle.setTextColor(statusDetail.currentTextColor)
            level.visibility = View.GONE
            transcript.visibility = View.GONE
            stopButton.isEnabled = false
            if (store.autoEnabled && RecorderService.instance != null) {
                statusTitle.text = "自動録音 待機中"
                statusDetail.text = st?.next?.let { "次：${it.lecture.name}  ${it.start.format(fmt)}〜${it.end.format(hm)}" }
                    ?: "予定されている授業はありません"
            } else if (store.autoEnabled) {
                statusTitle.text = "自動録音を再開してください"
                statusDetail.text = "マイクの権限を許可すると待機を始めます"
            } else {
                statusTitle.text = "自動録音 オフ"
                statusDetail.text = "下のスイッチをオンにすると、時間割どおりに録音します"
            }
        }
    }

    private fun refreshChecks() {
        checks.removeAllViews()
        fun row(ok: Boolean, label: String, fixLabel: String?, fix: (() -> Unit)?) {
            val t = text((if (ok) "✓ " else "✗ ") + label, 14f).apply {
                setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val r = hbox(t).apply { gravity = Gravity.CENTER_VERTICAL }
            if (!ok && fixLabel != null && fix != null) r.addView(button(fixLabel) { fix() })
            checks.addView(r)
        }
        row(hasMic(), "マイクの権限", "許可") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            row(hasNotif(), "通知の権限（待機中・録音中の表示）", "許可") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM)
            }
        } else {
            row(canExactAlarm(), "アラームとリマインダー（授業の時刻どおりに開始するため）", "設定") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        row(batteryExempt(), "電池の最適化の対象外（授業中に止められないため）", "設定") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        row(
            unusedAppRestrictionsOff(),
            "「使用されていないアプリ」の対象外（権限と保存先の許可が消えるのを防ぐ）",
            "設定",
        ) { openUnusedAppSettings() }
        fun granted(uri: String?) = uri != null &&
            contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }
        val base = store.baseFolderUri
        // 個別の保存先の許可が外れていて親フォルダーがあるなら、親フォルダーに任せる
        if (base != null) {
            store.lectures.filter { it.folderUri != null && !granted(it.folderUri) }.forEach {
                store.upsert(it.copy(folderUri = null, folderLabel = null))
            }
        }
        val lost = store.lectures.filter { it.folderUri != null && !granted(it.folderUri) }.map { it.name }
        val baseLost = base != null && !granted(base)
        row(
            lost.isEmpty() && !baseLost && (base != null || store.lectures.all { it.folderUri != null }),
            when {
                baseLost -> "親フォルダーの許可が外れています（選び直してください）"
                lost.isNotEmpty() -> "保存先の許可が外れています：${lost.joinToString("、")}（科目を開いて選び直してください）"
                base != null -> "保存先：${store.baseFolderLabel}／科目名"
                else -> "保存先が未設定（端末の Documents/$DEFAULT_DIR/ に保存します）"
            },
            null, null,
        )
        row(modelState.startsWith("OK"), "日本語の端末内音声認識：$modelState", "ダウンロード".takeIf { Build.VERSION.SDK_INT >= 33 }) { downloadModel() }
    }

    private fun refreshLectures() {
        lectureList.removeAllViews()
        val lectures = store.lectures
        if (lectures.isEmpty()) {
            lectureList.addView(text("まだ授業が登録されていません", 14f))
        }
        val hm = DateTimeFormatter.ofPattern("H:mm")
        for (l in lectures) {
            val title = text((if (l.enabled) "" else "（停止中）") + l.name, 16f, bold = true)
            val day = l.day.getDisplayName(TextStyle.SHORT, Locale.JAPAN)
            val sub = buildString {
                append("$day  ${l.start.format(hm)}〜${l.end.format(hm)}")
                if (l.dates.isNotEmpty()) {
                    val md = DateTimeFormatter.ofPattern("M/d")
                    val next = l.nextDate(java.time.LocalDate.now())
                    append("  ／ 授業日 ${l.dates.size}回（次回 ${next?.format(md) ?: "なし"}・最終 ${l.dates.max().format(md)}）")
                }
                l.skipDate?.takeIf { !it.isBefore(java.time.LocalDate.now()) }?.let {
                    append("  ／ ${it.format(DateTimeFormatter.ofPattern("M/d"))} は録音しない")
                }
                if (l.language != Language.JA) append("  ／ 言語：${l.language.label}")
                val where = l.folderLabel
                    ?: store.baseFolderLabel?.let { "$it/${l.name}" }
                    ?: "端末 Documents/$DEFAULT_DIR/${l.name}"
                append("\n保存先：$where")
            }
            val row = card(title, text(sub, 13f)).apply {
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, LectureEditActivity::class.java).putExtra(LectureEditActivity.EXTRA_ID, l.id))
                }
            }
            lectureList.addView(row)
        }
        val pending = store.pending
        baseFolderText.text = store.baseFolderLabel?.let { "親フォルダー：$it（この下に科目名のフォルダーを作ります）" }
            ?: "親フォルダー：未設定（端末の Documents/$DEFAULT_DIR/ に保存します）"
        pendingText.text = buildString {
            append(if (pending.isEmpty()) "保存待ちのファイルはありません" else "保存待ち ${pending.size}件：" + pending.joinToString("、") { it.displayName })
            store.lastError?.let { append("\n最後のエラー：$it") }
        }
    }

    // ---------------- 音声認識モデル ----------------

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

    private fun checkModel() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            modelState = "この端末では使えません"
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            // Android 12 には言語モデルを調べる手段がない
            modelState = "OK（Android 12 のため日本語モデルの有無は確認できません）"
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        r.checkRecognitionSupport(recognizerIntent(), mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                fun has(list: List<String>) = list.any { it.startsWith("ja") }
                modelState = when {
                    has(support.installedOnDeviceLanguages) -> "OK"
                    has(support.pendingOnDeviceLanguages) -> "ダウンロード中"
                    has(support.supportedOnDeviceLanguages) -> "未ダウンロード"
                    else -> "日本語に非対応"
                }
                r.destroy()
                refreshChecks()
            }

            override fun onError(error: Int) {
                modelState = "確認できません（エラー $error）"
                r.destroy()
                refreshChecks()
            }
        })
    }

    private fun downloadModel() {
        if (Build.VERSION.SDK_INT < 33) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        r.triggerModelDownload(recognizerIntent())
        toast("日本語モデルのダウンロードを依頼しました。しばらくしてから開き直してください")
        handler.postDelayed({ r.destroy(); checkModel() }, 5000)
    }

    // ---------------- 時間割ファイル（JSON） ----------------

    private fun openTimetableJson() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(TimetableJson.MIME, "text/plain", "text/*", "application/octet-stream"))
        }
        startActivityForResult(i, REQ_OPEN_JSON)
    }

    private fun createJsonFile(req: Int, name: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = TimetableJson.MIME
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, req)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_BASE_FOLDER -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    store.baseFolderUri = uri.toString()
                    store.baseFolderLabel = Exporter.treeLabel(this, uri)
                    Exporter.clearFolderCache(this)
                    refreshAll()
                    toast("親フォルダーを設定しました。科目名のフォルダーを自動で作ります")
                } catch (e: SecurityException) {
                    toast("このフォルダーには書き込みの許可を保存できませんでした：${e.message}")
                }
            }
            REQ_OPEN_JSON -> readTimetableJson(uri)
            REQ_SAVE_SAMPLE -> writeText(uri, TimetableJson.sample(), "サンプルを保存しました。メモ帳などで書き換えてから読み込んでください")
            REQ_SAVE_EXPORT -> writeText(uri, TimetableJson.export(store.lectures), "今の時間割を保存しました")
        }
    }

    private fun writeText(uri: Uri, text: String, done: String) {
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.onSuccess { toast(done) }.onFailure { toast("保存できませんでした：${it.message}") }
    }

    private fun readTimetableJson(uri: Uri) {
        val entries = try {
            val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= MAX_JSON_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (bytes.size > MAX_JSON_BYTES) throw TimetableJson.FormatException("ファイルが大きすぎます（1MB まで）。")
            TimetableJson.parse(String(bytes, Charsets.UTF_8))
        } catch (e: TimetableJson.FormatException) {
            showError(e.message.orEmpty())
            return
        } catch (e: Exception) {
            showError("ファイルを読めませんでした：${e.message}")
            return
        }
        val merge = TimetableJson.plan(store.lectures, entries, replace = false)
        val replace = TimetableJson.plan(store.lectures, entries, replace = true)
        val hm = DateTimeFormatter.ofPattern("H:mm")
        val message = buildString {
            append("${entries.size}コマの授業が見つかりました。\n\n")
            for (e in entries.sortedWith(compareBy({ it.day }, { it.start }))) {
                append("${TimetableJson.jaDay(e.day)} ${e.start.format(hm)}〜${e.end.format(hm)}  ${e.name}")
                append(if (e.dates.isEmpty()) "（毎週）" else "（授業日${e.dates.size}回）")
                if (!e.enabled) append("（自動録音しない）")
                append("\n")
            }
            append("\n追加・更新：追加 ${merge.added}・更新 ${merge.updated}。今ある他の授業は残します。")
            if (replace.removed.isNotEmpty()) {
                append("\n置き換え：上に加えて、ファイルにない ${replace.removed.size} コマ（")
                append(replace.removed.joinToString("、") { "${TimetableJson.jaDay(it.day)} ${it.name}" })
                append("）を削除します。")
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("時間割を読み込みます")
            .setMessage(message)
            .setPositiveButton("追加・更新") { _, _ -> applyPlan(merge) }
            .setNegativeButton("やめる", null)
        if (replace.removed.isNotEmpty()) dialog.setNeutralButton("置き換え") { _, _ -> applyPlan(replace) }
        dialog.show()
    }

    private fun applyPlan(plan: TimetableJson.Plan) {
        store.lectures = plan.result
        RecorderService.instance?.evaluate() ?: RecorderService.scheduleAlarm(this, store)
        refreshAll()
        toast("読み込みました（追加 ${plan.added}・更新 ${plan.updated}・削除 ${plan.removed.size}）。保存先は科目ごとに確認してください")
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("読み込めませんでした")
            .setMessage(message + "\n\n書き方は「サンプルを保存」で出てくるファイルの _説明 を見てください。")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (store.autoEnabled && hasMic()) ensureServiceRunning()
        refreshAll()
    }

    // ---------------- 小さな部品 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun hbox(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(s: String) = text(s, 18f, bold = true).apply { setPadding(0, dp(24), 0, dp(8)) }

    private fun button(label: String, weight: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        if (weight) layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun card(vararg views: View) = vbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(if (isNight()) 0xFF2A2A2A.toInt() else 0xFFF1F1F1.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
        views.forEach { addView(it) }
    }

    private fun isNight() = resources.configuration.isNightModeActive

    private fun numberRow(label: String, value: Int, onChange: (Int) -> Unit): View {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            width = dp(80)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    s?.toString()?.toIntOrNull()?.let(onChange)
                }
            })
        }
        val t = text(label, 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        return hbox(t, edit).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_RESUME = "resume"
        private const val REQ_PERM = 10
        private const val REQ_BASE_FOLDER = 29
        private const val REQ_OPEN_JSON = 30
        private const val REQ_SAVE_SAMPLE = 31
        private const val REQ_SAVE_EXPORT = 32
        private const val MAX_JSON_BYTES = 1024 * 1024
    }
}
