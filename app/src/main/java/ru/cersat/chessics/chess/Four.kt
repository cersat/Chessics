package ru.cersat.chessics.chess

// Шахматы на четверых, командные правила chess.com (Teams):
// доска 14×14 без углов 3×3; ходят по кругу красные → синие → жёлтые → зелёные;
// команды — красные+жёлтые против синих+зелёных (партнёр сидит напротив), фигуры напарника брать нельзя;
// пешки превращаются на своей 11-й горизонтали; мат засчитывается, когда наступает ход заматованного игрока,
// и тогда побеждает команда соперников; пат — ничья. Рокировка есть, взятия на проходе нет.
// От себя добавлено (чтобы партия не шла бесконечно): троекратное повторение и 200 полуходов
// подряд без взятий и ходов пешками — ничья.
object F4 {
	const val N = 14
	const val RED = 0
	const val BLUE = 1
	const val YELLOW = 2
	const val GREEN = 3
	val NAMES = arrayOf("Красные", "Синие", "Жёлтые", "Зелёные")
	val CODES = charArrayOf('r', 'b', 'y', 'g')

	fun valid(x: Int, y: Int) = x in 0 until N && y in 0 until N && !((x < 3 || x > 10) && (y < 3 || y > 10))
	fun sq(x: Int, y: Int) = y * N + x
	fun x(s: Int) = s % N
	fun y(s: Int) = s / N
	fun team(p: Int) = p and 1 // 0 — красные+жёлтые, 1 — синие+зелёные

	fun make(owner: Int, type: Int) = type or ((owner + 1) shl 3)
	fun owner(pc: Int) = (pc shr 3) - 1
	fun type(pc: Int) = pc and 7

	// локальные координаты игрока (как у красных снизу) → общие
	fun toGlobal(p: Int, lx: Int, ly: Int): Int = when (p) {
		RED -> sq(lx, ly)
		BLUE -> sq(ly, N - 1 - lx)
		YELLOW -> sq(N - 1 - lx, N - 1 - ly)
		else -> sq(N - 1 - ly, lx)
	}

	// локальная «горизонталь» клетки для игрока p (0 — его первая линия)
	fun rankOf(p: Int, s: Int): Int = when (p) {
		RED -> y(s)
		BLUE -> x(s)
		YELLOW -> N - 1 - y(s)
		else -> N - 1 - x(s)
	}

	val FORWARD = arrayOf(intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(-1, 0))
	const val PROMO_RANK = 10 // 11-я горизонталь (с нуля — 10)
	val BACK = intArrayOf(P.ROOK, P.KNIGHT, P.BISHOP, P.QUEEN, P.KING, P.BISHOP, P.KNIGHT, P.ROOK)
}

// Ход: from | to<<8 | promo<<16 | castle<<20 (1 — короткая, 2 — длинная)
object M4 {
	fun make(from: Int, to: Int, promo: Int = 0, castle: Int = 0) = from or (to shl 8) or (promo shl 16) or (castle shl 20)
	fun from(m: Int) = m and 255
	fun to(m: Int) = (m shr 8) and 255
	fun promo(m: Int) = (m shr 16) and 7
	fun castle(m: Int) = (m shr 20) and 3
}

class Pos4 {
	val sq = IntArray(F4.N * F4.N)
	var turn = F4.RED
	val king = IntArray(4)
	var castle = 0xFF // по 2 бита на игрока: 1 — короткая, 2 — длинная
	var quiet = 0 // полуходов подряд без взятий и ходов пешками
	private val stack = ArrayList<IntArray>() // ход, взятая фигура, права рокировки, прежний ход
	val history = HashMap<Long, Int>()

	init {
		for (p in 0..3) {
			for (i in 0..7) {
				sq[F4.toGlobal(p, 3 + i, 0)] = F4.make(p, F4.BACK[i])
				sq[F4.toGlobal(p, 3 + i, 1)] = F4.make(p, P.PAWN)
			}
			king[p] = F4.toGlobal(p, 7, 0)
		}
		history[hash()] = 1
	}

	fun copy(): Pos4 {
		val c = Pos4()
		System.arraycopy(sq, 0, c.sq, 0, sq.size)
		c.turn = turn; c.castle = castle; c.quiet = quiet
		System.arraycopy(king, 0, c.king, 0, 4)
		c.history.clear(); c.history.putAll(history)
		return c
	}

