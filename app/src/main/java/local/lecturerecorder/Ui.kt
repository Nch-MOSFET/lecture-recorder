package local.lecturerecorder

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * タイトルとスクロール領域からなる画面を作る。
 * Android 15 以降は全画面描画が必須なので、ステータスバー・ナビゲーションバー・キーボードの分だけ余白を取る。
 */
fun Activity.screen(title: String, content: View): View {
    val density = resources.displayMetrics.density
    val header = TextView(this).apply {
        text = title
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
    }
    val scroll = ScrollView(this).apply {
        addView(content)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header)
        addView(scroll)
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }
}
