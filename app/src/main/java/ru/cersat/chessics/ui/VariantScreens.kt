package ru.cersat.chessics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import ru.cersat.chessics.bots.MoveClass
import ru.cersat.chessics.bots.Stockfish
import ru.cersat.chessics.chess.F4
import ru.cersat.chessics.chess.FairyVariant
import ru.cersat.chessics.chess.MoveRule
import ru.cersat.chessics.chess.VBot
import ru.cersat.chessics.chess.VMaterial
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.PieceDef
import ru.cersat.chessics.chess.VBots
import ru.cersat.chessics.chess.VM
import ru.cersat.chessics.chess.VPos
import ru.cersat.chessics.chess.Variant
import ru.cersat.chessics.data.Shop
import ru.cersat.chessics.data.Sounds
import ru.cersat.chessics.data.Store

// Сохранённые варианты: JSON-массив в настройках
object VariantStore {
	private const val KEY = "variants"

	fun list(st: Store): MutableList<Variant> {
		val arr = try { JSONArray(st.str(KEY, "[]")) } catch (_: Exception) { JSONArray() }
		return MutableList(arr.length()) { Variant.from(arr.getJSONObject(it)) }
	}

	fun save(st: Store, all: List<Variant>) = st.setStr(KEY, JSONArray().apply { all.forEach { put(it.json()) } }.toString())

	fun put(st: Store, i: Int, v: Variant) {
		val all = list(st)
		if (i in all.indices) all[i] = v else all.add(v)
		save(st, all)
	}
}

// Рисование фигур конструктора: стандартные — картинками текущего набора, свои — буквой в кружке.
// На четверых — картинки четырёх цветов, а свои фигуры красятся в цвет игрока.
object VArt {
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
	private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
	private val dst = RectF()

	fun draw(ctx: Context, c: Canvas, d: PieceDef, color: Int, l: Float, t: Float, s: Float, four: Boolean = false) {
		if (d.art in 1..6) {
			val b = if (four) Four4Art.get(ctx, F4.make(color, d.art)) else PieceArt.get(ctx, Shop.pieces(ctx).set, P.make(color, d.art))
			if (b != null) {
				dst.set(l, t, l + s, t + s)
				c.drawBitmap(b, null, dst, fill)
				return
			}
		}
		val cx = l + s / 2; val cy = t + s / 2
		val bodyColor = if (four) Seats.COLORS[color] else if (color == 0) 0xFFF4F1E8.toInt() else 0xFF26282E.toInt()
		val inkColor = if (four) 0xFFFFFFFF.toInt() else if (color == 0) 0xFF1B1C20.toInt() else 0xFFF4F1E8.toInt()
		fill.style = Paint.Style.FILL; fill.color = bodyColor
		c.drawCircle(cx, cy, s * 0.4f, fill)
		fill.style = Paint.Style.STROKE; fill.strokeWidth = s * 0.06f; fill.color = inkColor
		c.drawCircle(cx, cy, s * 0.4f, fill)
		if (d.royal) { fill.strokeWidth = s * 0.03f; c.drawCircle(cx, cy, s * 0.31f, fill) }
		fill.style = Paint.Style.FILL
		text.textSize = s * (if (d.symbol.length > 1) 0.3f else 0.42f)
		text.color = inkColor
		c.drawText(d.symbol, cx, cy - (text.descent() + text.ascent()) / 2, text)
	}
}

// Доска варианта: и для редактора (рисование по клеткам), и для партии (выбор и ход)
class VBoardView(ctx: Context) : View(ctx) {
	var v: Variant = Variant.classic()
	var sq: IntArray = v.cells
	var flipped = false // на двоих — доска вверх ногами
	var seatDown = 0 // на четверых — чья сторона внизу
	var lastMove = 0
	var check = -1
	var legal: List<Int> = emptyList()
	// режим редактора: клетка и «первое ли это касание штриха»; штрих можно вести пальцем, рисуя по многим клеткам
	var onCell: ((Int, Boolean) -> Unit)? = null
	var onStrokeEnd: () -> Unit = {}
	var inert = false // доска не реагирует на касания — страница прокручивается за неё
	var canMove: () -> Boolean = { false }
	var onMove: (List<Int>) -> Unit = {}
	var onPlace: ((Int) -> Unit)? = null // расстановка перед партией: касание свободной клетки
	var placeCells: Set<Int> = emptySet() // куда сейчас можно поставить
	private var selected = -1
	private var strokeCell = -1
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)
	private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
	private val board = Shop.board(ctx)
	// значок типа хода, стрелка лучшего хода и вспышка — как в обычном разборе
	val fx = MoveFx(this, { i -> col(i) * cell to row(i) * cell }, { cell })
	var hint = 0
	var mark = "" // редактор: "promo", "zone" или "shuf" — показать клетки цвета markColor
	var markColor = 0

	fun clearSel() { selected = -1; invalidate() }

	override fun onMeasure(ws: Int, hs: Int) {
		val w = MeasureSpec.getSize(ws)
		setMeasuredDimension(w, w * v.h / v.w)
	}

	private val cell get() = width.toFloat() / v.w
	private val four get() = v.players == 4

	private fun col(s: Int) = if (four) v.toLx(seatDown, s) else if (flipped) v.w - 1 - s % v.w else s % v.w
	private fun row(s: Int) = if (four) v.h - 1 - v.toLy(seatDown, s) else if (flipped) s / v.w else v.h - 1 - s / v.w
	private fun at(cx: Int, cy: Int): Int =
		if (four) v.toG(seatDown, cx, v.h - 1 - cy) else v.idx(if (flipped) v.w - 1 - cx else cx, if (flipped) cy else v.h - 1 - cy)

	private fun tri(x: Float, y: Float, s: Float, fillC: Int, edge: Int) {
		val path = android.graphics.Path(); path.moveTo(x, y); path.lineTo(x + s * 0.42f, y); path.lineTo(x, y + s * 0.42f); path.close()
		p.style = Paint.Style.FILL; p.color = fillC; c0.drawPath(path, p)
		p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.04f; p.color = edge; c0.drawPath(path, p)
		p.style = Paint.Style.FILL
	}
	private lateinit var c0: Canvas

	private fun colorOf(c: Int) = if (four) Seats.COLORS[c] else if (c == 0) 0xFFF4F1E8.toInt() else 0xFF26282E.toInt()

	override fun onDraw(c: Canvas) {
		c0 = c
		val s = cell
		for (i in 0 until v.w * v.h) {
			val x = col(i) * s; val y = row(i) * s
			if (v.locked[i]) {
				p.color = 0xFF0E0F12.toInt(); c.drawRect(x, y, x + s, y + s, p)
				p.color = 0xFF3A3D46.toInt(); p.strokeWidth = s * 0.06f
				c.drawLine(x + s * 0.2f, y + s * 0.2f, x + s * 0.8f, y + s * 0.8f, p)
				c.drawLine(x + s * 0.8f, y + s * 0.2f, x + s * 0.2f, y + s * 0.8f, p)
				continue
			}
			val light = (i % v.w + i / v.w) % 2 == 1
			p.color = if (light) board.light else board.dark
			c.drawRect(x, y, x + s, y + s, p)
			if (lastMove != 0 && (i == VM.from(lastMove) || i == VM.to(lastMove))) { p.color = board.last; c.drawRect(x, y, x + s, y + s, p) }
			if (i == selected) { p.color = board.select; c.drawRect(x, y, x + s, y + s, p) }
			if (i == check) { p.color = 0x88FF2020.toInt(); c.drawCircle(x + s / 2, y + s / 2, s * 0.5f, p) }
		}
		// редактор: клетки превращения (уголок цвета пешек), зона расстановки (цветная заливка), перемешивание (номера)
		if (mark == "promo") {
			val edge = if (four) 0xFFFFFFFF.toInt() else if (markColor == 0) 0xFF26282E.toInt() else 0xFFF4F1E8.toInt()
			for (i in v.promoCells(markColor)) tri(col(i) * s, row(i) * s, s, colorOf(markColor), edge)
		} else if (mark == "zone") {
			for (i in v.zoneCells(markColor)) { p.color = (colorOf(markColor) and 0x00FFFFFF) or 0x66000000; c.drawRect(col(i) * s, row(i) * s, col(i) * s + s, row(i) * s + s, p) }
		} else if (mark == "shuf") {
			coord.textSize = s * 0.3f
			v.shuf[markColor].forEachIndexed { k, i ->
				val cx = col(i) * s + s * 0.78f; val cy = row(i) * s + s * 0.28f
				p.style = Paint.Style.FILL; p.color = 0xFFFFC23D.toInt(); c.drawCircle(cx, cy, s * 0.2f, p)
				coord.style = Paint.Style.FILL; coord.color = 0xFF1B1C20.toInt(); coord.textAlign = Paint.Align.CENTER
				c.drawText("${k + 1}", cx, cy - (coord.descent() + coord.ascent()) / 2, coord)
				coord.textAlign = Paint.Align.LEFT
			}
		}
		// расстановка перед партией: куда можно ставить
		if (placeCells.isNotEmpty()) {
			p.color = 0x5547C46A
			for (i in placeCells) if (sq[i] == 0) c.drawRect(col(i) * s, row(i) * s, col(i) * s + s, row(i) * s + s, p)
		}
		// холм: золотая рамка по клеткам
		if (v.win == Variant.WIN_HILL) {
			p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f; p.color = 0xFFFFC23D.toInt()
			for (i in v.hillCells()) { val x = col(i) * s; val y = row(i) * s; c.drawRect(x + s * 0.06f, y + s * 0.06f, x + s * 0.94f, y + s * 0.94f, p) }
			p.style = Paint.Style.FILL
		}
		fx.drawUnder(c)
		for (i in sq.indices) {
			val code = sq[i]
			if (code == 0 || !v.has(code)) continue
			VArt.draw(context, c, v.piece(code), code shr 8, col(i) * s, row(i) * s, s, four)
		}
		fx.drawOver(c, if (hint != 0) VM.from(hint) else -1, if (hint != 0) VM.to(hint) else -1)
		if (selected >= 0) {
			p.color = 0x66000000
			for (m in legal) if (VM.from(m) == selected) {
				val t = VM.to(m)
				c.drawCircle(col(t) * s + s / 2, row(t) * s + s / 2, s * (if (sq[t] != 0) 0.42f else 0.16f), p)
			}
		}
		// координаты: буквы снизу, цифры слева, с обводкой — читаются на любой клетке (на четверых доска вращается — без подписей)
		if (four) return
		coord.textSize = s * 0.22f
		for (x in 0 until v.w) {
			val i = if (flipped) v.idx(x, v.h - 1) else v.idx(x, 0)
			label(c, "${'a' + x}", col(i) * s + s - coord.measureText("${'a' + x}") - s * 0.06f, (row(i) + 1) * s - s * 0.07f)
		}
		for (y in 0 until v.h) {
			val i = if (flipped) v.idx(v.w - 1, y) else v.idx(0, y)
			label(c, "${y + 1}", col(i) * s + s * 0.06f, row(i) * s + s * 0.26f)
		}
	}

	private fun label(c: Canvas, t: String, x: Float, y: Float) {
		coord.style = Paint.Style.STROKE; coord.strokeWidth = coord.textSize * 0.22f; coord.color = 0xCC000000.toInt()
		c.drawText(t, x, y, coord)
		coord.style = Paint.Style.FILL; coord.color = 0xFFFFFFFF.toInt()
		c.drawText(t, x, y, coord)
	}

	private fun cellAt(e: MotionEvent): Int {
		val cx = (e.x / cell).toInt(); val cy = (e.y / cell).toInt()
		if (cx !in 0 until v.w || cy !in 0 until v.h) return -1
		return at(cx, cy)
	}

	override fun onTouchEvent(e: MotionEvent): Boolean {
		if (inert) return false
		val edit = onCell
		if (edit != null) {
			// рисование: страница не прокручивается, пока палец на доске
			parent?.requestDisallowInterceptTouchEvent(true)
			when (e.actionMasked) {
				MotionEvent.ACTION_DOWN -> { val i = cellAt(e); strokeCell = i; if (i >= 0) edit(i, true) }
				MotionEvent.ACTION_MOVE -> { val i = cellAt(e); if (i >= 0 && i != strokeCell) { strokeCell = i; edit(i, false) } }
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { strokeCell = -1; onStrokeEnd() }
			}
			return true
		}
		if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
		val i = cellAt(e)
		if (i < 0) return true
		onPlace?.let { it(i); return true }
		if (!canMove()) return true
		if (selected >= 0) {
			val ms = legal.filter { VM.from(it) == selected && VM.to(it) == i }
			if (ms.isNotEmpty()) { selected = -1; onMove(ms); invalidate(); return true }
		}
		selected = if (legal.any { VM.from(it) == i }) i else -1
		invalidate()
		return true
	}
}

