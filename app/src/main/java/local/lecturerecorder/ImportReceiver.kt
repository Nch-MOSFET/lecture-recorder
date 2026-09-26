package local.lecturerecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import java.time.LocalDate

private const val TAG = "ImportReceiver"

/**
 * PC から adb で時間割を取り込む。
 * マニフェストで android.permission.DUMP を要求しているため、送れるのは adb shell とシステムだけ。
 *
 *   am broadcast -n local.lecturerecorder/.ImportReceiver -a local.lecturerecorder.DUMP
 *   am broadcast -n local.lecturerecorder/.ImportReceiver -a local.lecturerecorder.IMPORT --es b64 <JSON を Base64>
 *
 * JSON の形と取り込みの規則は [TimetableJson] と同じ（アプリの「JSON から時間割を読み込む」と共通）。
 * --ez replace true を付けると、JSON にないコマを削除する。
 */
class ImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        when (intent.action) {
            ACTION_IMPORT -> {
                val result = runCatching {
                    val json = String(Base64.decode(intent.getStringExtra("b64").orEmpty(), Base64.DEFAULT), Charsets.UTF_8)
                    val plan = TimetableJson.plan(store.lectures, TimetableJson.parse(json), intent.getBooleanExtra("replace", false))
                    store.lectures = plan.result
                    "取り込み完了: 追加 ${plan.added} 件・更新 ${plan.updated} 件・削除 ${plan.removed.size} 件"
                }
                val msg = result.fold({ it }, { "取り込み失敗: ${it.message}" })
                Log.i(TAG, msg)
                resultData = msg
                RecorderService.instance?.evaluate() ?: RecorderService.scheduleAlarm(context, store)
            }
            ACTION_DUMP -> {
                val dump = buildString {
                    append("auto=${store.autoEnabled} early=${store.startEarlyMin} late=${store.endLateMin} pending=${store.pending.size}\n")
                    for (l in store.lectures) {
                        append("${l.name} | ${l.day} ${l.start}-${l.end} | enabled=${l.enabled} | folder=${l.folderLabel} | dates=${l.dates.size} next=${l.nextDate(LocalDate.now())} | skip=${l.skipDate}\n")
                    }
                }
                Log.i(TAG, dump)
                resultData = dump
            }
        }
    }

    companion object {
        const val ACTION_IMPORT = "local.lecturerecorder.IMPORT"
        const val ACTION_DUMP = "local.lecturerecorder.DUMP"
    }
}
