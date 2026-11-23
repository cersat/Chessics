package ru.cersat.chessics.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import ru.cersat.chessics.bots.Bot4
import ru.cersat.chessics.bots.Material4Bot
import ru.cersat.chessics.bots.Random4
import ru.cersat.chessics.chess.F4
import ru.cersat.chessics.chess.M4
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Pos4
import ru.cersat.chessics.data.Shop
import ru.cersat.chessics.data.Sounds

// Место за доской на четверых: "H" — человек, "M3" — Материал глубины 3, "R" — случайный
object Seats {
	fun title(s: String) = when {
		s == "H" -> "Человек"
		s == "R" -> "Случайный"
		s.startsWith("M") -> "Материал ${s.drop(1)}"
		s.startsWith("A") -> "Алгоритм ${s.drop(1)}"
		else -> s
	}

	fun bot(s: String): Bot4? = when {
		s == "R" -> Random4
		s.startsWith("M") -> Material4Bot(s.drop(1).toIntOrNull() ?: 2)
		s.startsWith("A") -> ru.cersat.chessics.bots.Algo4Bot(s.drop(1).toIntOrNull() ?: 3)
		else -> null
	}

	val OPTIONS = listOf("H", "M1", "M2", "M3", "M4", "A1", "A2", "A3", "A4", "A5", "R")
	val COLORS = intArrayOf(0xFFD9453B.toInt(), 0xFF3D7BD9.toInt(), 0xFFE0B42A.toInt(), 0xFF3FA65A.toInt())
}

object Four4Art {
	private val cache = HashMap<String, Bitmap>()
	private const val CODES = " pnbrqk"

	fun get(ctx: Context, pc: Int): Bitmap? {
		if (pc == 0) return null
		val name = "pc_four_${F4.CODES[F4.owner(pc)]}${CODES[F4.type(pc)]}"
		cache[name]?.let { return it }
		val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
		if (id == 0) return null
		val b = BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
		cache[name] = b
		return b
	}
}

class Board4View(ctx: Context) : View(ctx) {
	var pos = Pos4()
	var seatDown = F4.RED // чья сторона внизу
	var lastMove = 0
	var canMove: () -> Boolean = { false }
	var onMove: (Int) -> Unit = {}
	private var selected = -1
	private var legal = IntArray(0)
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val rect = RectF()
	private val board = Shop.board(ctx)

	fun refresh() { selected = -1; legal = IntArray(0); invalidate() }

	override fun onMeasure(w: Int, h: Int) {
		val s = MeasureSpec.getSize(w)
		setMeasuredDimension(s, s)
	}

	private val cell get() = width / 14f

	// общая клетка → экранные (колонка, ряд) с учётом того, кто внизу
	private fun screen(s: Int): Pair<Int, Int> {
		val gx = F4.x(s); val gy = F4.y(s)
		val (lx, ly) = when (seatDown) {
			F4.RED -> gx to gy
			F4.BLUE -> (13 - gy) to gx
			F4.YELLOW -> (13 - gx) to (13 - gy)
			else -> gy to (13 - gx)
		}
		return lx to (13 - ly)
	}

	private fun cellAt(px: Float, py: Float): Int {
		val col = (px / cell).toInt(); val row = (py / cell).toInt()
		if (col !in 0..13 || row !in 0..13) return -1
		val lx = col; val ly = 13 - row
		val s = F4.toGlobal(seatDown, lx, ly)
		return if (F4.valid(F4.x(s), F4.y(s))) s else -1
	}

	override fun onDraw(c: Canvas) {
		val s = cell
		for (sq in pos.sq.indices) {
			if (!F4.valid(F4.x(sq), F4.y(sq))) continue
			val (col, row) = screen(sq)
			val light = (F4.x(sq) + F4.y(sq)) % 2 == 1
			paint.color = if (light) board.light else board.dark
			c.drawRect(col * s, row * s, (col + 1) * s, (row + 1) * s, paint)
			if (lastMove != 0 && (sq == M4.from(lastMove) || sq == M4.to(lastMove))) { paint.color = board.last; c.drawRect(col * s, row * s, (col + 1) * s, (row + 1) * s, paint) }
			if (sq == selected) { paint.color = board.select; c.drawRect(col * s, row * s, (col + 1) * s, (row + 1) * s, paint) }
		}
		// шах: подсветка короля того, кто ходит
		if (pos.inCheck()) {
			val (col, row) = screen(pos.king[pos.turn])
			paint.color = 0x88FF2020.toInt()
			c.drawCircle(col * s + s / 2, row * s + s / 2, s * 0.55f, paint)
		}
		for (sq in pos.sq.indices) {
			val pc = pos.sq[sq]
			if (pc == 0) continue
			val b = Four4Art.get(context, pc) ?: continue
			val (col, row) = screen(sq)
			rect.set(col * s, row * s, (col + 1) * s, (row + 1) * s)
			c.drawBitmap(b, null, rect, paint)
		}
		if (selected >= 0) {
			paint.color = 0x55000000
			for (m in legal) {
				if (M4.from(m) != selected) continue
				val (col, row) = screen(M4.to(m))
				c.drawCircle(col * s + s / 2, row * s + s / 2, s * (if (pos.sq[M4.to(m)] != 0) 0.4f else 0.16f), paint)
			}
		}
	}

