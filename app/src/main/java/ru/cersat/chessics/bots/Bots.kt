package ru.cersat.chessics.bots

import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.Outcome
import ru.cersat.chessics.chess.Rules
import kotlin.random.Random

// Перенос chess-bots.js (Google Drive, папка Claude) на Kotlin.

object Eval {
	// Таблицы из оригинала: индекс 0 = a8 ... 63 = h1 (сверху вниз с точки зрения белых)
	private val PST = arrayOf(
		IntArray(64),
		intArrayOf(
			0, 0, 0, 0, 0, 0, 0, 0,
			50, 50, 50, 50, 50, 50, 50, 50,
			10, 10, 20, 30, 30, 20, 10, 10,
			5, 5, 10, 25, 25, 10, 5, 5,
			0, 0, 0, 20, 20, 0, 0, 0,
			5, -5, -10, 0, 0, -10, -5, 5,
			5, 10, 10, -20, -20, 10, 10, 5,
			0, 0, 0, 0, 0, 0, 0, 0
		),
		intArrayOf(
			-50, -40, -30, -30, -30, -30, -40, -50,
			-40, -20, 0, 0, 0, 0, -20, -40,
			-30, 0, 10, 15, 15, 10, 0, -30,
			-30, 5, 15, 20, 20, 15, 5, -30,
			-30, 0, 15, 20, 20, 15, 0, -30,
			-30, 5, 10, 15, 15, 10, 5, -30,
			-40, -20, 0, 5, 5, 0, -20, -40,
			-50, -40, -30, -30, -30, -30, -40, -50
		),
		intArrayOf(
			-20, -10, -10, -10, -10, -10, -10, -20,
			-10, 0, 0, 0, 0, 0, 0, -10,
			-10, 0, 10, 10, 10, 10, 0, -10,
			-10, 5, 5, 10, 10, 5, 5, -10,
			-10, 0, 10, 10, 10, 10, 0, -10,
			-10, 10, 10, 10, 10, 10, 10, -10,
			-10, 5, 0, 0, 0, 0, 5, -10,
			-20, -10, -10, -10, -10, -10, -10, -20
		),
		intArrayOf(
			0, 0, 0, 0, 0, 0, 0, 0,
			5, 10, 10, 10, 10, 10, 10, 5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			-5, 0, 0, 0, 0, 0, 0, -5,
			0, 0, 0, 5, 5, 0, 0, 0
		),
		intArrayOf(
			-20, -10, -10, -5, -5, -10, -10, -20,
			-10, 0, 0, 0, 0, 0, 0, -10,
			-10, 0, 5, 5, 5, 5, 0, -10,
			-5, 0, 5, 5, 5, 5, 0, -5,
			0, 0, 5, 5, 5, 5, 0, -5,
			-10, 5, 5, 5, 5, 5, 0, -10,
			-10, 0, 5, 0, 0, 0, 0, -10,
			-20, -10, -10, -5, -5, -10, -10, -20
		),
		intArrayOf(
			-30, -40, -40, -50, -50, -40, -40, -30,
			-30, -40, -40, -50, -50, -40, -40, -30,
			-30, -40, -40, -50, -50, -40, -40, -30,
			-30, -40, -40, -50, -50, -40, -40, -30,
			-20, -30, -30, -40, -40, -30, -30, -20,
			-10, -20, -20, -20, -20, -20, -20, -10,
			20, 20, 0, 0, 0, 0, 20, 20,
			20, 30, 10, 0, 0, 10, 30, 20
		)
	)
	private val KING_END = intArrayOf(
		-50, -40, -30, -20, -20, -30, -40, -50,
		-30, -20, -10, 0, 0, -10, -20, -30,
		-30, -10, 20, 30, 30, 20, -10, -30,
		-30, -10, 30, 40, 40, 30, -10, -30,
		-30, -10, 30, 40, 40, 30, -10, -30,
		-30, -10, 20, 30, 30, 20, -10, -30,
		-30, -30, 0, 0, 0, 0, -30, -30,
		-50, -30, -30, -30, -30, -30, -30, -50
	)
	private val PHASE = intArrayOf(0, 0, 1, 1, 2, 4, 0)

	private fun idx(s: Int) = (7 - P.rank(s)) * 8 + P.file(s)

