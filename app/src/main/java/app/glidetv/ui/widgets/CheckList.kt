package app.glidetv.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.glidetv.R

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

/** Список с галочками для пульта: OK переключает отметку, фокус подсвечивает строку белой рамкой. */
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
        holder.box.visibility = if (row.checkable) View.VISIBLE else View.GONE
        holder.box.background = rounded(
            if (row.checked) Palette.ACCENT else Color.TRANSPARENT,
            context.dp(6).toFloat(),
            if (row.checked) Palette.ACCENT else Palette.OUTLINE,
            context.dp(2),
        )
        holder.box.setImageDrawable(if (row.checked) holder.checkIcon else null)
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.note.text = row.note
        holder.note.visibility = if (row.note.isBlank()) View.GONE else View.VISIBLE
        holder.badge.text = row.badge
        holder.badge.setChipColor(row.badgeColor)
        holder.badge.visibility = if (row.badge.isBlank()) View.GONE else View.VISIBLE
        // Экранный диктор: «Название, отмечено/не отмечено»
        holder.root.contentDescription = buildString {
            append(row.title)
            if (row.badge.isNotBlank()) append(", ").append(row.badge)
            if (row.checkable) append(", ").append(context.getString(if (row.checked) R.string.a11y_checked else R.string.a11y_unchecked))
        }
        return holder.root
    }

    private class Holder(context: Context) {
        val checkIcon = context.icon(R.drawable.ic_check, ColorStateList.valueOf(Palette.ON_ACCENT))
        val box: ImageView = ImageView(context).apply {
            val p = context.dp(3)
            setPadding(p, p, p, p)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title: TextView = context.text("", Type.BODY, Palette.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle: TextView = context.text("", Type.CAPTION, Palette.TEXT_DIM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val note: TextView = context.text("", Type.CAPTION, Palette.TEXT_DIM).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val badge: TextView = context.chip()
        val root: LinearLayout = context.horizontal {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(64)
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
            background = rounded(Palette.CARD, dp(14).toFloat())
            addView(box, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(20) })
            addView(context.vertical {
                addView(title)
                addView(subtitle)
                addView(note, lp().apply { topMargin = dp(2) })
            }, lp(0, WRAP, 1f))
            addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(16) })
        }.also { it.tag = this }
    }
}

fun Context.tvListView(adapter: CheckListAdapter): ListView = ListView(this).apply {
    this.adapter = adapter
    divider = null
    dividerHeight = dp(8)
    // Рамка рисуется поверх строки: внутри строки текст светлый, белая заливка его бы скрыла
    val r = dp(14).toFloat()
    selector = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(Color.argb(40, 255, 255, 255), r, Palette.FOCUS, dp(3)))
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.argb(28, 255, 255, 255), r, Palette.FOCUS, dp(3)))
        addState(intArrayOf(android.R.attr.state_selected), rounded(Color.argb(28, 255, 255, 255), r, Palette.FOCUS, dp(3)))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, r))
    }
    setDrawSelectorOnTop(true)
    isFocusable = true
    itemsCanFocus = false
    isVerticalScrollBarEnabled = true
    cacheColorHint = Color.TRANSPARENT
    clipToPadding = false
    setPadding(dp(4), dp(4), dp(4), dp(4))
    setOnItemClickListener { _, _, position, _ -> adapter.toggle(position) }
}
