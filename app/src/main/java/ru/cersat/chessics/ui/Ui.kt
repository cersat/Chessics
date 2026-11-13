package ru.cersat.chessics.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.cersat.chessics.data.Shop

// Мини-DSL для построения интерфейса кодом (без XML и без библиотек)
class Ui(val ctx: Context) {
	val t: Shop.Theme = Shop.theme(ctx)
	private val density = ctx.resources.displayMetrics.density

	fun dp(v: Number) = (v.toFloat() * density + 0.5f).toInt()

	fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, margin: Int = 0) =
		LinearLayout.LayoutParams(w, h, weight).apply { setMargins(margin, margin, margin, margin) }

	fun round(color: Int, radius: Float = 16f, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
		setColor(color)
		cornerRadius = dp(radius).toFloat()
		if (stroke > 0) setStroke(dp(stroke), strokeColor)
	}

	fun col(pad: Int = 0, gap: Int = 0, vararg kids: View) = LinearLayout(ctx).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
		if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(1, dp(gap)) } }
		kids.forEach { addView(it) }
	}

	fun row(gap: Int = 0, vararg kids: View) = LinearLayout(ctx).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(dp(gap), 1) } }
		kids.forEach { addView(it) }
	}

	fun text(s: CharSequence, size: Float = 15f, color: Int = t.text, bold: Boolean = false, gravity: Int = Gravity.START) = TextView(ctx).apply {
		text = s
		textSize = size
		setTextColor(color)
		if (bold) typeface = Typeface.DEFAULT_BOLD
		this.gravity = gravity
	}

	fun title(s: String) = text(s, 24f, t.text, true).apply { setPadding(0, dp(4), 0, dp(4)) }
	fun sub(s: CharSequence, size: Float = 13f) = text(s, size, t.sub)

	fun card(pad: Int = 16, color: Int = t.card) = LinearLayout(ctx).apply {
		orientation = LinearLayout.VERTICAL
		background = round(color, 18f)
		setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
	}

	fun clickable(v: View, color: Int, radius: Float = 16f, onClick: () -> Unit): View {
		v.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(color, radius), null)
		v.isClickable = true
		v.setOnClickListener { onClick() }
		return v
	}

	// Кнопка: primary — цвет акцента, иначе — карточка
	fun button(label: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit): TextView {
		val bg = if (primary) t.accent else t.card2
		val fg = if (primary) contrast(t.accent) else t.text
		return text(label, 15f, fg, true, Gravity.CENTER).apply {
			setPadding(dp(14), dp(12), dp(14), dp(12))
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			clickable(this, bg, 14f, onClick)
			isEnabled = enabled
			alpha = if (enabled) 1f else 0.4f
		}
	}

	fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
		text(label, 14f, if (selected) contrast(t.accent) else t.text, selected, Gravity.CENTER).apply {
			setPadding(dp(12), dp(8), dp(12), dp(8))
			clickable(this, if (selected) t.accent else t.card2, 20f, onClick)
		}

	// Ряд чипов с переносом — простая «flow»-раскладка через вложенные строки
	fun chips(items: List<Pair<String, Boolean>>, perRow: Int = 4, onPick: (Int) -> Unit): LinearLayout {
		val box = col(0, 8)
		var r: LinearLayout? = null
		items.forEachIndexed { i, (label, sel) ->
			if (i % perRow == 0) { r = row(8); box.addView(r, lp()) }
			r!!.addView(chip(label, sel) { onPick(i) }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
		val rest = items.size % perRow
		if (rest != 0) repeat(perRow - rest) { r!!.addView(View(ctx), lp(0, 1, 1f)) }
		return box
	}

	fun scroll(content: View) = ScrollView(ctx).apply {
		isFillViewport = true
		addView(content)
	}

	fun hscroll(content: View) = HorizontalScrollView(ctx).apply {
		isHorizontalScrollBarEnabled = false
		addView(content)
	}

	fun progress(value: Int, max: Int, color: Int = t.accent): View {
		val f = FrameLayout(ctx)
		f.background = round(t.card2, 6f)
		val bar = View(ctx)
		bar.background = round(color, 6f)
		f.addView(bar, FrameLayout.LayoutParams(0, dp(8)))
		f.post {
			val w = if (max <= 0) 0 else (f.width * value.coerceIn(0, max) / max)
			bar.layoutParams = FrameLayout.LayoutParams(w, dp(8))
		}
		return f
	}

	fun space(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

	companion object {
		fun contrast(c: Int): Int {
			val l = (android.graphics.Color.red(c) * 299 + android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000
			return if (l > 150) 0xFF15171C.toInt() else 0xFFFFFFFF.toInt()
		}
	}
}
