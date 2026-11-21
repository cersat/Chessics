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
import ru.cersat.chessics.bots.Stockfish
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

// Рисование фигур конструктора: стандартные — картинками текущего набора, свои — буквой в кружке
object VArt {
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
	private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
	private val dst = RectF()

	fun draw(ctx: Context, c: Canvas, d: PieceDef, color: Int, l: Float, t: Float, s: Float) {
		if (d.art in 1..6) {
			val b = PieceArt.get(ctx, Shop.pieces(ctx).set, P.make(color, d.art))
			if (b != null) {
				dst.set(l, t, l + s, t + s)
				c.drawBitmap(b, null, dst, fill)
				return
			}
		}
		val cx = l + s / 2; val cy = t + s / 2
		val bodyColor = if (color == 0) 0xFFF4F1E8.toInt() else 0xFF26282E.toInt()
		val inkColor = if (color == 0) 0xFF1B1C20.toInt() else 0xFFF4F1E8.toInt()
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

// Доска варианта: и для редактора (нажатие по клетке), и для партии (выбор и ход)
class VBoardView(ctx: Context) : View(ctx) {
	var v: Variant = Variant.classic()
	var sq: IntArray = v.cells
	var flipped = false
	var lastMove = 0
	var check = -1
	var legal: List<Int> = emptyList()
	var onCell: ((Int) -> Unit)? = null // режим редактора
	var canMove: () -> Boolean = { false }
	var onMove: (List<Int>) -> Unit = {}
	private var selected = -1
	private val p = Paint(Paint.ANTI_ALIAS_FLAG)
	private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
	private val board = Shop.board(ctx)

	fun clearSel() { selected = -1; invalidate() }

	override fun onMeasure(ws: Int, hs: Int) {
		val w = MeasureSpec.getSize(ws)
		setMeasuredDimension(w, w * v.h / v.w)
	}

	private val cell get() = width.toFloat() / v.w

	private fun col(s: Int) = if (flipped) v.w - 1 - s % v.w else s % v.w
	private fun row(s: Int) = if (flipped) s / v.w else v.h - 1 - s / v.w

	override fun onDraw(c: Canvas) {
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
		for (i in sq.indices) {
			val code = sq[i]
			if (code == 0 || !v.has(code)) continue
			VArt.draw(context, c, v.piece(code), code shr 8, col(i) * s, row(i) * s, s)
		}
		if (selected >= 0) {
			p.color = 0x66000000
			for (m in legal) if (VM.from(m) == selected) {
				val t = VM.to(m)
				c.drawCircle(col(t) * s + s / 2, row(t) * s + s / 2, s * (if (sq[t] != 0) 0.42f else 0.16f), p)
			}
		}
		// координаты: буквы снизу, цифры слева, с обводкой — читаются на любой клетке
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

	override fun onTouchEvent(e: MotionEvent): Boolean {
		if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
		val cx = (e.x / cell).toInt(); val cy = (e.y / cell).toInt()
		if (cx !in 0 until v.w || cy !in 0 until v.h) return true
		val x = if (flipped) v.w - 1 - cx else cx
		val y = if (flipped) cy else v.h - 1 - cy
		val i = v.idx(x, y)
		onCell?.let { it(i); return true }
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
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 12)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Конструктор вариантов")))
		box.addView(ui.sub("Своя доска до 12×12, заблокированные клетки, свои фигуры с любыми ходами и правила победы. Играть можно против ботов или вдвоём."))
		box.addView(ui.row(8,
			ui.button("＋ Новый") { newVariant() },
			ui.button("📋 Вставить", false) { import() }).apply { (0 until childCount).forEach { getChildAt(it).layoutParams = ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) } })
		val all = VariantStore.list(st)
		if (all.isEmpty()) box.addView(ui.sub("Пока нет сохранённых вариантов."))
		all.forEachIndexed { i, v ->
			val c = ui.card()
			c.addView(ui.text(v.name, 17f, bold = true))
			c.addView(ui.sub("${v.w}×${v.h} · ${winText(v)} · фигур: ${v.pieces.size}"))
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
		body.addView(ui.button("С классических шахмат", false) { start(Variant.classic().also { it.name = "Мой вариант" }) })
		body.addView(ui.button("С мини-доски 6×6", false) { start(Variant.mini().also { it.name = "Мой вариант" }) })
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
	private val v: Variant = VariantStore.list(st).getOrNull(index) ?: Variant.classic()
	private var tool = 0 // 0 — поставить фигуру, 1 — стереть, 2 — заблокировать клетку
	private var color = 0
	private var piece = 0

	private fun save() = VariantStore.put(st, index, v)

	override fun build(): View {
		val box = ui.col(16, 12)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Редактор")))
		box.addView(ui.sub("Всё сохраняется сразу."))
		box.addView(ui.field(a, v.name, "название варианта", 40) { v.name = it.ifBlank { "Вариант" }; save() }, ui.lp())

		// доска
		val bc = ui.card()
		bc.addView(ui.text("Доска", 17f, bold = true))
		bc.addView(ui.stepper("Ширина", v.w, { d -> val nw = (v.w + d).coerceIn(Variant.MIN, Variant.MAX); if (nw != v.w) { v.resize(nw, v.h); save(); a.refresh() } }, 1))
		bc.addView(ui.stepper("Высота", v.h, { d -> val nh = (v.h + d).coerceIn(Variant.MIN, Variant.MAX); if (nh != v.h) { v.resize(v.w, nh); save(); a.refresh() } }, 1))
		bc.addView(ui.space(6))
		bc.addView(ui.chips(listOf("Фигура" to (tool == 0), "Стереть" to (tool == 1), "Блок клетки" to (tool == 2)), 3) { tool = it; a.refresh() })
		if (tool == 0) {
			bc.addView(ui.space(6))
			bc.addView(ui.chips(listOf("Белые" to (color == 0), "Чёрные" to (color == 1)), 2) { color = it; a.refresh() })
			bc.addView(ui.space(6))
			if (piece >= v.pieces.size) piece = 0
			if (v.pieces.isNotEmpty()) bc.addView(ui.chips(v.pieces.mapIndexed { i, d -> "${d.symbol} ${d.name}" to (i == piece) }, 3) { piece = it; a.refresh() })
		}
		bc.addView(ui.space(8))
		val bv = VBoardView(a)
		bv.v = v; bv.sq = v.cells
		bv.onCell = { i ->
			when (tool) {
				0 -> if (v.pieces.isNotEmpty() && !v.locked[i]) { val c = Variant.code(piece, color); v.cells[i] = if (v.cells[i] == c) 0 else c }
				1 -> { v.cells[i] = 0; v.locked[i] = false }
				else -> { v.locked[i] = !v.locked[i]; if (v.locked[i]) v.cells[i] = 0 }
			}
			save(); bv.invalidate()
		}
		bc.addView(bv, ui.lp())
		bc.addView(ui.space(8))
		bc.addView(ui.button("Очистить доску", false) { v.cells.fill(0); v.locked.fill(false); save(); a.refresh() })
		box.addView(bc)

		// правила
		val rc = ui.card()
		rc.addView(ui.text("Правила", 17f, bold = true))
		rc.addView(ui.sub("Как побеждают"))
		rc.addView(ui.chips(Variant.WIN_NAMES.mapIndexed { i, n -> n to (i == v.win) }, 2) { v.win = it; save(); a.refresh() })
		if (v.win == Variant.WIN_CHECKS) rc.addView(ui.stepper("Сколько шахов", v.nChecks, { d -> v.nChecks = (v.nChecks + d).coerceIn(1, 20); save(); a.refresh() }, 1))
		if (v.win == Variant.WIN_HILL) rc.addView(ui.sub("Холм — центральные клетки доски (2×2 на чётной стороне, 1 клетка на нечётной)."))
		if ((v.win == Variant.WIN_MATE || v.win == Variant.WIN_CAPTURE_ROYAL || v.win == Variant.WIN_HILL || v.win == Variant.WIN_CHECKS) && v.pieces.none { it.royal })
			rc.addView(ui.text("⚠ Нет ни одной королевской фигуры — побеждает тот, кто заберёт все фигуры соперника.", 13f, 0xFFFFB13B.toInt()))
		rc.addView(ui.space(6))
		rc.addView(ui.sub("Пат"))
		rc.addView(ui.chips(Variant.STALEMATE_NAMES.mapIndexed { i, n -> n to (i == v.stalemate) }, 3) { v.stalemate = it; save(); a.refresh() })
		rc.addView(ui.space(6))
		rc.addView(ui.chips(listOf("Рокировка" to v.castling, "Взятие на проходе" to v.enPassant, "Двойной ход пешки" to v.pawnDouble), 3) {
			when (it) { 0 -> v.castling = !v.castling; 1 -> v.enPassant = !v.enPassant; else -> v.pawnDouble = !v.pawnDouble }
			save(); a.refresh()
		})
		rc.addView(ui.space(6))
		rc.addView(ui.sub("Пешки превращаются на последней горизонтали в:"))
		val promoable = v.pieces.filter { !it.royal && !it.pawn }
		if (promoable.isEmpty()) rc.addView(ui.sub("— нет подходящих фигур"))
		else rc.addView(ui.chips(promoable.map { "${it.symbol} ${it.name}" to (it.name in v.promo) }, 3) { i ->
			val n = promoable[i].name
			if (n in v.promo) v.promo.remove(n) else v.promo.add(n)
			save(); a.refresh()
		})
		if (v.promo.isEmpty()) rc.addView(ui.sub("Ничего не выбрано — пешка на последней горизонтали остаётся пешкой."))
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
	private val v: Variant = VariantStore.list(st).getOrNull(vi) ?: Variant.classic()
	private val d: PieceDef = v.pieces.getOrNull(pi) ?: PieceDef.pawn()
	private var grid: MoveGridView? = null

	private fun save() = VariantStore.put(st, vi, v)

	override fun build(): View {
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
	private fun seat(c: Int, fairyOk: Boolean) = st.str("var_seat_$c", if (c == 0) "H" else "M2").let { if (it.startsWith("F") && !fairyOk) "M2" else it }

	override fun build(): View {
		val v = VariantStore.list(st).getOrNull(index) ?: run { a.pop(); return View(a) }
		val box = ui.col(16, 12)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title(v.name)))
		box.addView(ui.sub("${v.w}×${v.h} · ${winText(v)} · ${Variant.STALEMATE_NAMES[v.stalemate].lowercase()}"))
		val fairy = FairyVariant.plan(v)
		val options = VBots.OPTIONS + if (fairy.plan != null) VBots.FAIRY else emptyList()
		for (c in 0..1) {
			val card = ui.card()
			card.addView(ui.text(if (c == 0) "Белые" else "Чёрные", 17f, bold = true))
			card.addView(ui.space(6))
			card.addView(ui.chips(options.map { VBots.title(it) to (it == seat(c, fairy.plan != null)) }, 3) { st.setStr("var_seat_$c", options[it]); a.refresh() })
			box.addView(card)
		}
		box.addView(ui.sub("Материал — перебор по материалу, Алгоритм — набор правил без перебора; цифра — сила. Fairy-Stockfish — сильный движок, цифра — его Эло."))
		if (fairy.plan == null) box.addView(ui.text("Fairy-Stockfish в этом варианте не играет: ${fairy.reason}.", 13f, 0xFFFFB13B.toInt()))
		box.addView(ui.button("Начать партию") { a.replace(VariantGameScreen(a, v, seat(0, fairy.plan != null), seat(1, fairy.plan != null))) })
		return ui.scroll(box)
	}
}

class VariantGameScreen(a: MainActivity, private val v: Variant, private val white: String, private val black: String) : Screen(a) {
	private val pos = VPos(v).also { it.record() }
	private val seats = listOf(white, black)
	private val fairyPlan = if (seats.any { it.startsWith("F") }) FairyVariant.plan(v).plan else null
	private val bots: List<VBot?> = seats.map { s ->
		if (s.startsWith("F")) fairyPlan?.let { FairyVBot(a, s.drop(1).toIntOrNull() ?: 0, it) { msg -> BotRunner.main.post { a.toast(msg) } } } ?: VMaterial(3)
		else VBots.bot(s)
	}
	private val log = ArrayList<String>()
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

	override fun build(): View {
		val root = ui.col(10, 8)
		root.addView(ui.row(8, ui.button("‹", false) { a.goBack() }, ui.text(v.name, 15f, bold = true)))
		root.addView(bar, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(18)))
		board.v = v
		board.flipped = seats[0] != "H" && seats[1] == "H"
		board.canMove = { result < 0 && seats[pos.turn] == "H" && !thinking }
		board.onMove = { ms -> human(ms) }
		root.addView(board, ui.lp())
		status = ui.text("", 15f, ui.t.text, true, Gravity.CENTER)
		root.addView(status, ui.lp())
		controls = ui.row(8)
		root.addView(controls, ui.lp())
		moves = ui.sub("")
		root.addView(moves, ui.lp())
		redraw()
		maybeBot()
		return ui.scroll(root)
	}

	private fun redraw() {
		board.sq = pos.sq
		board.lastMove = lastMove
		board.legal = if (result < 0) pos.legal() else emptyList()
		board.check = if (result < 0) pos.royals(pos.turn).firstOrNull { pos.attacked(it, 1 - pos.turn) } ?: -1 else -1
		board.clearSel()
		// полоса: материал, а при конце партии — решающий счёт
		bar.cp = when (result) { 0 -> 3000; 1 -> -3000; 2 -> 0; else -> pos.material().coerceIn(-3000, 3000) }
		bar.label = when (result) { 0 -> "1-0"; 1 -> "0-1"; 2 -> "½"; else -> "%+.1f".format(pos.material() / 100f) }
		val side = if (pos.turn == 0) "белые" else "чёрные"
		status.text = when (result) {
			-1 -> (if (thinking) "${side.replaceFirstChar { it.uppercase() }} думают…" else "Ход: $side") +
				(if (board.check >= 0) " · шах!" else "") +
				(if (v.win == Variant.WIN_CHECKS) " · шахи ${pos.checks[0]}:${pos.checks[1]} из ${v.nChecks}" else "")
			2 -> "Ничья · $reason"
			else -> "Победа ${if (result == 0) "белых" else "чёрных"} · $reason"
		}
		moves.text = log.chunked(2).mapIndexed { i, p -> "${i + 1}. ${p.joinToString(" ")}" }.joinToString("   ")
		controls.removeAllViews()
		controls.addView(ui.button("⇅ Перевернуть", false) { board.flipped = !board.flipped; board.invalidate() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val humans = seats.indices.filter { seats[it] == "H" }
		if (result < 0 && humans.size == 1) controls.addView(ui.button("⚑ Сдаться", false) {
			a.dialog("Сдаться?", ui.sub("Партия будет проиграна."), "Сдаться" to { finish(1 - humans[0], "сдача") }, "Играть дальше" to {})
		}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (result >= 0) controls.addView(ui.button("Ещё партия") { a.replace(VariantGameScreen(a, v, white, black)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
	}

	private fun human(ms: List<Int>) {
		if (ms.size == 1) { apply(ms[0]); return }
		// несколько ходов на одну клетку — это превращение: спрашиваем фигуру
		val body = ui.row(8)
		var dlg: android.app.AlertDialog? = null
		for (m in ms) {
			val d = v.pieces[VM.promo(m) - 1]
			val icon = object : View(a) { override fun onDraw(c: Canvas) = VArt.draw(context, c, d, pos.turn, 0f, 0f, minOf(width, height).toFloat()) }
			ui.clickable(icon, ui.t.card2, 12f) { dlg?.dismiss(); apply(m) }
			body.addView(icon, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
		}
		dlg = a.dialog("Превращение пешки", body, "Отмена" to { redraw() })
	}

	private fun apply(m: Int) {
		if (result >= 0 || m !in pos.legal()) return
		val cap = pos.sq[VM.to(m)] != 0 || VM.flags(m) and VM.EP != 0
		log.add(pos.moveName(m))
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
			pos.quiet >= 100 -> "50 ходов без взятий и ходов пешками"
			(pos.history[pos.key()] ?: 0) >= 3 -> "троекратное повторение"
			else -> "пат"
		}
		return when (v.win) {
			Variant.WIN_CAPTURE_ROYAL -> "король взят"
			Variant.WIN_ALL -> "все фигуры взяты"
			Variant.WIN_HILL -> if (pos.royals(r).any { pos.onHill(it) }) "король на холме" else "мат"
			Variant.WIN_CHECKS -> if (pos.checks[r] >= v.nChecks) "${v.nChecks} шахов" else "мат"
			else -> if (pos.legal().isEmpty() && !pos.inCheck(pos.turn)) "пат" else "мат"
		}
	}

	private fun maybeBot() {
		if (result >= 0 || thinking) return
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

	private fun finish(r: Int, why: String) {
		if (result >= 0) return
		result = r; reason = why
		token++; thinking = false
		Sounds.play(a, Sounds.Kind.END)
		redraw()
		val head = when (r) { 2 -> "Ничья"; 0 -> "Победа белых"; else -> "Победа чёрных" }
		a.dialog(head, ui.sub(why.replaceFirstChar { it.uppercase() }, 15f),
			"Посмотреть доску" to {}, "Ещё партия" to { a.replace(VariantGameScreen(a, v, white, black)) }, "В меню" to { a.showTab(0) })
	}

	override fun dispose() { token++; if (fairyPlan != null) Stockfish.fairyCustom.stop() }
}
