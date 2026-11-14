package ru.cersat.chessics.bots

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Один процесс Stockfish 17.1 (UCI) со своим потоком.
// Бинарник лежит в nativeLibraryDir, нейросети копируются из assets в filesDir при первом запуске.
class Engine(private val name: String, private val threads: Int) {
	private var proc: Process? = null
	private var reader: BufferedReader? = null
	private var writer: OutputStreamWriter? = null
	private val lock = Object()
	private val writeLock = Object()
	private var curElo = -1
	private var curMultiPv = 1
	private var cur960 = false
	@Volatile var lastError: String? = null
		private set

	// Свой поток на каждый движок: анализ не ждёт ход бота и наоборот
	val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "sf-$name").apply { priority = Thread.NORM_PRIORITY - 1 } }

	class Line(val cp: Int, val mate: Int, val move: String, val pv: List<String>) {
		// оценка в сантипешках с точки зрения белых, мат — как ±(10000 − 10·n)
		val score: Int get() = if (mate != 0) (if (mate > 0) 10000 - mate * 10 else -10000 - mate * 10) else cp
	}

	private fun send(cmd: String) {
		synchronized(writeLock) {
			writer?.write(cmd + "\n")
			writer?.flush()
		}
	}

	private fun readUntil(prefix: String): String? {
		while (true) {
			val line = reader?.readLine() ?: return null
			if (line.startsWith(prefix)) return line
		}
	}

	fun start(ctx: Context): Boolean {
		synchronized(lock) {
			if (proc?.isAlive == true) return true
			try {
				val bin = Nets.binary(ctx) ?: run { lastError = "Stockfish не найден (нужен телефон arm64)"; return false }
				val (big, small) = Nets.files(ctx)
				val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
				proc = p
				reader = BufferedReader(InputStreamReader(p.inputStream))
				writer = OutputStreamWriter(p.outputStream)
				send("uci")
				readUntil("uciok") ?: throw IllegalStateException("нет uciok")
				send("setoption name Threads value $threads")
				send("setoption name Hash value 32")
				send("setoption name EvalFile value ${big.absolutePath}")
				send("setoption name EvalFileSmall value ${small.absolutePath}")
				send("isready")
				readUntil("readyok") ?: throw IllegalStateException("нет readyok")
				curElo = -1; curMultiPv = 1; cur960 = false
				lastError = null
				return true
			} catch (e: Exception) {
				lastError = e.message ?: e.toString()
				proc?.destroy(); proc = null
				return false
			}
		}
	}

	private fun setElo(elo: Int) {
		if (elo == curElo) return
		if (elo <= 0) send("setoption name UCI_LimitStrength value false")
		else {
			send("setoption name UCI_LimitStrength value true")
			send("setoption name UCI_Elo value $elo")
		}
		curElo = elo
	}

	private fun set960(v: Boolean) {
		if (v == cur960) return
		send("setoption name UCI_Chess960 value $v")
		cur960 = v
	}

	private fun setMultiPv(n: Int) {
		if (n == curMultiPv) return
		send("setoption name MultiPV value $n")
		curMultiPv = n
	}

	private fun positionCmd(startFen: String, moves: String) =
		"position fen $startFen" + if (moves.isNotBlank()) " moves $moves" else ""

	private fun fail(e: Exception) { lastError = e.message ?: e.toString(); proc?.destroy(); proc = null }

	// Ход бота. elo <= 0 — полная сила.
	fun bestMove(ctx: Context, startFen: String, moves: String, elo: Int, movetime: Int, c960: Boolean = false): String? {
		if (!start(ctx)) return null
		synchronized(lock) {
			return try {
				setElo(elo); setMultiPv(1); set960(c960)
				send(positionCmd(startFen, moves))
				send("go movetime $movetime")
				readUntil("bestmove")?.split(" ")?.getOrNull(1)?.takeIf { it != "(none)" }
			} catch (e: Exception) { fail(e); null }
		}
	}

	// Анализ полной силой. go — «depth 3» или «movetime 300». Строки отсортированы от лучшей.
	fun analyse(ctx: Context, startFen: String, moves: String, whiteToMove: Boolean, go: String, multipv: Int = 1, c960: Boolean = false): List<Line>? {
		if (!start(ctx)) return null
		synchronized(lock) {
			try {
				setElo(0); setMultiPv(multipv); set960(c960)
				send(positionCmd(startFen, moves))
				send("go $go")
				val lines = arrayOfNulls<Line>(multipv)
				val sign = if (whiteToMove) 1 else -1
				while (true) {
					val line = reader?.readLine() ?: return null
					if (line.startsWith("info") && line.contains(" score ") && line.contains(" pv ")) {
						val t = line.split(" ")
						val si = t.indexOf("score")
						val mi = t.indexOf("multipv")
						val k = if (mi >= 0) (t[mi + 1].toIntOrNull() ?: 1) - 1 else 0
						if (k !in 0 until multipv || si < 0 || si + 2 >= t.size) continue
						var cp = 0; var mate = 0
						if (t[si + 1] == "cp") cp = t[si + 2].toInt() else if (t[si + 1] == "mate") mate = t[si + 2].toInt()
						val pi = t.indexOf("pv")
						val pv = t.subList(pi + 1, t.size)
						lines[k] = Line(cp * sign, mate * sign, pv.firstOrNull() ?: "", pv)
					}
					if (line.startsWith("bestmove")) {
						val best = line.split(" ").getOrNull(1) ?: ""
						val out = lines.filterNotNull().toMutableList()
						if (out.isEmpty() && best.isNotEmpty() && best != "(none)") out.add(Line(0, 0, best, listOf(best)))
						return out
					}
				}
			} catch (e: Exception) { fail(e); return null }
		}
		@Suppress("UNREACHABLE_CODE")
		return null
	}

	// Прервать текущий расчёт (ответ bestmove всё равно придёт и будет проигнорирован вызывающим)
	fun stop() {
		if (proc?.isAlive != true) return
		try { send("stop") } catch (_: Exception) {}
	}

	fun newGame() {
		if (proc?.isAlive != true) return
		try { send("ucinewgame") } catch (_: Exception) {}
	}
}

