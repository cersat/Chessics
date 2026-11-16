package ru.cersat.chessics.bots

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.cersat.chessics.chess.GameRecord
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.Outcome
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.Rules
import kotlin.math.exp

// Тип хода, как в разборе партий
enum class MoveClass(val symbol: String, val title: String, val color: Int) {
	BRILLIANT("!!", "бриллиантовый ход", 0xFF1BC6C9.toInt()),
	GREAT("!", "отличный ход", 0xFF4D8BF5.toInt()),
	BEST("★", "лучший ход", 0xFF5FB83A.toInt()),
	EXCELLENT("👍", "очень хороший ход", 0xFF86C34A.toInt()),
	GOOD("✓", "хороший ход", 0xFF8FAE7A.toInt()),
	FORCED("□", "единственный ход", 0xFF8A8F98.toInt()),
	INACCURACY("?!", "неточность", 0xFFF2C230.toInt()),
	MISTAKE("?", "ошибка", 0xFFF0892B.toInt()),
	BLUNDER("??", "зевок", 0xFFE5383B.toInt());
}

object Judge {
	fun winPct(cp: Int): Double = 50 + 50 * (2 / (1 + exp(-0.00368208 * cp.coerceIn(-1500, 1500))) - 1)

	// Сколько материала сторона отдаёт этим ходом (с учётом того, что взяла сама)
	fun sacrifice(before: Position, m: Int): Int {
		val p = before.copy()
		val mover = p.side
		val cap = p.capturedBy(m)
		val movedType = P.type(p.sq[M.from(m)])
		if (movedType == P.KING) return 0
		p.make(m)
		if (Tactics.isMate(p)) return 0
		val loss = Tactics.maxLoss(p, mover)
		return loss - (if (cap != 0) P.VALUE[P.type(cap)] else 0)
	}

	// scores — с точки зрения белых. best/second — лучшая и вторая линии позиции до хода, after — оценка после
	fun classify(before: Position, m: Int, best: Engine.Line?, second: Engine.Line?, after: Int): MoveClass {
		if (before.legalMoves().size == 1) return MoveClass.FORCED
		val sign = if (before.side == P.WHITE) 1 else -1
		val bestScore = best?.score ?: after
		val wb = winPct(bestScore * sign)
		val wa = winPct(after * sign)
		val loss = maxOf(0.0, wb - wa)
		val isBest = best != null && best.move == M.uci(m)
		if ((isBest || loss < 2) && wa >= 45 && wb < 97 && sacrifice(before, m) >= 200) return MoveClass.BRILLIANT
		if (isBest && second != null) {
			val gap = wb - winPct(second.score * sign)
			if (gap >= 15 && wb < 97) return MoveClass.GREAT
		}
		return when {
			isBest || loss < 1 -> MoveClass.BEST
			loss < 3 -> MoveClass.EXCELLENT
			loss < 10 -> MoveClass.GOOD
			loss < 20 -> MoveClass.INACCURACY
			loss < 30 -> MoveClass.MISTAKE
			else -> MoveClass.BLUNDER
		}
	}

	// Мгновенная оценка одного хода во время партии (для анимации бриллианта)
	fun judgeLive(ctx: Context, startFen: String, uciBefore: String, before: Position, m: Int, variant: Int, done: (MoveClass?) -> Unit) {
		val main = Handler(Looper.getMainLooper())
		val e = Stockfish.analysis(variant)
		e.exec.execute {
			val lines = e.analyse(ctx, startFen, uciBefore, before.side == P.WHITE, "movetime 350", 2, variant)
			val after = before.copy().also { it.make(m) }
			val st = Rules.status(after)
			val afterScore = when (st.outcome) {
				Outcome.WHITE_WINS -> 10000
				Outcome.BLACK_WINS -> -10000
				Outcome.DRAW -> 0
				else -> e.analyse(ctx, startFen, (uciBefore + " " + M.uci(m)).trim(), after.side == P.WHITE, "movetime 350", 1, variant)?.firstOrNull()?.score
			}
			val cls = if (lines == null || afterScore == null) null else classify(before, m, lines.getOrNull(0), lines.getOrNull(1), afterScore)
			main.post { done(cls) }
		}
	}
}

// Разбор партии в отдельном процессе и потоке; результат кэшируется, чтобы можно было уйти с экрана
object Analyzer {
	// count — сколько позиций уже посчитано (с начала партии). Пока count < evals.size, результат частичный:
	// оценки и типы ходов для первых ходов уже можно смотреть.
	class Result(val evals: IntArray, val best: Array<String>, val classes: Array<MoveClass?>, val count: Int) {
		val complete get() = count >= evals.size
		fun hasEval(i: Int) = i < count
		fun hasClass(i: Int) = i + 1 < count
	}

	private val cache = HashMap<String, Result>()
	private val partial = HashMap<String, Result>()
	private val progress = HashMap<String, Int>()
	private val listeners = HashMap<String, MutableList<() -> Unit>>()
	private val failed = HashMap<String, String>()
	private val main = Handler(Looper.getMainLooper())

	fun key(startFen: String, uci: String, variant: Int = 0) = "$variant|$startFen|$uci"
	fun result(k: String) = cache[k] ?: partial[k]
	fun progress(k: String) = progress[k]
	fun error(k: String) = failed[k]

	fun listen(k: String, l: () -> Unit) { listeners.getOrPut(k) { ArrayList() }.add(l) }
	fun unlisten(k: String, l: () -> Unit) { listeners[k]?.remove(l) }
	private fun notify(k: String) = main.post { listeners[k]?.toList()?.forEach { it() } }

