package ru.cersat.chessics.ui

import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import ru.cersat.chessics.bots.AlgoBot
import ru.cersat.chessics.bots.Bot
import ru.cersat.chessics.bots.Analyzer
import ru.cersat.chessics.bots.Eval
import ru.cersat.chessics.bots.Judge
import ru.cersat.chessics.bots.MoveClass
import ru.cersat.chessics.bots.TacticsTracker
import ru.cersat.chessics.bots.MaterialBot
import ru.cersat.chessics.bots.Player
import ru.cersat.chessics.bots.RandomBot
import ru.cersat.chessics.bots.Stockfish
import ru.cersat.chessics.chess.GameRecord
import ru.cersat.chessics.chess.GameResult
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.Outcome
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Rules
import ru.cersat.chessics.data.Shop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

object BotRunner {
	val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "bot").apply { priority = Thread.NORM_PRIORITY - 1 } }
	val main = Handler(Looper.getMainLooper())
	private val materialCache = HashMap<Int, MaterialBot>()

	fun botFor(p: Player): Bot? = when (p.kind) {
		Player.Kind.RANDOM -> RandomBot
		Player.Kind.ALGO -> AlgoBot(p.level)
		Player.Kind.MATERIAL -> materialCache.getOrPut(p.level) { MaterialBot(p.level) }
		else -> null
	}
}