	// evaluateBoard: полная позиционная оценка, сантипешки, + в пользу белых. Формула полосы преимущества.
	fun evaluate(pos: Position): Int {
		val st = Rules.status(pos)
		when (st.outcome) {
			Outcome.WHITE_WINS -> return 100000
			Outcome.BLACK_WINS -> return -100000
			Outcome.DRAW -> return 0
			else -> {}
		}
		val pawnsByFile = arrayOf(IntArray(8), IntArray(8))
		var phase = 0
		val bishops = IntArray(2)
		for (s in 0 until 64) {
			val p = pos.sq[s]
			if (p == 0) continue
			if (P.type(p) == P.PAWN) pawnsByFile[P.color(p)][P.file(s)]++
			if (P.type(p) == P.BISHOP) bishops[P.color(p)]++
			phase += PHASE[P.type(p)]
		}
		phase = minOf(phase, 24)
		val endW = (24 - phase) / 24.0
		var score = 0.0
		for (color in 0..1) {
			val sign = if (color == P.WHITE) 1 else -1
			val enemy = color xor 1
			for (s in 0 until 64) {
				val p = pos.sq[s]
				if (p == 0 || P.color(p) != color) continue
				val t = P.type(p)
				val i = idx(s)
				val pi = if (color == P.WHITE) i else 63 - i
				val place = if (t == P.KING) PST[P.KING][pi] * (1 - endW) + KING_END[pi] * endW else PST[t][pi].toDouble()
				score += sign * (P.VALUE[t] + place)
			}
			if (bishops[color] >= 2) score += sign * 30
			val my = pawnsByFile[color]
			val their = pawnsByFile[enemy]
			for (f in 0..7) {
				if (my[f] > 1) score += sign * -12 * (my[f] - 1)
				if (my[f] > 0) {
					val nb = (f > 0 && my[f - 1] > 0) || (f < 7 && my[f + 1] > 0)
					if (!nb) score += sign * -15
				}
			}
			for (s in 0 until 64) {
				if (pos.sq[s] != P.make(color, P.PAWN)) continue
				val f = P.file(s); val r = P.rank(s)
				var blocked = false
				for (ff in maxOf(0, f - 1)..minOf(7, f + 1)) {
					if (color == P.WHITE) { for (rr in r + 1..7) if (pos.sq[P.sq(ff, rr)] == P.make(enemy, P.PAWN)) blocked = true }
					else { for (rr in r - 1 downTo 0) if (pos.sq[P.sq(ff, rr)] == P.make(enemy, P.PAWN)) blocked = true }
				}
				if (!blocked) {
					val adv = if (color == P.WHITE) r else 7 - r
					score += sign * (12 + adv * adv * 2)
				}
			}
			for (s in 0 until 64) {
				if (pos.sq[s] != P.make(color, P.ROOK)) continue
				val f = P.file(s)
				if (my[f] == 0 && their[f] == 0) score += sign * 18
				else if (my[f] == 0) score += sign * 9
			}
			if (endW < 0.7) {
				val kf = P.file(pos.king[color])
				if (kf <= 2 || kf >= 5) {
					var shield = 0
					for (ff in maxOf(0, kf - 1)..minOf(7, kf + 1)) if (my[ff] > 0) shield++
					score += sign * (shield - 3) * 10 * (1 - endW)
				}
			}
		}
		val mobility = pos.legalMoves().size
		score += (if (pos.side == P.WHITE) 1 else -1) * mobility * 3
		return score.toInt()
	}
}

class TimeUp : RuntimeException() {
	override fun fillInStackTrace(): Throwable = this
}

interface Bot {
	// pos — личная копия позиции, plyCount — сколько полуходов уже сыграно в партии
	fun pick(pos: Position, plyCount: Int, cancel: () -> Boolean): Int
}

object RandomBot : Bot {
	override fun pick(pos: Position, plyCount: Int, cancel: () -> Boolean): Int {
		val l = pos.legalMoves()
		return if (l.isEmpty()) 0 else l[Random.nextInt(l.size)]
	}
}

