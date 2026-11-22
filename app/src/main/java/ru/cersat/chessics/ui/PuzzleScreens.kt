package ru.cersat.chessics.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import ru.cersat.chessics.bots.Analyzer
import ru.cersat.chessics.bots.MoveClass
import ru.cersat.chessics.bots.Stockfish
import ru.cersat.chessics.bots.Tactics
import ru.cersat.chessics.chess.GameRecord
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.San
import ru.cersat.chessics.data.Puzzles

class PuzzlesScreen(a: MainActivity) : Screen(a) {
	override val tab = 1
	private val st = a.store

	override fun build(): View {
		val box = ui.col(16, 14)
		box.addView(ui.title("Задачи"))
		val all = Puzzles.all(a)
		box.addView(ui.sub("Решено: ${st.stat("puzzles")} из ${all.size}. С первой попытки: ${st.stat("puzzlesClean")}."))
		val rc = ui.card()
		rc.addView(ui.row(10, ui.col(0, 2, ui.text("Рейтинг ${st.puzzleRating}", 20f, ui.t.accent, true),
			ui.sub("Меняется после первой попытки каждой задачи с рейтингом", 12f)).apply { layoutParams = ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }))
		rc.addView(ui.space(10))
		val rr = ui.row(8)
		rr.addView(ui.button("🎯 Задача по рейтингу") { suitable()?.let { a.push(PuzzleScreen(a, it, false)) } ?: a.toast("Все задачи решены!") }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		rr.addView(ui.button("⚡ Штурм", false) { a.push(StormScreen(a)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		rc.addView(rr)
		rc.addView(ui.space(6))
		rc.addView(ui.sub("Штурм: 3 минуты, задачи всё сложнее, ошибка — минус 10 секунд. Рекорд: ${st.stormBest}", 12f))
		box.addView(rc)
		val due = st.repeatIds().mapNotNull { Puzzles.byId(a, it) }
		val total = st.repeatCount()
		if (total > 0) {
			val c = ui.card()
			c.addView(ui.text("🔁 Повторение ошибок", 17f, bold = true))
			c.addView(ui.sub(if (due.isNotEmpty()) "На сегодня: ${due.size}. Всего в очереди: $total. Задачи возвращаются через 1, 3, 7 и 14 дней, пока не решишь чисто."
				else "На сегодня всё повторено. В очереди: $total"))
			if (due.isNotEmpty()) { c.addView(ui.space(10)); c.addView(ui.button("Повторить (${due.size})") { a.push(PuzzleScreen(a, due.first(), false)) }) }
			box.addView(c)
		}
		Puzzles.daily(a)?.let { pz ->
			val solved = st.dailySolvedToday
			box.addView(ui.button(if (solved) "🗓 Задача дня — решена ✓" else "🗓 Задача дня · ${pz.goal} · +2 💎", !solved) { a.push(PuzzleScreen(a, pz, true)) })
		}
		box.addView(generatorCard())
		val gen = Puzzles.generated(a)
		if (gen.isNotEmpty()) box.addView(themeCard("Сгенерированные", gen, "generated"))
		for (th in Puzzles.THEMES) {
			val list = all.filter { !it.generated && th.id in it.themes }
			if (list.isNotEmpty()) box.addView(themeCard(th.title, list, th.id))
		}
		return ui.scroll(box)
	}

	// нерешённая задача ближе всего к рейтингу (случайная из 8 ближайших)
	private fun suitable(): Puzzles.Puzzle? {
		val r = st.puzzleRating
		val pool = Puzzles.all(a).filter { it.rating > 0 && !st.isSolved(it.id) && !st.wasRated(it.id) }
		if (pool.isEmpty()) return Puzzles.all(a).firstOrNull { !st.isSolved(it.id) }
		return pool.sortedBy { kotlin.math.abs(it.rating - r) }.take(8).random()
	}

	private fun generatorCard(): View {
		val c = ui.card()
		c.addView(ui.text("🎲 Генератор задач", 17f, bold = true))
		c.addView(ui.sub("Разыгрывает случайную партию, ищет в ней позицию с единственным выигрывающим ходом и проверяет решение Stockfish. Обычно 10–60 секунд."))
		c.addView(ui.space(10))
		val themes = listOf<Pair<String, String?>>("Случайная тема" to null) + Puzzles.THEMES.filter { it.id in Puzzles.GEN_THEMES }.map { it.title.substringBefore(" · ") to it.id }
		val sel = st.str("genTheme", "")
		c.addView(ui.chips(themes.map { it.first to ((it.second ?: "") == sel) }, 3) { i -> st.setStr("genTheme", themes[i].second ?: ""); a.refresh() })
		c.addView(ui.space(10))
		c.addView(ui.button("Сгенерировать") { runGenerator(sel.ifEmpty { null }) })
		return c
	}

	private fun runGenerator(theme: String?) {
		val cancel = Puzzles.GenCancel()
		val info = ui.sub("Готовлю Stockfish…", 14f)
		val dlg = a.dialog("Ищу задачу" + (theme?.let { " · ${Puzzles.themeTitle(it).substringBefore(" · ")}" } ?: ""), info,
			"Отмена" to { cancel.cancelled = true }, cancelable = false)
		Stockfish.analysis.exec.execute {
			val pz = Puzzles.generate(a, theme, cancel) { n -> BotRunner.main.post { info.text = "Проверено позиций: $n" } }
			BotRunner.main.post {
				dlg.dismiss()
				if (cancel.cancelled) return@post
				if (pz == null) { a.toast("За 2 минуты не нашлось подходящей позиции" + (Stockfish.analysis.lastError?.let { ": $it" } ?: ". Попробуй ещё раз или другую тему")); return@post }
				Puzzles.addGenerated(a, pz)
				a.push(PuzzleScreen(a, pz, false))
			}
		}
	}

	private fun themeCard(title: String, list: List<Puzzles.Puzzle>, key: String): View {
		val c = ui.card()
		val done = list.count { st.isSolved(it.id) }
		val open = st.str("openTheme", "") == key
		val head = ui.row(8)
		head.addView(ui.col(0, 2, ui.text(title, 16f, bold = true), ui.sub("$done из ${list.size}")), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		head.addView(ui.text(if (open) "▴" else "▾", 20f, ui.t.sub))
		head.setOnClickListener { st.setStr("openTheme", if (open) "" else key); a.refresh() }
		c.addView(head)
		c.addView(ui.space(8))
		c.addView(ui.progress(done, list.size))
		c.addView(ui.space(10))
		val next = list.firstOrNull { !st.isSolved(it.id) }
		c.addView(ui.button(if (next != null) "Следующая" else "Все решены ✓", next != null, next != null) {
			next?.let { a.push(PuzzleScreen(a, it, false)) }
		})
		if (open) {
			c.addView(ui.space(10))
			val grid = ui.col(0, 6)
			var row: LinearLayout? = null
			list.forEachIndexed { i, pz ->
				if (i % 8 == 0) { row = ui.row(6); grid.addView(row, ui.lp()) }
				val s = st.isSolved(pz.id)
				val tv = ui.text("${i + 1}", 12f, if (s) Ui.contrast(ui.t.accent) else ui.t.text, s, Gravity.CENTER)
				tv.setPadding(0, ui.dp(8), 0, ui.dp(8))
				ui.clickable(tv, if (s) ui.t.accent else ui.t.card2, 10f) { a.push(PuzzleScreen(a, pz, false)) }
				row!!.addView(tv, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			}
			val rest = list.size % 8
			if (rest != 0) repeat(8 - rest) { row!!.addView(View(a), ui.lp(0, 1, 1f)) }
			c.addView(grid)
		}
		return c
	}
}

// Решение задачи. Ход не из заготовленного решения тоже засчитывается, если Stockfish подтверждает,
// что он ведёт к цели не хуже (мат за оставшееся число ходов / сохраняет выигрыш).
// Следующая сгенерированная задача на ту же тему ищется в фоне, пока решается текущая
object NextPuzzle {
	private var theme: String? = null
	private var ready: Puzzles.Puzzle? = null
	private var cancel: Puzzles.GenCancel? = null
	var running = false
		private set
	private var waiter: ((Puzzles.Puzzle?) -> Unit)? = null

	fun themeOf(pz: Puzzles.Puzzle) = pz.themes.firstOrNull { it in Puzzles.GEN_THEMES }

	fun prepare(ctx: android.content.Context, th: String?) {
		if (theme == th && (running || ready != null)) return
		cancel?.cancelled = true
		theme = th; ready = null; running = true
		val c = Puzzles.GenCancel(); cancel = c
		val app = ctx.applicationContext
		Stockfish.analysis.exec.execute {
			val pz = Puzzles.generate(app, th, c) {}
			BotRunner.main.post {
				if (cancel !== c) return@post
				running = false
				val w = waiter; waiter = null
				if (w != null) w(pz) else ready = pz
			}
		}
	}

	// готовая задача, если есть; иначе — подождать текущий поиск (или начать его)
	fun next(ctx: android.content.Context, th: String?, got: (Puzzles.Puzzle?) -> Unit) {
		if (theme == th && ready != null) { val p = ready; ready = null; got(p); return }
		if (!(theme == th && running)) prepare(ctx, th)
		waiter = got
	}

	fun stopWaiting() { waiter = null }
}

class PuzzleScreen(a: MainActivity, private val pz: Puzzles.Puzzle, private val daily: Boolean) : Screen(a) {
	private val st = a.store
	init { if (pz.generated) NextPuzzle.prepare(a, NextPuzzle.themeOf(pz)) }

	// следующая сгенерированная на ту же тему
	private fun nextGenerated() {
		val th = NextPuzzle.themeOf(pz)
		var dlg: android.app.AlertDialog? = null
		var done = false
		NextPuzzle.next(a, th) { next ->
			done = true
			dlg?.dismiss()
			if (next == null) { a.toast("За 2 минуты не нашлось подходящей позиции — попробуй ещё раз"); return@next }
			Puzzles.addGenerated(a, next)
			a.replace(PuzzleScreen(a, next, false))
		}
		// задача уже была готова — она открыта сразу, ждать нечего
		// (а новый экран тут же начал искать следующую, поэтому смотреть только на running нельзя)
		if (!done) {
			dlg = a.dialog("Ищу задачу" + (th?.let { " · ${Puzzles.themeTitle(it).substringBefore(" · ")}" } ?: ""),
				ui.sub("Генератор уже ищет её в фоне, осталось немного.", 14f), "Отмена" to { NextPuzzle.stopWaiting() }, cancelable = false)
		}
	}
	private val pos = Position().also { it.setFen(pz.fen) }
	private val hero = pos.side
	private val startMat = Tactics.material(pos, hero) - Tactics.material(pos, hero xor 1)
	private var step = 0 // индекс в pz.moves, пока идём по заготовленной линии
	private var offLine = false // ушли с линии — дальше проверяет Stockfish
	private var heroLeft = pz.heroMoves
	private var clean = true
	private var solved = false
	private var busy = false
	private val history = ArrayList<Int>()
	private val board = BoardView(a)
	private lateinit var status: TextView
	private lateinit var controls: LinearLayout

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() },
			ui.col(0, 0, ui.text((if (daily) "Задача дня · " else "") + pz.goal, 18f, bold = true),
				ui.sub((if (pz.rating > 0) "Рейтинг ${pz.rating} · " else "") + pz.themes.filter { !it.startsWith("mate") }.joinToString(", ") { Puzzles.themeTitle(it) }.ifEmpty { " " }, 12f))))
		board.whiteBottom = hero == P.WHITE
		board.showCoords = st.flag("coords", true)
		board.showLegal = st.flag("legal", true)
		board.canMove = { c -> c == hero && !solved && !busy }
		board.onMove = { m -> onMove(m) }
		board.setPosition(pos.copy())
		box.addView(board.detached(), ui.lp())
		status = ui.text("", 16f, ui.t.text, true, Gravity.CENTER)
		box.addView(status, ui.lp())
		controls = ui.row(8)
		box.addView(controls, ui.lp())
		update(null)
		return ui.scroll(box)
	}

	private fun update(msg: String?) {
		status.text = msg ?: if (solved) "Задача решена ✓" else
			"Ход ${if (hero == P.WHITE) "белых" else "чёрных"}: " + if (pz.mate > 0) "мат в $heroLeft" else "выиграй материал"
		controls.removeAllViews()
		if (!solved) {
			controls.addView(ui.button("💡 Подсказка (${st.hintsLeft})", false, !busy) { showHint() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			controls.addView(ui.button("Заново", false, !busy) { restart() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			if (pz.generated) controls.addView(ui.button("Следующая", false, !busy) { nextGenerated() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		} else if (pz.generated) {
			controls.addView(ui.button("Следующая") { nextGenerated() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			controls.addView(ui.button("К задачам", false) { a.pop() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		} else {
			val nextRepeat = st.repeatIds().filter { it != pz.id }.mapNotNull { Puzzles.byId(a, it) }.firstOrNull()
			val next = nextRepeat ?: Puzzles.all(a).firstOrNull { !st.isSolved(it.id) && it.id != pz.id && it.generated == pz.generated && it.themes.firstOrNull() == pz.themes.firstOrNull() }
			if (next != null) controls.addView(ui.button("Следующая") { a.replace(PuzzleScreen(a, next, false)) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			controls.addView(ui.button("К задачам", next == null) { a.pop() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
	}

	private fun showHint() {
		if (!st.useHint()) { a.toast("Подсказки кончились — купи их в магазине (сейчас ${st.hintPrice} 💎)"); return }
		failOnce(false)
		update(null)
		if (!offLine) {
			board.hintMove = pos.parseUci(pz.moves[step]); board.invalidate()
			update("Подсказка показана")
			return
		}
		busy = true; update("Ищу ход…")
		val p = pos.copy()
		Stockfish.analysis.exec.execute {
			val mv = Stockfish.analysis.analyse(a, p.fen(), "", p.side == P.WHITE, "depth 14")?.firstOrNull()?.move
			BotRunner.main.post {
				busy = false
				if (mv != null) { board.hintMove = p.parseUci(mv); board.invalidate() }
				update("Подсказка показана")
			}
		}
	}

	private fun restart() {
		while (history.isNotEmpty()) { pos.unmake(); history.removeAt(history.size - 1) }
		step = 0; offLine = false; heroLeft = pz.heroMoves
		board.markSquare = -1
		board.setPosition(pos.copy())
		update(null)
	}

	private fun play(m: Int) { pos.make(m); history.add(m) }

	private fun onMove(m0: Int) {
		var m = m0
		if (m and BoardView.PROMO_ASK != 0) {
			val base = m and BoardView.PROMO_ASK.inv()
			val sol = if (!offLine) pos.parseUci(pz.moves[step]) else 0
			m = if (sol != 0 && M.from(sol) == M.from(base) && M.to(sol) == M.to(base)) sol
			else pos.legalMoves().first { M.from(it) == M.from(base) && M.to(it) == M.to(base) && M.promo(it) == P.QUEEN }
		}
		val expected = if (!offLine) pos.parseUci(pz.moves[step]) else 0
		val before = pos.copy()
		play(m)
		val mate = Tactics.isMate(pos)
		if (mate && pz.mate > 0) { accept(m, true); return }
		if (!offLine && m == expected) {
			step++
			accept(m, step >= pz.moves.size)
			return
		}
		// не тот ход — спрашиваем Stockfish, ведёт ли он к цели
		busy = true
		board.setPosition(pos.copy(), m, true)
		update("Проверяю ход…")
		val p = pos.copy()
		Stockfish.analysis.exec.execute {
			val sign = if (hero == P.WHITE) 1 else -1
			val after = Stockfish.analysis.analyse(a, p.fen(), "", p.side == P.WHITE, "depth 16")?.firstOrNull()
			var ok = false
			if (after != null) {
				if (pz.mate > 0) ok = after.mate * sign in 1..(heroLeft - 1)
				else {
					val best = Stockfish.analysis.analyse(a, before.fen(), "", before.side == P.WHITE, "depth 16")?.firstOrNull()
					val a1 = after.score * sign
					ok = best != null && (after.mate * sign > 0 || (a1 >= 200 && a1 >= best.score * sign - 80))
				}
			}
			BotRunner.main.post {
				busy = false
				if (ok) { offLine = true; accept(m, false, after?.move) }
				else reject(m)
			}
		}
	}

	// ход засчитан. finished — задача завершена; reply — ответ соперника, если ушли с линии
	private fun accept(m: Int, finished: Boolean, reply: String? = null) {
		board.markSquare = M.to(m); board.markColor = 0x8834C759.toInt()
		board.setPosition(pos.copy(), m, true)
		heroLeft--
		val gained = Tactics.material(pos, hero) - Tactics.material(pos, hero xor 1) - startMat
		if (finished || (pz.mate == 0 && offLine && (gained >= 200 || heroLeft <= 0)) || (pz.mate > 0 && heroLeft <= 0)) {
			if (pz.mate > 0 && !Tactics.isMate(pos)) { reject(m); return }
			solvedNow(); return
		}
		busy = true
		update("Верно! Соперник отвечает…")
		BotRunner.main.postDelayed({
			val r = if (offLine) (reply?.let { pos.parseUci(it) } ?: 0) else pos.parseUci(pz.moves[step])
			if (r == 0) { busy = false; solvedNow(); return@postDelayed }
			play(r)
			if (!offLine) step++
			board.markSquare = -1
			board.setPosition(pos.copy(), r, true)
			if (pz.mate == 0) {
				val g = Tactics.material(pos, hero) - Tactics.material(pos, hero xor 1) - startMat
				if (offLine && g >= 200) { busy = false; solvedNow(); return@postDelayed }
			}
			busy = false
			update(null)
		}, 500)
	}

	private fun reject(m: Int) {
		failOnce()
		board.markSquare = M.to(m); board.markColor = 0x88FF3B30.toInt()
		board.setPosition(pos.copy(), m, true)
		busy = true
		update("Не то. Попробуй ещё раз")
		BotRunner.main.postDelayed({
			pos.unmake(); history.removeAt(history.size - 1)
			board.markSquare = -1
			board.setPosition(pos.copy())
			busy = false
			update(null)
		}, 700)
	}

	// первая ошибка или подсказка: минус рейтинг (если задача ещё не оценивалась) и в очередь повторения
	private var mistake = false

	private fun failOnce(isMistake: Boolean = true) {
		if (isMistake) mistake = true
		if (!clean) return
		clean = false
		val d = st.puzzleRated(pz.id, pz.rating, false)
		if (d != 0) ratingDelta = d
		st.updateRepeat(pz.id, false)
		ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.Kind.BAD)
	}

	private var ratingDelta = 0

	private fun solvedNow() {
		solved = true
		if (clean) { val d = st.puzzleRated(pz.id, pz.rating, true); if (d != 0) ratingDelta = d }
		ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.Kind.GOOD)
		val r = st.puzzleSolved(pz.id, pz.reward, clean, daily && Puzzles.daily(a)?.id == pz.id, !mistake)
		if (ratingDelta != 0) r.lines.add(0, "Рейтинг в задачах: ${st.puzzleRating} (${if (ratingDelta > 0) "+" else ""}$ratingDelta)")
		update(null)
		val body = ui.col(0, 6)
		r.lines.forEach { body.addView(ui.text(it, 14f)) }
		if (r.diamonds > 0) body.addView(ui.text("Всего: +${r.diamonds} 💎", 16f, ui.t.accent, true))
		a.dialog(if (clean) "Чисто решено! 🎯" else "Решено ✓", body, "Отлично" to {})
	}
}

// Разбор партии: считается в отдельном процессе Stockfish и своём потоке, можно уйти с экрана и вернуться
class ReviewScreen(a: MainActivity, private val startFen: String, private val uci: String,
	private val wName: String, private val bName: String, private val resultLine: String, private val variant: Int = 0) : Screen(a) {

	private val game = GameRecord.fromUci(startFen, uci, variant)
	private val key = Analyzer.key(startFen, uci, variant)
	private var ply = 0
	private val board = BoardView(a)
	private val graph = EvalGraph(a)
	private lateinit var info: TextView
	private lateinit var list: LinearLayout
	private val listener: () -> Unit = { show(false) }

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.text("Анализ · $wName — $bName", 16f, bold = true)))
		if (resultLine.isNotEmpty()) box.addView(ui.sub(resultLine))
		board.canMove = { false }
		board.showCoords = a.store.flag("coords", true)
		box.addView(board.detached(), ui.lp())
		graph.onPick = { ply = it; show(true) }
		box.addView(graph, ui.lp(h = ui.dp(70)))
		info = ui.text("", 14f, ui.t.text)
		box.addView(info, ui.lp())
		val nav = ui.row(8)
		nav.addView(ui.button("⏮", false) { ply = 0; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("◀", false) { if (ply > 0) ply--; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("▶", false) { if (ply < game.moves.size) ply++; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("⏭", false) { ply = game.moves.size; show(true) }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		nav.addView(ui.button("PGN", false) { share() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		box.addView(nav, ui.lp())
		list = ui.col(0, 6)
		box.addView(list, ui.lp())
		Analyzer.unlisten(key, listener)
		Analyzer.listen(key, listener)
		Analyzer.start(a, startFen, uci, variant)
		show(false)
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

	// animate — пользователь перешёл на ход: бриллиант/отличный ход празднуем анимацией
	private fun show(animate: Boolean) {
		val r = Analyzer.result(key)
		val p = game.positionAt(ply)
		val last = if (ply > 0) game.moves[ply - 1] else 0
		board.setPosition(p, last)
		if (r == null) {
			val err = Analyzer.error(key)
			info.text = err?.let { "Анализ недоступен: $it" } ?: "Stockfish анализирует в фоне… ${Analyzer.progress(key) ?: 0} / ${game.moves.size + 1}"
			graph.values = emptyList()
			if (list.childCount == 0) buildList(null)
			return
		}
		graph.values = r.evals.toList().take(r.count)
		graph.cursor = if (r.hasEval(ply)) ply else -1
		val cls = if (ply > 0 && r.hasClass(ply - 1)) r.classes[ply - 1] else null
		if (cls != null && last != 0) {
			if (animate && (cls == MoveClass.BRILLIANT || cls == MoveClass.GREAT)) board.celebrate(M.to(last), cls)
			else { board.badgeSq = M.to(last); board.badgeClass = cls }
		}
		if (r.hasEval(ply) && r.best[ply].isNotEmpty() && ply < game.moves.size) board.hintMove = p.parseUci(r.best[ply])
		board.invalidate()
		var t = if (r.hasEval(ply)) {
			val e = r.evals[ply]
			"Оценка: " + if (kotlin.math.abs(e) >= 9000) "мат" else String.format(java.util.Locale.US, "%+.2f", e / 100.0)
		} else "Эта позиция ещё считается…"
		if (cls != null) t += " · ${game.san(ply - 1, a.store.flag("sanEn", false))} ${cls.symbol} — ${cls.title}"
		if (r.hasEval(ply) && r.best[ply].isNotEmpty()) {
			val bm = p.parseUci(r.best[ply])
			if (bm != 0) t += "\nЛучший ход здесь: ${San.of(p, bm, !a.store.flag("sanEn", false))} (синяя стрелка)"
		}
		if (!r.complete) t += "\nStockfish досчитывает в фоне: ${r.count} / ${game.moves.size + 1}"
		info.text = t
		buildList(r)
	}

	private fun buildList(r: Analyzer.Result?) {
		list.removeAllViews()
		if (r != null) {
			for (side in 0..1) {
				var acc = 0.0; var cnt = 0
				val counts = HashMap<MoveClass, Int>()
				for (i in game.moves.indices) {
					if (!r.hasClass(i) || game.positionAt(i).side != side) continue
					val sign = if (side == P.WHITE) 1 else -1
					val l = maxOf(0.0, ru.cersat.chessics.bots.Judge.winPct(r.evals[i] * sign) - ru.cersat.chessics.bots.Judge.winPct(r.evals[i + 1] * sign))
					acc += (103.1668 * Math.exp(-0.04354 * l) - 3.1669).coerceIn(0.0, 100.0); cnt++
					r.classes[i]?.let { counts[it] = (counts[it] ?: 0) + 1 }
				}
				val name = if (side == 0) "Белые ($wName)" else "Чёрные ($bName)"
				val row = ui.row(6)
				row.addView(ui.text(String.format(java.util.Locale.US, "%s · %.0f%%", name, if (cnt > 0) acc / cnt else 0.0), 13f, ui.t.text, true))
				list.addView(row)
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
		}
		// ходы: цвет и значок типа
		val pos0 = Position.of(startFen, variant)
		var num = pos0.full
		var white = pos0.side == P.WHITE
		var row: LinearLayout? = null
		for (i in game.moves.indices) {
			if (white || i == 0) {
				row = ui.row(6)
				row.addView(ui.text(if (white) "$num." else "$num…", 13f, ui.t.sub), LinearLayout.LayoutParams(ui.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
				list.addView(row)
			}
			val cls = if (r != null && r.hasClass(i)) r.classes[i] else null
			val sel = i + 1 == ply
			val tv = ui.text(game.san(i, a.store.flag("sanEn", false)) + (cls?.let { " " + it.symbol } ?: ""), 14f,
				if (sel) Ui.contrast(ui.t.accent) else (cls?.takeIf { it in listOf(MoveClass.BRILLIANT, MoveClass.GREAT, MoveClass.INACCURACY, MoveClass.MISTAKE, MoveClass.BLUNDER) }?.color ?: ui.t.text), true)
			tv.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
			val target = i + 1
			ui.clickable(tv, if (sel) ui.t.accent else ui.t.card, 8f) { ply = target; show(true) }
			row!!.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			if (!white) num++
			white = !white
		}
		if (!white && row != null) row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
	}

	override fun dispose() { Analyzer.unlisten(key, listener) }
}

// Штурм: 3 минуты, задачи всё сложнее, ошибка — минус 10 секунд и следующая задача, серия из 5 — плюс 3 секунды
class StormScreen(a: MainActivity) : Screen(a) {
	private val st = a.store
	private val pool = Puzzles.all(a).filter { !it.generated && it.rating > 0 }.sortedBy { it.rating }
	private val used = HashSet<String>()
	private var running = false
	private var over = false
	private var endAt = 0L
	private var penalty = 0L
	private var score = 0
	private var combo = 0
	private var bestCombo = 0
	private var errors = 0
	private var cur: Puzzles.Puzzle? = null
	private var pos = Position()
	private var step = 0
	private var hero = P.WHITE
	private var busy = false
	private val board = BoardView(a)
	private lateinit var timer: TextView
	private lateinit var info: TextView
	private lateinit var controls: LinearLayout

	private val tick = object : Runnable {
		override fun run() {
			if (!running) return
			val left = timeLeft()
			timer.text = String.format(java.util.Locale.US, "%d:%02d", left / 60000, (left / 1000) % 60)
			if (left <= 0) { end(); return }
			BotRunner.main.postDelayed(this, 200)
		}
	}

	private fun timeLeft() = maxOf(0L, endAt - penalty - System.currentTimeMillis())

	override fun build(): View {
		val box = ui.col(12, 10)
		box.addView(ui.row(8, ui.button("‹", false) { a.pop() }, ui.title("Штурм задач")))
		val head = ui.row(10)
		timer = ui.text("3:00", 30f, ui.t.accent, true)
		head.addView(timer, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		info = ui.text("", 15f, ui.t.text, true, Gravity.END)
		head.addView(info)
		box.addView(head, ui.lp())
		board.showCoords = st.flag("coords", true)
		board.showLegal = st.flag("legal", true)
		board.canMove = { c -> running && !busy && c == hero && pos.side == hero }
		board.onMove = { m -> onMove(m) }
		box.addView(board.detached(), ui.lp())
		controls = ui.row(8)
		box.addView(controls, ui.lp())
		refreshInfo()
		if (!running && !over) {
			controls.addView(ui.button("Старт ⚡") { start() }, ui.lp())
			box.addView(ui.sub("Рекорд: ${st.stormBest}. Решай задачи как можно быстрее: первая — лёгкая, дальше сложнее. Неверный ход — минус 10 секунд и следующая задача."))
		}
		return ui.scroll(box)
	}

	private fun refreshInfo() {
		info.text = "✓ $score  ·  серия $combo  ·  ✗ $errors"
	}

	private fun start() {
		running = true; over = false; score = 0; combo = 0; bestCombo = 0; errors = 0; penalty = 0; used.clear()
		endAt = System.currentTimeMillis() + 180_000
		controls.removeAllViews()
		controls.addView(ui.button("Закончить", false) { end() }, ui.lp())
		next()
		BotRunner.main.post(tick)
	}

	private fun next() {
		if (pool.isEmpty()) { end(); return }
		val target = 700 + score * 60
		val cands = pool.filter { it.id !in used }.sortedBy { kotlin.math.abs(it.rating - target) }.take(6)
		val p = cands.randomOrNull() ?: run { end(); return }
		used.add(p.id)
		cur = p
		pos = Position.of(p.fen)
		hero = pos.side
		step = 0
		board.markSquare = -1
		board.whiteBottom = hero == P.WHITE
		board.setPosition(pos.copy())
		refreshInfo()
	}

	private fun onMove(m0: Int) {
		val p = cur ?: return
		var m = m0
		if (m and BoardView.PROMO_ASK != 0) {
			val base = m and BoardView.PROMO_ASK.inv()
			val sol = pos.parseUci(p.moves[step])
			m = if (M.from(sol) == M.from(base) && M.to(sol) == M.to(base)) sol
			else pos.legalMoves().first { M.from(it) == M.from(base) && M.to(it) == M.to(base) && M.promo(it) == P.QUEEN }
		}
		val expected = pos.parseUci(p.moves[step])
		val cap = pos.capturedBy(m) != 0
		pos.make(m)
		val mate = Tactics.isMate(pos)
		ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.forMove(cap, M.isCastle(m), pos.inCheck()))
		board.setPosition(pos.copy(), m, true)
		if (m == expected || (mate && p.mate > 0)) {
			step++
			if (mate || step >= p.moves.size) {
				score++; combo++; bestCombo = maxOf(bestCombo, combo)
				if (combo % 5 == 0) { penalty -= 3000; a.toast("Серия $combo: +3 с") }
				ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.Kind.GOOD)
				busy = true
				BotRunner.main.postDelayed({ busy = false; if (running) next() }, 250)
				refreshInfo()
				return
			}
			busy = true
			BotRunner.main.postDelayed({
				val r = pos.parseUci(p.moves[step])
				if (r != 0 && running) { val c2 = pos.capturedBy(r) != 0; pos.make(r); step++; board.setPosition(pos.copy(), r, true)
					ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.forMove(c2, M.isCastle(r), pos.inCheck())) }
				busy = false
			}, 180)
		} else {
			errors++; combo = 0; penalty += 10_000
			ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.Kind.BAD)
			board.markSquare = M.to(m); board.markColor = 0x88FF3B30.toInt()
			board.invalidate()
			refreshInfo()
			busy = true
			BotRunner.main.postDelayed({ busy = false; if (running) next() }, 450)
		}
	}

	private fun end() {
		if (!running) return
		running = false; over = true
		BotRunner.main.removeCallbacks(tick)
		val r = st.stormFinished(score)
		controls.removeAllViews()
		controls.addView(ui.button("Ещё штурм") { start() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		controls.addView(ui.button("К задачам", false) { a.pop() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val body = ui.col(0, 6)
		body.addView(ui.text("Решено: $score · лучшая серия $bestCombo · ошибок $errors", 15f))
		r.lines.forEach { body.addView(ui.text(it, 14f)) }
		a.dialog("Штурм окончен ⚡", body, "Отлично" to {})
	}

	override fun dispose() { running = false; BotRunner.main.removeCallbacks(tick) }
}
