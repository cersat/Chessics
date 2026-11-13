package ru.cersat.chessics.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import ru.cersat.chessics.bots.Stockfish
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.data.Puzzles

class PuzzlesScreen(a: MainActivity) : Screen(a) {
	override val tab = 1

	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.title("Задачи"))
		val st = a.store
		box.addView(ui.sub("Решено: ${st.stat("puzzles")} из ${Puzzles.all(a).size}. С первой попытки: ${st.stat("puzzlesClean")}."))
		Puzzles.daily(a)?.let { pz ->
			val solved = st.dailySolvedToday
			box.addView(ui.button(if (solved) "🗓 Задача дня — решена ✓" else "🗓 Задача дня · мат в ${pz.mate} · +5 💎", !solved) { a.push(PuzzleScreen(a, pz, true)) })
		}
		for (mate in 1..3) {
			val list = Puzzles.byMate(a, mate)
			val c = ui.card()
			val done = list.count { st.isSolved(it.id) }
			c.addView(ui.text("Мат в $mate ${if (mate == 1) "ход" else "хода"}", 17f, bold = true))
			c.addView(ui.sub("$done из ${list.size} · награда ${mate + 1} 💎 за решение с первой попытки"))
			c.addView(ui.space(8))
			c.addView(ui.progress(done, list.size))
			c.addView(ui.space(10))
			val next = list.firstOrNull { !st.isSolved(it.id) }
			val r = ui.row(8)
			r.addView(ui.button(if (next != null) "Следующая №${next.number}" else "Все решены ✓", next != null, next != null) {
				next?.let { a.push(PuzzleScreen(a, it, false)) }
			}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			c.addView(r)
			c.addView(ui.space(10))
			// сетка номеров
			val grid = ui.col(0, 6)
			var row: android.widget.LinearLayout? = null
			list.forEachIndexed { i, pz ->
				if (i % 8 == 0) { row = ui.row(6); grid.addView(row, ui.lp()) }
				val s = st.isSolved(pz.id)
				val tv = ui.text("${pz.number}", 12f, if (s) Ui.contrast(ui.t.accent) else ui.t.text, s, Gravity.CENTER)
				tv.setPadding(0, ui.dp(8), 0, ui.dp(8))
				ui.clickable(tv, if (s) ui.t.accent else ui.t.card2, 10f) { a.push(PuzzleScreen(a, pz, false)) }
				row!!.addView(tv, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			}
			val rest = list.size % 8
			if (rest != 0) repeat(8 - rest) { row!!.addView(View(a), ui.lp(0, 1, 1f)) }
			c.addView(grid)
			box.addView(c)
		}
		return ui.scroll(box)
	}
}

