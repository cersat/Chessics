package ru.cersat.chessics.chess

// Клетки 0..63: a1 = 0, h1 = 7, a8 = 56, h8 = 63.
// Фигуры: 1..6 белые (П К С Л Ф Кр), 9..14 чёрные (тип + 8). 0 — пусто.
object P {
	const val EMPTY = 0
	const val PAWN = 1
	const val KNIGHT = 2
	const val BISHOP = 3
	const val ROOK = 4
	const val QUEEN = 5
	const val KING = 6
	const val WHITE = 0
	const val BLACK = 1

	fun make(color: Int, type: Int) = type or (color shl 3)
	fun type(p: Int) = p and 7
	fun color(p: Int) = p shr 3

	val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)

	fun file(s: Int) = s and 7
	fun rank(s: Int) = s shr 3
	fun sq(file: Int, rank: Int) = rank * 8 + file
	fun name(s: Int) = "${'a' + file(s)}${rank(s) + 1}"
	fun parse(n: String): Int {
		if (n.length != 2) return -1
		val f = n[0] - 'a'
		val r = n[1] - '1'
		return if (f in 0..7 && r in 0..7) sq(f, r) else -1
	}

	// Превращение строго по цвету: белые — 8-я горизонталь, чёрные — 1-я.
	fun isPromoRank(color: Int, rank: Int) = if (color == WHITE) rank == 7 else rank == 0
}

// Ход упакован в Int: from(6) | to(6)<<6 | promo(3)<<12 | flags<<15
object M {
	const val F_EP = 1
	const val F_CASTLE = 2
	const val F_DOUBLE = 4

	fun make(from: Int, to: Int, promo: Int = 0, flags: Int = 0) =
		from or (to shl 6) or (promo shl 12) or (flags shl 15)

	fun from(m: Int) = m and 63
	fun to(m: Int) = (m shr 6) and 63
	fun promo(m: Int) = (m shr 12) and 7
	fun flags(m: Int) = m shr 15
	fun isEp(m: Int) = flags(m) and F_EP != 0
	fun isCastle(m: Int) = flags(m) and F_CASTLE != 0

	fun uci(m: Int): String {
		val s = P.name(from(m)) + P.name(to(m))
		return when (promo(m)) {
			P.KNIGHT -> s + "n"
			P.BISHOP -> s + "b"
			P.ROOK -> s + "r"
			P.QUEEN -> s + "q"
			else -> s
		}
	}
}

object Zobrist {
	val piece = Array(15) { LongArray(64) }
	val side: Long
	val castle = LongArray(16)
	val ep = LongArray(8)

	init {
		val rnd = java.util.Random(0x5EED_CA75L)
		for (p in piece) for (i in 0 until 64) p[i] = rnd.nextLong()
		side = rnd.nextLong()
		for (i in 0 until 16) castle[i] = rnd.nextLong()
		for (i in 0 until 8) ep[i] = rnd.nextLong()
	}
}

class Position {
	val sq = IntArray(64)
	var side = P.WHITE
	var castle = 0 // 1 — белые O-O, 2 — белые O-O-O, 4 — чёрные O-O, 8 — чёрные O-O-O
	var ep = -1
	var half = 0
	var full = 1
	var hash = 0L
	val king = IntArray(2)

	// стек отмены
	private var stackSize = 0
	private val stMove = IntArray(1024)
	private val stCap = IntArray(1024)
	private val stCastle = IntArray(1024)
	private val stEp = IntArray(1024)
	private val stHalf = IntArray(1024)
	private val stHash = LongArray(1024)

	// история хэшей для троекратного повторения
	val hashHistory = LongArray(2048)
	var hashCount = 0

	fun copy(): Position {
		val p = Position()
		System.arraycopy(sq, 0, p.sq, 0, 64)
		p.side = side; p.castle = castle; p.ep = ep; p.half = half; p.full = full
		p.hash = hash; p.king[0] = king[0]; p.king[1] = king[1]
		System.arraycopy(hashHistory, 0, p.hashHistory, 0, hashCount)
		p.hashCount = hashCount
		return p
	}