	override fun onTouchEvent(e: MotionEvent): Boolean {
		if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
		if (!canMove()) return true
		val sq = cellAt(e.x, e.y)
		if (sq < 0) { refresh(); return true }
		val pc = pos.sq[sq]
		if (selected >= 0) {
			val cands = legal.filter { M4.from(it) == selected && M4.to(it) == sq }
			if (cands.isNotEmpty()) {
				selected = -1
				onMove(if (cands.size > 1) (cands[0] or ASK_PROMO) else cands[0])
				invalidate()
				return true
			}
		}
		if (pc != 0 && F4.owner(pc) == pos.turn) { selected = sq; legal = pos.legal() } else selected = -1
		invalidate()
		return true
	}

	companion object { const val ASK_PROMO = 1 shl 30 }
}

class FourSetupScreen(a: MainActivity, private val widgetId: Int = -1) : Screen(a) {
	private val st = a.store

	private fun seat(i: Int) = st.str("four_seat_$i", if (i == 0) "H" else "M2")

	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Шахматы на четверых")))
		val rules = ui.card()
		rules.addView(ui.text("Правила (командные, по мотивам chess.com)", 15f, bold = true))
		rules.addView(ui.sub("Красные и жёлтые против синих и зелёных — партнёр сидит напротив, его фигуры брать нельзя. Ходят по кругу: красные → синие → жёлтые → зелёные. " +
			"Пешки превращаются на своей 8-й горизонтали (в центре доски). Мат засчитывается, когда наступает ход заматованного, — и его команда проигрывает. Пат — ничья. " +
			"Рокировка есть, взятия на проходе нет. Ничья также при троекратном повторении и после 200 полуходов без взятий и ходов пешками.", 12f))
		box.addView(rules)
		for (i in 0..3) {
			val c = ui.card()
			val head = ui.row(8)
			head.addView(View(a).apply { background = ui.round(Seats.COLORS[i], 8f) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
			head.addView(ui.text(F4.NAMES[i] + if (F4.team(i) == 0) " · команда 1" else " · команда 2", 16f, bold = true))
			c.addView(head)
			c.addView(ui.space(8))
			c.addView(ui.chips(Seats.OPTIONS.map { Seats.title(it) to (it == seat(i)) }, 3) { st.setStr("four_seat_$i", Seats.OPTIONS[it]); a.refresh() })
			box.addView(c)
		}
		box.addView(ui.sub("Материал — перебор по материалу, Алгоритм — набор правил без перебора (цифра — сила): не ставит фигуры под бой, бьёт висящее, ставит мат и шахи, развивается к центру."))
		box.addView(ui.sub("Победы засчитываются (и идут в «Радугу»), когда люди играют за одну команду, а в другой есть хотя бы один бот."))
		val seats = (0..3).map { seat(it) }
		if (widgetId >= 0) box.addView(ui.button("📌 Поставить в виджет") {
			WidgetSetup.save(a, widgetId, JSONObject().put("type", "four").put("seats", JSONArray(seats)),
				"На четверых", seats.mapIndexed { i, s -> "${F4.NAMES[i].take(3)}: ${Seats.title(s)}" }.joinToString(" · "))
		})
		else box.addView(ui.button("Начать партию") { a.replace(FourGameScreen(a, seats)) })
		return ui.scroll(box)
	}
}

class FourGameScreen(a: MainActivity, private val seats: List<String>) : Screen(a) {
	private val st = a.store
	private val pos = Pos4()
	private val moves = ArrayList<Int>()
	private var result = -1
	private var thinking = false
	private var token = 0
	private val bots = seats.map { Seats.bot(it) }
	private val humanTeams = seats.indices.filter { seats[it] == "H" }.map { F4.team(it) }.toSet()
	private var manualBottom = -1
	private val board = Board4View(a)
	private lateinit var status: TextView
	private lateinit var controls: LinearLayout
	private lateinit var bars: LinearLayout

	private fun bottomSeat(): Int {
		if (manualBottom >= 0) return manualBottom
		val humans = seats.indices.filter { seats[it] == "H" }
		return when {
			humans.size == 1 -> humans[0]
			humans.isNotEmpty() && seats[pos.turn] == "H" -> pos.turn // передают телефон — доска к ходящему
			humans.isNotEmpty() -> humans[0]
			else -> F4.RED
		}
	}

	override fun build(): View {
		val root = ui.col(10, 8)
		root.addView(ui.row(8, ui.button("‹", false) { a.goBack() }, ui.text("Шахматы на четверых", 15f, bold = true)))
		bars = ui.row(6)
		root.addView(bars, ui.lp())
		board.canMove = { result < 0 && seats[pos.turn] == "H" && !thinking }
		board.onMove = { m -> onHuman(m) }
		root.addView(board.detached(), ui.lp())
		status = ui.text("", 15f, ui.t.text, true, Gravity.CENTER)
		root.addView(status, ui.lp())
		controls = ui.row(8)
		root.addView(controls, ui.lp())
		redraw()
		maybeBot()
		return ui.scroll(root)
	}

