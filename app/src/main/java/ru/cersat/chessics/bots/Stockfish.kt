package ru.cersat.chessics.bots

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

// Stockfish 17.1 как отдельный процесс (UCI). Бинарник лежит в nativeLibraryDir,
// нейросети копируются из assets в filesDir при первом запуске.
object Stockfish {
	private const val BIG = "nn-1c0000000000.nnue"
	private const val SMALL = "nn-37f18f62d772.nnue"

	private var proc: Process? = null
	private var reader: BufferedReader? = null
	private var writer: OutputStreamWriter? = null
	private val lock = Object()
	private val writeLock = Object()
	private var curElo = -1
	@Volatile var lastError: String? = null
		private set

	class Analysis(val cp: Int, val mate: Int, val best: String, val pv: List<String>)

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

	private fun hasDotProd(): Boolean = try {
		File("/proc/cpuinfo").readText().contains("asimddp")
	} catch (e: Exception) { false }

	private fun copyNet(ctx: Context, name: String): File {
		val dir = File(ctx.filesDir, "nets").apply { mkdirs() }
		val f = File(dir, name)
		val size = ctx.assets.openFd(name).use { it.length }
		if (!f.exists() || f.length() != size) {
			ctx.assets.open(name).use { inp -> f.outputStream().use { inp.copyTo(it) } }
		}
		return f
	}

	fun start(ctx: Context): Boolean {
		synchronized(lock) {
			if (proc?.isAlive == true) return true
			try {
				val libDir = ctx.applicationInfo.nativeLibraryDir
				val bin = File(libDir, if (hasDotProd()) "libstockfish_dp.so" else "libstockfish.so")
				if (!bin.exists()) { lastError = "Stockfish не найден (нужен телефон arm64)"; return false }
				val big = copyNet(ctx, BIG)
				val small = copyNet(ctx, SMALL)
				val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
				proc = p
				reader = BufferedReader(InputStreamReader(p.inputStream))
				writer = OutputStreamWriter(p.outputStream)
				send("uci")
				readUntil("uciok") ?: throw IllegalStateException("нет uciok")
				val threads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
				send("setoption name Threads value $threads")
				send("setoption name Hash value 64")
				send("setoption name EvalFile value ${big.absolutePath}")
				send("setoption name EvalFileSmall value ${small.absolutePath}")
				send("isready")
				readUntil("readyok") ?: throw IllegalStateException("нет readyok")
				curElo = -1
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

	private fun positionCmd(startFen: String, moves: String) =
		"position fen $startFen" + if (moves.isNotBlank()) " moves $moves" else ""

	// Ход бота. elo <= 0 — полная сила.
	fun bestMove(ctx: Context, startFen: String, moves: String, elo: Int, movetime: Int): String? {
		if (!start(ctx)) return null
		synchronized(lock) {
			return try {
				setElo(elo)
				send(positionCmd(startFen, moves))
				send("go movetime $movetime")
				readUntil("bestmove")?.split(" ")?.getOrNull(1)?.takeIf { it != "(none)" }
			} catch (e: Exception) { lastError = e.message; proc?.destroy(); proc = null; null }
		}
	}

	// Анализ полной силой. Оценка — с точки зрения белых.
	fun analyse(ctx: Context, startFen: String, moves: String, whiteToMove: Boolean, movetime: Int): Analysis? {
		if (!start(ctx)) return null
		synchronized(lock) {
			return try {
				setElo(0)
				send(positionCmd(startFen, moves))
				send("go movetime $movetime")
				var cp = 0; var mate = 0; var pv = emptyList<String>()
				while (true) {
					val line = reader?.readLine() ?: return null
					if (line.startsWith("info") && line.contains(" score ")) {
						val t = line.split(" ")
						val i = t.indexOf("score")
						if (i >= 0 && i + 2 < t.size) {
							if (t[i + 1] == "cp") { cp = t[i + 2].toInt(); mate = 0 }
							else if (t[i + 1] == "mate") { mate = t[i + 2].toInt(); cp = 0 }
						}
						val pi = t.indexOf("pv")
						if (pi >= 0) pv = t.subList(pi + 1, t.size)
					}
					if (line.startsWith("bestmove")) {
						val best = line.split(" ").getOrNull(1) ?: ""
						val sign = if (whiteToMove) 1 else -1
						return Analysis(cp * sign, mate * sign, best, pv)
					}
				}
				@Suppress("UNREACHABLE_CODE") null
			} catch (e: Exception) { lastError = e.message; proc?.destroy(); proc = null; null }
		}
	}

	// Прервать текущий расчёт (ответ bestmove всё равно придёт и будет проигнорирован вызывающим)
	fun stop() {
		try { send("stop") } catch (_: Exception) {}
	}

	fun newGame() {
		if (proc?.isAlive != true) return
		try { send("ucinewgame") } catch (_: Exception) {}
	}
}

// Описание игрока: человек или бот
data class Player(val kind: Kind, val level: Int = 0) {
	enum class Kind { HUMAN, RANDOM, ALGO, MATERIAL, STOCKFISH }

	val isHuman get() = kind == Kind.HUMAN

	val title: String get() = when (kind) {
		Kind.HUMAN -> "Человек"
		Kind.RANDOM -> "Случайный"
		Kind.ALGO -> "Алгоритм · ${AlgoBot.LABELS[(level - 1).coerceIn(0, 4)]}"
		Kind.MATERIAL -> "Материал · глубина $level"
		Kind.STOCKFISH -> if (level <= 0) "Stockfish · полная сила" else "Stockfish $level"
	}

	val shortName: String get() = when (kind) {
		Kind.HUMAN -> "Человек"
		Kind.RANDOM -> "Случайный"
		Kind.ALGO -> "Алгоритм $level"
		Kind.MATERIAL -> "Материал $level"
		Kind.STOCKFISH -> if (level <= 0) "Stockfish MAX" else "Stockfish $level"
	}

	// Примерный рейтинг. Для Stockfish — его UCI_Elo; для наших ботов — оценка «на глаз».
	val elo: Int get() = when (kind) {
		Kind.HUMAN -> 0
		Kind.RANDOM -> 200
		Kind.ALGO -> intArrayOf(350, 550, 750, 900, 1050)[(level - 1).coerceIn(0, 4)]
		Kind.MATERIAL -> intArrayOf(450, 650, 850, 1000, 1150, 1250, 1300, 1350)[(level - 1).coerceIn(0, 7)]
		Kind.STOCKFISH -> if (level <= 0) 3500 else level
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
