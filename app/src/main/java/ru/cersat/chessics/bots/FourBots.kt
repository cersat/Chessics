package ru.cersat.chessics.bots

import ru.cersat.chessics.chess.F4
import ru.cersat.chessics.chess.M4
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Pos4
import kotlin.random.Random

interface Bot4 {
	fun pick(pos: Pos4, cancel: () -> Boolean): Int
}

object Random4 : Bot4 {
	override fun pick(pos: Pos4, cancel: () -> Boolean): Int {
		val l = pos.legal()
		return if (l.isEmpty()) 0 else l[Random.nextInt(l.size)]
	}
}

// «Материал» для четверых: альфа-бета «параноика» — своя команда максимизирует,
// обе фигуры соперников минимизируют разницу материала команд. Итеративное углубление, лимит ~2 с.
class Material4Bot(private val depth: Int) : Bot4 {
	private var deadline = 0L
	private var nodes = 0
	private var cancel: () -> Boolean = { false }
	private var myTeam = 0

	private fun order(pos: Pos4, moves: IntArray): IntArray {
		val sc = IntArray(moves.size) {
			val m = moves[it]
			val cap = pos.sq[M4.to(m)]
			(if (cap != 0) 1000 + P.VALUE[F4.type(cap)] * 10 - P.VALUE[F4.type(pos.sq[M4.from(m)])] else 0) + M4.promo(m) * 100
		}
		return moves.indices.sortedByDescending { sc[it] }.map { moves[it] }.toIntArray()
	}

	private fun search(pos: Pos4, d: Int, a0: Int, b0: Int, ply: Int): Int {
		nodes++
		if (nodes and 255 == 0 && (System.currentTimeMillis() > deadline || cancel())) throw TimeUp()
		var alpha = a0; var beta = b0
		val moves = pos.legal()
		val mine = F4.team(pos.turn) == myTeam
		if (moves.isEmpty()) {
			if (!pos.inCheck()) return 0
			return if (mine) -100000 + ply else 100000 - ply
		}
		if (d == 0) return pos.material(myTeam) - pos.material(1 - myTeam)
		var best = if (mine) Int.MIN_VALUE else Int.MAX_VALUE
		for (m in order(pos, moves)) {
			pos.make(m)
			val s: Int
			try { s = search(pos, d - 1, alpha, beta, ply + 1) } finally { pos.unmake() }
			if (mine) { if (s > best) best = s; alpha = maxOf(alpha, s) }
			else { if (s < best) best = s; beta = minOf(beta, s) }
			if (beta <= alpha) break
		}
		return best
	}

	override fun pick(pos: Pos4, cancel: () -> Boolean): Int {
		val root = pos.legal()
		if (root.isEmpty()) return 0
		this.cancel = cancel
		myTeam = F4.team(pos.turn)
		deadline = System.currentTimeMillis() + 2000
		nodes = 0
		var bestMove = root[Random.nextInt(root.size)]
		var order = order(pos, root)
		for (d in 1..depth) {
			var best = Int.MIN_VALUE
			var bm = 0
			val ties = ArrayList<Int>()
			try {
				for (m in order) {
					pos.make(m)
					val s: Int
					try { s = search(pos, d - 1, if (best == Int.MIN_VALUE) Int.MIN_VALUE else best - 1, Int.MAX_VALUE, 1) } finally { pos.unmake() }
					if (s > best) { best = s; bm = m; ties.clear(); ties.add(m) } else if (s == best) ties.add(m)
				}
			} catch (e: TimeUp) { break }
			if (cancel()) break
			bestMove = if (ties.size > 1) ties[Random.nextInt(ties.size)] else bm
			order = intArrayOf(bm) + order.filter { it != bm }
			if (best > 90000) break
		}
		return bestMove
	}
}