class PuzzleScreen(a: MainActivity, private val pz: Puzzles.Puzzle, private val daily: Boolean) : Screen(a) {
	private val st = a.store
	private val pos = Position().also { it.setFen(pz.fen) }
	private val hero = pos.side
	private var step = 0 // индекс в pz.moves
	private var clean = true
	private var solved = false
	private var busy = false
	private val board = BoardView(a)
	private lateinit var status: android.widget.TextView
	private lateinit var controls: android.widget.LinearLayout

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() },
			ui.text((if (daily) "Задача дня · " else "") + "Мат в ${pz.mate} · №${pz.number}", 18f, bold = true)))
		board.whiteBottom = hero == P.WHITE
		board.showCoords = st.flag("coords", true)
		board.showLegal = st.flag("legal", true)
		board.canMove = { c -> c == hero && !solved && !busy }
		board.onMove = { m -> onMove(m) }
		board.setPosition(pos.copy())
		box.addView(board, ui.lp())
		status = ui.text("", 16f, ui.t.text, true, Gravity.CENTER)
		box.addView(status, ui.lp())
		controls = ui.row(8)
		box.addView(controls, ui.lp())
		update(null)
		return ui.scroll(box)
	}

	private fun update(msg: String?) {
		status.text = msg ?: if (solved) "Мат! Задача решена ✓" else "Ход ${if (hero == P.WHITE) "белых" else "чёрных"}: мат в ${pz.mate - step / 2}"
		controls.removeAllViews()
		if (!solved) {
			controls.addView(ui.button("💡 Подсказка", false) {
				clean = false
				board.hintMove = pos.parseUci(pz.moves[step])
				board.invalidate()
				update("Подсказка показана — награда будет 1 💎")
			}, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			controls.addView(ui.button("Заново", false) { restart() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		} else {
			val list = Puzzles.byMate(a, pz.mate)
			val next = list.firstOrNull { !st.isSolved(it.id) && it.id != pz.id }
			if (next != null) controls.addView(ui.button("Следующая") { a.replace(PuzzleScreen(a, next, false)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			controls.addView(ui.button("К задачам", next == null) { a.pop() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
	}

	private fun restart() {
		while (step > 0) { pos.unmake(); step-- }
		board.markSquare = -1
		board.setPosition(pos.copy())
		update(null)
	}

	private fun onMove(m0: Int) {
		var m = m0
		if (m and BoardView.PROMO_ASK != 0) {
			// в задачах превращаем в фигуру из решения, иначе — в ферзя
			val base = m and BoardView.PROMO_ASK.inv()
			val sol = pos.parseUci(pz.moves[step])
			m = if (M.from(sol) == M.from(base) && M.to(sol) == M.to(base)) sol
			else pos.legalMoves().first { M.from(it) == M.from(base) && M.to(it) == M.to(base) && M.promo(it) == P.QUEEN }
		}
		val expected = pos.parseUci(pz.moves[step])
		pos.make(m)
		val mate = pos.inCheck() && !pos.hasLegalMove()
		val lastStep = step == pz.moves.size - 1
		if (m == expected || (lastStep && mate)) {
			step++
			board.markSquare = M.to(m); board.markColor = 0x8834C759.toInt()
			board.setPosition(pos.copy(), m, true)
			if (mate || step >= pz.moves.size) { solved(); return }
			busy = true
			update("Верно! Соперник отвечает…")
			BotRunner.main.postDelayed({
				val reply = pos.parseUci(pz.moves[step])
				pos.make(reply); step++
				board.markSquare = -1
				board.setPosition(pos.copy(), reply, true)
				busy = false
				update(null)
			}, 500)
		} else {
			clean = false
			board.markSquare = M.to(m); board.markColor = 0x88FF3B30.toInt()
			board.setPosition(pos.copy(), m, true)
			busy = true
			update("Не то. Попробуй ещё раз")
			BotRunner.main.postDelayed({
				pos.unmake()
				board.markSquare = -1
				board.setPosition(pos.copy())
				busy = false
				update(null)
			}, 700)
		}
	}

	private fun solved() {
		solved = true
		val r = st.puzzleSolved(pz.id, pz.mate, clean, daily && Puzzles.daily(a)?.id == pz.id)
		update(null)
		val body = ui.col(0, 6)
		r.lines.forEach { body.addView(ui.text(it, 14f)) }
		if (r.diamonds > 0) body.addView(ui.text("Всего: +${r.diamonds} 💎", 16f, ui.t.accent, true))
		a.dialog(if (clean) "Чисто решено! 🎯" else "Решено ✓", body, "Отлично" to {})
	}
}

// Разбор партии Stockfish: оценка каждой позиции, ошибки, точность
class ReviewScreen(a: MainActivity, private val startFen: String, private val uci: String,
	private val wName: String, private val bName: String, private val resultLine: String) : Screen(a) {

	private val game = ru.cersat.chessics.chess.GameRecord.fromUci(startFen, uci)
	private val evals = ArrayList<Int>() // cp с точки зрения белых для позиций 0..n
	private val bests = ArrayList<String>()
	private var ply = 0
	private var done = false
	private var cancelled = false
	private val board = BoardView(a)
	private val graph = EvalGraph(a)
	private lateinit var info: android.widget.TextView
	private lateinit var list: android.widget.LinearLayout

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.text("Анализ · $wName — $bName", 16f, bold = true)))
		if (resultLine.isNotEmpty()) box.addView(ui.sub(resultLine))
		board.canMove = { false }
		board.showCoords = a.store.flag("coords", true)
		box.addView(board, ui.lp())
		graph.onPick = { ply = it; show() }
		box.addView(graph, ui.lp(h = ui.dp(70)))
		info = ui.text("", 14f, ui.t.text)
		box.addView(info, ui.lp())
		val nav = ui.row(8)
		nav.addView(ui.button("⏮", false) { ply = 0; show() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("◀", false) { if (ply > 0) ply--; show() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("▶", false) { if (ply < game.moves.size) ply++; show() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("⏭", false) { ply = game.moves.size; show() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("PGN", false) { share() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		box.addView(nav, ui.lp())
		list = ui.col(0, 4)
		box.addView(list, ui.lp())
		show()
		if (evals.isEmpty()) analyse()
		return ui.scroll(box)
	}

	private fun share() {
		val pgn = game.pgn(wName, bName, when {
			resultLine.startsWith("Победа белых") -> "1-0"
			resultLine.startsWith("Победа чёрных") -> "0-1"
			resultLine.startsWith("Ничья") -> "1/2-1/2"
			else -> "*"
		}, java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).format(java.util.Date()))
		val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, pgn)
		a.startActivity(android.content.Intent.createChooser(i, "Поделиться PGN"))
	}

	private fun analyse() {
		val n = game.moves.size
		val moves = game.moves.map { M.uci(it) }
		BotRunner.exec.execute {
			val res = ArrayList<Int>(); val best = ArrayList<String>()
			var ok = true
			for (i in 0..n) {
				if (cancelled) return@execute
				val p = game.positionAt(i)
				val st = ru.cersat.chessics.chess.Rules.status(p)
				if (st.outcome != ru.cersat.chessics.chess.Outcome.NONE) {
					res.add(when (st.outcome) { ru.cersat.chessics.chess.Outcome.WHITE_WINS -> 10000; ru.cersat.chessics.chess.Outcome.BLACK_WINS -> -10000; else -> 0 })
					best.add("")
				} else {
					val an = Stockfish.analyse(a, startFen, moves.take(i).joinToString(" "), p.side == P.WHITE, 250)
					if (an == null) { ok = false; break }
					res.add(if (an.mate != 0) (if (an.mate > 0) 10000 - an.mate * 10 else -10000 - an.mate * 10) else an.cp)
					best.add(an.best)
				}
				val prog = i
				BotRunner.main.post { if (!cancelled) info.text = "Stockfish анализирует… $prog / $n" }
			}
			BotRunner.main.post {
				if (cancelled) return@post
				if (!ok) { info.text = "Анализ недоступен: ${Stockfish.lastError ?: "Stockfish не ответил"}"; return@post }
				evals.clear(); evals.addAll(res); bests.clear(); bests.addAll(best)
				done = true
				show()
			}
		}
	}

	private fun winPct(cp: Int) = 50 + 50 * (2 / (1 + Math.exp(-0.00368208 * cp.coerceIn(-1500, 1500))) - 1)

	// Метка хода i (0-based): потеря шансов для сходившего
	private fun loss(i: Int): Double {
		val whiteMoved = game.positionAt(i).side == P.WHITE
		val before = winPct(evals[i]); val after = winPct(evals[i + 1])
		return if (whiteMoved) before - after else after - before
	}

	private fun mark(l: Double) = when { l >= 30 -> "??"; l >= 20 -> "?"; l >= 10 -> "?!"; else -> "" }

	private fun show() {
		val p = game.positionAt(ply)
		board.setPosition(p, if (ply > 0) game.moves[ply - 1] else 0)
		if (done && ply < bests.size && bests[ply].isNotEmpty()) { board.hintMove = p.parseUci(bests[ply]); board.invalidate() }
		graph.values = evals
		graph.cursor = if (done) ply else -1
		if (!done) { if (evals.isEmpty() && !info.text.startsWith("Анализ")) info.text = "Stockfish анализирует…"; buildList(); return }
		val e = evals[ply]
		val evalTxt = if (abs(e) >= 9000) "мат" else String.format(java.util.Locale.US, "%+.2f", e / 100.0)
		var t = "Оценка: $evalTxt"
		if (ply > 0) {
			val l = loss(ply - 1)
			val mk = mark(l)
			t += " · ход ${game.sanRu[ply - 1]}$mk" + when (mk) { "??" -> " — зевок"; "?" -> " — ошибка"; "?!" -> " — неточность"; else -> "" }
		}
		if (ply < bests.size && bests[ply].isNotEmpty()) {
			val bm = p.parseUci(bests[ply])
			if (bm != 0) t += "\nЛучший ход здесь: ${ru.cersat.chessics.chess.San.of(p, bm, true)} (синяя стрелка)"
		}
		info.text = t
		buildList()
	}

	private fun abs(x: Int) = if (x < 0) -x else x

	private fun buildList() {
		list.removeAllViews()
		if (!done) return
		val acc = DoubleArray(2); val cnt = IntArray(2)
		val counts = Array(2) { IntArray(3) }
		for (i in game.moves.indices) {
			val side = game.positionAt(i).side
			val l = maxOf(0.0, loss(i))
			acc[side] += (103.1668 * Math.exp(-0.04354 * l) - 3.1669).coerceIn(0.0, 100.0)
			cnt[side]++
			when (mark(l)) { "??" -> counts[side][0]++; "?" -> counts[side][1]++; "?!" -> counts[side][2]++ }
		}
		for (side in 0..1) {
			val name = if (side == 0) "Белые ($wName)" else "Чёрные ($bName)"
			val a1 = if (cnt[side] > 0) acc[side] / cnt[side] else 0.0
			list.addView(ui.text(String.format(java.util.Locale.US, "%s: точность %.0f%% · зевков %d · ошибок %d · неточностей %d",
				name, a1, counts[side][0], counts[side][1], counts[side][2]), 13f, ui.t.sub))
		}
		// ходы с пометками
		val r = ui.col(0, 2)
		var line = StringBuilder()
		val pos0 = Position().also { it.setFen(startFen) }
		var num = pos0.full
		var white = pos0.side == P.WHITE
		for (i in game.moves.indices) {
			if (white) line.append("$num. ") else if (i == 0) line.append("$num… ")
			line.append(game.sanRu[i]).append(mark(maxOf(0.0, loss(i)))).append("  ")
			if (!white) { r.addView(ui.text(line.toString(), 13f)); line = StringBuilder(); num++ }
			white = !white
		}
		if (line.isNotEmpty()) r.addView(ui.text(line.toString(), 13f))
		list.addView(r)
	}

	override fun dispose() { cancelled = true }
}
