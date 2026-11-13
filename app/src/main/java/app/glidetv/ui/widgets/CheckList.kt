package app.glidetv.ui.widgets

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

data class CheckRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val note: String = "",
    val badge: String = "",
    val badgeColor: Int = Palette.TEXT_DIM,
    var checked: Boolean = false,
    val checkable: Boolean = true,
)

/** Список с галочками для пульта: OK переключает отметку, фокус подсвечивает строку. */
class CheckListAdapter(private val context: Context, private val onChanged: () -> Unit) : BaseAdapter() {
    var rows: List<CheckRow> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    fun toggle(position: Int) {
        val row = rows.getOrNull(position) ?: return
        if (!row.checkable) return
        row.checked = !row.checked
        notifyDataSetChanged()
        onChanged()
    }

    fun checked(): List<CheckRow> = rows.filter { it.checked }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder = (convertView?.tag as? Holder) ?: Holder(context)
        val row = rows[position]
        holder.box.text = if (row.checked) "✓" else ""
        holder.box.background = rounded(
            if (row.checked) Palette.ACCENT else Color.TRANSPARENT,
            context.dp(6).toFloat(),
            if (row.checkable) Palette.ACCENT else Palette.TEXT_DIM,
            context.dp(2),
        )
        holder.box.alpha = if (row.checkable) 1f else 0.4f
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.note.text = row.note
        holder.note.visibility = if (row.note.isBlank()) View.GONE else View.VISIBLE
        holder.badge.text = row.badge
        holder.badge.setTextColor(row.badgeColor)
        holder.badge.visibility = if (row.badge.isBlank()) View.GONE else View.VISIBLE
        return holder.root
    }

    private class Holder(context: Context) {
        val box: TextView = context.text("", 16f, Palette.BG, bold = true).apply { gravity = Gravity.CENTER }
        val title: TextView = context.text("", 18f, Palette.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle: TextView = context.text("", 13f, Palette.TEXT_DIM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val note: TextView = context.text("", 13f, Palette.TEXT_DIM).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val badge: TextView = context.text("", 13f, Palette.TEXT_DIM, bold = true).apply { gravity = Gravity.END }
        val root: LinearLayout = context.horizontal {
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
            addView(box, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(18) })
            addView(context.vertical {
                addView(title)
                addView(subtitle)
                addView(note)
            }, lp(0, WRAP, 1f))
            addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        }.also { it.tag = this }
    }
}

fun Context.tvListView(adapter: CheckListAdapter): ListView = ListView(this).apply {
    this.adapter = adapter
    divider = null
    dividerHeight = dp(6)
    selector = focusBackground(context, Color.TRANSPARENT, Palette.CARD_FOCUS)
    isFocusable = true
    itemsCanFocus = false
    isVerticalScrollBarEnabled = true
    cacheColorHint = Color.TRANSPARENT
    setOnItemClickListener { _, _, position, _ -> adapter.toggle(position) }
}