// Сетка 9×9 ходов фигуры: центр — сама фигура, клетка — смещение (dx, dy), вверх — «вперёд»
class MoveGridView(ctx: Context, private val d: PieceDef, private val onChange: () -> Unit) : View(ctx) {
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)

	override fun onMeasure(ws: Int, hs: Int) { val w = MeasureSpec.getSize(ws); setMeasuredDimension(w, w) }

	// состояние клетки: 0 — нет, 1..3 — прыжок (ход и взятие / только ход / только взятие), 4..6 — луч
	private fun state(dx: Int, dy: Int): Int {
		val r = d.moves.firstOrNull { it.dx == dx && it.dy == dy } ?: return 0
		return (if (r.slide) 4 else 1) + r.mode
	}

	override fun onDraw(c: Canvas) {
		val s = width / 9f
		for (gy in 0..8) for (gx in 0..8) {
			val dx = gx - 4; val dy = 4 - gy
			p.style = Paint.Style.FILL
			p.color = if ((gx + gy) % 2 == 0) 0xFF3A3F52.toInt() else 0xFF2C3040.toInt()
			c.drawRect(gx * s, gy * s, (gx + 1) * s, (gy + 1) * s, p)
			if (dx == 0 && dy == 0) { VArt.draw(context, c, d, 0, gx * s, gy * s, s); continue }
			val st = state(dx, dy)
			if (st == 0) continue
			p.color = when ((st - 1) % 3) { 0 -> 0xFF47C46A.toInt(); 1 -> 0xFF4EA8FF.toInt(); else -> 0xFFFF5F5F.toInt() }
			val cx = gx * s + s / 2; val cy = gy * s + s / 2
			if (st >= 4) {
				// луч: стрелка в сторону движения
				p.strokeWidth = s * 0.12f; p.strokeCap = Paint.Cap.ROUND
				val len = kotlin.math.hypot(dx.toFloat(), dy.toFloat())
				val ux = dx / len; val uy = -dy / len
				c.drawLine(cx - ux * s * 0.3f, cy - uy * s * 0.3f, cx + ux * s * 0.3f, cy + uy * s * 0.3f, p)
				c.drawCircle(cx + ux * s * 0.3f, cy + uy * s * 0.3f, s * 0.14f, p)
			} else c.drawCircle(cx, cy, s * 0.26f, p)
		}
		// стрелка «вперёд» сверху
		p.color = 0x88FFFFFF.toInt(); p.textSize = s * 0.32f; p.textAlign = Paint.Align.CENTER
		c.drawText("▲ вперёд", width / 2f, s * 0.36f, p)
		p.textAlign = Paint.Align.LEFT
	}

	override fun onTouchEvent(e: MotionEvent): Boolean {
		if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
		val s = width / 9f
		val dx = (e.x / s).toInt() - 4; val dy = 4 - (e.y / s).toInt()
		if (dx !in -4..4 || dy !in -4..4 || (dx == 0 && dy == 0)) return true
		val next = (state(dx, dy) + 1) % 7
		d.moves.removeAll { it.dx == dx && it.dy == dy }
		if (next != 0) d.moves.add(MoveRule(dx, dy, next >= 4, (next - 1) % 3))
		invalidate()
		onChange()
		return true
	}
}

private fun Ui.field(ctx: Context, value: String, hint: String, max: Int, onText: (String) -> Unit): EditText = EditText(ctx).apply {
	setText(value); this.hint = hint; setTextColor(t.text); setHintTextColor(t.sub); setSingleLine()
	filters = arrayOf(InputFilter.LengthFilter(max))
	background = round(t.card2, 12f); setPadding(dp(12), dp(10), dp(12), dp(10))
	addTextChangedListener(object : TextWatcher {
		override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
		override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
		override fun afterTextChanged(s: Editable?) = onText(s.toString())
	})
}