	fun start(ctx: Context, startFen: String, uci: String, variant: Int = 0) {
		val k = key(startFen, uci, variant)
		if (cache.containsKey(k) || progress.containsKey(k)) return
		failed.remove(k)
		progress[k] = 0
		val app = ctx.applicationContext
		val e = Stockfish.analysis(variant)
		e.exec.execute {
			val game = GameRecord.fromUci(startFen, uci, variant)
			val n = game.moves.size
			val uciList = game.moves.map { M.uci(it) }
			val evals = IntArray(n + 1)
			val best = Array(n + 1) { "" }
			val lines = arrayOfNulls<List<Engine.Line>>(n + 1)
			val classes = arrayOfNulls<MoveClass>(n)
			for (i in 0..n) {
				val p = game.positionAt(i)
				val st = Rules.status(p)
				if (st.outcome != Outcome.NONE) {
					evals[i] = when (st.outcome) { Outcome.WHITE_WINS -> 10000; Outcome.BLACK_WINS -> -10000; else -> 0 }
				} else {
					val an = e.analyse(app, startFen, uciList.take(i).joinToString(" "), p.side == P.WHITE, "movetime 250", 2, variant)
					if (an == null || an.isEmpty()) {
						main.post { progress.remove(k); partial.remove(k); failed[k] = e.lastError ?: "Stockfish не ответил" }
						notify(k)
						return@execute
					}
					lines[i] = an
					evals[i] = an[0].score
					best[i] = an[0].move
				}
				// тип предыдущего хода известен, как только посчитана позиция после него
				if (i > 0) classes[i - 1] = Judge.classify(game.positionAt(i - 1), game.moves[i - 1], lines[i - 1]?.getOrNull(0), lines[i - 1]?.getOrNull(1), evals[i])
				val snap = Result(evals.copyOf(), best.copyOf(), classes.copyOf(), i + 1)
				main.post {
					progress[k] = i + 1
					if (snap.complete) { cache[k] = snap; partial.remove(k); progress.remove(k) } else partial[k] = snap
				}
				notify(k)
			}
		}
	}
}

// Отслеживание тактики человека: засчитывается, когда приём доведён до выигрыша материала
class TacticsTracker(private val human: Int) {
	private class Fork(val sq: Int, val piece: Int, val targets: Set<Int>)
	private class Skewer(val sq: Int, val piece: Int, val back: Int)

	private var forks = ArrayList<Fork>()
	private var skewers = ArrayList<Skewer>()
	private var trapAge = -1 // сколько ходов человека назад поймали ферзя (−1 — не ловили)

	// before — позиция до хода человека. Возвращает реализованные приёмы: "fork", "skewer", "queenTrap"
	fun onHumanMove(before: Position, m: Int): List<String> {
		val out = ArrayList<String>()
		val from = M.from(m); val to = M.to(m)
		val cap = before.capturedBy(m)
		val mover = before.sq[from]
		if (cap != 0) {
			if (forks.any { it.sq == from && it.piece == mover && to in it.targets } && P.type(cap) != P.PAWN) out.add("fork")
			if (skewers.any { it.sq == from && it.piece == mover && it.back == to }) out.add("skewer")
			if (trapAge in 0..1 && P.type(cap) == P.QUEEN && P.type(mover) != P.QUEEN) out.add("queenTrap")
		}
		if (trapAge >= 0) trapAge++
		if (trapAge > 2 || out.contains("queenTrap")) trapAge = -1
		forks = ArrayList(); skewers = ArrayList()

		val after = before.copy().also { it.make(m) }
		if (Tactics.isMate(after)) return out
		val piece = after.sq[to]
		val enemy = human xor 1
		// вилка
		val targets = Tactics.attackSquares(after, to).filter {
			val q = after.sq[it]
			q != 0 && P.color(q) == enemy && P.type(q) != P.PAWN &&
				(P.type(q) == P.KING || P.VALUE[P.type(q)] > P.VALUE[P.type(piece)] || Tactics.squareLoss(after, it, enemy) > 0)
		}.toSet()
		if (targets.size >= 2 && Tactics.squareLoss(after, to, human) == 0) forks.add(Fork(to, piece, targets - setOf(after.king[enemy])))
		// шампур
		val t = P.type(piece)
		if (t == P.BISHOP || t == P.ROOK || t == P.QUEEN) {
			for (d in Tactics.dirs(t)) {
				var f = P.file(to) + d[0]; var r = P.rank(to) + d[1]
				var front = -1
				while (f in 0..7 && r in 0..7) {
					val s = P.sq(f, r)
					val q = after.sq[s]
					if (q != 0) {
						if (P.color(q) != enemy) break
						if (front < 0) front = s
						else {
							val fv = if (P.type(after.sq[front]) == P.KING) 20000 else P.VALUE[P.type(after.sq[front])]
							val bv = P.VALUE[P.type(q)]
							if (fv > bv && bv >= 300) skewers.add(Skewer(to, piece, s))
							break
						}
					}
					f += d[0]; r += d[1]
				}
			}
		}
		// ловушка ферзя: ферзь атакован, и ни на месте, ни после любого его хода он не спасается
		if (trapAge < 0) {
			val q = (0 until 64).firstOrNull { after.sq[it] == P.make(enemy, P.QUEEN) }
			if (q != null && Tactics.attackMap(after, human)[q].isNotEmpty() && Tactics.squareLoss(after, q, enemy) >= 600) {
				var escapes = false
				for (qm in after.legalMoves()) {
					if (M.from(qm) != q) continue
					after.make(qm)
					val loss = Tactics.squareLoss(after, M.to(qm), enemy)
					after.unmake()
					if (loss < 600) { escapes = true; break }
				}
				if (!escapes) trapAge = 0
			}
		}
		return out
	}
}