class GameScreen(a: MainActivity, private val cfg: GameConfig, saved: JSONObject?,
	private val link: ru.cersat.chessics.data.Link? = null, private val remoteName: String = "") : Screen(a) {
	private val endgame = ru.cersat.chessics.data.Endgames.byId(cfg.training)
	private var linkLost = false
	private var openingName = ""
	private val st = a.store
	private val game = GameRecord(cfg.startFen, cfg.variant)
	private val c960 = cfg.variant == ru.cersat.chessics.chess.V.CHESS960
	private val times = LongArray(2) { cfg.minutes * 60_000L }
	private var takebacks = 0
	private var hints = 0
	private var result: GameResult? = null
	private var viewPly = -1 // -1 — живая позиция
	private var drawOfferBy = -1
	private var paused = false
	private var thinking = false
	private val token = AtomicInteger(0)
	private var whiteBottom = true
	private var lastTick = 0L
	private var rewarded = false
	private var resultShownOnce = false
	private var barCp = 0 // последняя оценка полосы (белые +)
	private val barToken = AtomicInteger(0)
	private val liveClasses = HashMap<Int, MoveClass>() // номер хода (1..) → тип хода человека
	private val tracker = if (cfg.humanColor >= 0) TacticsTracker(cfg.humanColor) else null

	private val board = BoardView(a)
	private val evalBar = EvalBar(a)
	private lateinit var topBar: LinearLayout
	private lateinit var bottomBar: LinearLayout
	private lateinit var status: TextView
	private lateinit var movesRow: LinearLayout
	private lateinit var movesScroll: HorizontalScrollView
	private lateinit var controls: LinearLayout

	private val tick = object : Runnable {
		override fun run() {
			val now = System.currentTimeMillis()
			if (clockRunning()) {
				val side = game.pos.side
				times[side] -= now - lastTick
				if (times[side] <= 0) {
					times[side] = 0
					val opp = side xor 1
					if (cfg.remote && side == cfg.humanColor) sendRemote(JSONObject().put("t", "flag"))
					finish(if (game.pos.canMate(opp)) GameResult(if (opp == P.WHITE) Outcome.WHITE_WINS else Outcome.BLACK_WINS, "время вышло")
					else GameResult(Outcome.DRAW, "время вышло, но у соперника не хватает материала для мата"))
				}
				updateClocks()
			}
			lastTick = now
			BotRunner.main.postDelayed(this, 100)
		}
	}

	init {
		whiteBottom = when {
			cfg.humanColor == P.BLACK -> false
			else -> true
		}
		if (saved != null) {
			val uci = saved.optString("uci")
			for (u in uci.split(' ')) {
				if (u.isBlank()) continue
				val m = game.pos.parseUci(u)
				if (m == 0) break
				game.play(m)
			}
			times[0] = saved.optLong("wt", times[0]); times[1] = saved.optLong("bt", times[1])
			takebacks = saved.optInt("tb"); hints = saved.optInt("hints")
			whiteBottom = saved.optBoolean("wb", whiteBottom)
		}
		if (cfg.hvh && !cfg.faceToFace) whiteBottom = game.pos.side == P.WHITE
		Stockfish.newGame()
		link?.onClosed = {
			if (result == null) { linkLost = true; a.toast("Соединение с соперником потеряно"); updateStatus() }
		}
		link?.attach { onRemote(it) }
	}

	// ---------- сетевой соперник ----------

	private val remoteColor get() = if (cfg.white.kind == Player.Kind.REMOTE) P.WHITE else P.BLACK

	private fun onRemote(o: JSONObject) {
		when (o.optString("t")) {
			"move" -> {
				if (result != null || o.optInt("ply") != game.moves.size || playerOf(game.pos.side).kind != Player.Kind.REMOTE) return
				val m = game.pos.parseUci(o.optString("uci"))
				if (m == 0) return
				if (o.has("time") && cfg.hasClock) times[remoteColor] = o.optLong("time")
				applyMove(m)
			}
			"resign" -> finish(GameResult(if (remoteColor == P.WHITE) Outcome.BLACK_WINS else Outcome.WHITE_WINS, "соперник сдался"))
			"draw" -> { drawOfferBy = remoteColor; refreshAll(false); a.toast("Соперник предлагает ничью") }
			"drawYes" -> finish(GameResult(Outcome.DRAW, "ничья по соглашению"))
			"drawNo" -> { drawOfferBy = -1; a.toast("Соперник отклонил ничью"); refreshAll(false) }
			"flag" -> finish(GameResult(if (remoteColor == P.WHITE) Outcome.BLACK_WINS else Outcome.WHITE_WINS, "у соперника вышло время"))
		}
	}

	private fun sendRemote(o: JSONObject) { link?.send(o) }

	// для «ещё одной» партии в Chess960 — новая случайная расстановка
	private fun freshConfig() = if (c960) cfg.copy(startFen = ru.cersat.chessics.chess.V.random960()) else cfg

	private lateinit var openingView: TextView

	private fun nameOf(c: Int): String {
		val p = playerOf(c)
		return if (p.kind == Player.Kind.REMOTE) remoteName.ifEmpty { "Соперник" } else p.shortName
	}

	private fun clockRunning() = cfg.hasClock && result == null && game.moves.isNotEmpty() && !paused && !linkLost

	private fun playerOf(color: Int) = if (color == P.WHITE) cfg.white else cfg.black
	private fun humanTurn() = result == null && viewPly < 0 && playerOf(game.pos.side).isHuman

	override fun build(): View {
		val root = ui.col(10, 8)
		topBar = ui.row(8)
		bottomBar = ui.row(8)
		status = ui.text("", 14f, ui.t.sub, gravity = Gravity.CENTER)
		movesRow = ui.row(4)
		movesScroll = ui.hscroll(movesRow)
		controls = ui.row(6)

		board.whiteBottom = whiteBottom
		board.faceToFace = cfg.hvh && cfg.faceToFace
		board.showCoords = st.flag("coords", true)
		board.showLegal = st.flag("legal", true)
		board.canMove = { c -> humanTurn() && playerOf(c).isHuman }
		board.onMove = { m -> onHumanMove(m) }
		board.premoveColor = {
			val hc = cfg.humanColor
			if (st.flag("premove", true) && result == null && viewPly < 0 && hc >= 0 && !playerOf(game.pos.side).isHuman) hc else -1
		}

		val header = ui.row(8)
		header.addView(ui.button("‹", false) { a.goBack() })
		val titleText = endgame?.let { "🎓 ${it.title} · ${if (it.win) "выиграй" else "удержи ничью"}" }
			?: ("${nameOf(P.WHITE)} — ${nameOf(P.BLACK)}" + if (cfg.hasClock) " · ${GameConfig.clockLabel(cfg.minutes, cfg.inc)}" else "")
		val titleBox = ui.col(0, 0, ui.text(titleText, 14f, ui.t.sub))
		openingView = ui.text("", 12f, ui.t.accent)
		titleBox.addView(openingView)
		header.addView(titleBox, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		root.addView(header)
		root.addView(topBar, ui.lp())
		root.addView(board, ui.lp())
		root.addView(bottomBar, ui.lp())
		if (st.flag("evalbar", true) && !cfg.hvh) root.addView(evalBar, ui.lp(h = ui.dp(16)))
		root.addView(status, ui.lp())
		root.addView(movesScroll, ui.lp())
		root.addView(controls, ui.lp())

		refreshAll(false)
		updateOpening()
		lastTick = System.currentTimeMillis()
		BotRunner.main.removeCallbacks(tick)
		BotRunner.main.post(tick)
		maybeBot()
		return ui.scroll(root)
	}

	// ---------- отрисовка ----------

	private fun refreshAll(animate: Boolean) {
		val live = viewPly < 0
		val pos = if (live) game.pos.copy() else game.positionAt(viewPly)
		val move = if (live) game.lastMove else if (viewPly > 0) game.moves[viewPly - 1] else 0
		board.whiteBottom = whiteBottom
		board.setPosition(pos, move, animate && st.flag("anim", true))
		buildBar(topBar, if (whiteBottom) P.BLACK else P.WHITE, cfg.hvh && cfg.faceToFace)
		buildBar(bottomBar, if (whiteBottom) P.WHITE else P.BLACK, false)
		requestBar(pos, if (live) game.moves.size else viewPly)
		applyBadge(if (live) game.moves.size else viewPly)
		updateStatus()
		buildMoves()
		buildControls()
	}

	// Полоса преимущества: Stockfish глубины 3 в отдельном процессе; если он недоступен — оценка из chess-bots.js
	private fun requestBar(pos: ru.cersat.chessics.chess.Position, ply: Int) {
		if (!st.flag("evalbar", true) || cfg.hvh) return
		val my = barToken.incrementAndGet()
		val uci = game.moves.take(ply).joinToString(" ") { M.uci(it) }
		val p = pos.copy()
		val e = Stockfish.bar(cfg.variant)
		e.exec.execute {
			if (barToken.get() != my) return@execute
			val st0 = Rules.status(p)
			val score: Int
			var mate = 0
			if (st0.outcome != Outcome.NONE) score = when (st0.outcome) { Outcome.WHITE_WINS -> 10000; Outcome.BLACK_WINS -> -10000; else -> 0 }
			else {
				val l = e.analyse(a, cfg.startFen, uci, p.side == P.WHITE, "depth 3", 1, cfg.variant)?.firstOrNull()
				score = l?.score ?: Eval.evaluate(p).coerceIn(-9000, 9000)
				mate = l?.mate ?: 0
			}
			BotRunner.main.post {
				if (barToken.get() != my) return@post
				barCp = score
				evalBar.cp = score.coerceIn(-3000, 3000)
				evalBar.label = when {
					mate != 0 -> "M${kotlin.math.abs(mate)}"
					score >= 9000 || score <= -9000 -> "мат"
					else -> String.format(Locale.US, "%+.1f", score / 100.0)
				}
			}
		}
	}

	// Значок типа хода остаётся на последнем оценённом ходе человека, пока соперник отвечает,
	// а не пропадает сразу после быстрого хода бота
	private fun badgePly(ply: Int): Int = (ply downTo maxOf(1, ply - 1)).firstOrNull { liveClasses.containsKey(it) } ?: -1

	private fun applyBadge(ply: Int, celebrate: Boolean = false) {
		val p = badgePly(ply)
		if (p < 0 || p > game.moves.size) return
		val cls = liveClasses[p] ?: return
		val big = cls == MoveClass.BRILLIANT || cls == MoveClass.GREAT
		if (!big && !st.flag("liveJudge", true)) return
		val to = M.to(game.moves[p - 1])
		if (celebrate && big) board.celebrate(to, cls)
		else { board.badgeSq = to; board.badgeClass = cls; board.invalidate() }
	}

	private fun materialDiff(color: Int): Int {
		var d = 0
		for (p in game.pos.sq) if (p != 0) d += if (P.color(p) == color) P.VALUE[P.type(p)] else -P.VALUE[P.type(p)]
		return d / 100
	}

	private fun fmt(ms: Long): String {
		val s = (ms + 999) / 1000
		return if (ms < 10_000) String.format(Locale.US, "%d.%d", ms / 1000, (ms % 1000) / 100) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
	}

	private val clockViews = arrayOfNulls<TextView>(2)

	private fun buildBar(bar: LinearLayout, color: Int, rotated: Boolean) {
		bar.removeAllViews()
		bar.rotation = if (rotated) 180f else 0f
		bar.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
		val toMove = result == null && game.pos.side == color
		bar.background = ui.round(if (toMove) ui.t.card2 else ui.t.card, 14f, if (toMove) 2 else 0, ui.t.accent)
		val pl = playerOf(color)
		val icon = ImageView(a)
		icon.setImageBitmap(PieceArt.get(a, Shop.pieces(a).set, P.make(color, P.KING)))
		bar.addView(icon, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
		val name = when {
			pl.kind == Player.Kind.REMOTE -> nameOf(color)
			pl.isHuman -> if (cfg.hvh) (if (color == P.WHITE) "Белые" else "Чёрные") else if (cfg.remote) "Ты" else "Ты · ${st.rating}"
			else -> "${pl.shortName} · ≈${pl.elo}"
		}
		val diff = materialDiff(color)
		val info = ui.col(0, 0, ui.text(name, 15f, bold = true), ui.sub(if (diff > 0) "+$diff по материалу" else " ", 11f))
		bar.addView(info, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		// кнопки сдачи/ничьей у каждого человека в игре вдвоём
		if (cfg.hvh && result == null) {
			if (drawOfferBy == (color xor 1)) {
				bar.addView(ui.button("Принять ½") { finish(GameResult(Outcome.DRAW, "ничья по соглашению")) })
				bar.addView(ui.button("✕", false) { drawOfferBy = -1; refreshAll(false) })
			} else {
				bar.addView(ui.button(if (drawOfferBy == color) "½ предложена" else "½", false, drawOfferBy != color) { drawOfferBy = color; refreshAll(false) })
				bar.addView(ui.button("⚑", false) { resign(color) })
			}
		}
		if (cfg.hasClock) {
			val c = ui.text(fmt(times[color]), 20f, if (toMove) Ui.contrast(ui.t.accent) else ui.t.text, true, Gravity.CENTER)
			c.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
			c.background = ui.round(if (toMove) ui.t.accent else ui.t.bg, 10f)
			c.minWidth = ui.dp(78)
			clockViews[color] = c
			bar.addView(c)
		} else clockViews[color] = null
	}

	private fun updateClocks() {
		for (c in 0..1) clockViews[c]?.let {
			it.text = fmt(times[c])
			if (times[c] < 10_000 && result == null && game.pos.side == c) it.setTextColor(0xFFFF4D4D.toInt())
		}
	}

	private fun updateStatus() {
		val r = result
		val v = cfg.variant
		val extra = when (v) {
			ru.cersat.chessics.chess.V.THREE_CHECK -> " · шахи: белые ${game.pos.checks[0]}/3, чёрные ${game.pos.checks[1]}/3"
			ru.cersat.chessics.chess.V.KOTH -> " · цель: король в центр"
			ru.cersat.chessics.chess.V.CHESS960 -> " · Chess960"
			else -> ""
		}
		status.text = (when {
			r != null -> resultText(r)
			viewPly >= 0 -> "Просмотр: ход ${viewPly} из ${game.moves.size}"
			paused -> "Пауза"
			linkLost -> "Соединение потеряно"
			thinking -> "${playerOf(game.pos.side).shortName} думает…"
			playerOf(game.pos.side).kind == Player.Kind.REMOTE -> "Ход соперника" + if (board.premoveFrom >= 0) " · предход назначен" else ""
			game.pos.inCheck() -> "Шах! Ход ${if (game.pos.side == P.WHITE) "белых" else "чёрных"}"
			else -> "Ход ${if (game.pos.side == P.WHITE) "белых" else "чёрных"}"
		}) + (if (r == null) extra else "")
	}

	private fun resultText(r: GameResult) = when (r.outcome) {
		Outcome.WHITE_WINS -> "Победа белых · ${r.reason}"
		Outcome.BLACK_WINS -> "Победа чёрных · ${r.reason}"
		Outcome.DRAW -> "Ничья · ${r.reason}"
		Outcome.NONE -> ""
	}

	private fun buildMoves() {
		movesRow.removeAllViews()
		val startWhite = game.startSide() == P.WHITE
		var num = ru.cersat.chessics.chess.Position.of(cfg.startFen, cfg.variant).full
		val cur = if (viewPly < 0) game.moves.size else viewPly
		var whiteTurn = startWhite
		for (i in game.sanRu.indices) {
			if (whiteTurn || i == 0) movesRow.addView(ui.text(if (whiteTurn) "$num." else "$num…", 13f, ui.t.sub))
			val sel = i + 1 == cur
			val tv = ui.text(game.san(i, st.flag("sanEn", false)), 14f, if (sel) Ui.contrast(ui.t.accent) else ui.t.text, sel)
			tv.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
			val ply = i + 1
			ui.clickable(tv, if (sel) ui.t.accent else 0, 8f) { viewPly = if (ply == game.moves.size) -1 else ply; refreshAll(false) }
			movesRow.addView(tv)
			if (!whiteTurn) num++
			whiteTurn = !whiteTurn
		}
		movesScroll.post { if (viewPly < 0) movesScroll.fullScroll(View.FOCUS_RIGHT) }
	}

	private fun buildControls() {
		controls.removeAllViews()
		fun add(label: String, enabled: Boolean = true, primary: Boolean = false, f: () -> Unit) =
			controls.addView(ui.button(label, primary, enabled, f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val over = result != null
		val n = game.moves.size
		val cur = if (viewPly < 0) n else viewPly
		add("◀", cur > 0) { viewPly = cur - 1; refreshAll(false) }
		add("▶", viewPly >= 0) { viewPly = if (cur + 1 >= n) -1 else cur + 1; refreshAll(viewPly < 0) }
		if (over) {
			add("Анализ", primary = true) { openReview() }
			if (!cfg.remote) add("Ещё", primary = false) { a.replace(GameScreen(a, freshConfig(), null)) }
			return
		}
		add("⇅") { whiteBottom = !whiteBottom; refreshAll(false) }
		val botVsBot = !cfg.white.isHuman && !cfg.black.isHuman
		if (botVsBot) {
			add(if (paused) "▶ Продолжить" else "⏸ Пауза", primary = true) {
				paused = !paused
				if (paused) { token.incrementAndGet(); Stockfish.stopPlay(); thinking = false }
				refreshAll(false); maybeBot()
			}
			return
		}
		if (cfg.remote) {
			if (drawOfferBy == remoteColor) {
				add("Принять ½", primary = true) { sendRemote(JSONObject().put("t", "drawYes")); finish(GameResult(Outcome.DRAW, "ничья по соглашению")) }
				add("✕") { sendRemote(JSONObject().put("t", "drawNo")); drawOfferBy = -1; refreshAll(false) }
			} else add("½", drawOfferBy < 0 && !linkLost) { drawOfferBy = cfg.humanColor; sendRemote(JSONObject().put("t", "draw")); a.toast("Ничья предложена"); refreshAll(false) }
			add("⚑") { resign(cfg.humanColor) }
			return
		}
		add("↩", n > 0 && viewPly < 0) { takeback() }
		if (cfg.bot != null) {
			add("💡${HINT}", viewPly < 0 && humanTurn()) { hint() }
			if (endgame == null) add("½") { offerDrawToBot() }
			add("⚑") { resign(cfg.humanColor) }
		}
	}

	// ---------- ходы ----------

	private fun onHumanMove(m0: Int) {
		if (!humanTurn()) return
		if (m0 and BoardView.PROMO_ASK != 0) {
			val base = m0 and BoardView.PROMO_ASK.inv()
			if (st.flag("autoqueen", false)) { applyMove(findPromo(base, P.QUEEN)); return }
			askPromotion(game.pos.side) { t -> applyMove(findPromo(base, t)) }
			return
		}
		applyMove(m0)
	}

	private fun findPromo(base: Int, type: Int): Int =
		game.pos.legalMoves().firstOrNull { M.from(it) == M.from(base) && M.to(it) == M.to(base) && M.promo(it) == type } ?: 0

	private fun askPromotion(color: Int, done: (Int) -> Unit) {
		val r = ui.row(8)
		var dlg: android.app.AlertDialog? = null
		for (t in intArrayOf(P.QUEEN, P.ROOK, P.BISHOP, P.KNIGHT)) {
			val iv = ImageView(a)
			iv.setImageBitmap(PieceArt.get(a, Shop.pieces(a).set, P.make(color, t)))
			iv.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
			ui.clickable(iv, ui.t.card2, 12f) { dlg?.dismiss(); done(t) }
			r.addView(iv, LinearLayout.LayoutParams(0, ui.dp(70), 1f))
		}
		dlg = a.dialog("Превращение пешки", r, "Отмена" to { board.setPosition(game.pos.copy(), game.lastMove) })
	}

	private fun applyMove(m: Int) {
		if (m == 0 || result != null) return
		val mover = game.pos.side
		if (game.pos.legalMoves().none { it == m }) return
		val before = game.pos.copy()
		val uciBefore = game.uciList()
		val humanMove = playerOf(mover).isHuman
		val captured = before.capturedBy(m) != 0
		game.play(m)
		if (humanMove) afterHumanMove(before, uciBefore, m, game.moves.size)
		if (cfg.hasClock && game.moves.size > 1) times[mover] += cfg.inc * 1000L
		if (humanMove && cfg.remote) sendRemote(JSONObject().put("t", "move").put("uci", M.uci(m)).put("ply", game.moves.size - 1).put("time", times[mover]))
		ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.forMove(captured, M.isCastle(m), game.pos.inCheck()))
		updateOpening()
		if (drawOfferBy == (mover xor 1)) drawOfferBy = -1
		if (st.flag("vibro", true)) vibrate()
		if (cfg.hvh && !cfg.faceToFace) whiteBottom = game.pos.side == P.WHITE
		var r = Rules.status(game.pos)
		// тренажёр «удержи ничью»: 40 своих ходов без поражения — успех
		if (r.outcome == Outcome.NONE && endgame != null && !endgame.win && humanMove && game.moves.size >= 80)
			r = GameResult(Outcome.DRAW, "продержался 40 ходов")
		refreshAll(true)
		if (r.outcome != Outcome.NONE) finish(r) else {
			save(); maybeBot()
			if (!humanMove) tryPremove()
		}
	}

	private fun tryPremove() {
		val f = board.premoveFrom; val t = board.premoveTo
		board.clearPremove()
		if (f < 0 || t < 0 || !humanTurn()) return
		val cands = game.pos.legalMoves().filter { M.from(it) == f && M.to(it) == t }
		val m = cands.firstOrNull { M.promo(it) == 0 || M.promo(it) == P.QUEEN } ?: return
		BotRunner.main.postDelayed({ if (humanTurn() && game.pos.legalMoves().contains(m)) applyMove(m) }, 60)
	}

	private fun updateOpening() {
		if (cfg.variant != ru.cersat.chessics.chess.V.STANDARD || !::openingView.isInitialized) return
		val o = ru.cersat.chessics.data.Openings.detect(a, cfg.startFen, game.moves)
		if (o != null) openingName = "📖 ${o.eco} ${o.name}"
		openingView.text = openingName
	}

	private fun afterHumanMove(before: ru.cersat.chessics.chess.Position, uciBefore: String, m: Int, ply: Int) {
		val rated = cfg.bot != null
		// тактика: засчитывается, когда приём реализован
		tracker?.onHumanMove(before, m)?.forEach { key ->
			val name = when (key) { "fork" -> "Вилка! 🍴"; "skewer" -> "Шампур! 🍢"; else -> "Ферзь пойман! 👑" }
			val r = st.countMotif(key)
			a.toast(name + r.lines.joinToString("") { "\n$it" })
		}
		// оценка хода Stockfish (в потоке анализа)
		Judge.judgeLive(a, cfg.startFen, uciBefore, before, m, cfg.variant) { cls ->
			if (cls == null || ply > game.moves.size || game.moves[ply - 1] != m) return@judgeLive
			liveClasses[ply] = cls
			val cur = if (viewPly < 0) game.moves.size else viewPly
			if (badgePly(cur) == ply) applyBadge(cur, true)
			if (cls == MoveClass.BRILLIANT || cls == MoveClass.GREAT) {
				status.text = if (cls == MoveClass.BRILLIANT) "!! Бриллиантовый ход! 💎" else "! Отличный ход — единственный сильный"
				if (rated) {
					val r = st.countMotif(if (cls == MoveClass.BRILLIANT) "brilliant" else "great")
					if (r.lines.isNotEmpty()) a.toast(r.lines.joinToString("\n"))
				}
			}
		}
	}

	private fun vibrate() {
		try {
			val v = a.getSystemService(Vibrator::class.java)
			v?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
		} catch (_: Exception) {}
	}

	private fun maybeBot() {
		if (result != null || paused || thinking) return
		val pl = playerOf(game.pos.side)
		if (pl.isHuman || pl.kind == Player.Kind.REMOTE) return
		thinking = true
		updateStatus()
		val my = token.incrementAndGet()
		val pos = game.pos.copy()
		val plies = game.moves.size
		val startFen = cfg.startFen
		val uci = game.uciList()
		val myTime = times[game.pos.side]
		BotRunner.exec.execute {
			val t0 = System.currentTimeMillis()
			var mv = 0
			var err: String? = null
			if (pl.kind == Player.Kind.STOCKFISH) {
				var mt = if (pl.level <= 0) 1500 else 700
				if (cfg.hasClock) mt = minOf(mt, maxOf(80, (myTime / 40).toInt()))
				val eng = Stockfish.play(cfg.variant, pl.level)
				val best = eng.bestMove(a, startFen, uci, pl.level, mt, cfg.variant)
				if (best != null) mv = pos.parseUci(best)
				if (mv == 0) { err = "Stockfish недоступен: ${eng.lastError ?: "нет ответа"}. Ход сделал Материал."; mv = MaterialBot(4).pick(pos, plies) { token.get() != my } }
			} else {
				mv = BotRunner.botFor(pl)!!.pick(pos, plies) { token.get() != my }
			}
			val wait = 350 - (System.currentTimeMillis() - t0)
			BotRunner.main.postDelayed({
				if (token.get() != my) return@postDelayed
				thinking = false
				if (err != null) a.toast(err)
				if (mv != 0) applyMove(mv) else updateStatus()
			}, maxOf(0L, wait))
		}
	}

	private fun takeback() {
		if (result != null) return
		token.incrementAndGet(); Stockfish.stopPlay(); thinking = false
		if (cfg.bot != null) {
			// откатываем до хода человека
			do { if (!game.undo()) break } while (game.moves.isNotEmpty() && !playerOf(game.pos.side).isHuman)
			if (!playerOf(game.pos.side).isHuman) { refreshAll(false); maybeBot(); return }
			takebacks++
		} else game.undo()
		if (cfg.hvh && !cfg.faceToFace) whiteBottom = game.pos.side == P.WHITE
		drawOfferBy = -1
		save()
		refreshAll(false)
		maybeBot()
	}

	private fun hint() {
		if (!st.spend(HINT)) { a.toast("Подсказка стоит $HINT 💎 — алмазов не хватает"); return }
		hints++
		st.hintUsed()
		status.text = "Ищу лучший ход…"
		val startFen = cfg.startFen; val uci = game.uciList(); val pos = game.pos.copy()
		val ply = game.moves.size
		BotRunner.exec.execute {
			var m = Stockfish.play(cfg.variant).analyse(a, startFen, uci, pos.side == P.WHITE, "movetime 800", 1, cfg.variant)?.firstOrNull()?.move?.let { pos.parseUci(it) } ?: 0
			if (m == 0) m = MaterialBot(5).pick(pos, ply) { false }
			BotRunner.main.post {
				if (game.moves.size != ply || result != null) return@post
				board.hintMove = m
				board.invalidate()
				updateStatus()
			}
		}
	}

	private fun offerDrawToBot() {
		val botColor = cfg.humanColor xor 1
		val e = barCp * (if (botColor == P.WHITE) 1 else -1)
		if (e < -150 || (game.moves.size >= 60 && abs(e) <= 40)) finish(GameResult(Outcome.DRAW, "ничья по соглашению"))
		else a.toast("${playerOf(botColor).shortName} отклоняет ничью")
	}

	private fun resign(color: Int) {
		a.dialog("Сдаться?", ui.sub("${if (color == P.WHITE) "Белые" else "Чёрные"} признают поражение."),
			"Сдаться" to {
				if (cfg.remote) sendRemote(JSONObject().put("t", "resign"))
				finish(GameResult(if (color == P.WHITE) Outcome.BLACK_WINS else Outcome.WHITE_WINS, "${if (color == P.WHITE) "белые" else "чёрные"} сдались"))
			},
			"Играть дальше" to {})
	}

	// ---------- конец партии ----------

	private fun finish(r: GameResult) {
		if (result != null) return
		result = r
		token.incrementAndGet(); Stockfish.stopPlay(); thinking = false
		viewPly = -1
		st.setStr("current", "")
		ru.cersat.chessics.data.Sounds.play(a, ru.cersat.chessics.data.Sounds.Kind.END)
		board.clearPremove()
		val reward = giveRewards(r)
		saveHistory(r)
		if (game.moves.isNotEmpty()) Analyzer.start(a, cfg.startFen, game.uciList(), cfg.variant)
		refreshAll(false)
		showEnd(r, reward)
	}

	private fun giveRewards(r: GameResult): ru.cersat.chessics.data.Store.Reward? {
		if (rewarded) return null
		rewarded = true
		val plies = game.moves.size
		if (cfg.hvh) return st.gameFinished(null, 0.5, plies, 0, true, false, cfg.hasClock)
		val hc = cfg.humanColor
		val score = when (r.outcome) {
			Outcome.DRAW -> 0.5
			Outcome.WHITE_WINS -> if (hc == P.WHITE) 1.0 else 0.0
			Outcome.BLACK_WINS -> if (hc == P.BLACK) 1.0 else 0.0
			Outcome.NONE -> 0.0
		}
		if (cfg.remote) return st.lanFinished(score, plies)
		if (endgame != null) return st.endgameFinished(endgame.id, if (endgame.win) score >= 1.0 else score >= 0.5, endgame.reward)
		val bot = cfg.bot ?: return null
		return st.gameFinished(bot, score, plies, takebacks, false, score >= 1.0 && r.reason == "мат", cfg.hasClock)
	}

	private fun showEnd(r: GameResult, reward: ru.cersat.chessics.data.Store.Reward?) {
		if (resultShownOnce) return
		resultShownOnce = true
		val hc = cfg.humanColor
		val head = when {
			r.outcome == Outcome.DRAW -> "Ничья"
			hc < 0 -> if (r.outcome == Outcome.WHITE_WINS) "Победа белых" else "Победа чёрных"
			(r.outcome == Outcome.WHITE_WINS) == (hc == P.WHITE) -> "Победа! 🎉"
			else -> "Поражение"
		}
		val body = ui.col(0, 6)
		body.addView(ui.sub(r.reason.replaceFirstChar { it.uppercase() }, 15f))
		reward?.lines?.forEach { body.addView(ui.text(it, 14f)) }
		if (reward != null && reward.diamonds > 0) body.addView(ui.text("Всего: +${reward.diamonds} 💎", 16f, ui.t.accent, true))
		if (endgame != null) {
			val ok = if (endgame.win) r.outcome != Outcome.DRAW && (r.outcome == Outcome.WHITE_WINS) == (hc == P.WHITE) else r.outcome == Outcome.DRAW || (r.outcome == Outcome.WHITE_WINS) == (hc == P.WHITE)
			a.dialog(if (ok) "Пройдено! 🎓" else "Не вышло", body,
				"Посмотреть ходы" to {}, "Ещё раз" to { a.replace(GameScreen(a, cfg, null)) }, "К тренажёру" to { a.pop() })
			return
		}
		if (cfg.remote) {
			a.dialog(head, body, "Посмотреть ходы" to {}, "Анализ партии" to { openReview() }, "В меню" to { a.showTab(0) })
			return
		}
		a.dialog(head, body,
			"Посмотреть ходы" to {},
			"Анализ партии" to { openReview() },
			"Новая партия" to { a.replace(GameScreen(a, freshConfig(), null)) },
			"В меню" to { a.showTab(0) })
	}

	private fun openReview() {
		a.push(ReviewScreen(a, cfg.startFen, game.uciList(), cfg.white.shortName, cfg.black.shortName, variant = cfg.variant, resultLine =
			result?.let { resultText(it) } ?: ""))
	}

	private fun saveHistory(r: GameResult) {
		if (game.moves.isEmpty()) return
		val o = JSONObject()
			.put("date", SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()))
			.put("w", nameOf(P.WHITE)).put("b", nameOf(P.BLACK))
			.put("res", r.pgnTag).put("reason", r.reason)
			.put("fen", cfg.startFen).put("uci", game.uciList())
			.put("tc", if (cfg.hasClock) GameConfig.clockLabel(cfg.minutes, cfg.inc) else "")
			.put("variant", cfg.variant)
		st.saveGame(o)
	}

	private fun save() {
		if (result != null || cfg.remote) return
		if (game.moves.isEmpty()) { st.setStr("current", ""); return }
		val o = JSONObject().put("cfg", cfg.toJson()).put("uci", game.uciList()).put("wt", times[0]).put("bt", times[1])
			.put("tb", takebacks).put("hints", hints).put("wb", whiteBottom)
		st.setStr("current", o.toString())
	}

	override fun onPause() {
		save()
	}

	override fun onBack(): Boolean {
		save()
		return false
	}

	override fun dispose() {
		save()
		if (cfg.remote && result == null) sendRemote(JSONObject().put("t", "resign"))
		link?.close()
		barToken.incrementAndGet()
		token.incrementAndGet()
		Stockfish.stopPlay()
		BotRunner.main.removeCallbacks(tick)
	}

	companion object {
		const val HINT = 3
	}
}
