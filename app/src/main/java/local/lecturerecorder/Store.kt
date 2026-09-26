package local.lecturerecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** 時間割の1コマ。同じ科目が複数曜日にある場合はコマごとに登録する。 */
data class Lecture(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val day: DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
    val enabled: Boolean = true,
    /** 保存先フォルダー（SAF のツリーURI）。null なら端末の Documents/講義録音/{科目名} */
    val folderUri: String? = null,
    val folderLabel: String? = null,
    /** この日付の回だけ録音しない（休講など） */
    val skipDate: LocalDate? = null,
    /** 授業日の一覧。空なら毎週録音する */
    val dates: List<LocalDate> = emptyList(),
    /** 文字起こしの言語（[Language]） */
    val language: Language = Language.JA,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("day", day.value)
        .put("start", start.toString()).put("end", end.toString())
        .put("enabled", enabled)
        .put("folderUri", folderUri).put("folderLabel", folderLabel)
        .put("skipDate", skipDate?.toString())
        .put("dates", JSONArray(dates.map { it.toString() }))
        .put("language", language.key)

    companion object {
        fun fromJson(o: JSONObject) = Lecture(
            id = o.getString("id"),
            name = o.getString("name"),
            day = DayOfWeek.of(o.getInt("day")),
            start = LocalTime.parse(o.getString("start")),
            end = LocalTime.parse(o.getString("end")),
            enabled = o.optBoolean("enabled", true),
            folderUri = o.optStringOrNull("folderUri"),
            folderLabel = o.optStringOrNull("folderLabel"),
            skipDate = o.optStringOrNull("skipDate")?.let(LocalDate::parse),
            dates = o.optJSONArray("dates")?.let { a -> (0 until a.length()).map { LocalDate.parse(a.getString(it)) } }.orEmpty(),
            language = Language.of(o.optStringOrNull("language")),
        )
    }
}

/** 文字起こしに使う言語 */
enum class Language(val key: String, val label: String, val tags: List<String>) {
    JA("ja", "日本語", listOf("ja-JP")),
    JA_EN("ja+en", "日本語＋英語（自動で切り替え）", listOf("ja-JP", "en-US")),
    EN("en", "英語", listOf("en-US"));

    /** 認識エンジンに渡す主な言語 */
    val primaryTag: String get() = tags.first()

    companion object {
        fun of(key: String?): Language = entries.firstOrNull { it.key == key } ?: JA
    }
}

/** 録音済みで保存先へまだ書き出せていないファイル */
data class PendingExport(
    val localPath: String,
    val lectureId: String,
    val lectureName: String,
    val displayName: String,
    val mime: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("localPath", localPath).put("lectureId", lectureId)
        .put("lectureName", lectureName).put("displayName", displayName).put("mime", mime)

    companion object {
        fun fromJson(o: JSONObject) = PendingExport(
            o.getString("localPath"), o.getString("lectureId"), o.getString("lectureName"),
            o.getString("displayName"), o.getString("mime"),
        )
    }
}

/** 次の授業日（授業日の一覧があるとき）。一覧が空なら null */
fun Lecture.nextDate(from: LocalDate): LocalDate? = dates.filter { !it.isBefore(from) }.minOrNull()

/** 実際に録音する区間（開始・終了の余裕を含む） */
data class Slot(val lecture: Lecture, val date: LocalDate, val start: LocalDateTime, val end: LocalDateTime)

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)

    var lectures: List<Lecture>
        get() {
            val arr = JSONArray(prefs.getString("lectures", "[]"))
            return (0 until arr.length()).map { Lecture.fromJson(arr.getJSONObject(it)) }
                .sortedWith(compareBy({ it.day }, { it.start }))
        }
        set(value) {
            val arr = JSONArray().apply { value.forEach { put(it.toJson()) } }
            prefs.edit().putString("lectures", arr.toString()).apply()
        }

    fun upsert(lecture: Lecture) {
        lectures = lectures.filter { it.id != lecture.id } + lecture
    }

    fun delete(id: String) {
        lectures = lectures.filter { it.id != id }
    }

    fun find(id: String): Lecture? = lectures.firstOrNull { it.id == id }

    /** 自動録音の待機を利用者がオンにしているか */
    var autoEnabled: Boolean
        get() = prefs.getBoolean("autoEnabled", false)
        set(v) = prefs.edit().putBoolean("autoEnabled", v).apply()

    var startEarlyMin: Int
        get() = prefs.getInt("startEarlyMin", 0)
        set(v) = prefs.edit().putInt("startEarlyMin", v.coerceIn(0, 30)).apply()

    var endLateMin: Int
        get() = prefs.getInt("endLateMin", 0)
        set(v) = prefs.edit().putInt("endLateMin", v.coerceIn(0, 30)).apply()

    /** 句読点などの整形を認識エンジンに依頼するか（非対応の端末では自動でオフ） */
    var useFormatting: Boolean
        get() = prefs.getBoolean("useFormatting", true)
        set(v) = prefs.edit().putBoolean("useFormatting", v).apply()

    var pending: List<PendingExport>
        get() {
            val arr = JSONArray(prefs.getString("pending", "[]"))
            return (0 until arr.length()).map { PendingExport.fromJson(arr.getJSONObject(it)) }
        }
        set(value) {
            val arr = JSONArray().apply { value.forEach { put(it.toJson()) } }
            prefs.edit().putString("pending", arr.toString()).commit()
        }

    /** 録音中のファイル（強制終了後の回収に使う）。形式: lectureId|lectureName|baseName|aacPath|txtPath */
    var activeRecording: String?
        get() = prefs.getString("activeRecording", null)
        set(v) {
            prefs.edit().putString("activeRecording", v).commit()
        }

    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(v) = prefs.edit().putString("lastError", v).apply()
}

object Schedule {
    /** now 時点で録音すべきコマ。manualStop で止めた回は skipDate で除外される。 */
    fun current(store: Store, now: LocalDateTime): Slot? =
        candidates(store, now.toLocalDate().minusDays(1), 2)
            .filter { it.start <= now && now < it.end }
            .minByOrNull { it.start }

    /** now より後で最初に始まるコマ */
    fun next(store: Store, now: LocalDateTime): Slot? =
        candidates(store, now.toLocalDate(), 8)
            .filter { it.start > now }
            .minByOrNull { it.start }

    /** now より後にある最も近い開始・終了時刻（アラームの設定に使う） */
    fun nextBoundary(store: Store, now: LocalDateTime): LocalDateTime? =
        candidates(store, now.toLocalDate().minusDays(1), 9)
            .flatMap { listOf(it.start, it.end) }
            .filter { it > now }
            .minOrNull()

    private fun candidates(store: Store, from: LocalDate, days: Int): List<Slot> {
        val early = store.startEarlyMin.toLong()
        val late = store.endLateMin.toLong()
        val lectures = store.lectures.filter { it.enabled }
        return (0 until days).flatMap { offset ->
            val date = from.plusDays(offset.toLong())
            lectures.filter { it.day == date.dayOfWeek && it.skipDate != date && (it.dates.isEmpty() || date in it.dates) }.map { l ->
                val start = LocalDateTime.of(date, l.start)
                var end = LocalDateTime.of(date, l.end)
                if (end <= start) end = end.plusDays(1)
                Slot(l, date, start.minusMinutes(early), end.plusMinutes(late))
            }
        }
    }

}
