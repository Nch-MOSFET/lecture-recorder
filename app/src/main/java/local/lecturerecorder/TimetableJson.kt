package local.lecturerecorder

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 時間割ファイル（JSON）の読み書き。
 * 「_」で始まる項目は説明用として読み飛ばすので、ファイルの中に書き方を残しておける。
 */
object TimetableJson {
    const val FORMAT = "lecture-recorder-timetable"
    const val VERSION = 1
    const val MIME = "application/json"
    private const val BOM = "\uFEFF"

    /** ファイルに入れる書き方の説明（サンプル・書き出しの両方に付ける） */
    private val GUIDE = listOf(
        "このファイルは Android アプリ「講義録音」の時間割ファイルです。アプリの「JSON から時間割を読み込む」で取り込めます。",
        "「_」で始まる項目（この _説明 など）は説明用で、読み込み時は無視されます。消しても構いません。",
        "lectures：授業（コマ）の一覧です。1コマにつき { } を1つ書きます。同じ科目が週2回あれば2つ書きます。",
        "name（必須）：科目名。録音ファイルの名前になります（例：英語_2026-04-08.m4a）。",
        "day（必須）：曜日。\"月\" \"火\" \"水\" \"木\" \"金\" \"土\" \"日\" のどれか（\"月曜\" \"Mon\" や、1=月〜7=日 の数字でも可）。",
        "start / end（必須）：開始・終了時刻。24時間表記の \"HH:MM\"（例：\"13:00\"）。",
        "dates（省略可）：授業日の一覧。\"YYYY-MM-DD\"（例：\"2026-04-08\"）。書いた日だけ録音します。省略するか [] にすると毎週録音します。",
        "enabled（省略可）：false にすると、そのコマは自動録音しません。省略時は true。",
        "language（省略可）：文字起こしの言語。\"ja\"（日本語・省略時）、\"ja+en\"（日本語と英語が混ざる授業。Android 14 以降で自動的に切り替え）、\"en\"（英語）。",
        "読み込むとき、同じ科目名・同じ曜日のコマがアプリにあれば、時刻・授業日・enabled・language を上書きします。保存先フォルダーはそのまま残ります。",
        "「置き換え」で読み込むと、このファイルにないコマはアプリから削除されます。",
        "保存先フォルダーはこのファイルでは指定できません。読み込んだ後、アプリで科目ごとに選んでください（同じ科目名のコマがあれば引き継ぎます）。",
        "編集はメモ帳などで行い、文字コードは UTF-8 で保存してください。項目の間はカンマで区切り、最後の項目の後ろにはカンマを付けません。",
    )

    data class Entry(
        val name: String,
        val day: DayOfWeek,
        val start: LocalTime,
        val end: LocalTime,
        val dates: List<LocalDate>,
        val enabled: Boolean,
        val language: Language,
    )

    class FormatException(message: String) : Exception(message)

    // ---------------- 読み込み ----------------

    fun parse(text: String): List<Entry> {
        val root = try {
            JSONObject(text.removePrefix(BOM))
        } catch (e: JSONException) {
            throw FormatException("JSON の書き方に誤りがあります${lineHint(text, e.message)}。カンマや括弧、\" の付け忘れがないか確認してください。")
        }
        val arr = root.optJSONArray("lectures")
            ?: throw FormatException("\"lectures\" の一覧が見つかりません。")
        if (arr.length() == 0) throw FormatException("\"lectures\" に授業が1つもありません。")
        return (0 until arr.length()).map { i ->
            val where = "${i + 1}件目の授業"
            val o = arr.optJSONObject(i) ?: throw FormatException("$where が { } の形になっていません。")
            val name = o.optString("name").trim()
            if (name.isEmpty()) throw FormatException("$where：name（科目名）がありません。")
            val label = "$where（$name）"
            Entry(
                name = name,
                day = parseDay(o.opt("day")) ?: throw FormatException("$label：day（曜日）は \"月\"〜\"日\" で書いてください。"),
                start = parseTime(o.optString("start")) ?: throw FormatException("$label：start（開始時刻）は \"13:00\" のように書いてください。"),
                end = parseTime(o.optString("end")) ?: throw FormatException("$label：end（終了時刻）は \"14:30\" のように書いてください。"),
                dates = o.optJSONArray("dates")?.let { a ->
                    (0 until a.length()).map { j ->
                        parseDate(a.optString(j))
                            ?: throw FormatException("$label：dates の ${j + 1}番目「${a.optString(j)}」は \"2026-04-08\" のように書いてください。")
                    }.distinct().sorted()
                }.orEmpty(),
                enabled = o.optBoolean("enabled", true),
                language = parseLanguage(o.optString("language"))
                    ?: throw FormatException("$label：language は \"ja\"・\"ja+en\"・\"en\" のどれかで書いてください。"),
            ).also {
                if (it.start == it.end) throw FormatException("$label：start と end が同じ時刻です。")
                it.dates.firstOrNull { d -> d.dayOfWeek != it.day }?.let { d ->
                    throw FormatException("$label：dates の ${d} は${jaDay(d.dayOfWeek)}曜日で、day（${jaDay(it.day)}）と合いません。")
                }
            }
        }
    }

    data class Plan(val added: Int, val updated: Int, val removed: List<Lecture>, val result: List<Lecture>)