	private fun redraw() {
		board.pos = pos
		board.seatDown = bottomSeat()
		board.lastMove = moves.lastOrNull() ?: 0
		board.refresh()
		bars.removeAllViews()
		for (i in 0..3) {
			val on = result < 0 && pos.turn == i
			val t = ui.text("${F4.NAMES[i].take(3)}\n${Seats.title(seats[i])}", 11f, if (on) 0xFFFFFFFF.toInt() else ui.t.text, on, Gravity.CENTER)
			t.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
			t.background = ui.round(if (on) Seats.COLORS[i] else ui.t.card, 10f, 2, Seats.COLORS[i])
			bars.addView(t, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
		status.text = when (result) {
			-1 -> (if (thinking) "${F4.NAMES[pos.turn]} думают…" else "Ход: ${F4.NAMES[pos.turn]}") + if (pos.inCheck()) " · шах!" else ""
			2 -> "Ничья"
			else -> "Победила команда ${if (result == 0) "красных и жёлтых" else "синих и зелёных"}"
		}
		controls.removeAllViews()
		controls.addView(ui.button("⟳ Повернуть", false) { manualBottom = (board.seatDown + 1) % 4; redraw() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (result < 0 && humanTeams.size == 1) controls.addView(ui.button("⚑ Сдаться", false) {
			a.dialog("Сдаться?", ui.sub("Команда сдаётся целиком."), "Сдаться" to { finish(1 - humanTeams.first(), "сдача") }, "Играть дальше" to {})
		}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		if (result >= 0) controls.addView(ui.button("Ещё партия") { a.replace(FourGameScreen(a, seats)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
	}

	private fun onHuman(m0: Int) {
		if (m0 and Board4View.ASK_PROMO != 0) {
			val base = m0 and Board4View.ASK_PROMO.inv()
			val r = ui.row(8)
			var dlg: android.app.AlertDialog? = null
			for (t in intArrayOf(P.QUEEN, P.ROOK, P.BISHOP, P.KNIGHT)) {
				val iv = ImageView(a)
				iv.setImageBitmap(Four4Art.get(a, F4.make(pos.turn, t)))
				ui.clickable(iv, ui.t.card2, 12f) {
					dlg?.dismiss()
					pos.legal().firstOrNull { M4.from(it) == M4.from(base) && M4.to(it) == M4.to(base) && M4.promo(it) == t }?.let { apply(it) }
				}
				r.addView(iv, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
			}
			dlg = a.dialog("Превращение пешки", r, "Отмена" to { redraw() })
			return
		}
		apply(m0)
	}

	private fun apply(m: Int) {
		if (result >= 0 || pos.legal().none { it == m }) return
		val cap = pos.sq[M4.to(m)] != 0
		pos.make(m); pos.record(); moves.add(m)
		Sounds.play(a, Sounds.forMove(cap, M4.castle(m) != 0, pos.inCheck()))
		val r = pos.result()
		if (r >= 0) { finish(r, if (r == 2) "" else "мат"); return }
		redraw()
		maybeBot()
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
			val m = bot.pick(p) { token != my }
			val wait = maxOf(0L, 300 - (System.currentTimeMillis() - t0))
			BotRunner.main.postDelayed({
				if (token != my) return@postDelayed
				thinking = false
				if (m != 0) apply(m) else redraw()
			}, wait)
		}
	}

	private fun finish(r: Int, reason: String) {
		if (result >= 0) return
		result = r
		token++
		thinking = false
		Sounds.play(a, Sounds.Kind.END)
		redraw()
		val body = ui.col(0, 6)
		if (reason.isNotEmpty()) body.addView(ui.sub(reason.replaceFirstChar { it.uppercase() }, 15f))
		// награды: люди только в одной команде, а в другой есть бот
		val counted = humanTeams.size == 1 && seats.indices.any { F4.team(it) != humanTeams.first() && seats[it] != "H" }
		if (counted) {
			val rw = st.fourFinished(r == humanTeams.first(), r == 2, moves.size)
			rw.lines.forEach { body.addView(ui.text(it, 14f)) }
			if (rw.diamonds > 0) body.addView(ui.text("Всего: +${rw.diamonds} 💎", 16f, ui.t.accent, true))
		}
		val head = when {
			r == 2 -> "Ничья"
			humanTeams.size == 1 -> if (r == humanTeams.first()) "Победа команды! 🎉" else "Поражение"
			else -> "Победа: ${if (r == 0) "красные и жёлтые" else "синие и зелёные"}"
		}
		a.dialog(head, body, "Посмотреть доску" to {}, "Ещё партия" to { a.replace(FourGameScreen(a, seats)) }, "В меню" to { a.showTab(0) })
	}

	override fun dispose() { token++ }
}