	private fun computeHash(): Long {
		var h = 0L
		for (i in 0 until 64) if (sq[i] != 0) h = h xor Zobrist.piece[sq[i]][i]
		if (side == P.BLACK) h = h xor Zobrist.side
		h = h xor Zobrist.castle[castle]
		if (ep >= 0) h = h xor Zobrist.ep[P.file(ep)]
		return h
	}

	fun setFen(fen: String): Boolean {
		val parts = fen.trim().split(Regex("\\s+"))
		if (parts.size < 2) return false
		sq.fill(0)
		var r = 7
		var f = 0
		for (c in parts[0]) {
			when {
				c == '/' -> { r--; f = 0 }
				c.isDigit() -> f += c - '0'
				else -> {
					val t = when (c.lowercaseChar()) {
						'p' -> P.PAWN; 'n' -> P.KNIGHT; 'b' -> P.BISHOP
						'r' -> P.ROOK; 'q' -> P.QUEEN; 'k' -> P.KING
						else -> return false
					}
					if (r !in 0..7 || f !in 0..7) return false
					val col = if (c.isUpperCase()) P.WHITE else P.BLACK
					sq[P.sq(f, r)] = P.make(col, t)
					if (t == P.KING) king[col] = P.sq(f, r)
					f++
				}
			}
		}
		side = if (parts[1] == "b") P.BLACK else P.WHITE
		castle = 0
		if (parts.size > 2) for (c in parts[2]) when (c) {
			'K' -> castle = castle or 1
			'Q' -> castle = castle or 2
			'k' -> castle = castle or 4
			'q' -> castle = castle or 8
		}
		ep = if (parts.size > 3) P.parse(parts[3]) else -1
		half = parts.getOrNull(4)?.toIntOrNull() ?: 0
		full = parts.getOrNull(5)?.toIntOrNull() ?: 1
		hash = computeHash()
		stackSize = 0
		hashCount = 1
		hashHistory[0] = hash
		return true
	}

	fun fen(): String {
		val sb = StringBuilder()
		for (r in 7 downTo 0) {
			var empty = 0
			for (f in 0..7) {
				val p = sq[P.sq(f, r)]
				if (p == 0) { empty++; continue }
				if (empty > 0) { sb.append(empty); empty = 0 }
				val ch = " pnbrqk"[P.type(p)]
				sb.append(if (P.color(p) == P.WHITE) ch.uppercaseChar() else ch)
			}
			if (empty > 0) sb.append(empty)
			if (r > 0) sb.append('/')
		}
		sb.append(if (side == P.WHITE) " w " else " b ")
		var c = ""
		if (castle and 1 != 0) c += "K"
		if (castle and 2 != 0) c += "Q"
		if (castle and 4 != 0) c += "k"
		if (castle and 8 != 0) c += "q"
		sb.append(if (c.isEmpty()) "-" else c)
		sb.append(' ').append(if (ep >= 0) P.name(ep) else "-")
		sb.append(' ').append(half).append(' ').append(full)
		return sb.toString()
	}

	// ---------- атаки ----------

	fun attacked(s: Int, by: Int): Boolean {
		val f = P.file(s)
		val r = P.rank(s)
		// пешки
		val pr = if (by == P.WHITE) r - 1 else r + 1
		if (pr in 0..7) {
			val pawn = P.make(by, P.PAWN)
			if (f > 0 && sq[P.sq(f - 1, pr)] == pawn) return true
			if (f < 7 && sq[P.sq(f + 1, pr)] == pawn) return true
		}
		val knight = P.make(by, P.KNIGHT)
		for (d in KNIGHT) {
			val ff = f + d[0]; val rr = r + d[1]
			if (ff in 0..7 && rr in 0..7 && sq[P.sq(ff, rr)] == knight) return true
		}
		val kingP = P.make(by, P.KING)
		for (d in KING) {
			val ff = f + d[0]; val rr = r + d[1]
			if (ff in 0..7 && rr in 0..7 && sq[P.sq(ff, rr)] == kingP) return true
		}
		val bishop = P.make(by, P.BISHOP)
		val rook = P.make(by, P.ROOK)
		val queen = P.make(by, P.QUEEN)
		for (d in DIAG) {
			var ff = f + d[0]; var rr = r + d[1]
			while (ff in 0..7 && rr in 0..7) {
				val p = sq[P.sq(ff, rr)]
				if (p != 0) { if (p == bishop || p == queen) return true; break }
				ff += d[0]; rr += d[1]
			}
		}
		for (d in ORTHO) {
			var ff = f + d[0]; var rr = r + d[1]
			while (ff in 0..7 && rr in 0..7) {
				val p = sq[P.sq(ff, rr)]
				if (p != 0) { if (p == rook || p == queen) return true; break }
				ff += d[0]; rr += d[1]
			}
		}
		return false
	}