    /** 取り込んだ結果を計算する（保存はしない）。replace なら JSON にないコマを消す。 */
    fun plan(current: List<Lecture>, entries: List<Entry>, replace: Boolean): Plan {
        var lectures = current
        var added = 0
        var updated = 0
        val touched = mutableSetOf<String>()
        for (e in entries) {
            val same = lectures.firstOrNull { it.name == e.name && it.day == e.day && it.id !in touched }
            if (same != null) {
                lectures = lectures.map {
                    if (it.id == same.id) it.copy(start = e.start, end = e.end, dates = e.dates, enabled = e.enabled, language = e.language) else it
                }
                touched += same.id
                updated++
            } else {
                val folderFrom = lectures.firstOrNull { it.name == e.name && it.folderUri != null }
                val l = Lecture(
                    name = e.name, day = e.day, start = e.start, end = e.end, dates = e.dates, enabled = e.enabled,
                    language = e.language,
                    folderUri = folderFrom?.folderUri, folderLabel = folderFrom?.folderLabel,
                )
                lectures = lectures + l
                touched += l.id
                added++
            }
        }
        val removed = if (replace) lectures.filter { it.id !in touched } else emptyList()
        return Plan(added, updated, removed, lectures.filter { it !in removed })
    }

    // ---------------- 書き出し ----------------

    /** 書き方の説明と記入例だけのファイル */
    fun sample(): String = document(
        listOf(
            JSONObject()
                .put("_メモ", "授業日を指定する例。dates に書いた日だけ録音します。")
                .put("name", "英語").put("day", "水").put("start", "10:40").put("end", "12:10")
                .put("dates", JSONArray(listOf("2026-04-08", "2026-04-15", "2026-04-22")))
                .put("language", "ja+en"),
            JSONObject()
                .put("_メモ", "dates を省略した例。毎週月曜に録音します。")
                .put("name", "数学").put("day", "月").put("start", "9:00").put("end", "10:30"),
            JSONObject()
                .put("_メモ", "自動録音を止めておく例（enabled を false）。")
                .put("name", "体育").put("day", "金").put("start", "13:00").put("end", "14:30")
                .put("enabled", false),
        ),
        note = "記入例です。自分の時間割に書き換えてから読み込んでください。",
    )

    /** 今アプリにある時間割 */
    fun export(lectures: List<Lecture>): String = document(
        lectures.map { l ->
            JSONObject()
                .put("name", l.name).put("day", jaDay(l.day))
                .put("start", l.start.format(HM)).put("end", l.end.format(HM))
                .apply {
                    if (l.dates.isNotEmpty()) put("dates", JSONArray(l.dates.map { it.toString() }))
                    if (!l.enabled) put("enabled", false)
                    if (l.language != Language.JA) put("language", l.language.key)
                }
        },
        note = "アプリから書き出した時間割です（${LocalDate.now()}）。保存先フォルダーは含みません。",
    )

    private fun document(lectures: List<JSONObject>, note: String): String {
        val root = JSONObject()
            .put("_説明", JSONArray(GUIDE))
            .put("_このファイルについて", note)
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("lectures", JSONArray(lectures))
        return pretty(root)
    }

    /** 日付の並びは1行に収め、それ以外は字下げして読みやすくする */
    private fun pretty(root: JSONObject): String {
        val text = root.toString(2).replace("\\/", "/")
        val date = "\"\\d{4}-\\d{2}-\\d{2}\""
        return Regex("\\[\\s*($date(?:,\\s*$date)*)\\s*]").replace(text) { m ->
            "[" + m.groupValues[1].split(Regex(",\\s*")).joinToString(", ") + "]"
        } + "\n"
    }

    // ---------------- 部品 ----------------

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun jaDay(d: DayOfWeek): String = d.getDisplayName(TextStyle.NARROW, Locale.JAPAN)

    private fun parseDay(v: Any?): DayOfWeek? {
        if (v is Number) return v.toInt().takeIf { it in 1..7 }?.let(DayOfWeek::of)
        val s = v?.toString()?.trim()?.lowercase() ?: return null
        s.toIntOrNull()?.let { return it.takeIf { n -> n in 1..7 }?.let(DayOfWeek::of) }
        val ja = "月火水木金土日".indexOf(s.firstOrNull() ?: return null)
        if (ja >= 0 && (s.length == 1 || s.endsWith("曜") || s.endsWith("曜日"))) return DayOfWeek.of(ja + 1)
        return DayOfWeek.entries.firstOrNull { s.length >= 3 && it.name.lowercase().startsWith(s) }
    }

    /** 空欄は日本語。「日本語」「英語」「日本語＋英語」なども受け付ける */
    private fun parseLanguage(s: String): Language? {
        val v = s.trim().lowercase().replace("＋", "+").replace("・", "+").replace(" ", "")
        if (v.isEmpty()) return Language.JA
        return when (v) {
            "ja", "jp", "ja-jp", "日本語" -> Language.JA
            "ja+en", "en+ja", "ja-jp+en-us", "日本語+英語", "英語+日本語", "mixed" -> Language.JA_EN
            "en", "en-us", "英語" -> Language.EN
            else -> null
        }
    }

    private fun parseTime(s: String): LocalTime? {
        val m = Regex("^\\s*(\\d{1,2})[:：](\\d{2})\\s*$").find(toHalfWidth(s)) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun parseDate(s: String): LocalDate? {
        val m = Regex("^\\s*(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})\\s*$").find(toHalfWidth(s)) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    private fun toHalfWidth(s: String) = s.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")

    /** org.json のエラー文から行番号を出す（Android 内蔵版は「at character N」、新しい版は「line N」） */
    private fun lineHint(text: String, message: String?): String {
        val msg = message.orEmpty()
        Regex("line (\\d+)").find(msg)?.let { return "（${it.groupValues[1]}行目付近）" }
        val pos = Regex("at character (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        val line = text.take(pos.coerceAtMost(text.length)).count { it == '\n' } + 1
        return "（${line}行目付近）"
    }
}