	fun hash(): Long {
		var h = 1469598103934665603L
		for (v in sq) { h = h xor v.toLong(); h *= 1099511628211L }
		h = h xor (turn.toLong() * 31 + castle)
		return h
	}

	// атакована ли клетка s фигурами команды-соперника по отношению к игроку p
	fun attackedFor(s: Int, p: Int): Boolean {
		val myTeam = F4.team(p)
		val x = F4.x(s); val y = F4.y(s)
		for (d in Position.KNIGHT) {
			val xx = x + d[0]; val yy = y + d[1]
			if (!F4.valid(xx, yy)) continue
			val pc = sq[F4.sq(xx, yy)]
			if (pc != 0 && F4.type(pc) == P.KNIGHT && F4.team(F4.owner(pc)) != myTeam) return true
		}
		for (d in Position.KING) {
			val xx = x + d[0]; val yy = y + d[1]
			if (!F4.valid(xx, yy)) continue
			val pc = sq[F4.sq(xx, yy)]
			if (pc != 0 && F4.type(pc) == P.KING && F4.team(F4.owner(pc)) != myTeam) return true
		}
		for ((dirs, ok) in listOf(Position.DIAG to P.BISHOP, Position.ORTHO to P.ROOK)) {
			for (d in dirs) {
				var xx = x + d[0]; var yy = y + d[1]
				while (F4.valid(xx, yy)) {
					val pc = sq[F4.sq(xx, yy)]
					if (pc != 0) {
						val t = F4.type(pc)
						if (F4.team(F4.owner(pc)) != myTeam && (t == ok || t == P.QUEEN)) return true
						break
					}
					xx += d[0]; yy += d[1]
				}
			}
		}
		// пешки: пешка игрока q бьёт по диагонали вперёд по своему направлению
		for (q in 0..3) {
			if (F4.team(q) == myTeam) continue
			val f = F4.FORWARD[q]
			for (side in intArrayOf(-1, 1)) {
				// пешка стоит на клетке, с которой её удар попадает в s
				val px = x - f[0] - (if (f[0] == 0) side else 0)
				val py = y - f[1] - (if (f[1] == 0) side else 0)
				if (F4.valid(px, py) && sq[F4.sq(px, py)] == F4.make(q, P.PAWN)) return true
			}
		}
		return false
	}

	fun inCheck(p: Int = turn) = attackedFor(king[p], p)

	fun pseudo(p: Int): IntArray {
		val out = ArrayList<Int>()
		val team = F4.team(p)
		fun can(s: Int) = sq[s] == 0 || F4.team(F4.owner(sq[s])) != team
		for (s in sq.indices) {
			val pc = sq[s]
			if (pc == 0 || F4.owner(pc) != p) continue
			val x = F4.x(s); val y = F4.y(s)
			when (F4.type(pc)) {
				P.PAWN -> {
					val f = F4.FORWARD[p]
					val x1 = x + f[0]; val y1 = y + f[1]
					fun add(t: Int) {
						if (F4.rankOf(p, t) == F4.PROMO_RANK) for (pr in intArrayOf(P.QUEEN, P.ROOK, P.BISHOP, P.KNIGHT)) out.add(M4.make(s, t, pr))
						else out.add(M4.make(s, t))
					}
					if (F4.valid(x1, y1) && sq[F4.sq(x1, y1)] == 0) {
						add(F4.sq(x1, y1))
						val x2 = x1 + f[0]; val y2 = y1 + f[1]
						if (F4.rankOf(p, s) == 1 && F4.valid(x2, y2) && sq[F4.sq(x2, y2)] == 0) out.add(M4.make(s, F4.sq(x2, y2)))
					}
					for (side in intArrayOf(-1, 1)) {
						val cx = x1 + (if (f[0] == 0) side else 0); val cy = y1 + (if (f[1] == 0) side else 0)
						if (!F4.valid(cx, cy)) continue
						val t = F4.sq(cx, cy)
						if (sq[t] != 0 && F4.team(F4.owner(sq[t])) != team) add(t)
					}
				}
				P.KNIGHT, P.KING -> {
					for (d in if (F4.type(pc) == P.KNIGHT) Position.KNIGHT else Position.KING) {
						val xx = x + d[0]; val yy = y + d[1]
						if (F4.valid(xx, yy) && can(F4.sq(xx, yy))) out.add(M4.make(s, F4.sq(xx, yy)))
					}
				}
				else -> {
					val dirs = when (F4.type(pc)) { P.BISHOP -> Position.DIAG; P.ROOK -> Position.ORTHO; else -> Position.DIAG + Position.ORTHO }
					for (d in dirs) {
						var xx = x + d[0]; var yy = y + d[1]
						while (F4.valid(xx, yy)) {
							val t = F4.sq(xx, yy)
							if (sq[t] == 0) out.add(M4.make(s, t))
							else { if (F4.team(F4.owner(sq[t])) != team) out.add(M4.make(s, t)); break }
							xx += d[0]; yy += d[1]
						}
					}
				}
			}
		}
		// рокировка: король и ладья не ходили, между ними пусто, король не под шахом и не проходит битые поля
		for (side in 1..2) {
			if (castle and (side shl (p * 2)) == 0) continue
			if (king[p] != F4.toGlobal(p, 7, 0)) continue
			val rookL = if (side == 1) 10 else 3
			if (sq[F4.toGlobal(p, rookL, 0)] != F4.make(p, P.ROOK)) continue
			val between = if (side == 1) 8..9 else 4..6
			if (between.any { sq[F4.toGlobal(p, it, 0)] != 0 }) continue
			val path = if (side == 1) intArrayOf(7, 8, 9) else intArrayOf(7, 6, 5)
			if (path.any { attackedFor(F4.toGlobal(p, it, 0), p) }) continue
			out.add(M4.make(king[p], F4.toGlobal(p, if (side == 1) 9 else 5, 0), 0, side))
		}
		return out.toIntArray()
	}