	fun inCheck(color: Int = side) = attacked(king[color], color xor 1)

	// ---------- генерация ----------

	fun pseudoMoves(out: IntArray, capturesOnly: Boolean = false): Int {
		var n = 0
		val me = side
		val them = me xor 1
		for (s in 0 until 64) {
			val p = sq[s]
			if (p == 0 || P.color(p) != me) continue
			val f = P.file(s); val r = P.rank(s)
			when (P.type(p)) {
				P.PAWN -> {
					val dir = if (me == P.WHITE) 1 else -1
					val r1 = r + dir
					if (r1 in 0..7) {
						val t1 = P.sq(f, r1)
						if (sq[t1] == 0 && !capturesOnly) {
							n = addPawn(out, n, s, t1, me, 0)
							val start = if (me == P.WHITE) 1 else 6
							val t2 = P.sq(f, r + 2 * dir)
							if (r == start && sq[t2] == 0) out[n++] = M.make(s, t2, 0, M.F_DOUBLE)
						} else if (sq[t1] == 0 && P.isPromoRank(me, r1)) {
							n = addPawn(out, n, s, t1, me, 0)
						}
						for (df in intArrayOf(-1, 1)) {
							val ff = f + df
							if (ff !in 0..7) continue
							val t = P.sq(ff, r1)
							val q = sq[t]
							if (q != 0 && P.color(q) == them) n = addPawn(out, n, s, t, me, 0)
							else if (t == ep) out[n++] = M.make(s, t, 0, M.F_EP)
						}
					}
				}
				P.KNIGHT -> n = jumps(out, n, s, KNIGHT, me, capturesOnly)
				P.KING -> {
					n = jumps(out, n, s, KING, me, capturesOnly)
					if (!capturesOnly) n = castles(out, n, me)
				}
				P.BISHOP -> n = slides(out, n, s, DIAG, me, capturesOnly)
				P.ROOK -> n = slides(out, n, s, ORTHO, me, capturesOnly)
				P.QUEEN -> {
					n = slides(out, n, s, DIAG, me, capturesOnly)
					n = slides(out, n, s, ORTHO, me, capturesOnly)
				}
			}
		}
		return n
	}

	private fun addPawn(out: IntArray, n0: Int, from: Int, to: Int, me: Int, flags: Int): Int {
		var n = n0
		if (P.isPromoRank(me, P.rank(to))) {
			out[n++] = M.make(from, to, P.QUEEN, flags)
			out[n++] = M.make(from, to, P.ROOK, flags)
			out[n++] = M.make(from, to, P.BISHOP, flags)
			out[n++] = M.make(from, to, P.KNIGHT, flags)
		} else out[n++] = M.make(from, to, 0, flags)
		return n
	}

	private fun jumps(out: IntArray, n0: Int, s: Int, dirs: Array<IntArray>, me: Int, capOnly: Boolean): Int {
		var n = n0
		val f = P.file(s); val r = P.rank(s)
		for (d in dirs) {
			val ff = f + d[0]; val rr = r + d[1]
			if (ff !in 0..7 || rr !in 0..7) continue
			val t = P.sq(ff, rr)
			val q = sq[t]
			if (q == 0) { if (!capOnly) out[n++] = M.make(s, t) }
			else if (P.color(q) != me) out[n++] = M.make(s, t)
		}
		return n
	}

	private fun slides(out: IntArray, n0: Int, s: Int, dirs: Array<IntArray>, me: Int, capOnly: Boolean): Int {
		var n = n0
		val f = P.file(s); val r = P.rank(s)
		for (d in dirs) {
			var ff = f + d[0]; var rr = r + d[1]
			while (ff in 0..7 && rr in 0..7) {
				val t = P.sq(ff, rr)
				val q = sq[t]
				if (q == 0) { if (!capOnly) out[n++] = M.make(s, t) }
				else { if (P.color(q) != me) out[n++] = M.make(s, t); break }
				ff += d[0]; rr += d[1]
			}
		}
		return n
	}