object Nets {
	private const val BIG = "nn-1c0000000000.nnue"
	private const val SMALL = "nn-37f18f62d772.nnue"
	private val lock = Object()

	private fun hasDotProd(): Boolean = try {
		File("/proc/cpuinfo").readText().contains("asimddp")
	} catch (e: Exception) { false }

	fun binary(ctx: Context): File? {
		val f = File(ctx.applicationInfo.nativeLibraryDir, if (hasDotProd()) "libstockfish_dp.so" else "libstockfish.so")
		return if (f.exists()) f else null
	}

	fun files(ctx: Context): Pair<File, File> = synchronized(lock) { Pair(copy(ctx, BIG), copy(ctx, SMALL)) }

	private fun copy(ctx: Context, name: String): File {
		val dir = File(ctx.filesDir, "nets").apply { mkdirs() }
		val f = File(dir, name)
		val size = ctx.assets.openFd(name).use { it.length }
		if (!f.exists() || f.length() != size) {
			val tmp = File(dir, "$name.tmp")
			ctx.assets.open(name).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
			tmp.renameTo(f)
		}
		return f
	}
}

// Три независимых процесса: ходы ботов и подсказки, полоса оценки, разбор партий
object Stockfish {
	private val cores = Runtime.getRuntime().availableProcessors()
	val play = Engine("play", maxOf(1, minOf(4, cores / 2)))
	val bar = Engine("bar", 1)
	val analysis = Engine("analysis", maxOf(1, minOf(2, cores / 4)))
}

// Описание игрока: человек или бот
data class Player(val kind: Kind, val level: Int = 0) {
	enum class Kind { HUMAN, RANDOM, ALGO, MATERIAL, STOCKFISH, REMOTE }

	val isHuman get() = kind == Kind.HUMAN

	val title: String get() = when (kind) {
		Kind.HUMAN -> "Человек"
		Kind.RANDOM -> "Случайный"
		Kind.ALGO -> "Алгоритм · ${AlgoBot.LABELS[(level - 1).coerceIn(0, 4)]}"
		Kind.MATERIAL -> "Материал · глубина $level"
		Kind.STOCKFISH -> if (level <= 0) "Stockfish · полная сила" else "Stockfish $level"
		Kind.REMOTE -> "Соперник по сети"
	}

	val shortName: String get() = when (kind) {
		Kind.HUMAN -> "Человек"
		Kind.RANDOM -> "Случайный"
		Kind.ALGO -> "Алгоритм $level"
		Kind.MATERIAL -> "Материал $level"
		Kind.STOCKFISH -> if (level <= 0) "Stockfish MAX" else "Stockfish $level"
		Kind.REMOTE -> "Соперник"
	}

	// Примерный рейтинг. Для Stockfish — его UCI_Elo; для наших ботов — оценка «на глаз».
	val elo: Int get() = when (kind) {
		Kind.HUMAN -> 0
		Kind.RANDOM -> 200
		Kind.ALGO -> intArrayOf(400, 600, 800, 950, 1100)[(level - 1).coerceIn(0, 4)]
		Kind.MATERIAL -> intArrayOf(500, 700, 900, 1050, 1200, 1300, 1400, 1450)[(level - 1).coerceIn(0, 7)]
		Kind.STOCKFISH -> if (level <= 0) 3500 else level
		Kind.REMOTE -> 0
	}

	fun encode() = "${kind.name}:$level"

	companion object {
		val HUMAN = Player(Kind.HUMAN)
		val SF_LEVELS = intArrayOf(1350, 1500, 1700, 1900, 2100, 2400, 2700, 3000, 0)
		fun decode(s: String): Player = try {
			val (k, l) = s.split(":")
			Player(Kind.valueOf(k), l.toInt())
		} catch (e: Exception) { HUMAN }
	}
}
