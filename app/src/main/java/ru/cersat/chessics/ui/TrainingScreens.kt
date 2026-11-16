package ru.cersat.chessics.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ru.cersat.chessics.bots.Player
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.San
import ru.cersat.chessics.data.Endgames
import ru.cersat.chessics.data.Openings
import ru.cersat.chessics.data.Sounds

class TrainingScreen(a: MainActivity) : Screen(a) {
	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Тренировка")))
		val st = a.store
		box.addView(card("📖", "Дебюты", "Выучи линию ход за ходом: ${Openings.all(a).size} дебютов и вариантов из базы lichess. Выучено: ${st.stat("openings")}") { a.push(OpeningsScreen(a)) })
		box.addView(card("🎓", "Эндшпиль", "Дожми или удержи против полной силы Stockfish. Пройдено ${Endgames.ALL.count { st.endgameDone(it.id) }} из ${Endgames.ALL.size}") { a.push(EndgamesScreen(a)) })
		return ui.scroll(box)
	}

	private fun card(icon: String, title: String, desc: String, f: () -> Unit): View {
		val r = ui.row(14)
		r.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
		r.addView(ui.text(icon, 30f))
		r.addView(ui.col(0, 2, ui.text(title, 17f, bold = true), ui.sub(desc)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		r.addView(ui.text("›", 26f, ui.t.sub))
		ui.clickable(r, ui.t.card, 18f, f)
		return r
	}
}

class EndgamesScreen(a: MainActivity) : Screen(a) {
	override fun build(): View {
		val box = ui.col(16, 10)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Эндшпиль")))
		box.addView(ui.sub("Соперник — Stockfish на полной силе. Выигрыш — поставь мат (успей до правила 50 ходов). Защита — добейся ничьей или продержись 40 ходов."))
		for (e in Endgames.ALL) {
			val done = a.store.endgameDone(e.id)
			val r = ui.row(10)
			r.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
			r.addView(ui.text(if (done) "✅" else if (e.win) "⚔" else "🛡", 22f))
			r.addView(ui.col(0, 2, ui.text(e.title, 15f, bold = true), ui.sub(e.desc, 12f)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			r.addView(ui.text("+${e.reward} 💎", 13f, if (done) ui.t.sub else ui.t.accent, true))
			ui.clickable(r, ui.t.card, 16f) { start(e) }
			box.addView(r)
		}
		return ui.scroll(box)
	}

	private fun start(e: Endgames.Endgame) {
		val p = Position.of(e.fen)
		val sf = Player(Player.Kind.STOCKFISH, 0)
		val cfg = if (p.side == P.WHITE) GameConfig(Player.HUMAN, sf, startFen = e.fen, training = e.id)
		else GameConfig(sf, Player.HUMAN, startFen = e.fen, training = e.id)
		a.push(GameScreen(a, cfg, null))
	}
}

class OpeningsScreen(a: MainActivity) : Screen(a) {
	private var query = ""
	private var openFamily = ""
	private lateinit var list: LinearLayout

	override fun build(): View {
		val box = ui.col(16, 10)
		box.addView(ui.row(10, ui.button("‹", false) { a.pop() }, ui.title("Дебюты")))
		box.addView(ui.sub("Названия — из базы lichess (на английском). Выбери линию и цвет: соперник ходит по теории, ты повторяешь ходы своей стороны."))
		val search = EditText(a).apply {
			hint = "поиск: Sicilian, Italian, B20…"; setTextColor(ui.t.text); setHintTextColor(ui.t.sub); setSingleLine()
			background = ui.round(ui.t.card2, 12f); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
			setText(query)
			addTextChangedListener(object : TextWatcher {
				override fun afterTextChanged(e: Editable?) { query = e.toString(); fill() }
				override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
				override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
			})
		}
		box.addView(search, ui.lp())
		list = ui.col(0, 8)
		box.addView(list, ui.lp())
		fill()
		return ui.scroll(box)
	}

	private fun fill() {
		list.removeAllViews()
		val q = query.trim().lowercase()
		val st = a.store
		if (q.isNotEmpty()) {
			val found = Openings.all(a).filter { it.name.lowercase().contains(q) || it.eco.lowercase() == q }.take(60)
			if (found.isEmpty()) list.addView(ui.sub("Ничего не нашлось"))
			for (o in found) list.addView(line(o))
			return
		}
		for ((fam, lines) in Openings.families(a)) {
			val done = lines.count { o -> (0..1).any { st.openingDone(o.doneKey(it)) } }
			val isOpen = fam == openFamily
			val head = ui.row(8)
			head.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
			head.addView(ui.col(0, 2, ui.text(fam, 15f, bold = true), ui.sub("${lines.size} линий" + if (done > 0) " · выучено $done" else "", 12f)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			head.addView(ui.text(if (isOpen) "▴" else "▾", 20f, ui.t.sub))
			ui.clickable(head, if (isOpen) ui.t.card2 else ui.t.card, 14f) { openFamily = if (isOpen) "" else fam; fill() }
			list.addView(head)
			if (isOpen) for (o in lines.sortedBy { it.moves.size }) list.addView(line(o))
		}
	}

	private fun line(o: Openings.Opening): View {
		val st = a.store
		val done = (0..1).any { st.openingDone(o.doneKey(it)) }
		val r = ui.col(0, 6)
		r.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
		r.addView(ui.text((if (done) "✅ " else "") + o.name, 14f, bold = true))
		r.addView(ui.sub(if (o.isSystem) "${o.eco} · система: ${o.sysWhite?.size ?: o.sysBlack?.size} ходов своей стороны, соперник играет что угодно"
			else "${o.eco} · ${(o.moves.size + 1) / 2} ход.", 12f))
		val b = ui.row(8)
		for (c in 0..1) {
			if (o.isSystem && o.system(c) == null) continue
			b.addView(ui.button((if (st.openingDone(o.doneKey(c))) "✓ " else "") + if (c == P.WHITE) "За белых" else "За чёрных", false) {
				a.push(OpeningTrainerScreen(a, o, c))
			}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
		r.addView(b)
		r.background = ui.round(ui.t.card, 14f)
		return r
	}
}

// Тренажёр линии: соперник ходит по линии сам, ты должен повторить ходы своего цвета
class OpeningTrainerScreen(a: MainActivity, private val o: Openings.Opening, private val color: Int) : Screen(a) {
	private val pos = Position.start()
	private var step = 0
	private var mistakes = 0
	private var done = false
	private var busy = false
	private val board = BoardView(a)
	private lateinit var status: TextView
	private lateinit var movesText: TextView
	private lateinit var controls: LinearLayout
	private val sans = ArrayList<String>()
	private val sys = o.system(color) // для системы — только свои ходы
	private var mine = 0 // сколько своих ходов системы уже сделано
	private val bot = ru.cersat.chessics.bots.MaterialBot(3)
	private fun expectedMove(): Int = if (sys != null) (if (mine < sys.size) pos.parseUci(sys[mine]) else 0) else pos.parseUci(o.moves[step])
	private fun lineOver() = if (sys != null) mine >= sys.size else step >= o.moves.size

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.col(0, 0, ui.text(o.name, 16f, bold = true), ui.sub("${o.eco} · за ${if (color == P.WHITE) "белых" else "чёрных"}", 12f))))
		board.whiteBottom = color == P.WHITE
		board.showCoords = a.store.flag("coords", true)
		board.showLegal = a.store.flag("legal", true)
		board.canMove = { c -> c == color && !done && !busy && pos.side == color }
		board.onMove = { m -> onMove(m) }
		board.setPosition(pos.copy())
		box.addView(board, ui.lp())
		status = ui.text("", 16f, ui.t.text, true, Gravity.CENTER)
		box.addView(status, ui.lp())
		movesText = ui.text("", 14f, ui.t.sub)
		box.addView(movesText, ui.lp())
		controls = ui.row(8)
		box.addView(controls, ui.lp())
		update(null)
		BotRunner.main.postDelayed({ autoPlay() }, 400)
		return ui.scroll(box)
	}

	private fun update(msg: String?) {
		status.text = msg ?: when {
			done -> "Линия пройдена ✓" + if (mistakes > 0) " (ошибок: $mistakes)" else ""
			pos.side == color -> "Твой ход по теории"
			else -> "Ход соперника…"
		}
		movesText.text = sans.joinToString(" ")
		controls.removeAllViews()
		if (!done) controls.addView(ui.button("💡 Подсказать ход", false) {
			mistakes++
			board.hintMove = expectedMove(); board.invalidate()
		}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		controls.addView(ui.button("Заново", false) { restart() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
	}

	private fun restart() {
		while (step > 0) { pos.unmake(); step-- }
		sans.clear(); done = false; mistakes = 0; busy = false; mine = 0; blocked = false; gen++
		board.markSquare = -1
		board.setPosition(pos.copy())
		update(null)
		BotRunner.main.postDelayed({ autoPlay() }, 300)
	}

	private fun play(m: Int) {
		val cap = pos.capturedBy(m) != 0
		sans.add(San.of(pos, m, !a.store.flag("sanEn", false)))
		pos.make(m); step++
		Sounds.play(a, Sounds.forMove(cap, M.isCastle(m), pos.inCheck()))
		board.setPosition(pos.copy(), m, true)
	}

	private fun autoPlay() {
		if (done) return
		if (lineOver()) { finished(); return }
		if (pos.side != color) {
			// в системе соперник ходит как бот «Материал 3» (считается в фоне), в обычной линии — по теории
			if (sys != null) {
				busy = true
				update("Соперник думает…")
				val p = pos.copy(); val ply = step; val g = gen
				BotRunner.exec.execute {
					val m = bot.pick(p, ply) { g != gen }
					BotRunner.main.post {
						if (g != gen) return@post
						busy = false
						if (m == 0) { finished(); return@post }
						play(m)
						if (lineOver()) { finished(); return@post }
						if (expectedMove() == 0) { blocked = true; finished(); return@post }
						update(null)
					}
				}
				return
			}
			val m = pos.parseUci(o.moves[step])
			if (m == 0) { finished(); return }
			play(m)
			if (lineOver()) { finished(); return }
		}
		// соперник помешал системе — её ход стал невозможен
		if (sys != null && expectedMove() == 0) { blocked = true; finished(); return }
		update(null)
	}

	private var blocked = false
	private var gen = 0 // при «Заново» ответ бота от старой попытки выбрасывается

	private fun onMove(m0: Int) {
		var m = m0
		if (m and BoardView.PROMO_ASK != 0) {
			val base = m and BoardView.PROMO_ASK.inv()
			m = pos.legalMoves().first { M.from(it) == M.from(base) && M.to(it) == M.to(base) && M.promo(it) == P.QUEEN }
		}
		val expected = expectedMove()
		if (m == expected) {
			board.markSquare = -1
			play(m)
			mine++
			if (lineOver()) { finished(); return }
			busy = true
			update(null)
			BotRunner.main.postDelayed({ busy = false; autoPlay() }, 450)
		} else {
			mistakes++
			Sounds.play(a, Sounds.Kind.BAD)
			board.markSquare = M.to(m); board.markColor = 0x88FF3B30.toInt()
			board.setPosition(pos.copy())
			update(if (sys != null) "Не по системе. Здесь по плану другой ход" else "Не по теории. По линии здесь другой ход")
		}
	}

	private fun finished() {
		done = true
		Sounds.play(a, Sounds.Kind.GOOD)
		update(null)
		if (blocked) a.toast("Соперник помешал закончить систему — засчитано то, что успел")
		if (mistakes == 0) {
			val r = a.store.openingFinished(o.doneKey(color))
			val body = ui.col(0, 6)
			r.lines.forEach { body.addView(ui.text(it, 14f)) }
			a.dialog("Без ошибок! 📖", body.apply { if (childCount == 0) addView(ui.sub("Линия уже была выучена")) }, "Отлично" to {})
		} else a.dialog("Линия пройдена", ui.sub("Ошибок: $mistakes. Пройди её без ошибок и подсказок, чтобы засчитать."), "Ещё раз" to { restart() }, "Закрыть" to {})
	}
}