	private fun castles(out: IntArray, n0: Int, me: Int): Int {
		var n = n0
		val them = me xor 1
		if (me == P.WHITE) {
			if (castle and 1 != 0 && sq[5] == 0 && sq[6] == 0 && sq[4] == P.make(me, P.KING) && sq[7] == P.make(me, P.ROOK)
				&& !attacked(4, them) && !attacked(5, them) && !attacked(6, them)) out[n++] = M.make(4, 6, 0, M.F_CASTLE)
			if (castle and 2 != 0 && sq[3] == 0 && sq[2] == 0 && sq[1] == 0 && sq[4] == P.make(me, P.KING) && sq[0] == P.make(me, P.ROOK)
				&& !attacked(4, them) && !attacked(3, them) && !attacked(2, them)) out[n++] = M.make(4, 2, 0, M.F_CASTLE)
		} else {
			if (castle and 4 != 0 && sq[61] == 0 && sq[62] == 0 && sq[60] == P.make(me, P.KING) && sq[63] == P.make(me, P.ROOK)
				&& !attacked(60, them) && !attacked(61, them) && !attacked(62, them)) out[n++] = M.make(60, 62, 0, M.F_CASTLE)
			if (castle and 8 != 0 && sq[59] == 0 && sq[58] == 0 && sq[57] == 0 && sq[60] == P.make(me, P.KING) && sq[56] == P.make(me, P.ROOK)
				&& !attacked(60, them) && !attacked(59, them) && !attacked(58, them)) out[n++] = M.make(60, 58, 0, M.F_CASTLE)
		}
		return n
	}

	private val tmp = IntArray(256)

	fun legalMoves(): IntArray {
		val n = pseudoMoves(tmp)
		val res = IntArray(n)
		var k = 0
		for (i in 0 until n) {
			val m = tmp[i]
			make(m)
			if (!inCheck(side xor 1)) res[k++] = m
			unmake()
		}
		return res.copyOf(k)
	}

	fun legalCaptures(): IntArray {
		val n = pseudoMoves(tmp, true)
		val res = IntArray(n)
		var k = 0
		for (i in 0 until n) {
			val m = tmp[i]
			make(m)
			if (!inCheck(side xor 1)) res[k++] = m
			unmake()
		}
		return res.copyOf(k)
	}

	fun hasLegalMove(): Boolean {
		val buf = IntArray(256)
		val n = pseudoMoves(buf)
		for (i in 0 until n) {
			make(buf[i])
			val ok = !inCheck(side xor 1)
			unmake()
			if (ok) return true
		}
		return false
	}

	// ---------- ход / отмена ----------

	private fun put(s: Int, p: Int) {
		val old = sq[s]
		if (old != 0) hash = hash xor Zobrist.piece[old][s]
		sq[s] = p
		if (p != 0) hash = hash xor Zobrist.piece[p][s]
	}

	fun make(m: Int) {
		val from = M.from(m); val to = M.to(m)
		val p = sq[from]
		val me = side
		var cap = sq[to]
		stMove[stackSize] = m
		stCastle[stackSize] = castle
		stEp[stackSize] = ep
		stHalf[stackSize] = half
		stHash[stackSize] = hash

		hash = hash xor Zobrist.castle[castle]
		if (ep >= 0) hash = hash xor Zobrist.ep[P.file(ep)]

		if (M.isEp(m)) {
			val capSq = if (me == P.WHITE) to - 8 else to + 8
			cap = sq[capSq]
			put(capSq, 0)
		}
		stCap[stackSize] = cap
		stackSize++

		put(from, 0)
		val promo = M.promo(m)
		put(to, if (promo != 0) P.make(me, promo) else p)

		if (M.isCastle(m)) {
			when (to) {
				6 -> { put(7, 0); put(5, P.make(me, P.ROOK)) }
				2 -> { put(0, 0); put(3, P.make(me, P.ROOK)) }
				62 -> { put(63, 0); put(61, P.make(me, P.ROOK)) }
				58 -> { put(56, 0); put(59, P.make(me, P.ROOK)) }
			}
		}
		if (P.type(p) == P.KING) king[me] = to

		castle = castle and CASTLE_MASK[from] and CASTLE_MASK[to]
		ep = if (M.flags(m) and M.F_DOUBLE != 0) (from + to) / 2 else -1
		half = if (P.type(p) == P.PAWN || cap != 0) 0 else half + 1
		if (me == P.BLACK) full++
		side = me xor 1

		hash = hash xor Zobrist.castle[castle] xor Zobrist.side
		if (ep >= 0) hash = hash xor Zobrist.ep[P.file(ep)]
		if (hashCount < hashHistory.size) hashHistory[hashCount] = hash
		hashCount++
	}