// число со стрелками
private fun Ui.stepper(label: String, value: Int, onDelta: (Int) -> Unit, vararg steps: Int): LinearLayout {
	val r = row(6)
	r.addView(text(label, 14f), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
	for (s in steps.reversed()) r.addView(chip("−$s", false) { onDelta(-s) })
	r.addView(text("$value", 16f, t.text, true, Gravity.CENTER), lp(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
	for (s in steps) r.addView(chip("+$s", false) { onDelta(s) })
	r.gravity = Gravity.CENTER_VERTICAL
	return r
}

private fun copyText(a: MainActivity, s: String) =
	(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chessics variant", s))

private fun pasteText(a: MainActivity): String =
	(a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString() ?: ""

private fun winText(v: Variant) = Variant.WIN_NAMES[v.win].let { if (v.win == Variant.WIN_CHECKS) it.replace("N", "${v.nChecks}") else it }

class VariantListScreen(a: MainActivity) : Screen(a) {
	override val tab = 2
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 12)
		box.addView(ui.title("Конструктор вариантов"))
		box.addView(ui.sub("Своя доска до 16×16, заблокированные клетки, свои фигуры с любыми ходами, правила победы, игра вчетвером и расстановка фигур перед партией. Играть можно против ботов или вдвоём."))
		box.addView(ui.row(8,
			ui.button("＋ Новый") { newVariant() },
			ui.button("📋 Вставить", false) { import() }).apply { (0 until childCount).forEach { getChildAt(it).layoutParams = ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) } })
		val all = VariantStore.list(st)
		if (all.isEmpty()) box.addView(ui.sub("Пока нет сохранённых вариантов."))
		all.forEachIndexed { i, v ->
			val c = ui.card()
			c.addView(ui.text(v.name, 17f, bold = true))
			c.addView(ui.sub("${v.w}×${v.h} · ${winText(v)} · фигур: ${v.pieces.size}" + (if (v.players == 4) " · на четверых" else "") + (if (v.setup) " · с расстановкой" else "")))
			c.addView(ui.space(8))
			val r1 = ui.row(8)
			r1.addView(ui.button("▶ Играть") { a.push(VariantSetupScreen(a, i)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r1.addView(ui.button("✎ Изменить", false) { a.push(VariantEditorScreen(a, i)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			c.addView(r1)
			c.addView(ui.space(8))
			val r2 = ui.row(8)
			r2.addView(ui.button("Копия", false) {
				val list = VariantStore.list(st); list.add(i + 1, v.copy().also { it.name = v.name + " (копия)" }); VariantStore.save(st, list); a.refresh()
			}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r2.addView(ui.button("Скопировать JSON", false) { copyText(a, v.json().toString()); a.toast("Вариант скопирован — его можно вставить на другом телефоне") }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r2.addView(ui.button("Удалить", false) { val list = VariantStore.list(st); list.removeAt(i); VariantStore.save(st, list); a.refresh() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			c.addView(r2)
			box.addView(c)
		}
		return ui.scroll(box)
	}

	private fun newVariant() {
		val body = ui.col(0, 8)
		var dlg: android.app.AlertDialog? = null
		fun start(v: Variant) { dlg?.dismiss(); val list = VariantStore.list(st); list.add(v); VariantStore.save(st, list); a.push(VariantEditorScreen(a, list.size - 1)) }
		body.addView(ui.sub("Образцы — встроенные варианты Chessics:"))
		body.addView(ui.button("Классические шахматы", false) { start(Variant.classic()) })
		body.addView(ui.button("Chess960", false) { start(Variant.chess960()) })
		body.addView(ui.button("Три шаха", false) { start(Variant.threeCheck()) })
		body.addView(ui.button("Царь горы", false) { start(Variant.koth()) })
		body.addView(ui.button("Мини 6×6", false) { start(Variant.mini()) })
		body.addView(ui.button("На четверых 14×14", false) { start(Variant.four()) })
		body.addView(ui.button("Расстановка (ставим фигуры сами)", false) { start(Variant.setupChess()) })
		body.addView(ui.button("С пустой доски 8×8", false) {
			start(Variant("Мой вариант", 8, 8, PieceDef.standard(), IntArray(64), BooleanArray(64)).also { it.promo.addAll(listOf("Ферзь", "Ладья", "Слон", "Конь")) })
		})
		dlg = a.dialog("Новый вариант", body, "Отмена" to {})
	}

	private fun import() {
		val s = pasteText(a)
		val v = try { Variant.from(JSONObject(s)) } catch (_: Exception) { null }
		if (v == null) { a.toast("В буфере обмена нет варианта — сначала скопируй его кнопкой «Скопировать JSON»"); return }
		val list = VariantStore.list(st); list.add(v); VariantStore.save(st, list)
		a.toast("Вариант «${v.name}» добавлен")
		a.refresh()
	}
}

class VariantEditorScreen(a: MainActivity, private val index: Int) : Screen(a) {
	private val st = a.store
	// перечитываем при каждой отрисовке: редактор фигуры сохраняет свои правки сам,
	// и устаревшая копия здесь затёрла бы их при следующем сохранении
	private var v: Variant = Variant.classic()
	private var tool = "piece" // piece, erase, lock, hill, promo, zone, shuf, drag
	private var color = 0
	private var piece = 0

	private fun save() = VariantStore.put(st, index, v)

	override fun build(): View {
		v = VariantStore.list(st).getOrNull(index) ?: run { a.pop(); return View(a) }
		val box = ui.col(16, 12)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Редактор")))
		box.addView(ui.sub("Всё сохраняется сразу."))
		box.addView(ui.field(a, v.name, "название варианта", 40) { v.name = it.ifBlank { "Вариант" }; save() }, ui.lp())

		// доска
		val bc = ui.card()
		bc.addView(ui.text("Доска", 17f, bold = true))
		bc.addView(ui.sub("Игроков"))
		bc.addView(ui.chips(listOf("Двое" to (v.players == 2), "Четверо (команды 1+3 и 2+4)" to (v.players == 4)), 2) { v.switchPlayers(if (it == 0) 2 else 4); if (color >= v.players) color = 0; save(); a.refresh() })
		if (v.players == 4) {
			bc.addView(ui.sub("Доска квадратная; ходят по кругу, фигуры напарника брать нельзя. Углы закрой инструментом «Блок клетки». Рокировки и взятия на проходе здесь нет."))
			bc.addView(ui.stepper("Сторона доски", v.w, { d -> val n = (v.w + d).coerceIn(Variant.MIN, Variant.MAX); if (n != v.w) { v.resize(n, n); save(); a.refresh() } }, 1))
		} else {
			bc.addView(ui.stepper("Ширина", v.w, { d -> val nw = (v.w + d).coerceIn(Variant.MIN, Variant.MAX); if (nw != v.w) { v.resize(nw, v.h); save(); a.refresh() } }, 1))
			bc.addView(ui.stepper("Высота", v.h, { d -> val nh = (v.h + d).coerceIn(Variant.MIN, Variant.MAX); if (nh != v.h) { v.resize(v.w, nh); save(); a.refresh() } }, 1))
		}
		bc.addView(ui.space(6))
		val hasPawns = v.pieces.any { it.pawn }
		val tools = ArrayList<Pair<String, String>>()
		tools.add("piece" to "Фигура"); tools.add("erase" to "Стереть"); tools.add("lock" to "Блок клетки")
		if (v.win == Variant.WIN_HILL) tools.add("hill" to "Холм")
		if (hasPawns) tools.add("promo" to "Превращение")
		if (v.setup) tools.add("zone" to "Зона расстановки")
		tools.add("shuf" to "Перемешать")
		tools.add("drag" to "Прокрутка")
		if (tools.none { it.first == tool }) tool = "piece"
		bc.addView(ui.chips(tools.map { it.second to (it.first == tool) }, 3) { tool = tools[it].first; a.refresh() })
		val colorNames = if (v.players == 4) listOf("Красные", "Синие", "Жёлтые", "Зелёные") else listOf("Белые", "Чёрные")
		val colorGen = if (v.players == 4) listOf("красные", "синие", "жёлтые", "зелёные") else listOf("белые", "чёрные")
		if (color >= v.players) color = 0
		bc.addView(ui.sub(when (tool) {
			"piece" -> "Проведи пальцем по доске — фигуры встанут на все клетки, которых коснёшься. Начал с клетки, где уже стоит такая же, — сотрёшь."
			"erase" -> "Проведи пальцем, чтобы стереть клетки: фигуры, блоки, холм, зоны."
			"lock" -> "Проведи пальцем: заблокированные клетки (на них нельзя встать) ставятся или снимаются сразу по многим. Начал с занятой — снимаешь."
			"hill" -> "Проведи по клеткам, чтобы добавить их к холму или убрать. Пока не выбрано ни одной, холм — центр доски."
			"promo" -> "Проведи по клеткам, где ${colorGen[color]} пешки превращаются (или выигрывают, если победа превращением). Отмечены уголком. Пока не выбрано ни одной — " +
				(if (v.players == 4) "средняя линия игрока." else "последняя горизонталь — ${if (color == 0) v.h else 1}-я.")
			"zone" -> "Проведи по клеткам, куда ${colorGen[color]} могут ставить фигуры. Ставят те фигуры этого цвета, что лежат на доске (это их набор). Пока не выбрано ни одной — две ближние к игроку горизонтали."
			"shuf" -> "Нажимай клетки в нужном порядке: перед каждой партией фигуры на выбранных клетках ${colorGen[color]} случайно меняются местами. Если рокировка из-за этого не получится, приложение предупредит перед партией."
			else -> "Доска не реагирует на касания — страницу можно двигать, держа палец на доске."
		}))
		if (tool in listOf("piece", "promo", "zone", "shuf")) {
			bc.addView(ui.space(6))
			bc.addView(ui.chips(colorNames.mapIndexed { i, n -> n to (color == i) }, if (v.players == 4) 4 else 2) { color = it; a.refresh() })
		}
		if (tool == "piece") {
			bc.addView(ui.space(6))
			if (piece >= v.pieces.size) piece = 0
			if (v.pieces.isNotEmpty()) bc.addView(ui.chips(v.pieces.mapIndexed { i, d -> "${d.symbol} ${d.name}" to (i == piece) }, 3) { piece = it; a.refresh() })
		}
		bc.addView(ui.space(8))
		val bv = VBoardView(a)
		bv.v = v; bv.sq = v.cells
		bv.mark = when (tool) { "promo", "zone", "shuf" -> tool; else -> "" }
		bv.markColor = color
		bv.inert = tool == "drag"
		var stroke = false // что делает текущий штрих: ставит или стирает
		bv.onCell = if (tool == "drag") null else { i, first ->
			when (tool) {
				"piece" -> if (v.pieces.isNotEmpty() && !v.locked[i]) {
					val c = Variant.code(piece, color)
					if (first) stroke = v.cells[i] != c
					v.cells[i] = if (stroke) c else 0
				}
				"erase" -> { v.cells[i] = 0; v.locked[i] = false; v.hill[i] = false; for (k in 0..3) { v.promoZ[k][i] = false; v.zone[k][i] = false; v.shuf[k].remove(i) } }
				"lock" -> {
					if (first) stroke = !v.locked[i]
					v.locked[i] = stroke
					if (stroke) { v.cells[i] = 0; v.hill[i] = false; for (k in 0..3) { v.promoZ[k][i] = false; v.zone[k][i] = false; v.shuf[k].remove(i) } }
				}
				"hill" -> if (!v.locked[i]) { if (first) stroke = !v.hill[i]; v.hill[i] = stroke }
				"promo" -> if (!v.locked[i]) { if (first) stroke = !v.promoZ[color][i]; v.promoZ[color][i] = stroke }
				"zone" -> if (!v.locked[i]) { if (first) stroke = !v.zone[color][i]; v.zone[color][i] = stroke }
				"shuf" -> if (!v.locked[i]) {
					if (first) stroke = i !in v.shuf[color]
					if (stroke) { if (i !in v.shuf[color]) v.shuf[color].add(i) } else v.shuf[color].remove(i)
				}
			}
			bv.invalidate()
		}
		bv.onStrokeEnd = { save() }
		bc.addView(bv, ui.lp())
		bc.addView(ui.space(8))
		val clr = ui.row(8)
		clr.addView(ui.button("Очистить доску", false) { v.cells.fill(0); v.locked.fill(false); save(); a.refresh() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (tool == "shuf") clr.addView(ui.button("Сбросить выбор", false) { v.shuf[color].clear(); save(); a.refresh() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		bc.addView(clr)
		box.addView(bc)

		// правила
		val rc = ui.card()
		rc.addView(ui.text("Правила", 17f, bold = true))
		rc.addView(ui.sub("Как побеждают"))
		rc.addView(ui.chips(Variant.WIN_NAMES.mapIndexed { i, n -> n to (i == v.win) }, 2) { v.win = it; save(); a.refresh() })
		if (v.win == Variant.WIN_CHECKS) rc.addView(ui.stepper("Сколько шахов", v.nChecks, { d -> v.nChecks = (v.nChecks + d).coerceIn(1, 20); save(); a.refresh() }, 1))
		if (v.win == Variant.WIN_HILL) rc.addView(ui.sub("Холм отмечен на доске золотой рамкой. Свои клетки холма — инструмент «Холм» над доской; если не выбрано ни одной, холм — центр."))
		if (v.win == Variant.WIN_PROMO) rc.addView(ui.sub("Побеждает тот, чья пешка первой дойдёт до клетки превращения (по умолчанию — последней горизонтали; свои клетки — инструменты «Превращение» над доской). Мат тоже выигрывает, если есть король."))
		if (v.win == Variant.WIN_PROMO && v.pieces.none { it.pawn }) rc.addView(ui.text("Внимание: Нет ни одной пешки — так победить не получится.", 13f, 0xFFFFB13B.toInt()))
		if ((v.win == Variant.WIN_MATE || v.win == Variant.WIN_CAPTURE_ROYAL || v.win == Variant.WIN_HILL || v.win == Variant.WIN_CHECKS) && v.pieces.none { it.royal })
			rc.addView(ui.text("Внимание: Нет ни одной королевской фигуры — побеждает тот, кто заберёт все фигуры соперника.", 13f, 0xFFFFB13B.toInt()))
		rc.addView(ui.space(6))
		rc.addView(ui.sub("Пат"))
		rc.addView(ui.chips(Variant.STALEMATE_NAMES.mapIndexed { i, n -> n to (i == v.stalemate) }, 3) { v.stalemate = it; save(); a.refresh() })
		rc.addView(ui.space(6))
		val flags = ArrayList<Pair<String, Boolean>>()
		if (v.players == 2) { flags.add("Рокировка" to v.castling); flags.add("Взятие на проходе" to v.enPassant) }
		flags.add("Двойной ход пешки" to v.pawnDouble)
		if (v.players == 2) flags.add("Перемешивать первую линию" to v.shuffle)
		flags.add("Расстановка перед партией" to v.setup)
		rc.addView(ui.chips(flags, 2) {
			when (flags[it].first) {
				"Рокировка" -> v.castling = !v.castling
				"Взятие на проходе" -> v.enPassant = !v.enPassant
				"Двойной ход пешки" -> v.pawnDouble = !v.pawnDouble
				"Перемешивать первую линию" -> v.shuffle = !v.shuffle
				else -> v.setup = !v.setup
			}
			save(); a.refresh()
		})
		if ((0 until v.players).any { v.shuf[it].size >= 2 }) {
			rc.addView(ui.sub("Перемешивание клеток (инструмент «Перемешать»): перед каждой партией фигуры на выбранных клетках каждого цвета меняются местами."))
			// если ни одна расстановка не оставляет рокировку возможной — предупреждаем сразу, а не перед партией
			val warn = (1..20).firstNotNullOfOrNull { v.dealt().dealtWarning }
			if (warn != null) rc.addView(ui.text("Внимание: $warn.", 13f, 0xFFFFB13B.toInt()))
		}
		if (v.setup) {
			rc.addView(ui.sub("Перед партией игроки по очереди ставят фигуры, лежащие на доске, в свои зоны (инструмент «Зона расстановки»). Fairy-Stockfish, нейросеть и игра по сети в таком варианте не работают."))
			for (c in 0 until v.players) {
				val pool = v.cells.count { it != 0 && v.has(it) && (it shr 8) == c }
				val room = v.zoneCells(c).size
				if (pool > room) rc.addView(ui.text("Внимание: у ${colorGen[c]} ${pool} фигур, а в зоне только $room клеток — все не поместятся.", 13f, 0xFFFFB13B.toInt()))
				else if (pool == 0) rc.addView(ui.text("Внимание: у ${colorGen[c]} на доске нет фигур для расстановки.", 13f, 0xFFFFB13B.toInt()))
			}
		}
		if (v.shuffle && v.players == 2) rc.addView(ui.sub("Перед каждой партией фигуры первой линии встают случайно, у чёрных — зеркально. Одинаковые фигуры, ходящие только по своему цвету (как слоны), — на клетки разного цвета, король — между двумя рокирующимися фигурами. Рокировка — по правилам конструктора: король идёт на две клетки к ладье."))
		rc.addView(ui.space(6))
		if (v.win != Variant.WIN_PROMO) {
			rc.addView(ui.sub("Пешки превращаются на клетках превращения (по умолчанию — последняя горизонталь; свои клетки — инструменты «Превращение» над доской) в:"))
			val promoable = v.pieces.filter { !it.royal && !it.pawn }
			if (promoable.isEmpty()) rc.addView(ui.sub("— нет подходящих фигур"))
			else rc.addView(ui.chips(promoable.map { "${it.symbol} ${it.name}" to (it.name in v.promo) }, 3) { i ->
				val n = promoable[i].name
				if (n in v.promo) v.promo.remove(n) else v.promo.add(n)
				save(); a.refresh()
			})
			if (v.promo.isEmpty()) rc.addView(ui.sub("Ничего не выбрано — пешка на клетке превращения остаётся пешкой."))
		}
		box.addView(rc)

		// фигуры
		val pc = ui.card()
		pc.addView(ui.text("Фигуры", 17f, bold = true))
		v.pieces.forEachIndexed { i, d ->
			val r = ui.row(10)
			r.gravity = Gravity.CENTER_VERTICAL
			val icon = object : View(a) { override fun onDraw(c: Canvas) = VArt.draw(context, c, d, 0, 0f, 0f, width.toFloat()) }
			r.addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
			val tags = listOfNotNull("${d.value}", "король".takeIf { d.royal }, "пешка".takeIf { d.pawn }, "рокируется".takeIf { d.castler })
			r.addView(ui.col(0, 0, ui.text(d.name, 15f, bold = true), ui.sub(tags.joinToString(" · "))), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r.addView(ui.button("✎", false) { a.push(PieceEditorScreen(a, index, i)) })
			r.addView(ui.button("✕", false) { v.removePiece(i); save(); a.refresh() })
			pc.addView(r, ui.lp())
			pc.addView(ui.space(6))
		}
		pc.addView(ui.button("＋ Новая фигура") {
			var n = "Фигура"; var k = 2
			while (v.pieces.any { it.name == n }) n = "Фигура ${k++}"
			v.pieces.add(PieceDef(n, "?", 0, 300, false, false, false, mutableListOf()))
			save()
			a.push(PieceEditorScreen(a, index, v.pieces.size - 1))
		})
		box.addView(pc)

		box.addView(ui.button("▶ Играть") { a.push(VariantSetupScreen(a, index)) })
		return ui.scroll(box)
	}
}

class PieceEditorScreen(a: MainActivity, private val vi: Int, private val pi: Int) : Screen(a) {
	private val st = a.store
	private var v: Variant = Variant.classic()
	private var d: PieceDef = PieceDef.pawn()
	private var grid: MoveGridView? = null

	private fun save() = VariantStore.put(st, vi, v)

	override fun build(): View {
		v = VariantStore.list(st).getOrNull(vi) ?: run { a.pop(); return View(a) }
		d = v.pieces.getOrNull(pi) ?: run { a.pop(); return View(a) }
		val box = ui.col(16, 12)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Фигура")))
		box.addView(ui.sub("Название"))
		box.addView(ui.field(a, d.name, "название", 24) { s ->
			val nn = s.trim()
			if (nn.isEmpty() || v.pieces.any { it !== d && it.name == nn }) return@field
			val i = v.promo.indexOf(d.name)
			if (i >= 0) v.promo[i] = nn
			d.name = nn; save()
		}, ui.lp())
		box.addView(ui.sub("Обозначение (1–2 знака): им фигура пишется в ходах и рисуется, если не выбран рисунок"))
		box.addView(ui.field(a, d.symbol, "символ", 2) { s -> if (s.isNotBlank()) { d.symbol = s.trim(); save(); grid?.invalidate() } }, ui.lp())
		box.addView(ui.sub("Рисунок"))
		box.addView(ui.chips(listOf("Символ", "Пешка", "Конь", "Слон", "Ладья", "Ферзь", "Король").mapIndexed { i, n -> n to (i == d.art) }, 4) { d.art = it; save(); a.refresh() })
		box.addView(ui.stepper("Ценность", d.value, { dv -> d.value = (d.value + dv).coerceIn(0, 5000); save(); a.refresh() }, 10, 100))
		box.addView(ui.chips(listOf("Король" to d.royal, "Пешка" to d.pawn, "Рокируется" to d.castler), 3) {
			when (it) { 0 -> d.royal = !d.royal; 1 -> d.pawn = !d.pawn; else -> d.castler = !d.castler }
			if (d.royal || d.pawn) v.promo.remove(d.name)
			save(); a.refresh()
		})
		box.addView(ui.sub("Король — его мат или взятие решает партию. Пешка — двойной ход с места, взятие на проходе, превращение. Рокируется — король может рокироваться с этой фигурой."))
		if (d.royal) {
			val cc = ui.card()
			cc.addView(ui.text("Рокировки", 17f, bold = true))
			cc.addView(ui.sub("Король идёт к фигуре с флагом «Рокируется» минимум на 2 клетки (на 1 — это был бы обычный ход короля), она встаёт рядом с ним с другой стороны. Обе фигуры не должны были ходить, между ними пусто, а при шахе (если он есть в варианте) нельзя рокироваться из-под шаха и через битые клетки."))
			cc.addView(ui.sub("Направление рокировки"))
			cc.addView(ui.chips(listOf("По горизонтали" to (d.castleAxis == 0), "По вертикали" to (d.castleAxis == 1)), 2) { d.castleAxis = it; save(); a.refresh() })
			cc.addView(ui.sub(if (d.castleAxis == 0) "Король идёт вправо или влево по своей горизонтали." else "Король идёт вверх или вниз по своей вертикали, к фигуре с флагом «Рокируется» в том же столбце. Fairy-Stockfish так не умеет — в таком варианте он не играет."))
			cc.addView(ui.chips(listOf((if (d.castleAxis == 0) "Короткая (вправо, к h)" else "Вверх") to d.castleShort, (if (d.castleAxis == 0) "Длинная (влево, к a)" else "Вниз") to d.castleLong), 2) {
				if (it == 0) d.castleShort = !d.castleShort else d.castleLong = !d.castleLong
				save(); a.refresh()
			})
			if (d.castleShort) cc.addView(ui.stepper("Короткая: король идёт на", d.stepsShort, { dv -> d.stepsShort = (d.stepsShort + dv).coerceIn(2, 6); save(); a.refresh() }, 1))
			if (d.castleLong) cc.addView(ui.stepper("Длинная: король идёт на", d.stepsLong, { dv -> d.stepsLong = (d.stepsLong + dv).coerceIn(2, 6); save(); a.refresh() }, 1))
			if (!v.castling) cc.addView(ui.text("Внимание: В правилах варианта рокировка выключена.", 13f, 0xFFFFB13B.toInt()))
			box.addView(cc)
		}

		val gc = ui.card()
		gc.addView(ui.text("Ходы", 17f, bold = true))
		gc.addView(ui.sub("Нажимай на клетку, чтобы перебрать: точка — прыжок ровно туда, стрелка — луч в этом направлении до препятствия. Цвет: зелёный — ход и взятие, синий — только ход, красный — только взятие."))
		gc.addView(ui.space(8))
		val g = MoveGridView(a, d) { save() }
		grid = g
		gc.addView(g, ui.lp())
		gc.addView(ui.space(8))
		val r = ui.row(8)
		r.addView(ui.button("Очистить", false) { d.moves.clear(); save(); a.refresh() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		r.addView(ui.button("Как у…", false) { copyFrom() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		gc.addView(r)
		box.addView(gc)
		return ui.scroll(box)
	}

	// взять ходы стандартной фигуры за основу
	private fun copyFrom() {
		val body = ui.col(0, 8)
		var dlg: android.app.AlertDialog? = null
		for (src in PieceDef.standard()) body.addView(ui.button(src.name, false) {
			dlg?.dismiss()
			d.moves.clear(); d.moves.addAll(src.moves)
			save(); a.refresh()
		})
		dlg = a.dialog("Ходы как у фигуры", body, "Отмена" to {})
	}
}

// Fairy-Stockfish в варианте из конструктора. Сначала сверяем перфт: если Fairy понял правила иначе — играет Материал 3.
class FairyVBot(private val ctx: Context, private val elo: Int, private val plan: FairyVariant.Plan, private val note: (String) -> Unit) : VBot {
	private val fallback = VMaterial(3)
	private var ok: Boolean? = null
	private var warned = false

	private fun verify(v: Variant): Boolean {
		val start = VPos(v)
		val depth = if (v.w * v.h <= 64) 3 else 2
		val ours = FairyVariant.perft(start, depth)
		val theirs = Stockfish.fairyCustom.customPerft(ctx, plan.section, plan.ini, FairyVariant.fen(start, plan), depth)
		if (theirs == null) { note("Fairy-Stockfish не запустился (${Stockfish.fairyCustom.lastError ?: "без ответа"}) — вместо него играет Материал 3"); return false }
		if (theirs != ours) { note("Fairy-Stockfish понял правила этого варианта иначе (перфт $theirs вместо $ours) — вместо него играет Материал 3"); return false }
		return true
	}

	override fun pick(p: VPos, cancel: () -> Boolean): Int {
		if (ok == null) ok = verify(p.v)
		if (ok != true) return fallback.pick(p, cancel)
		val uci = Stockfish.fairyCustom.customBest(ctx, plan.section, plan.ini, FairyVariant.fen(p, plan), elo, 1200)
		val m = uci?.let { FairyVariant.parse(p, plan, it) } ?: 0
		if (m != 0) return m
		if (!warned) { warned = true; note("Fairy-Stockfish предложил ход, которого нет по правилам варианта (${uci ?: "нет хода"}) — этот ход сделал Материал 3") }
		return fallback.pick(p, cancel)
	}
}

class VariantSetupScreen(a: MainActivity, private val index: Int) : Screen(a) {
	private val st = a.store
	private fun seat(c: Int, fairyOk: Boolean, noNeural: Boolean) = st.str("var_seat_$c", if (c == 0) "H" else "M2")
		.let { if (it.startsWith("F") && !fairyOk) "M2" else it }.let { if (it == "N" && noNeural) "M2" else it }

	override fun build(): View {
		val v = VariantStore.list(st).getOrNull(index) ?: run { a.pop(); return View(a) }
		val box = ui.col(16, 12)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title(v.name)))
		box.addView(ui.sub("${v.w}×${v.h} · ${winText(v)} · ${Variant.STALEMATE_NAMES[v.stalemate].lowercase()}" + if (v.players == 4) " · команды: красные + жёлтые против синих + зелёных" else ""))
		val fairy = FairyVariant.plan(v)
		val noNeural = v.players == 4 || v.setup
		val options = VBots.OPTIONS.filter { !(noNeural && it == "N") } + if (fairy.plan != null) VBots.FAIRY else emptyList()
		val names = if (v.players == 4) listOf("Красные", "Синие", "Жёлтые", "Зелёные") else listOf("Белые", "Чёрные")
		for (c in 0 until v.players) {
			val card = ui.card()
			card.addView(ui.text(names[c], 17f, bold = true))
			card.addView(ui.space(6))
			card.addView(ui.chips(options.map { VBots.title(it) to (it == seat(c, fairy.plan != null, noNeural)) }, 3) { st.setStr("var_seat_$c", options[it]); a.refresh() })
			box.addView(card)
		}
		box.addView(ui.sub("Материал — перебор по материалу, Алгоритм — набор правил без перебора; цифра — сила. Fairy-Stockfish — сильный движок, цифра — его Эло."))
		if (fairy.plan == null) box.addView(ui.text("Fairy-Stockfish в этом варианте не играет: ${fairy.reason}.", 13f, 0xFFFFB13B.toInt()))
		if (noNeural) box.addView(ui.text("Нейросеть через промпт в этом варианте недоступна (${if (v.players == 4) "четверо игроков" else "расстановка перед партией"}).", 13f, 0xFFFFB13B.toInt()))
		box.addView(ui.button("Начать партию") {
			val seats = (0 until v.players).map { seat(it, fairy.plan != null, noNeural) }
			a.replace(VariantGameScreen(a, v, seats[0], seats[1], extra = seats.drop(2)))
		})
		return ui.scroll(box)
	}
}

// Сиденья: "H" человек, "N" нейросеть через промпт, "L" соперник по сети, остальные — боты (VBots).
// На четверых сидений четыре (белые/чёрные = первое/второе, extra — третье и четвёртое).
class VariantGameScreen(a: MainActivity, private val base: Variant, white: String, black: String,
	private val link: ru.cersat.chessics.data.Link? = null, private val remoteName: String = "", dealt: Boolean = false,
	extra: List<String> = emptyList()) : Screen(a) {
	private val seats = listOf(white, black) + extra
	private val n = base.players
	private val placing0 = base.setup
	private val pool = Array(4) { mutableListOf<Int>() } // набор фигур каждого цвета при расстановке
	private var v: Variant = if (placing0) base.copy().also { d ->
		for (i in d.cells.indices) { val c = d.cells[i]; if (c != 0 && d.has(c) && (c shr 8) < d.players) pool[c shr 8].add(c); d.cells[i] = 0 }
		d.setup = false
		for (list in d.shuf) list.clear()
		for (list in pool) list.sortByDescending { c -> d.piece(c).value }
	} else if (dealt) base else base.dealt()
	private var pos = VPos(v).also { it.record() }
	private var placing = placing0
	private var placeColor = 0
	private var pickCode = 0
	private val trayBox by lazy { ui.col(0, 0) }
	init {
		// пасхалка: фигура без единого хода стоит на доске
		if (v.cells.any { it != 0 && v.has(it) && v.piece(it).moves.isEmpty() })
			ru.cersat.chessics.data.Eggs.find(a.store, "statue")?.let { msg -> BotRunner.main.post { a.toast(msg) } }
		v.dealtWarning?.let { w -> BotRunner.main.post { a.toast("Внимание: $w") } }
	}
	private val fairyPlan = if (seats.any { it.startsWith("F") }) FairyVariant.plan(v).plan else null
	private val bots: List<VBot?> = seats.map { s ->
		if (s.startsWith("F")) fairyPlan?.let { FairyVBot(a, s.drop(1).toIntOrNull() ?: 0, it) { msg -> BotRunner.main.post { a.toast(msg) } } } ?: VMaterial(3)
		else VBots.bot(s)
	}
	private var linkLost = false
	private val remoteColor = seats.indexOf("L")
	private val neuralBox by lazy { ui.col(0, 0) }
	private var manualBottom = -1

	init {
		link?.onClosed = { BotRunner.main.post { if (result < 0) { linkLost = true; a.toast("Соединение с соперником потеряно"); redraw() } } }
		link?.attach { o -> onRemote(o) }
	}

	private fun onRemote(o: JSONObject) {
		when (o.optString("t")) {
			"vmove" -> {
				if (result >= 0 || pos.turn != remoteColor || o.optInt("ply") != log.size) return
				val m = pos.legal().firstOrNull { VM.from(it) == o.optInt("f") && VM.to(it) == o.optInt("to") && VM.promo(it) == o.optInt("p") }
				if (m == null) { a.toast("Соперник прислал ход, которого нет по правилам — партии разошлись"); return }
				apply(m)
			}
			"resign" -> finish(1 - pos.team(remoteColor), "соперник сдался")
		}
	}
	private val log = ArrayList<String>()
	private val played = ArrayList<Int>() // ходы партии — для истории
	private val logEn = ArrayList<String>() // те же ходы латиницей — для промпта нейросети
	private var lastMove = 0
	private var result = -1
	private var reason = ""
	private var thinking = false
	private var token = 0
	private val board = VBoardView(a)
	private val bar = EvalBar(a)
	private lateinit var status: TextView
	private lateinit var moves: TextView
	private lateinit var controls: LinearLayout

	private fun sideName(c: Int) = v.colorName(c)
	private fun teamName(t: Int) = if (n == 4) (if (t == 0) "красных и жёлтых" else "синих и зелёных") else (if (t == 0) "белых" else "чёрных")

	override fun build(): View {
		val root = ui.col(10, 8)
		root.addView(ui.row(8, ui.button("‹", false) { a.goBack() }, ui.text(v.name, 15f, bold = true)))
		root.addView(bar.detached(), ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(18)))
		board.v = v
		board.flipped = n == 2 && seats[0] != "H" && seats[1] == "H"
		board.canMove = { result < 0 && !placing && seats[pos.turn] == "H" && !thinking }
		board.onMove = { ms -> human(ms) }
		root.addView(board.detached(), ui.lp())
		status = ui.text("", 15f, ui.t.text, true, Gravity.CENTER)
		root.addView(status, ui.lp())
		root.addView(trayBox.detached(), ui.lp())
		root.addView(neuralBox.detached(), ui.lp())
		controls = ui.row(8)
		root.addView(controls, ui.lp())
		moves = ui.sub("")
		root.addView(moves, ui.lp())
		redraw()
		if (placing) placeNext() else maybeBot()
		return ui.scroll(root)
	}

	private fun bottomSeat(): Int {
		if (manualBottom >= 0) return manualBottom
		val humans = seats.indices.filter { seats[it] == "H" }
		return when {
			humans.isEmpty() -> 0
			humans.size > 1 && seats[if (placing) placeColor else pos.turn] == "H" -> if (placing) placeColor else pos.turn // передают телефон — доска к ходящему
			else -> humans[0]
		}
	}

	private fun redraw() {
		board.sq = pos.sq
		board.lastMove = lastMove
		val live = result < 0 && !placing
		board.legal = if (live) pos.legal() else emptyList()
		board.check = if (live) pos.royals(pos.turn).firstOrNull { pos.attacked(it, pos.foe(pos.turn)) } ?: -1 else -1
		if (n == 4) board.seatDown = bottomSeat()
		board.clearSel()
		// полоса: материал, а при конце партии — решающий счёт
		bar.cp = when (result) { 0 -> 3000; 1 -> -3000; 2 -> 0; else -> pos.material().coerceIn(-3000, 3000) }
		bar.label = when (result) { 0 -> "1-0"; 1 -> "0-1"; 2 -> "½"; else -> "%+.1f".format(pos.material() / 100f) }
		val side = sideName(pos.turn)
		status.text = if (placing) {
			"Расстановка: ${sideName(placeColor)} — осталось ${pool[placeColor].size}" + if (seats[placeColor] == "H") "" else " (думает бот)"
		} else when (result) {
			-1 -> (when {
				linkLost -> "Соединение потеряно"
				thinking -> "${side.replaceFirstChar { it.uppercase() }} думают…"
				seats[pos.turn] == "N" -> "Ждём ход нейросети"
				seats[pos.turn] == "L" -> "Ход соперника"
				else -> "Ход: $side"
			}) +
				(if (board.check >= 0) " · шах!" else "") +
				(if (v.win == Variant.WIN_CHECKS) " · шахи ${pos.checks[0]}:${pos.checks[1]} из ${v.nChecks}" else "")
			2 -> "Ничья · $reason"
			else -> "Победа ${teamName(result)} · $reason"
		}
		moves.text = log.chunked(n).mapIndexed { i, p -> "${i + 1}. ${p.joinToString(" ")}" }.joinToString("   ")
		updateNeural()
		updateTray()
		controls.removeAllViews()
		if (n == 4) controls.addView(ui.button("⟳ Повернуть", false) { manualBottom = (board.seatDown + 1) % 4; redraw() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		else controls.addView(ui.button("⇅ Перевернуть", false) { board.flipped = !board.flipped; board.invalidate() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val humans = seats.indices.filter { seats[it] == "H" }
		val humanTeams = humans.map { pos.team(it) }.toSet()
		if (result < 0 && humanTeams.size == 1) controls.addView(ui.button("⚑ Сдаться", false) {
			a.dialog("Сдаться?", ui.sub("Партия будет проиграна."), "Сдаться" to {
				link?.send(JSONObject().put("t", "resign"))
				finish(1 - humanTeams.first(), "сдача")
			}, "Играть дальше" to {})
		}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (result >= 0 && link == null) controls.addView(ui.button("Ещё партия") { a.replace(again()) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
	}

	private fun again() = VariantGameScreen(a, base, seats[0], seats[1], extra = seats.drop(2))

	// ===== расстановка перед партией =====

	private fun freeZone(c: Int) = v.zoneCells(c).filter { pos.sq[it] == 0 }

	private fun updateTray() {
		trayBox.removeAllViews()
		board.onPlace = null; board.placeCells = emptySet()
		if (!placing) return
		if (seats[placeColor] != "H") return
		val c = placeColor
		board.placeCells = freeZone(c).toSet()
		board.onPlace = { i -> if (i in board.placeCells && pickCode != 0 && pool[c].contains(pickCode)) putPiece(c, pickCode, i) }
		if (pickCode == 0 || !pool[c].contains(pickCode)) pickCode = pool[c].firstOrNull() ?: 0
		trayBox.addView(ui.sub("Выбери фигуру и нажми зелёную клетку:"))
		val kinds = pool[c].distinct()
		val row = ui.row(6)
		for (code in kinds) {
			val d = v.piece(code)
			val cnt = pool[c].count { it == code }
			val icon = object : View(a) {
				override fun onDraw(cv: Canvas) {
					VArt.draw(context, cv, d, c, 0f, 0f, minOf(width, height).toFloat(), n == 4)
					val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = height * 0.3f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()) }
					cv.drawText("$cnt", width - tp.measureText("$cnt") - 4f, height - 6f, tp)
				}
			}
			ui.clickable(icon, if (code == pickCode) ui.t.accent else ui.t.card2, 12f) { pickCode = code; redraw() }
			row.addView(icon, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
		}
		trayBox.addView(ui.hscroll(row))
	}

	private fun putPiece(c: Int, code: Int, cell: Int) {
		pool[c].remove(code)
		pos.sq[cell] = code
		Sounds.play(a, Sounds.Kind.MOVE)
		placeNext()
	}

	// следующий ход расстановки: цвета ставят по одной фигуре по очереди
	private fun placeNext() {
		token++
		var guard = 0
		while (guard++ < 8) {
			if (pool.take(n).all { it.isEmpty() }) { endPlacement(); return }
			if (pool[placeColor].isNotEmpty() && freeZone(placeColor).isEmpty()) {
				a.toast("У ${sideName(placeColor)} в зоне не хватило клеток — оставшиеся фигуры не выставлены")
				pool[placeColor].clear()
			}
			if (pool[placeColor].isEmpty()) { placeColor = (placeColor + 1) % n; continue }
			break
		}
		redraw()
		if (seats[placeColor] != "H") {
			val my = token
			BotRunner.main.postDelayed({ if (token == my && placing) botPlace(placeColor) }, 150)
		}
	}

	// бот ставит фигуру: короля и тяжёлые — к своей линии, пешки — на вторую, остальное случайно
	private fun botPlace(c: Int) {
		val code = pool[c].firstOrNull { v.piece(it).royal } ?: pool[c].random()
		val d = v.piece(code)
		val free = freeZone(c)
		val cell = free.maxByOrNull { i ->
			val ly = v.toLy(c, i)
			val want = when { d.pawn -> 1; d.royal -> 0; else -> 0 }
			-Math.abs(ly - want) * 3.0 + Math.random() * 4
		} ?: return
		putPiece(c, code, cell)
	}

	private fun endPlacement() {
		placing = false
		val nv = v.copy()
		nv.cells = pos.sq.copyOf()
		v = nv
		pos = VPos(v).also { it.record() }
		board.v = v
		board.onPlace = null; board.placeCells = emptySet()
		token++
		val r = pos.result()
		if (r >= 0) { redraw(); finish(r, reasonFor(r)); return }
		redraw()
		maybeBot()
	}

	private fun human(ms: List<Int>) {
		if (ms.size == 1) { apply(ms[0]); return }
		// несколько ходов на одну клетку — это превращение: спрашиваем фигуру
		val body = ui.row(8)
		var dlg: android.app.AlertDialog? = null
		for (m in ms) {
			val d = v.pieces[VM.promo(m) - 1]
			val icon = object : View(a) { override fun onDraw(c: Canvas) = VArt.draw(context, c, d, pos.turn, 0f, 0f, minOf(width, height).toFloat(), n == 4) }
			ui.clickable(icon, ui.t.card2, 12f) { dlg?.dismiss(); apply(m) }
			body.addView(icon, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
		}
		dlg = a.dialog("Превращение пешки", body, "Отмена" to { redraw() })
	}

	private fun apply(m: Int) {
		if (result >= 0 || placing || m !in pos.legal()) return
		if (link != null && seats[pos.turn] == "H") link.send(JSONObject().put("t", "vmove").put("f", VM.from(m)).put("to", VM.to(m)).put("p", VM.promo(m)).put("ply", log.size))
		val cap = pos.sq[VM.to(m)] != 0 || VM.flags(m) and VM.EP != 0
		log.add(pos.moveName(m))
		logEn.add(Neural.name(pos, m))
		played.add(m)
		pos.make(m); pos.record()
		lastMove = m
		val check = pos.inCheck(pos.turn)
		Sounds.play(a, Sounds.forMove(cap, VM.flags(m) and VM.CASTLE != 0, check))
		val r = pos.result()
		if (r >= 0) { finish(r, reasonFor(r)); return }
		redraw()
		maybeBot()
	}

	private fun reasonFor(r: Int): String {
		if (r == 2) return when {
			pos.quiet >= 50 * n -> "${50} ходов без взятий и ходов пешками"
			(pos.history[pos.key()] ?: 0) >= 3 -> "троекратное повторение"
			else -> "пат"
		}
		val last = pos.prev()
		return when (v.win) {
			Variant.WIN_CAPTURE_ROYAL -> "король взят"
			Variant.WIN_ALL -> "все фигуры взяты"
			Variant.WIN_HILL -> if (pos.royals(last).any { pos.onHill(it) }) "король на холме" else "мат"
			Variant.WIN_CHECKS -> if (pos.checks[r] >= v.nChecks) "${v.nChecks} шахов" else "мат"
			Variant.WIN_PROMO -> if (pos.promoted == r) "пешка дошла до конца" else "мат"
			else -> if (pos.legal().isEmpty() && !pos.inCheck(pos.turn)) "пат" else "мат"
		}
	}

	private fun maybeBot() {
		if (result >= 0 || thinking || placing) return
		val bot = bots[pos.turn] ?: return
		thinking = true
		redraw()
		val my = ++token
		val p = pos.copy()
		BotRunner.exec.execute {
			val t0 = System.currentTimeMillis()
			val m = try { bot.pick(p) { token != my } } catch (_: Exception) { p.legal().randomOrNull() ?: 0 }
			val wait = maxOf(0L, 300 - (System.currentTimeMillis() - t0))
			BotRunner.main.postDelayed({
				if (token != my) return@postDelayed
				thinking = false
				if (m != 0) apply(m) else redraw()
			}, wait)
		}
	}

	// сиденье → описание игрока для наград и рейтинга; null — не бот (человек, нейросеть, сеть)
	private fun botOf(s: String): ru.cersat.chessics.bots.Player? = when {
		s == "R" -> ru.cersat.chessics.bots.Player(ru.cersat.chessics.bots.Player.Kind.RANDOM, 0)
		s.startsWith("M") -> ru.cersat.chessics.bots.Player(ru.cersat.chessics.bots.Player.Kind.MATERIAL, s.drop(1).toIntOrNull() ?: 2)
		s.startsWith("A") -> ru.cersat.chessics.bots.Player(ru.cersat.chessics.bots.Player.Kind.ALGO, s.drop(1).toIntOrNull() ?: 3)
		s.startsWith("F") -> ru.cersat.chessics.bots.Player(ru.cersat.chessics.bots.Player.Kind.STOCKFISH, s.drop(1).toIntOrNull() ?: 0)
		else -> null
	}

	private fun nameOf(c: Int) = if (seats[c] == "L") remoteName.ifEmpty { "Соперник" } else VBots.title(seats[c])
	private fun sideLine(team: Int) = (0 until n).filter { it and 1 == team }.joinToString(" + ") { nameOf(it) }

	private fun saveHistory(r: Int, why: String) {
		if (played.isEmpty()) return
		val o = JSONObject()
			.put("date", java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
			.put("w", sideLine(0)).put("b", sideLine(1))
			.put("res", when (r) { 0 -> "1-0"; 1 -> "0-1"; else -> "1/2-1/2" }).put("reason", why)
			.put("uci", "").put("tc", "").put("variant", 0)
			.put("custom", v.json())
			.put("vmoves", JSONArray().apply { played.forEach { m -> put(JSONArray().put(VM.from(m)).put(VM.to(m)).put(VM.promo(m))) } })
		a.store.saveGame(o)
	}

	// награды и статистика, как в обычных партиях: победа над ботом в своём варианте — это победа над ботом
	private fun giveRewards(r: Int, why: String): ru.cersat.chessics.data.Store.Reward? {
		if (link != null || played.isEmpty()) return null
		val humans = seats.indices.filter { seats[it] == "H" }
		val st = a.store
		val plies = played.size
		if (n == 2) {
			if (humans.size == 2) return st.gameFinished(null, 0.5, plies, 0, true, false, false)
			if (humans.size != 1) return null
			val bot = botOf(seats[1 - humans[0]]) ?: return null
			val score = if (r == 2) 0.5 else if (r == humans[0]) 1.0 else 0.0
			return st.gameFinished(bot, score, plies, 0, false, score >= 1.0 && why == "мат", false)
		}
		// на четверых — как в «На четверых»: человек (или люди) в одной команде против ботов
		val teams = humans.map { it and 1 }.toSet()
		if (teams.size != 1) return null
		val mine = teams.first()
		if ((0 until n).none { it and 1 != mine && seats[it] != "H" }) return null
		return st.fourFinished(r == mine, r == 2, plies)
	}

	private fun finish(r: Int, why: String) {
		if (result >= 0) return
		saveHistory(r, why)
		result = r; reason = why
		token++; thinking = false
		Sounds.play(a, Sounds.Kind.END)
		val reward = giveRewards(r, why)
		redraw()
		val head = when (r) { 2 -> "Ничья"; else -> "Победа ${teamName(r)}" }
		val body = ui.col(0, 4)
		body.addView(ui.sub(why.replaceFirstChar { it.uppercase() }, 15f))
		reward?.lines?.forEach { body.addView(ui.sub(it, 14f)) }
		if (link != null) a.dialog(head, body, "Посмотреть доску" to {}, "Анализ партии" to { openReview(head, why) }, "В меню" to { a.showTab(2) })
		else a.dialog(head, body, "Анализ партии" to { openReview(head, why) }, "Ещё партия" to { a.replace(again()) }, "В меню" to { a.showTab(2) })
	}

	private fun openReview(head: String, why: String) {
		a.push(VariantReviewScreen(a, v, played.map { intArrayOf(VM.from(it), VM.to(it), VM.promo(it)) }, "${sideLine(0)} — ${sideLine(1)} · ${v.name}", "$head · $why"))
	}

	// ход нейросети: промпт → ответ → проверка
	private fun updateNeural() {
		neuralBox.removeAllViews()
		if (result >= 0 || placing || n != 2 || seats[pos.turn] != "N" || thinking) return
		neuralBox.addView(Neural.panel(a, if (pos.turn == 0) "белые" else "чёрные",
			prompt = { Neural.prompt(pos, logEn) },
			tryAnswer = { text ->
				val legal = pos.legal()
				val i = Neural.pick(text, legal.map { Neural.name(pos, it) })
				if (i == null) "Не понял ответ: в нём нет номера хода из списка. Скопируй промпт ещё раз и попроси ответить JSON."
				else { apply(legal[i]); null }
			},
			fallback = {
				val p = pos.copy(); val my = token
				BotRunner.exec.execute {
					val m = VMaterial(3).pick(p) { token != my }
					BotRunner.main.post { if (token == my && m != 0) apply(m) }
				}
			}))
	}

	override fun dispose() {
		token++
		if (fairyPlan != null) Stockfish.fairyCustom.stop()
		if (link != null) { if (result < 0) link.send(JSONObject().put("t", "resign")); link.close() }
	}
}

// ===== Разбор партии в своём варианте: как обычный разбор, только движок — Fairy-Stockfish =====

// Анализ идёт в фоне и кэшируется: можно уйти с экрана и вернуться — посчитанное не пропадёт
object VariantAnalyzer {
	class State(val n: Int) {
		val evals = HashMap<Int, Int>() // оценка позиции после i полуходов, за белых
		val best = HashMap<Int, String>() // лучший ход Fairy в позиции i
		val second = HashMap<Int, Int>() // оценка второй линии (для «отличного» хода)
		val bestInt = HashMap<Int, Int>() // лучший ход движка «Материал» (уже в наших ходах)
		var engine = "Fairy-Stockfish" // кто считает сейчас
		var note: String? = null // почему сменился движок
		@Volatile var running = false
		var error: String? = null
		val complete get() = evals.size >= n
	}

	private val states = HashMap<String, State>()
	private val listeners = HashMap<String, MutableList<() -> Unit>>()

	fun key(v: Variant, moves: List<IntArray>) = (v.json().toString() + moves.joinToString(";") { it.joinToString(",") }).hashCode().toString()
	fun state(key: String) = states[key]
	fun listen(key: String, f: () -> Unit) { listeners.getOrPut(key) { ArrayList() }.add(f) }
	fun unlisten(key: String, f: () -> Unit) { listeners[key]?.remove(f) }
	private fun notify(key: String) = listeners[key]?.toList()?.forEach { it() }

	private val materialExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } } }

	// Простой движок на чистом материале: перебор с форсированными взятиями. Работает в любом варианте, в том числе там, где Fairy не справляется.
	private fun materialLine(p: VPos): Triple<Int, Int?, Int?> { // оценка за белых, лучший ход, оценка второй линии
		val sign = if (p.team(p.turn) == 0) 1 else -1
		fun cp(x: Int) = if (Math.abs(x) >= 500_000) (if (x > 0) 10000 else -10000) * sign else x * sign
		val depth = if (p.n <= 64) 4 else if (p.n <= 144) 3 else 2
		var lines = VMaterial(depth).lines(p, depth, 900)
		if (lines.isEmpty()) lines = VMaterial(1).lines(p, 1, 3000)
		if (lines.isEmpty()) return Triple(p.material().coerceIn(-3000, 3000), null, null)
		return Triple(cp(lines[0].second), lines[0].first, lines.getOrNull(1)?.let { cp(it.second) })
	}

	// plan == null — Fairy этот вариант не понимает, считает «Материал». Если Fairy замолчит посреди разбора, дальше тоже считает «Материал».
	fun start(ctx: android.content.Context, key: String, plan: FairyVariant.Plan?, positions: List<VPos>) {
		val st = states.getOrPut(key) { State(positions.size) }
		if (st.running || st.complete) return
		st.running = true; st.error = null
		if (plan == null) st.engine = "Материал"
		val app = ctx.applicationContext
		val eng = Stockfish.fairyReview
		var useFairy = plan != null
		fun run() {
			for (i in positions.indices) {
				if (st.evals.containsKey(i)) continue
				val p = positions[i]
				val r = p.result()
				if (r >= 0) {
					val score = when (r) { 0 -> 10000; 1 -> -10000; else -> 0 }
					BotRunner.main.post { st.evals[i] = score; notify(key) }
					continue
				}
				if (useFairy && plan != null) {
					val lines = eng.customLines(app, plan.section, plan.ini, FairyVariant.fen(p, plan), p.turn == 0, 500, 2)
					if (!lines.isNullOrEmpty()) {
						BotRunner.main.post {
							st.evals[i] = lines[0].score
							st.best[i] = lines[0].move
							lines.getOrNull(1)?.let { st.second[i] = it.score }
							notify(key)
						}
						continue
					}
					useFairy = false
					val why = eng.lastError ?: "не ответил"
					BotRunner.main.post { st.engine = "Материал"; st.note = "Fairy-Stockfish не справился ($why) — дальше считает «Материал»"; notify(key) }
				}
				val (score, best, second) = try { materialLine(p) } catch (e: Exception) { Triple(p.material().coerceIn(-3000, 3000), null, null) }
				BotRunner.main.post {
					st.evals[i] = score
					best?.let { st.bestInt[i] = it }
					second?.let { st.second[i] = it }
					notify(key)
				}
			}
			BotRunner.main.post { st.running = false; notify(key) }
		}
		if (plan != null) eng.exec.execute { run() } else materialExec.execute { run() }
	}

	// сколько материала сторона отдаёт ходом: что может забрать соперник сразу после, минус то, что взяли сами
	private fun sacrifice(before: VPos, m: Int): Int {
		val mover = before.turn
		if (before.def(before.sq[VM.from(m)]).royal) return 0
		val capVal = before.sq[VM.to(m)].let { if (it != 0) before.def(it).value else 0 } + if (VM.flags(m) and VM.EP != 0) 100 else 0
		val p = before.copy(); p.make(m)
		if (p.result() >= 0) return 0
		var loss = 0
		for (c in p.legal()) {
			val victim = p.sq[VM.to(c)]
			if (victim == 0 || p.team(p.color(victim)) != p.team(mover) || p.def(victim).royal) continue
			val q = p.copy(); q.make(c)
			val attacker = p.def(p.sq[VM.from(c)]).value
			val gain = p.def(victim).value - (if (q.attacked(VM.to(c), q.team(mover))) attacker else 0)
			loss = maxOf(loss, gain)
		}
		return loss - capVal
	}

	// тип хода по тем же правилам, что в обычном разборе (Judge.classify)
	fun classify(before: VPos, m: Int, played: Int, st: State, i: Int, plan: FairyVariant.Plan?): MoveClass? {
		val wbScore = st.evals[i] ?: return null
		val after = st.evals[i + 1] ?: return null
		if (before.legal().size == 1) return MoveClass.FORCED
		val sign = if (before.team(before.turn) == 0) 1 else -1
		val wb = ru.cersat.chessics.bots.Judge.winPct(wbScore * sign)
		val wa = ru.cersat.chessics.bots.Judge.winPct(after * sign)
		val loss = maxOf(0.0, wb - wa)
		val isBest = st.bestInt[i]?.let { it == played } ?: (plan != null && st.best[i]?.let { FairyVariant.parse(before, plan, it) } == played)
		if ((isBest || loss < 2) && wa >= 45 && wb < 97 && sacrifice(before, m) >= 200) return MoveClass.BRILLIANT
		if (isBest) st.second[i]?.let { s2 -> if (wb - ru.cersat.chessics.bots.Judge.winPct(s2 * sign) >= 15 && wb < 97) return MoveClass.GREAT }
		return when {
			isBest || loss < 1 -> MoveClass.BEST
			loss < 3 -> MoveClass.EXCELLENT
			loss < 10 -> MoveClass.GOOD
			loss < 20 -> MoveClass.INACCURACY
			loss < 30 -> MoveClass.MISTAKE
			else -> MoveClass.BLUNDER
		}
	}
}

class VariantReviewScreen(a: MainActivity, private val v: Variant, private val moves: List<IntArray>, private val title: String, private val resultLine: String) : Screen(a) {
	private val positions = ArrayList<VPos>()
	private val played = ArrayList<Int>()
	private val names = ArrayList<String>()
	private var ply = 0
	private val plan = FairyVariant.plan(v)
	private val key = VariantAnalyzer.key(v, moves)
	private val classes = HashMap<Int, MoveClass>() // тип хода i (0 — первый ход)
	private val board = VBoardView(a)
	private val graph = EvalGraph(a)
	private lateinit var info: TextView
	private lateinit var list: LinearLayout
	private val listener: () -> Unit = { show(false) }

	init {
		var p = VPos(v)
		positions.add(p)
		for (mv in moves) {
			val m = p.legal().firstOrNull { VM.from(it) == mv[0] && VM.to(it) == mv[1] && VM.promo(it) == mv[2] } ?: break
			names.add(p.moveName(m)); played.add(m)
			p = p.copy(); p.make(m)
			positions.add(p)
		}
	}

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.text("Анализ · $title", 16f, bold = true)))
		if (resultLine.isNotEmpty()) box.addView(ui.sub(resultLine))
		board.v = v
		box.addView(board.detached(), ui.lp())
		graph.onPick = { ply = it.coerceIn(0, played.size); show(true) }
		box.addView(graph.detached(), ui.lp(h = ui.dp(70)))
		info = ui.text("", 14f, ui.t.text)
		box.addView(info, ui.lp())
		val nav = ui.row(8)
		nav.addView(ui.button("⏮", false) { ply = 0; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("◀", false) { if (ply > 0) ply--; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("▶", false) { if (ply < played.size) ply++; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("⏭", false) { ply = played.size; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		box.addView(nav, ui.lp())
		list = ui.col(0, 6)
		box.addView(list, ui.lp())
		VariantAnalyzer.unlisten(key, listener)
		VariantAnalyzer.listen(key, listener)
		VariantAnalyzer.start(a, key, plan.plan, positions)
		show(false)
		return ui.scroll(box)
	}

	private fun classOf(i: Int): MoveClass? {
		classes[i]?.let { return it }
		val st = VariantAnalyzer.state(key) ?: return null
		val c = VariantAnalyzer.classify(positions[i], played[i], played[i], st, i, plan.plan) ?: return null
		classes[i] = c
		return c
	}

	// animate — пользователь перешёл на ход: бриллиант и отличный ход празднуем вспышкой
	private fun show(animate: Boolean) {
		val st = VariantAnalyzer.state(key)
		val p = positions[ply]
		val last = if (ply > 0) played[ply - 1] else 0
		board.sq = p.sq
		board.lastMove = last
		board.check = p.royals(p.turn).firstOrNull { p.attacked(it, p.foe(p.turn)) } ?: -1
		board.fx.clear()
		board.hint = 0
		if (st == null) { info.text = "Анализ идёт в фоне…"; buildList(null); board.invalidate(); return }
		// график — по уже посчитанным подряд позициям
		val seq = ArrayList<Int>()
		while (st.evals.containsKey(seq.size)) seq.add(st.evals[seq.size]!!)
		graph.values = seq
		graph.cursor = if (st.evals.containsKey(ply)) ply else -1
		val cls = if (ply > 0) classOf(ply - 1) else null
		if (cls != null && last != 0) {
			if (animate && (cls == MoveClass.BRILLIANT || cls == MoveClass.GREAT)) board.fx.celebrate(VM.to(last), cls)
			else board.fx.set(VM.to(last), cls)
		}
		val bm = st.bestInt[ply] ?: st.best[ply]?.let { u -> plan.plan?.let { FairyVariant.parse(p, it, u) } } ?: 0
		if (bm != 0 && ply < played.size) board.hint = bm
		board.invalidate()
		var t = st.evals[ply]?.let { e -> "Оценка: " + if (kotlin.math.abs(e) >= 9000) "мат" else String.format(java.util.Locale.US, "%+.2f", e / 100.0) }
			?: "Эта позиция ещё считается…"
		if (cls != null) t += " · ${names[ply - 1]} ${cls.symbol} — ${cls.title}"
		if (bm != 0) t += "\nЛучший ход здесь: ${p.moveName(bm)} (синяя стрелка)"
		t += "\nСчитает: ${st.engine}" + (if (plan.plan == null) " — Fairy-Stockfish не понимает этот вариант (${plan.reason})" else "") + (if (st.engine == "Материал") " · только материал, без позиционной оценки" else "")
		st.note?.let { t += "\n$it" }
		if (st.error != null) t += "\nАнализ остановился: ${st.error}"
		else if (!st.complete) t += "\nДосчитывается в фоне: ${st.evals.size} / ${positions.size}"
		info.text = t
		buildList(st)
	}

	private fun buildList(st: VariantAnalyzer.State?) {
		list.removeAllViews()
		if (st != null) for (side in 0..1) {
			var acc = 0.0; var cnt = 0
			val counts = HashMap<MoveClass, Int>()
			for (i in played.indices) {
				if (positions[i].team(positions[i].turn) != side) continue
				val c = classOf(i) ?: continue
				val sign = if (side == 0) 1 else -1
				val l = maxOf(0.0, ru.cersat.chessics.bots.Judge.winPct(st.evals[i]!! * sign) - ru.cersat.chessics.bots.Judge.winPct(st.evals[i + 1]!! * sign))
				acc += (103.1668 * Math.exp(-0.04354 * l) - 3.1669).coerceIn(0.0, 100.0); cnt++
				counts[c] = (counts[c] ?: 0) + 1
			}
			val name = if (v.players == 4) (if (side == 0) "Красные+жёлтые" else "Синие+зелёные") else if (side == 0) "Белые" else "Чёрные"
			list.addView(ui.text(String.format(java.util.Locale.US, "%s · %.0f%%", name, if (cnt > 0) acc / cnt else 0.0), 13f, ui.t.text, true))
			val chipsRow = ui.row(4)
			for (c in MoveClass.values()) {
				val n = counts[c] ?: 0
				if (n == 0) continue
				chipsRow.addView(ui.text("${c.symbol} $n", 12f, 0xFFFFFFFF.toInt(), true).apply {
					setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3)); background = ui.round(c.color, 10f)
				})
			}
			list.addView(ui.hscroll(chipsRow))
		}
		// ходы: цвет и значок типа, нажатие — переход
		var row: LinearLayout? = null
		for (i in played.indices) {
			if (i % 2 == 0) {
				row = ui.row(6)
				row.addView(ui.text("${i / 2 + 1}.", 13f, ui.t.sub), LinearLayout.LayoutParams(ui.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
				list.addView(row)
			}
			val cls = if (st != null) classOf(i) else null
			val sel = i + 1 == ply
			val tv = ui.text(names[i] + (cls?.let { " " + it.symbol } ?: ""), 14f,
				if (sel) Ui.contrast(ui.t.accent) else (cls?.takeIf { it in listOf(MoveClass.BRILLIANT, MoveClass.GREAT, MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER) }?.color ?: ui.t.text), true)
			tv.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
			val target = i + 1
			ui.clickable(tv, if (sel) ui.t.accent else ui.t.card, 8f) { ply = target; show(true) }
			row!!.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
		if (played.size % 2 == 1 && row != null) row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
	}

	override fun dispose() { VariantAnalyzer.unlisten(key, listener) }
}
