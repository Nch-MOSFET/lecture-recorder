package local.lecturerecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 授業の開始・終了時刻に呼ばれる。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = RecorderService.instance
        if (service != null) {
            service.evaluate()
            return
        }
        val store = Store(context)
        if (!store.autoEnabled) return
        // サービスが止まっている（強制終了・再起動後など）。
        // バックグラウンドからはマイクを使い始められないので、利用者にタップしてもらう
        RecorderService.postResumeNotification(context)
        RecorderService.scheduleAlarm(context, store)
    }
}

/** 端末の再起動・アプリ更新の後に、自動録音の再開を促す。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (!store.autoEnabled) return
        RecorderService.scheduleAlarm(context, store)
        RecorderService.postResumeNotification(context)
    }
}