	fun unmake() {
		if (stackSize == 0) return // у копии позиции истории ходов нет — откатывать нечего
		stackSize--
		hashCount--
		val m = stMove[stackSize]
		val from = M.from(m); val to = M.to(m)
		side = side xor 1
		val me = side
		if (me == P.BLACK) full--
		val moved = if (M.promo(m) != 0) P.make(me, P.PAWN) else sq[to]
		sq[from] = moved
		sq[to] = 0
		val cap = stCap[stackSize]
		if (M.isEp(m)) {
			val capSq = if (me == P.WHITE) to - 8 else to + 8
			sq[capSq] = cap
		} else sq[to] = cap
		if (M.isCastle(m)) {
			when (to) {
				6 -> { sq[5] = 0; sq[7] = P.make(me, P.ROOK) }
				2 -> { sq[3] = 0; sq[0] = P.make(me, P.ROOK) }
				62 -> { sq[61] = 0; sq[63] = P.make(me, P.ROOK) }
				58 -> { sq[59] = 0; sq[56] = P.make(me, P.ROOK) }
			}
		}
		if (P.type(moved) == P.KING) king[me] = from
		castle = stCastle[stackSize]
		ep = stEp[stackSize]
		half = stHalf[stackSize]
		hash = stHash[stackSize]
	}

	fun capturedBy(m: Int): Int = if (M.isEp(m)) P.make(side xor 1, P.PAWN) else sq[M.to(m)]

	// ---------- ничьи ----------

	fun repetitions(): Int {
		var c = 0
		var i = hashCount - 1
		val limit = maxOf(0, hashCount - 1 - half)
		while (i >= limit) {
			if (i < hashHistory.size && hashHistory[i] == hash) c++
			i -= 2
		}
		return c
	}

	fun insufficientMaterial(): Boolean {
		var minors = 0
		val bishopColors = IntArray(2)
		var bishops = 0
		for (s in 0 until 64) {
			val p = sq[s]
			if (p == 0) continue
			when (P.type(p)) {
				P.KING -> {}
				P.KNIGHT -> minors++
				P.BISHOP -> { minors++; bishops++; bishopColors[(P.file(s) + P.rank(s)) and 1]++ }
				else -> return false
			}
		}
		if (minors <= 1) return true
		// только слоны, все на клетках одного цвета
		return bishops == minors && (bishopColors[0] == 0 || bishopColors[1] == 0)
	}

	// Может ли сторона в принципе поставить мат (для флажка по времени)
	fun canMate(color: Int): Boolean {
		var minors = 0
		for (s in 0 until 64) {
			val p = sq[s]
			if (p == 0 || P.color(p) != color) continue
			when (P.type(p)) {
				P.PAWN, P.ROOK, P.QUEEN -> return true
				P.KNIGHT, P.BISHOP -> minors++
			}
		}
		return minors >= 2
	}

	fun parseUci(u: String): Int {
		val legal = legalMoves()
		for (m in legal) if (M.uci(m) == u) return m
		// O-O может прийти как «взятие своей ладьи» — не наш случай, но e1h1 поддержим
		return 0
	}

	companion object {
		const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
		val KNIGHT = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2), intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
		val KING = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
		val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
		val ORTHO = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
		val CASTLE_MASK = IntArray(64) { 15 }.also {
			it[0] = 15 and 2.inv(); it[4] = 15 and 3.inv(); it[7] = 15 and 1.inv()
			it[56] = 15 and 8.inv(); it[60] = 15 and 12.inv(); it[63] = 15 and 4.inv()
		}

		fun start() = Position().also { it.setFen(START) }
	}
}