// Бот «Материал»: минимакс + альфа-бета, TT, итеративное углубление, киллеры, лимит ~2.5с. Оценка — только материал.
class MaterialBot(private val depth: Int) : Bot {
	private class Entry(val depth: Int, val score: Int, val flag: Int, val move: Int)

	private val tt = HashMap<Long, Entry>()
	private var nodes = 0
	private var deadline = 0L
	private var cancel: () -> Boolean = { false }
	private val killers = Array(64) { IntArray(2) }

	private fun materialOnly(pos: Position, ply: Int, hasMoves: Boolean): Int {
		if (!hasMoves) {
			if (pos.inCheck()) return if (pos.side == P.WHITE) -100000 + ply else 100000 - ply
			return 0
		}
		if (pos.half >= 100 || pos.insufficientMaterial() || pos.repetitions() >= 3) return 0
		var s = 0
		for (p in pos.sq) if (p != 0) s += if (P.color(p) == P.WHITE) P.VALUE[P.type(p)] else -P.VALUE[P.type(p)]
		return s
	}

	private fun orderScore(pos: Position, m: Int, ttMove: Int, k: IntArray?): Int {
		if (m == ttMove) return 1000000
		val cap = pos.capturedBy(m)
		if (cap != 0) return 100000 + P.VALUE[P.type(cap)] * 16 - P.VALUE[P.type(pos.sq[M.from(m)])]
		if (k != null) {
			if (m == k[0]) return 5000
			if (m == k[1]) return 4000
		}
		return 0
	}

	private fun ordered(pos: Position, moves: IntArray, ttMove: Int, k: IntArray?): IntArray {
		val sc = IntArray(moves.size) { orderScore(pos, moves[it], ttMove, k) }
		val idx = moves.indices.sortedByDescending { sc[it] }
		return IntArray(moves.size) { moves[idx[it]] }
	}

	private fun search(pos: Position, depth: Int, a0: Int, b0: Int, maximizing: Boolean, ply: Int): Int {
		var alpha = a0; var beta = b0
		nodes++
		if (nodes and 511 == 0 && (System.currentTimeMillis() > deadline || cancel())) throw TimeUp()
		val key = pos.hash xor (depth.toLong() * -0x61c8864680b583ebL)
		val e = tt[key]
		var ttMove = 0
		if (e != null) {
			ttMove = e.move
			if (e.depth >= depth) {
				if (e.flag == 0) return e.score
				if (e.flag == 1) alpha = maxOf(alpha, e.score) else beta = minOf(beta, e.score)
				if (alpha >= beta) return e.score
			}
		}
		val moves = pos.legalMoves()
		if (depth == 0 || moves.isEmpty() || pos.half >= 100 || pos.repetitions() >= 3 || pos.insufficientMaterial())
			return materialOnly(pos, ply, moves.isNotEmpty())
		val k = if (depth < killers.size) killers[depth] else null
		var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
		var bestMove = 0
		for (m in ordered(pos, moves, ttMove, k)) {
			val quiet = pos.capturedBy(m) == 0
			pos.make(m)
			val s: Int
			try { s = search(pos, depth - 1, alpha, beta, !maximizing, ply + 1) } finally { pos.unmake() }
			if (maximizing) { if (s > best) { best = s; bestMove = m }; alpha = maxOf(alpha, best) }
			else { if (s < best) { best = s; bestMove = m }; beta = minOf(beta, best) }
			if (beta <= alpha) {
				if (quiet && k != null && k[0] != m) { k[1] = k[0]; k[0] = m }
				break
			}
		}
		val flag = if (best <= a0) 2 else if (best >= b0) 1 else 0
		tt[key] = Entry(depth, best, flag, bestMove)
		if (tt.size > 300000) tt.clear()
		return best
	}