	fun make(m: Int) {
		val from = M4.from(m); val to = M4.to(m)
		val pc = sq[from]
		val p = F4.owner(pc)
		stack.add(intArrayOf(m, sq[to], castle, turn, quiet))
		quiet = if (sq[to] != 0 || F4.type(pc) == P.PAWN) 0 else quiet + 1
		sq[to] = if (M4.promo(m) != 0) F4.make(p, M4.promo(m)) else pc
		sq[from] = 0
		val c = M4.castle(m)
		if (c != 0) {
			val rf = F4.toGlobal(p, if (c == 1) 10 else 3, 0)
			val rt = F4.toGlobal(p, if (c == 1) 8 else 6, 0)
			sq[rt] = sq[rf]; sq[rf] = 0
		}
		if (F4.type(pc) == P.KING) { king[p] = to; castle = castle and (3 shl (p * 2)).inv() }
		for (q in 0..3) {
			if (from == F4.toGlobal(q, 10, 0) || to == F4.toGlobal(q, 10, 0)) castle = castle and (1 shl (q * 2)).inv()
			if (from == F4.toGlobal(q, 3, 0) || to == F4.toGlobal(q, 3, 0)) castle = castle and (2 shl (q * 2)).inv()
		}
		turn = (turn + 1) % 4
	}

	fun unmake() {
		val st = stack.removeAt(stack.size - 1)
		val m = st[0]
		val from = M4.from(m); val to = M4.to(m)
		turn = st[3]
		val p = turn
		val moved = sq[to]
		sq[from] = if (M4.promo(m) != 0) F4.make(p, P.PAWN) else moved
		sq[to] = st[1]
		val c = M4.castle(m)
		if (c != 0) {
			val rf = F4.toGlobal(p, if (c == 1) 10 else 3, 0)
			val rt = F4.toGlobal(p, if (c == 1) 8 else 6, 0)
			sq[rf] = sq[rt]; sq[rt] = 0
		}
		if (F4.type(sq[from]) == P.KING) king[p] = from
		castle = st[2]
		quiet = st[4]
	}

	fun legal(p: Int = turn): IntArray {
		val out = ArrayList<Int>()
		for (m in pseudo(p)) {
			make(m)
			if (!attackedFor(king[p], p)) out.add(m)
			unmake()
		}
		return out.toIntArray()
	}

	// После хода: записать позицию для правила троекратного повторения
	fun record() { val h = hash(); history[h] = (history[h] ?: 0) + 1 }
	fun repetitions() = history[hash()] ?: 0

	// −1 — игра идёт; 0/1 — победила команда; 2 — ничья
	fun result(): Int {
		if (legal().isEmpty()) return if (inCheck()) 1 - F4.team(turn) else 2
		if (repetitions() >= 3 || quiet >= 200) return 2
		return -1
	}

	fun material(team: Int): Int {
		var s = 0
		for (pc in sq) if (pc != 0 && F4.team(F4.owner(pc)) == team) s += P.VALUE[F4.type(pc)]
		return s
	}
}
