package local.lecturerecorder

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class LectureEditActivity : Activity() {

    private lateinit var store: Store
    private var original: Lecture? = null

    private lateinit var nameEdit: AutoCompleteTextView
    private lateinit var daySpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var enabledSwitch: Switch
    private lateinit var folderText: TextView
    private lateinit var skipSwitch: Switch

    private var start = LocalTime.of(9, 0)
    private var end = LocalTime.of(10, 30)
    private var folderUri: String? = null
    private var folderLabel: String? = null

    private val days = DayOfWeek.entries
    private val hm = DateTimeFormatter.ofPattern("H:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        original = intent.getStringExtra(EXTRA_ID)?.let { store.find(it) }
        title = if (original == null) "授業を追加" else "授業を編集"
        original?.let {
            start = it.start
            end = it.end
            folderUri = it.folderUri
            folderLabel = it.folderLabel
        }
        setContentView(buildUi())
        savedInstanceState?.let {
            folderUri = it.getString("folderUri")
            folderLabel = it.getString("folderLabel")
            start = LocalTime.parse(it.getString("start"))
            end = LocalTime.parse(it.getString("end"))
            refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("folderUri", folderUri)
        outState.putString("folderLabel", folderLabel)
        outState.putString("start", start.toString())
        outState.putString("end", end.toString())
    }

    private fun buildUi(): View {
        val o = original
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        root.addView(label("科目名（ファイル名になります）"))
        nameEdit = AutoCompleteTextView(this).apply {
            setSingleLine()
            setText(o?.name.orEmpty())
            val names = store.lectures.map { it.name }.distinct()
            setAdapter(ArrayAdapter(this@LectureEditActivity, android.R.layout.simple_dropdown_item_1line, names))
            threshold = 1
        }
        root.addView(nameEdit)

        root.addView(label("曜日"))
        daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LectureEditActivity, android.R.layout.simple_spinner_dropdown_item,
                days.map { it.getDisplayName(TextStyle.FULL, Locale.JAPAN) },
            )
            setSelection(days.indexOf(o?.day ?: LocalDate.now().dayOfWeek))
        }
        root.addView(daySpinner)

        root.addView(label("時刻"))
        startButton = Button(this).apply { setOnClickListener { pickTime(true) } }
        endButton = Button(this).apply { setOnClickListener { pickTime(false) } }
        root.addView(LinearLayout(this).apply {
            addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@LectureEditActivity).apply { text = " 〜 " })
            addView(endButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(label("文字起こしの言語"))
        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LectureEditActivity, android.R.layout.simple_spinner_dropdown_item,
                Language.entries.map { it.label },
            )
            setSelection(Language.entries.indexOf(o?.language ?: Language.JA))
        }
        root.addView(languageSpinner)
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "「日本語＋英語」は、話している言語に合わせて認識を切り替えます（Android 14 以降の端末のみ。" +
                "非対応の端末では日本語で認識します）。英語だけの授業は「英語」を選びます。"
        })

        enabledSwitch = Switch(this).apply {
            text = "この授業を自動録音する"
            isChecked = o?.enabled ?: true
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(enabledSwitch)

        root.addView(label("保存先"))
        folderText = TextView(this).apply { textSize = 14f }
        root.addView(folderText)
        root.addView(LinearLayout(this).apply {
            addView(button("フォルダーを選ぶ") { pickFolder() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("個別の指定を外す") { folderUri = null; folderLabel = null; refresh() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Google ドライブに保存する場合は、フォルダー選択画面の左上メニューから Google ドライブを選び、" +
                "科目のフォルダーを選んで「このフォルダを使用」を押してください。" +
                "OneDrive アプリはフォルダーの選択に対応していないため、一覧に出ません。" +
                "同じ科目の別のコマにも同じ保存先が適用されます。"
        })

        skipSwitch = Switch(this).apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(skipSwitch)

        root.addView(button("保存") { save() })
        if (o != null) {
            root.addView(button("このコマを削除") {
                AlertDialog.Builder(this)
                    .setMessage("「${o.name}」（${o.day.getDisplayName(TextStyle.FULL, Locale.JAPAN)}）を削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        store.delete(o.id)
                        releaseUnusedPermissions()
                        RecorderService.instance?.evaluate()
                        finish()
                    }
                    .setNegativeButton("やめる", null)
                    .show()
            })
        }
        refresh()
        skipSwitch.isChecked = o?.skipDate != null && o.skipDate == nextDate()
        daySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        return screen(title.toString(), root)
    }

    private fun nextDate(): LocalDate {
        val day = days[daySpinner.selectedItemPosition]
        val today = LocalDate.now()
        // 授業日の一覧があり曜日を変えていなければ、一覧から次回を選ぶ
        original?.takeIf { it.dates.isNotEmpty() && it.day == day }?.let { o ->
            val from = if (LocalTime.now() >= end) today.plusDays(1) else today
            o.nextDate(from)?.let { return it }
        }
        val base = today.with(TemporalAdjusters.nextOrSame(day))
        // 今日の授業がもう終わっていれば来週
        return if (base == today && LocalTime.now() >= end) base.plusWeeks(1) else base
    }

    private fun refresh() {
        startButton.text = "開始 ${start.format(hm)}"
        endButton.text = "終了 ${end.format(hm)}"
        folderText.text = folderLabel
            ?: Store(this).baseFolderLabel?.let { "親フォルダーに従う：$it/${nameEdit.text}" }
            ?: "端末：Documents/$DEFAULT_DIR/（科目名）"
        val next = nextDate()
        skipSwitch.text = "次回（${next.format(DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPAN))}）は録音しない"
    }

    private fun pickTime(isStart: Boolean) {
        val t = if (isStart) start else end
        TimePickerDialog(this, { _, h, m ->
            val picked = LocalTime.of(h, m)
            if (isStart) {
                // 開始を動かしたら、授業の長さを保ったまま終了も動かす
                val length = java.time.Duration.between(start, end)
                start = picked
                end = picked.plus(length)
            } else {
                end = picked
            }
            refresh()
        }, t.hour, t.minute, true).show()
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_FOLDER)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            toast("このフォルダーには書き込みの許可を保存できませんでした：${e.message}")
            return
        }
        folderUri = uri.toString()
        folderLabel = Exporter.treeLabel(this, uri)
        refresh()
    }

    private fun save() {
        val name = nameEdit.text.toString().trim()
        if (name.isEmpty()) {
            toast("科目名を入力してください")
            return
        }
        if (start == end) {
            toast("開始と終了が同じ時刻です")
            return
        }
        val next = nextDate()
        val skip = when {
            skipSwitch.isChecked -> next
            original?.skipDate == next -> null
            else -> original?.skipDate?.takeIf { !it.isBefore(LocalDate.now()) }
        }
        val lecture = (original ?: Lecture(name = name, day = DayOfWeek.MONDAY, start = start, end = end)).copy(
            name = name,
            day = days[daySpinner.selectedItemPosition],
            start = start,
            end = end,
            enabled = enabledSwitch.isChecked,
            language = Language.entries[languageSpinner.selectedItemPosition],
            folderUri = folderUri,
            folderLabel = folderLabel,
            skipDate = skip,
        )
        store.upsert(lecture)
        // 同じ科目の別のコマにも保存先をそろえる
        if (original?.folderUri != folderUri) {
            store.lectures.filter { it.name == name && it.id != lecture.id }.forEach {
                store.upsert(it.copy(folderUri = folderUri, folderLabel = folderLabel))
            }
        }
        releaseUnusedPermissions()
        RecorderService.instance?.evaluate() ?: RecorderService.scheduleAlarm(this, store)
        finish()
    }

    /** どの授業からも使われなくなった保存先の権限を返す */
    private fun releaseUnusedPermissions() {
        val used = store.lectures.mapNotNull { it.folderUri }.toSet()
        for (p in contentResolver.persistedUriPermissions) {
            if (p.uri.toString() !in used) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ID = "id"
        private const val REQ_FOLDER = 20
    }
}