	override fun pick(pos: Position, plyCount: Int, cancel: () -> Boolean): Int {
		val root = pos.legalMoves()
		if (root.isEmpty()) return 0
		this.cancel = cancel
		val maximizing = pos.side == P.WHITE
		deadline = System.currentTimeMillis() + 2500
		nodes = 0
		for (k in killers) { k[0] = 0; k[1] = 0 }
		var pv = 0
		var lastComplete = root[0]
		for (d in 1..depth) {
			var alpha = Int.MIN_VALUE + 1; var beta = Int.MAX_VALUE - 1
			var iterBest = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
			var iterMove = 0
			var timedOut = false
			try {
				for (m in ordered(pos, root, pv, null)) {
					pos.make(m)
					val s: Int
					try { s = search(pos, d - 1, alpha, beta, !maximizing, 1) } finally { pos.unmake() }
					if (maximizing) { if (s > iterBest) { iterBest = s; iterMove = m }; alpha = maxOf(alpha, iterBest) }
					else { if (s < iterBest) { iterBest = s; iterMove = m }; beta = minOf(beta, iterBest) }
				}
			} catch (e: TimeUp) { timedOut = true }
			if (timedOut || cancel()) break
			pv = iterMove; lastComplete = iterMove
			if (System.currentTimeMillis() >= deadline) break
		}
		return lastComplete
	}
}

// Бот «Алгоритм»: не минимакс, а правила. Уровень 1..5. Позиционную оценку (полосу) НЕ использует.
class AlgoBot(level0: Int) : Bot {
	private class Cfg(val label: String, val blunder: Double, val margin: Int, val hangingScan: Boolean, val tactics: Boolean)

	private val level = if (level0 in 1..5) level0 else 3
	private val cfg = LEVELS[level - 1]

	private fun attackSquares(pos: Position, s: Int): IntArray {
		val p = pos.sq[s]
		if (p == 0) return IntArray(0)
		val out = IntArray(32)
		var n = 0
		val f = P.file(s); val r = P.rank(s)
		fun add(ff: Int, rr: Int) { if (ff in 0..7 && rr in 0..7) out[n++] = P.sq(ff, rr) }
		when (P.type(p)) {
			P.PAWN -> { val dr = if (P.color(p) == P.WHITE) 1 else -1; add(f - 1, r + dr); add(f + 1, r + dr) }
			P.KNIGHT -> for (d in Position.KNIGHT) add(f + d[0], r + d[1])
			P.KING -> for (d in Position.KING) add(f + d[0], r + d[1])
			else -> {
				val dirs = when (P.type(p)) { P.BISHOP -> Position.DIAG; P.ROOK -> Position.ORTHO; else -> Position.DIAG + Position.ORTHO }
				for (d in dirs) {
					var ff = f + d[0]; var rr = r + d[1]
					while (ff in 0..7 && rr in 0..7) {
						out[n++] = P.sq(ff, rr)
						if (pos.sq[P.sq(ff, rr)] != 0) break
						ff += d[0]; rr += d[1]
					}
				}
			}
		}
		return out.copyOf(n)
	}

	private fun attackValueMap(pos: Position, color: Int): Array<IntArray> {
		val lists = Array(64) { ArrayList<Int>(2) }
		for (s in 0 until 64) {
			val p = pos.sq[s]
			if (p == 0 || P.color(p) != color) continue
			for (t in attackSquares(pos, s)) lists[t].add(P.VALUE[P.type(p)])
		}
		return Array(64) { lists[it].sorted().toIntArray() }
	}

	// SEE: x-ray не учитываем (как в оригинале)
	private fun see(target: Int, toMove: Int, lists: Array<IntArray>): Int {
		val list = lists[toMove]
		if (list.isEmpty()) return 0
		val cap = list[0]
		val rest = arrayOf(lists[0], lists[1])
		rest[toMove] = list.copyOfRange(1, list.size)
		val gain = target - maxOf(0, see(cap, toMove xor 1, rest))
		return maxOf(0, gain)
	}

	private fun squareLoss(pos: Position, s: Int, owner: Int, att: Array<IntArray>, def: Array<IntArray>): Int {
		val p = pos.sq[s]
		if (p == 0 || P.type(p) == P.KING) return 0
		if (att[s].isEmpty()) return 0
		val lists = arrayOf(IntArray(0), IntArray(0))
		lists[owner] = def[s]
		lists[owner xor 1] = att[s]
		return see(P.VALUE[P.type(p)], owner xor 1, lists)
	}

	private fun hangingLoss(pos: Position, s: Int, owner: Int) =
		squareLoss(pos, s, owner, attackValueMap(pos, owner xor 1), attackValueMap(pos, owner))

