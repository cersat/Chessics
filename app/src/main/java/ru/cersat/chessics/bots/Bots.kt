package ru.cersat.chessics.bots

import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.Outcome
import ru.cersat.chessics.chess.Rules
import kotlin.random.Random

// Перенос chess-bots.js (Google Drive, папка Claude) на Kotlin, с доработками.

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

	// evaluateBoard из chess-bots.js. Запасная оценка для полосы, если Stockfish не запустился.
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

// Атаки, размены (SEE), висящие фигуры — общее для «Алгоритма», анализа и ачивок
object Tactics {
	fun attackSquares(pos: Position, s: Int): IntArray {
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
				for (d in dirs(P.type(p))) {
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

	fun dirs(type: Int): Array<IntArray> = when (type) {
		P.BISHOP -> Position.DIAG
		P.ROOK -> Position.ORTHO
		else -> Position.DIAG + Position.ORTHO
	}

	// для каждой клетки — отсортированные стоимости атакующих фигур цвета color
	fun attackMap(pos: Position, color: Int): Array<IntArray> {
		val lists = Array(64) { ArrayList<Int>(2) }
		for (s in 0 until 64) {
			val p = pos.sq[s]
			if (p == 0 || P.color(p) != color) continue
			val v = if (P.type(p) == P.KING) 20000 else P.VALUE[P.type(p)]
			for (t in attackSquares(pos, s)) lists[t].add(v)
		}
		return Array(64) { lists[it].sorted().toIntArray() }
	}

	// SEE без x-ray (как в оригинале)
	fun see(target: Int, toMove: Int, lists: Array<IntArray>): Int {
		val list = lists[toMove]
		if (list.isEmpty()) return 0
		val cap = list[0]
		val rest = arrayOf(lists[0], lists[1])
		rest[toMove] = list.copyOfRange(1, list.size)
		// королём можно бить только туда, где больше никто не защищает
		if (cap >= 20000 && rest[toMove xor 1].isNotEmpty()) return 0
		val gain = target - maxOf(0, see(cap, toMove xor 1, rest))
		return maxOf(0, gain)
	}

	fun squareLoss(pos: Position, s: Int, owner: Int, att: Array<IntArray>, def: Array<IntArray>): Int {
		val p = pos.sq[s]
		if (p == 0 || P.type(p) == P.KING) return 0
		if (att[s].isEmpty()) return 0
		val lists = arrayOf(IntArray(0), IntArray(0))
		lists[owner] = def[s]
		lists[owner xor 1] = att[s]
		return see(P.VALUE[P.type(p)], owner xor 1, lists)
	}

	fun squareLoss(pos: Position, s: Int, owner: Int) =
		squareLoss(pos, s, owner, attackMap(pos, owner xor 1), attackMap(pos, owner))

	// сумма потерь по всем фигурам цвета color
	fun hangingScan(pos: Position, color: Int): Int {
		val att = attackMap(pos, color xor 1)
		val def = attackMap(pos, color)
		var total = 0
		for (s in 0 until 64) {
			val p = pos.sq[s]
			if (p == 0 || P.color(p) != color || P.type(p) == P.KING) continue
			total += squareLoss(pos, s, color, att, def)
		}
		return total
	}

	// самая крупная потеря одной фигуры (соперник за ход может взять только одну)
	fun maxLoss(pos: Position, color: Int): Int {
		val att = attackMap(pos, color xor 1)
		val def = attackMap(pos, color)
		var best = 0
		for (s in 0 until 64) {
			val p = pos.sq[s]
			if (p == 0 || P.color(p) != color || P.type(p) == P.KING) continue
			best = maxOf(best, squareLoss(pos, s, color, att, def))
		}
		return best
	}

	// мат или победа по правилу варианта (король в центре, третий шах) для только что походившей стороны
	fun isMate(pos: Position) = pos.variantWinner() == (pos.side xor 1) || (pos.inCheck() && !pos.hasLegalMove())

	fun hasMateInOne(pos: Position): Boolean {
		for (m in pos.legalMoves()) {
			pos.make(m)
			val mate = isMate(pos)
			pos.unmake()
			if (mate) return true
		}
		return false
	}

	fun material(pos: Position, color: Int): Int {
		var s = 0
		for (p in pos.sq) if (p != 0 && P.color(p) == color) s += P.VALUE[P.type(p)]
		return s
	}
}

// Бот «Материал»: минимакс с альфа-бетой, TT, итеративное углубление, киллеры, лимит ~2.5с.
// Оценка — только материал. Добавлено: форсированный счёт взятий в конце (quiescence),
// продление на шахах, быстрая сортировка ходов, предпочтение более быстрого мата.
class MaterialBot(private val depth: Int) : Bot {
	private class Entry(val depth: Int, val score: Int, val flag: Int, val move: Int)

	private val tt = HashMap<Long, Entry>()
	private var nodes = 0
	private var deadline = 0L
	private var cancel: () -> Boolean = { false }
	private val killers = Array(128) { IntArray(2) }
	private val buf = Array(128) { IntArray(256) }
	private val scores = Array(128) { IntArray(256) }

	private fun material(pos: Position): Int {
		var s = 0
		for (p in pos.sq) if (p != 0) s += if (P.color(p) == P.WHITE) P.VALUE[P.type(p)] else -P.VALUE[P.type(p)]
		return s
	}

	private fun checkTime() {
		nodes++
		if (nodes and 1023 == 0 && (System.currentTimeMillis() > deadline || cancel())) throw TimeUp()
	}

	// легальные ходы в buf[ply], отсортированные по убыванию оценки; возвращает количество
	private fun gen(pos: Position, ply: Int, ttMove: Int, capturesOnly: Boolean): Int {
		val raw = buf[ply]
		val n0 = pos.pseudoMoves(raw, capturesOnly)
		val sc = scores[ply]
		var n = 0
		val k = killers[ply]
		for (i in 0 until n0) {
			val m = raw[i]
			pos.make(m)
			val ok = !pos.inCheck(pos.side xor 1)
			pos.unmake()
			if (!ok) continue
			raw[n] = m
			val cap = pos.capturedBy(m)
			sc[n] = when {
				m == ttMove -> 1_000_000
				cap != 0 -> 100_000 + P.VALUE[P.type(cap)] * 16 - P.VALUE[P.type(pos.sq[M.from(m)])]
				M.promo(m) != 0 -> 90_000
				m == k[0] -> 5000
				m == k[1] -> 4000
				else -> 0
			}
			n++
		}
		// сортировка вставками (ходов мало)
		for (i in 1 until n) {
			val m = raw[i]; val s = sc[i]
			var j = i - 1
			while (j >= 0 && sc[j] < s) { raw[j + 1] = raw[j]; sc[j + 1] = sc[j]; j-- }
			raw[j + 1] = m; sc[j + 1] = s
		}
		return n
	}

	private fun isDraw(pos: Position) = pos.half >= 100 || pos.repetitions() >= 2 || pos.insufficientMaterial()

	// форсированный счёт взятий: «можно не брать» (stand pat), иначе — только взятия
	private fun variantScore(pos: Position, ply: Int): Int? {
		val w = pos.variantWinner()
		if (w < 0) return null
		return if (w == P.WHITE) 100000 - ply else -100000 + ply
	}

	private fun quiesce(pos: Position, a0: Int, b0: Int, maximizing: Boolean, ply: Int): Int {
		checkTime()
		variantScore(pos, ply)?.let { return it }
		var alpha = a0; var beta = b0
		if (ply >= 120) return material(pos)
		val inCheck = pos.inCheck()
		val n = gen(pos, ply, 0, !inCheck)
		if (inCheck && n == 0) return if (pos.side == P.WHITE) -100000 + ply else 100000 - ply
		val stand = material(pos)
		if (!inCheck) {
			if (maximizing) { if (stand >= beta) return stand; alpha = maxOf(alpha, stand) }
			else { if (stand <= alpha) return stand; beta = minOf(beta, stand) }
		}
		var best = if (inCheck) (if (maximizing) Int.MIN_VALUE + 1 else Int.MAX_VALUE - 1) else stand
		val moves = buf[ply].copyOf(n)
		for (m in moves) {
			pos.make(m)
			val s: Int
			try { s = quiesce(pos, alpha, beta, !maximizing, ply + 1) } finally { pos.unmake() }
			if (maximizing) { if (s > best) best = s; alpha = maxOf(alpha, s) }
			else { if (s < best) best = s; beta = minOf(beta, s) }
			if (beta <= alpha) break
		}
		return best
	}

	private fun search(pos: Position, depth0: Int, a0: Int, b0: Int, maximizing: Boolean, ply: Int): Int {
		checkTime()
		var alpha = a0; var beta = b0
		variantScore(pos, ply)?.let { return it }
		if (ply > 0 && isDraw(pos)) return 0
		val inCheck = pos.inCheck()
		val depth = if (inCheck && ply < 40) depth0 + 1 else depth0
		if (depth <= 0) return quiesce(pos, alpha, beta, maximizing, ply)
		val e = tt[pos.hash]
		var ttMove = 0
		if (e != null) {
			ttMove = e.move
			if (e.depth >= depth && ply > 0) {
				if (e.flag == 0) return e.score
				if (e.flag == 1) alpha = maxOf(alpha, e.score) else beta = minOf(beta, e.score)
				if (alpha >= beta) return e.score
			}
		}
		val n = gen(pos, ply, ttMove, false)
		if (n == 0) return if (inCheck) (if (pos.side == P.WHITE) -100000 + ply else 100000 - ply) else 0
		val moves = buf[ply].copyOf(n)
		var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
		var bestMove = 0
		for (m in moves) {
			val quiet = pos.capturedBy(m) == 0
			pos.make(m)
			val s: Int
			try { s = search(pos, depth - 1, alpha, beta, !maximizing, ply + 1) } finally { pos.unmake() }
			if (maximizing) { if (s > best) { best = s; bestMove = m }; alpha = maxOf(alpha, best) }
			else { if (s < best) { best = s; bestMove = m }; beta = minOf(beta, best) }
			if (beta <= alpha) {
				val k = killers[ply]
				if (quiet && k[0] != m) { k[1] = k[0]; k[0] = m }
				break
			}
		}
		val flag = if (best <= a0) 2 else if (best >= b0) 1 else 0
		val old = tt[pos.hash]
		if (old == null || old.depth <= depth) tt[pos.hash] = Entry(depth, best, flag, bestMove)
		if (tt.size > 400000) tt.clear()
		return best
	}

	override fun pick(pos: Position, plyCount: Int, cancel: () -> Boolean): Int {
		val root = pos.legalMoves()
		if (root.isEmpty()) return 0
		if (root.size == 1) return root[0]
		this.cancel = cancel
		val maximizing = pos.side == P.WHITE
		deadline = System.currentTimeMillis() + 2500
		nodes = 0
		for (k in killers) { k[0] = 0; k[1] = 0 }
		var order = root.toList()
		var lastComplete = root[0]
		for (d in 1..depth) {
			var alpha = Int.MIN_VALUE + 1; var beta = Int.MAX_VALUE - 1
			var iterBest = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
			var iterMove = 0
			val results = ArrayList<Pair<Int, Int>>()
			var timedOut = false
			try {
				for (m in order) {
					pos.make(m)
					val s: Int
					try { s = search(pos, d - 1, alpha, beta, !maximizing, 1) } finally { pos.unmake() }
					results.add(m to s)
					if (maximizing) { if (s > iterBest) { iterBest = s; iterMove = m }; alpha = maxOf(alpha, iterBest) }
					else { if (s < iterBest) { iterBest = s; iterMove = m }; beta = minOf(beta, iterBest) }
				}
			} catch (e: TimeUp) { timedOut = true }
			if (timedOut || cancel()) break
			lastComplete = iterMove
			// лучший ход — первым на следующей итерации
			order = listOf(iterMove) + order.filter { it != iterMove }
			if (abs(iterBest) > 90000) break // мат найден
			if (System.currentTimeMillis() >= deadline) break
		}
		return lastComplete
	}

	private fun abs(x: Int) = if (x < 0) -x else x
}

// Бот «Алгоритм»: не минимакс, а правила. Уровень 1..5. Позиционную оценку (полосу) НЕ использует.
class AlgoBot(level0: Int) : Bot {
	private class Cfg(val blunder: Double, val margin: Int, val hangingScan: Boolean, val tactics: Boolean, val mateEye: Double, val lookahead: Boolean, val positional: Boolean = false, val deep: Boolean = false)

	private val level = if (level0 in 1..5) level0 else 3
	private val cfg = LEVELS[level - 1]

	// Вилки и удары по незащищённым фигурам
	private fun tactical(pos: Position, to: Int, mover: Int): Double {
		val targets = Tactics.attackSquares(pos, to).filter {
			val p = pos.sq[it]
			p != 0 && P.color(p) != mover && P.VALUE[P.type(p)] >= 300
		}
		if (targets.size >= 2) {
			val v = targets.map { P.VALUE[P.type(pos.sq[it])] }.sorted()
			return 25 + v[0] * 0.05 + v[1] * 0.05
		}
		if (targets.size == 1 && Tactics.squareLoss(pos, targets[0], mover xor 1) > 0) return 10.0
		return 0.0
	}

	private fun safestCheckResponse(pos: Position, pool: List<Int>, mover: Int): Int {
		var best = 0
		var bestScore = Double.NEGATIVE_INFINITY
		for (m in pool) {
			val cap = pos.capturedBy(m)
			pos.make(m)
			var s = if (cap != 0) P.VALUE[P.type(cap)].toDouble() else 0.0
			if (Tactics.isMate(pos)) s += 100000
			if (cfg.hangingScan) s -= Tactics.hangingScan(pos, mover)
			if (cfg.lookahead && Tactics.hasMateInOne(pos)) s -= 50000
			pos.unmake()
			if (s > bestScore) { bestScore = s; best = m }
		}
		return if (best != 0) best else pool[0]
	}

	// Позиционные правила (с уровня «Крепкий»): центр, развитие, открытые линии, безопасность короля, не гонять одну фигуру
	private fun positional(pos: Position, m: Int, mover: Int, piece: Int, isCapture: Boolean, moveNum: Int, eg: Boolean): Double {
		var s = 0.0
		val from = M.from(m); val to = M.to(m)
		val ff = P.file(from); val tf = P.file(to)
		val fr = P.rank(from); val tr = P.rank(to)
		fun centre(f: Int, r: Int) = 3.5 - Math.abs(f - 3.5) / 2 - Math.abs(r - 3.5) / 2
		// в дебюте центральные пешки и фигуры к центру
		if (moveNum <= 14 && !eg) {
			if (piece == P.PAWN && (ff == 3 || ff == 4)) s += 14.0 + (centre(tf, tr) - centre(ff, fr)) * 4
			else if (piece == P.KNIGHT || piece == P.BISHOP) s += (centre(tf, tr) - centre(ff, fr)) * 5
			// не выводить ферзя и ладью слишком рано
			if (moveNum <= 6 && (piece == P.QUEEN || piece == P.ROOK) && !isCapture) s -= 14.0
			// не ходить краевой пешкой в дебюте
			if (piece == P.PAWN && (ff == 0 || ff == 7) && !isCapture) s -= 5.0
		}
		// ладья на открытую или полуоткрытую линию
		if (piece == P.ROOK && ff != tf) {
			var own = false; var their = false
			for (r in 0..7) {
				val pc = pos.sq[P.sq(tf, r)]
				if (pc != 0 && P.type(pc) == P.PAWN) { if (P.color(pc) == mover) own = true else their = true }
			}
			if (!own && !their) s += 14.0 else if (!own) s += 8.0
		}
		// король в эндшпиле идёт к центру, в миттельшпиле остаётся за пешками
		if (piece == P.KING && !M.isCastle(m)) s += if (eg) (centre(tf, tr) - centre(ff, fr)) * 8 else 0.0
		// не оставлять пешки перед своим королём без нужды
		if (piece == P.PAWN && !eg) {
			val king = pos.king[mover]
			if (Math.abs(P.file(king) - ff) <= 1 && Math.abs(P.rank(king) - fr) <= 2 && !isCapture) s -= 7.0
		}
		return s
	}

	private fun endgame(pos: Position): Boolean {
		var heavy = 0
		for (p in pos.sq) if (p != 0 && P.type(p) in P.KNIGHT..P.QUEEN) heavy += if (P.type(p) == P.QUEEN) 2 else 1
		return heavy <= 4
	}

	private fun passed(pos: Position, s: Int, color: Int): Boolean {
		val f = P.file(s); val r = P.rank(s)
		val enemyPawn = P.make(color xor 1, P.PAWN)
		for (ff in maxOf(0, f - 1)..minOf(7, f + 1)) {
			val range = if (color == P.WHITE) (r + 1..7) else (0 until r)
			for (rr in range) if (pos.sq[P.sq(ff, rr)] == enemyPawn) return false
		}
		return true
	}

	private fun scoreQuiet(pos: Position, m: Int, mover: Int, plyCount: Int, eg: Boolean): Double {
		val cap = pos.capturedBy(m)
		val piece = P.type(pos.sq[M.from(m)])
		val myMatBefore = Tactics.material(pos, mover); val theirMatBefore = Tactics.material(pos, mover xor 1)
		pos.make(m)
		var score = 0.0
		if (cap != 0) score += P.VALUE[P.type(cap)]
		if (M.promo(m) != 0) score += P.VALUE[M.promo(m)] - 100
		val mate = Tactics.isMate(pos)
		if (mate) score += 100000
		else if (pos.inCheck()) score += 25
		// правило: не запатовать соперника, когда сам впереди
		if (!mate && !pos.hasLegalMove() && myMatBefore > theirMatBefore) score -= 5000
		// правило самосохранения
		score -= if (cfg.hangingScan) Tactics.hangingScan(pos, mover).toDouble() else Tactics.squareLoss(pos, M.to(m), mover) * 0.9
		if (cfg.tactics) score += tactical(pos, M.to(m), mover)
		// правило «посмотри на ответ соперника»: мат в 1 и его вилки
		if (cfg.lookahead && !mate) {
			if (Tactics.hasMateInOne(pos)) score -= 50000
			else {
				var worst = 0.0
				for (r in pos.legalMoves()) {
					if (P.type(pos.sq[M.from(r)]) == P.KING) continue
					pos.make(r)
					val t = tactical(pos, M.to(r), mover xor 1)
					pos.unmake()
					if (t > worst) worst = t
				}
				score -= worst
			}
		}
		pos.unmake()
		val moveNum = plyCount + 1
		val backRank = if (mover == P.WHITE) 0 else 7
		if (cfg.positional) score += positional(pos, m, mover, piece, cap != 0, moveNum, eg)
		if (moveNum <= 10 && (piece == P.KNIGHT || piece == P.BISHOP) && P.rank(M.from(m)) == backRank) score += 12
		if (M.isCastle(m)) score += 30
		if (piece == P.KING && !M.isCastle(m) && !eg) score -= 15
		// правило эндшпиля: проходные — вперёд
		if (eg && piece == P.PAWN && passed(pos, M.from(m), mover)) {
			val adv = if (mover == P.WHITE) P.rank(M.to(m)) else 7 - P.rank(M.to(m))
			score += 6 + adv * 4
		}
		return score
	}

	override fun pick(pos: Position, plyCount: Int, cancel: () -> Boolean): Int {
		val moves = pos.legalMoves()
		if (moves.isEmpty()) return 0
		val mover = pos.side
		// правило «увидел мат — ставь»
		if (Random.nextDouble() < cfg.mateEye) {
			for (m in moves) { pos.make(m); val mate = Tactics.isMate(pos); pos.unmake(); if (mate) return m }
		}
		if (cfg.blunder > 0 && Random.nextDouble() < cfg.blunder) return moves[Random.nextInt(moves.size)]
		if (pos.inCheck()) {
			val caps = moves.filter { pos.capturedBy(it) != 0 }
			val blocks = moves.filter { P.type(pos.sq[M.from(it)]) != P.KING && pos.capturedBy(it) == 0 }
			val kings = moves.filter { P.type(pos.sq[M.from(it)]) == P.KING }
			val pool = if (caps.isNotEmpty()) caps else if (blocks.isNotEmpty()) blocks else kings
			// на старших уровнях — среди всех ответов на шах, а не по жёсткому порядку
			return safestCheckResponse(pos, if (cfg.lookahead) moves.toList() else pool, mover)
		}
		val eg = endgame(pos)
		val scored = moves.map { it to scoreQuiet(pos, it, mover, plyCount, eg) }.sortedByDescending { it.second }
		val top = scored.filter { it.second >= scored[0].second - cfg.margin }
		return top[Random.nextInt(top.size)].first
	}

	companion object {
		private val LEVELS = arrayOf(
			Cfg(0.35, 60, false, false, 0.4, false),
			Cfg(0.18, 35, false, false, 0.75, false),
			Cfg(0.06, 18, true, false, 1.0, false, true),
			Cfg(0.0, 10, true, true, 1.0, true, true),
			Cfg(0.0, 4, true, true, 1.0, true, true)
		)
		val LABELS = arrayOf("Новичок", "Любитель", "Крепкий", "Сильный", "Мастер")
	}
}
