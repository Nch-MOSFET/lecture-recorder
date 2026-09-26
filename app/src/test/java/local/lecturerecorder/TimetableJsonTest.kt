package local.lecturerecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class TimetableJsonTest {

    private fun lecture(name: String, day: DayOfWeek, folder: String? = null) = Lecture(
        name = name, day = day, start = LocalTime.of(9, 0), end = LocalTime.of(10, 30),
        folderUri = folder, folderLabel = folder?.let { "Google ドライブ: $it" },
    )

    private fun errorOf(json: String): String = try {
        TimetableJson.parse(json)
        fail("エラーになるはず: $json")
        ""
    } catch (e: TimetableJson.FormatException) {
        e.message.orEmpty()
    }

    @Test
    fun サンプルはそのまま読み込める() {
        val entries = TimetableJson.parse(TimetableJson.sample())
        assertEquals(3, entries.size)
        assertEquals("英語", entries[0].name)
        assertEquals(DayOfWeek.WEDNESDAY, entries[0].day)
        assertEquals(3, entries[0].dates.size)
        assertTrue(entries[1].dates.isEmpty())
        assertFalse(entries[2].enabled)
    }

    @Test
    fun サンプルには説明が入っていて日付は1行にまとまる() {
        val text = TimetableJson.sample()
        assertTrue(text.contains("\"_説明\""))
        assertTrue(text.contains("[\"2026-04-08\", \"2026-04-15\", \"2026-04-22\"]"))
        assertFalse(text.contains("\\/"))
    }

    @Test
    fun 書き出した時間割を読み戻すと同じになる() {
        val src = listOf(
            lecture("数学", DayOfWeek.MONDAY).copy(dates = listOf(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28))),
            lecture("体育", DayOfWeek.SATURDAY).copy(enabled = false),
        )
        val back = TimetableJson.parse(TimetableJson.export(src))
        assertEquals(src.map { listOf(it.name, it.day, it.start, it.end, it.dates, it.enabled) },
            back.map { listOf(it.name, it.day, it.start, it.end, it.dates, it.enabled) })
    }

    @Test
    fun 曜日や時刻はいろいろな書き方を受け付ける() {
        val e = TimetableJson.parse(
            """{"lectures":[
              {"name":"A","day":"月曜","start":"９:０５","end":"10:35"},
              {"name":"B","day":3,"start":"10:40","end":"12:10","dates":["2026/9/16"]},
              {"name":"C","day":"Fri","start":"19:50","end":"21:20"},
              {"name":"D","day":"7","start":"1:00","end":"2:00"}
            ]}""",
        )
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), e.map { it.day })
        assertEquals(LocalTime.of(9, 5), e[0].start)
        assertEquals(listOf(LocalDate.of(2026, 9, 16)), e[1].dates)
    }

    @Test
    fun 言語は省略すると日本語で書き方にも幅がある() {
        val e = TimetableJson.parse(
            """{"lectures":[
              {"name":"A","day":"月","start":"9:00","end":"10:30"},
              {"name":"B","day":"月","start":"9:00","end":"10:30","language":"ja+en"},
              {"name":"C","day":"月","start":"9:00","end":"10:30","language":"日本語＋英語"},
              {"name":"D","day":"月","start":"9:00","end":"10:30","language":"EN"}
            ]}""",
        )
        assertEquals(listOf(Language.JA, Language.JA_EN, Language.JA_EN, Language.EN), e.map { it.language })
    }

    @Test
    fun 言語は書き出しと読み戻しで保たれる() {
        val src = listOf(
            lecture("英語", DayOfWeek.WEDNESDAY).copy(language = Language.JA_EN),
            lecture("数学", DayOfWeek.MONDAY),
        )
        val text = TimetableJson.export(src)
        assertTrue(text.contains("\"language\": \"ja+en\""))
        assertEquals(listOf(Language.JA_EN, Language.JA), TimetableJson.parse(text).map { it.language })
    }

    @Test
    fun 取り込みで言語も更新される() {
        val current = listOf(lecture("英語", DayOfWeek.WEDNESDAY, folder = "drive-1"))
        val entries = TimetableJson.parse("""{"lectures":[{"name":"英語","day":"水","start":"10:40","end":"12:10","language":"ja+en"}]}""")
        val result = TimetableJson.plan(current, entries, replace = false).result.single()
        assertEquals(Language.JA_EN, result.language)
        assertEquals("drive-1", result.folderUri)
    }

    @Test
    fun 誤りはどこが悪いかを日本語で返す() {
        assertTrue(errorOf("{\"lectures\": [ {\"name\": \"A\",, } ]}").contains("行目付近"))
        assertTrue(errorOf("{\"lecture\": []}").contains("lectures"))
        assertTrue(errorOf("""{"lectures":[{"day":"月","start":"9:00","end":"10:00"}]}""").contains("1件目の授業：name"))
        assertTrue(errorOf("""{"lectures":[{"name":"A","day":"月曜日ではない","start":"9:00","end":"10:00"}]}""").contains("day"))
        assertTrue(errorOf("""{"lectures":[{"name":"A","day":"月","start":"25:00","end":"10:00"}]}""").contains("start"))
        assertTrue(errorOf("""{"lectures":[{"name":"A","day":"月","start":"9:00","end":"10:00","dates":["2026-04-08"]}]}""").contains("合いません"))
        assertTrue(errorOf("""{"lectures":[{"name":"A","day":"水","start":"9:00","end":"10:00","dates":["9/16"]}]}""").contains("dates の 1番目"))
        assertTrue(errorOf("""{"lectures":[{"name":"A","day":"月","start":"9:00","end":"10:00","language":"fr"}]}""").contains("language"))
    }

    @Test
    fun 追加更新では保存先と他の授業を残す() {
        val current = listOf(
            lecture("英語", DayOfWeek.WEDNESDAY, folder = "drive-1"),
            lecture("部活", DayOfWeek.THURSDAY),
        )
        val entries = TimetableJson.parse(
            """{"lectures":[
              {"name":"英語","day":"水","start":"10:40","end":"12:10","dates":["2026-04-08"]},
              {"name":"英語","day":"金","start":"10:40","end":"12:10"},
              {"name":"新科目","day":"月","start":"16:30","end":"18:00"}
            ]}""",
        )
        val plan = TimetableJson.plan(current, entries, replace = false)
        assertEquals(2, plan.added)
        assertEquals(1, plan.updated)
        assertTrue(plan.removed.isEmpty())
        assertEquals(4, plan.result.size)
        val wed = plan.result.first { it.name == "英語" && it.day == DayOfWeek.WEDNESDAY }
        assertEquals(current[0].id, wed.id)
        assertEquals("drive-1", wed.folderUri)
        assertEquals(LocalTime.of(10, 40), wed.start)
        // 同じ科目の新しいコマは保存先を引き継ぐ
        assertEquals("drive-1", plan.result.first { it.name == "英語" && it.day == DayOfWeek.FRIDAY }.folderUri)
        assertEquals(null, plan.result.first { it.name == "新科目" }.folderUri)
    }

    @Test
    fun 置き換えではファイルにない授業を消す() {
        val current = listOf(lecture("英語", DayOfWeek.WEDNESDAY), lecture("部活", DayOfWeek.THURSDAY))
        val entries = TimetableJson.parse("""{"lectures":[{"name":"英語","day":"水","start":"10:40","end":"12:10"}]}""")
        val plan = TimetableJson.plan(current, entries, replace = true)
        assertEquals(listOf("部活"), plan.removed.map { it.name })
        assertEquals(listOf("英語"), plan.result.map { it.name })
    }
}
