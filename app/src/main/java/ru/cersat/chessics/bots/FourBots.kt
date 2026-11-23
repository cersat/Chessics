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

// «Алгоритм» для четверых: правила вместо перебора, как у обычного, но с командами.
// Не ставит фигуры под бой команды соперников (защищённая стоит дешевле), уводит висящие, бьёт то, что висит,
// ставит мат, даёт шахи, развивается к центру, двигает пешки к превращению. Уровни 1–5: на низких чаще зевает.
class Algo4Bot(level0: Int) : Bot4 {
	private val level = if (level0 in 1..5) level0 else 3

	private fun value(pc: Int) = if (F4.type(pc) == P.KING) 0 else P.VALUE[F4.type(pc)]

	private fun centre(s: Int): Double {
		val d = Math.abs(F4.x(s) - 6.5) + Math.abs(F4.y(s) - 6.5)
		return 1.0 - d / 13.0
	}

	override fun pick(pos: Pos4, cancel: () -> Boolean): Int {
		val moves = pos.legal()
		if (moves.isEmpty()) return 0
		val blunder = when (level) { 1 -> 0.5; 2 -> 0.3; 3 -> 0.15; 4 -> 0.05; else -> 0.0 }
		if (Random.nextDouble() < blunder) return moves[Random.nextInt(moves.size)]
		val me = pos.turn
		val team = F4.team(me)
		val ally = (me + 1) % 4 // игрок другой команды: через него спрашиваем, бьёт ли клетку наша команда
		var best = moves[0]; var bestSc = Double.NEGATIVE_INFINITY
		fun hanging(): Int {
			var sum = 0
			for (s in pos.sq.indices) {
				val pc = pos.sq[s]
				if (pc == 0 || F4.owner(pc) != me || F4.type(pc) == P.KING) continue
				if (pos.attackedFor(s, me) && !pos.attackedFor(s, ally)) sum += value(pc)
			}
			return sum
		}
		val hangBefore = if (level >= 2) hanging() else 0
		for (m in moves) {
			val from = M4.from(m); val to = M4.to(m)
			val mover = pos.sq[from]
			val victim = pos.sq[to]
			val moverVal = if (M4.promo(m) != 0) P.VALUE[M4.promo(m)] else value(mover)
			var sc = if (victim != 0) value(victim) * 10.0 else 0.0
			if (M4.promo(m) != 0) sc += (P.VALUE[M4.promo(m)] - 100) * 10.0
			pos.make(m)
			val res = pos.result()
			if (res == team) sc = 10_000_000.0
			else if (res == 1 - team) sc = -10_000_000.0
			else if (res == 2) sc = -50.0
			else {
				// не ставить фигуру под бой команды соперников
				if (F4.type(mover) != P.KING && pos.attackedFor(to, me)) {
					val defended = pos.attackedFor(to, ally)
					sc -= if (defended) maxOf(0, moverVal - (if (victim != 0) value(victim) else 0)) * 4.0 else moverVal * 10.0
				}
				if (level >= 2) {
					val hang = hanging()
					sc -= hang * 6.0
					if (hang < hangBefore) sc += (hangBefore - hang) * 2.0
				}
				// шах сопернику, чей ход следующий
				if (level >= 3 && pos.inCheck(pos.turn)) sc += 150
				if (level >= 3) {
					val gain = centre(to) - centre(from)
					sc += gain * (if (F4.type(mover) == P.PAWN) 25 else 40)
					if (F4.type(mover) == P.KING && M4.castle(m) == 0) sc -= 30
					if (M4.castle(m) != 0) sc += 120
					if (F4.type(mover) == P.PAWN) sc += F4.rankOf(me, to) * 5.0
				}
				// высший уровень: не подставлять мат в один ход следующему сопернику
				if (level >= 5 && !cancel()) {
					for (r in pos.legal()) {
						pos.make(r)
						val rr = pos.result()
						pos.unmake()
						if (rr == 1 - team) { sc -= 5000.0; break }
					}
				}
				sc += Random.nextDouble() * 20
			}
			pos.unmake()
			if (sc > bestSc) { bestSc = sc; best = m }
			if (cancel()) break
		}
		return best
	}
}