	private fun hangingScan(pos: Position, color: Int): Int {
		val att = attackValueMap(pos, color xor 1)
		val def = attackValueMap(pos, color)
		var total = 0
		for (s in 0 until 64) {
			val p = pos.sq[s]
			if (p == 0 || P.color(p) != color || P.type(p) == P.KING) continue
			total += squareLoss(pos, s, color, att, def)
		}
		return total
	}

	private fun tactical(pos: Position, to: Int, mover: Int): Double {
		val targets = attackSquares(pos, to).filter {
			val p = pos.sq[it]
			p != 0 && P.color(p) != mover && P.VALUE[P.type(p)] >= 300
		}
		if (targets.size >= 2) {
			val v = targets.map { P.VALUE[P.type(pos.sq[it])] }.sorted()
			return 25 + v[0] * 0.05 + v[1] * 0.05
		}
		if (targets.size == 1 && hangingLoss(pos, targets[0], mover xor 1) > 0) return 10.0
		return 0.0
	}

	private fun isMate(pos: Position) = pos.inCheck() && !pos.hasLegalMove()

	private fun safestCheckResponse(pos: Position, pool: List<Int>, mover: Int): Int {
		var best = 0
		var bestScore = Double.NEGATIVE_INFINITY
		for (m in pool) {
			val cap = pos.capturedBy(m)
			pos.make(m)
			var s = if (cap != 0) P.VALUE[P.type(cap)].toDouble() else 0.0
			if (isMate(pos)) s += 100000
			if (cfg.hangingScan) s -= hangingScan(pos, mover)
			pos.unmake()
			if (s > bestScore) { bestScore = s; best = m }
		}
		return if (best != 0) best else pool[0]
	}

	private fun scoreQuiet(pos: Position, m: Int, mover: Int, plyCount: Int): Double {
		val cap = pos.capturedBy(m)
		val piece = P.type(pos.sq[M.from(m)])
		pos.make(m)
		var score = 0.0
		if (cap != 0) score += P.VALUE[P.type(cap)]
		if (isMate(pos)) score += 100000
		else if (pos.inCheck()) score += 25
		score -= if (cfg.hangingScan) hangingScan(pos, mover).toDouble() else hangingLoss(pos, M.to(m), mover) * 0.9
		if (cfg.tactics) score += tactical(pos, M.to(m), mover)
		val moveNum = plyCount + 1
		val backRank = if (mover == P.WHITE) 0 else 7
		if (moveNum <= 10 && (piece == P.KNIGHT || piece == P.BISHOP) && P.rank(M.from(m)) == backRank) score += 12
		if (M.isCastle(m)) score += 30
		if (piece == P.KING && !M.isCastle(m)) score -= 15
		pos.unmake()
		return score
	}

	override fun pick(pos: Position, plyCount: Int, cancel: () -> Boolean): Int {
		val moves = pos.legalMoves()
		if (moves.isEmpty()) return 0
		val mover = pos.side
		if (cfg.blunder > 0 && Random.nextDouble() < cfg.blunder) return moves[Random.nextInt(moves.size)]
		if (pos.inCheck()) {
			val caps = moves.filter { pos.capturedBy(it) != 0 }
			val blocks = moves.filter { P.type(pos.sq[M.from(it)]) != P.KING && pos.capturedBy(it) == 0 }
			val kings = moves.filter { P.type(pos.sq[M.from(it)]) == P.KING }
			val pool = if (caps.isNotEmpty()) caps else if (blocks.isNotEmpty()) blocks else kings
			return safestCheckResponse(pos, pool, mover)
		}
		val scored = moves.map { it to scoreQuiet(pos, it, mover, plyCount) }.sortedByDescending { it.second }
		val top = scored.filter { it.second >= scored[0].second - cfg.margin }
		return top[Random.nextInt(top.size)].first
	}

	companion object {
		private val LEVELS = arrayOf(
			Cfg("Новичок", 0.35, 60, false, false),
			Cfg("Любитель", 0.18, 35, false, false),
			Cfg("Крепкий", 0.06, 18, true, false),
			Cfg("Сильный", 0.0, 10, true, true),
			Cfg("Мастер", 0.0, 4, true, true)
		)
		val LABELS = arrayOf("Новичок", "Любитель", "Крепкий", "Сильный", "Мастер")
	}
}
