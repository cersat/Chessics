package ru.cersat.chessics.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Звуки ходов синтезируются кодом (никаких чужих сэмплов). Каждый пак — свой «тембр».
object Sounds {
	enum class Kind { MOVE, CAPTURE, CHECK, CASTLE, END, GOOD, BAD }

	private const val RATE = 22050
	private val cache = HashMap<String, AudioTrack>()

	private fun pcm(seconds: Double, f: (Double) -> Double): ShortArray {
		val n = (seconds * RATE).toInt()
		return ShortArray(n) { i ->
			val v = f(i.toDouble() / RATE).coerceIn(-1.0, 1.0)
			(v * 32000).toInt().toShort()
		}
	}

	private fun mix(vararg parts: ShortArray): ShortArray {
		val n = parts.maxOf { it.size }
		return ShortArray(n) { i -> parts.sumOf { if (i < it.size) it[i].toInt() else 0 }.coerceIn(-32000, 32000).toShort() }
	}

	private fun concat(a: ShortArray, gap: Double, b: ShortArray): ShortArray {
		val g = (gap * RATE).toInt()
		return ShortArray(a.size + g + b.size) { i -> if (i < a.size) a[i] else if (i < a.size + g) 0 else b[i - a.size - g] }
	}

	private fun tone(freq: Double, len: Double, decay: Double, vol: Double = 0.6, square: Boolean = false, sweepTo: Double = freq) = pcm(len) { t ->
		val fr = freq + (sweepTo - freq) * (t / len)
		val ph = 2 * PI * fr * t
		val w = if (square) (if (sin(ph) >= 0) 1.0 else -1.0) * 0.5 else sin(ph)
		val attack = minOf(1.0, t / 0.003)
		w * vol * attack * exp(-t / decay)
	}

	private fun knock(len: Double, decay: Double, body: Double, vol: Double, bright: Double): ShortArray {
		val rnd = Random(7)
		var lp = 0.0
		return pcm(len) { t ->
			val noise = rnd.nextDouble(-1.0, 1.0)
			lp += (noise - lp) * bright
			(lp * 1.6 + sin(2 * PI * body * t) * 0.7) * vol * exp(-t / decay)
		}
	}

	private fun synth(style: Int, k: Kind): ShortArray = when (style) {
		// Классика: деревянный щелчок фигуры о доску
		1 -> when (k) {
			Kind.MOVE -> knock(0.09, 0.018, 190.0, 0.8, 0.5)
			Kind.CAPTURE -> mix(knock(0.12, 0.025, 150.0, 1.0, 0.65), knock(0.12, 0.012, 320.0, 0.4, 0.8))
			Kind.CHECK -> concat(knock(0.08, 0.018, 220.0, 0.8, 0.5), 0.02, tone(880.0, 0.12, 0.05, 0.25))
			Kind.CASTLE -> concat(knock(0.07, 0.015, 190.0, 0.7, 0.5), 0.06, knock(0.08, 0.018, 170.0, 0.8, 0.5))
			Kind.END -> mix(tone(523.0, 0.6, 0.25, 0.25), tone(659.0, 0.6, 0.25, 0.2), tone(784.0, 0.6, 0.25, 0.18))
			Kind.GOOD -> concat(tone(660.0, 0.08, 0.05, 0.3), 0.0, tone(990.0, 0.14, 0.07, 0.3))
			Kind.BAD -> tone(220.0, 0.2, 0.08, 0.35, sweepTo = 160.0)
		}
		// Дерево: глухой, тёплый стук
		2 -> when (k) {
			Kind.MOVE -> knock(0.12, 0.03, 120.0, 0.9, 0.18)
			Kind.CAPTURE -> mix(knock(0.15, 0.04, 95.0, 1.0, 0.25), knock(0.1, 0.02, 240.0, 0.4, 0.3))
			Kind.CHECK -> concat(knock(0.1, 0.03, 130.0, 0.9, 0.18), 0.03, knock(0.1, 0.03, 160.0, 0.8, 0.2))
			Kind.CASTLE -> concat(knock(0.1, 0.025, 120.0, 0.8, 0.18), 0.07, knock(0.1, 0.025, 110.0, 0.8, 0.18))
			Kind.END -> mix(tone(196.0, 0.8, 0.3, 0.3), tone(294.0, 0.8, 0.3, 0.22))
			Kind.GOOD -> tone(392.0, 0.25, 0.1, 0.35)
			Kind.BAD -> tone(130.0, 0.25, 0.1, 0.4)
		}
		// 8 бит: квадратные блипы
		3 -> when (k) {
			Kind.MOVE -> tone(660.0, 0.05, 0.04, 0.35, true)
			Kind.CAPTURE -> tone(520.0, 0.12, 0.08, 0.4, true, 180.0)
			Kind.CHECK -> concat(tone(990.0, 0.06, 0.05, 0.35, true), 0.03, tone(990.0, 0.06, 0.05, 0.35, true))
			Kind.CASTLE -> concat(tone(440.0, 0.05, 0.04, 0.35, true), 0.02, tone(660.0, 0.05, 0.04, 0.35, true))
			Kind.END -> concat(concat(tone(523.0, 0.1, 0.08, 0.35, true), 0.0, tone(659.0, 0.1, 0.08, 0.35, true)), 0.0, tone(1046.0, 0.25, 0.15, 0.35, true))
			Kind.GOOD -> concat(tone(784.0, 0.06, 0.05, 0.35, true), 0.0, tone(1175.0, 0.1, 0.08, 0.35, true))
			Kind.BAD -> tone(300.0, 0.18, 0.12, 0.4, true, 120.0)
		}
		// Стекло: звонкие колокольчики
		4 -> when (k) {
			Kind.MOVE -> mix(tone(2093.0, 0.25, 0.08, 0.25), tone(3136.0, 0.2, 0.05, 0.12))
			Kind.CAPTURE -> mix(tone(1568.0, 0.35, 0.12, 0.3), tone(2637.0, 0.3, 0.08, 0.18))
			Kind.CHECK -> concat(tone(2349.0, 0.15, 0.06, 0.3), 0.02, tone(2794.0, 0.25, 0.1, 0.3))
			Kind.CASTLE -> concat(tone(2093.0, 0.15, 0.06, 0.25), 0.04, tone(2637.0, 0.2, 0.08, 0.25))
			Kind.END -> mix(tone(1046.0, 1.0, 0.4, 0.2), tone(1568.0, 1.0, 0.35, 0.15), tone(2093.0, 1.0, 0.3, 0.12))
			Kind.GOOD -> tone(2637.0, 0.3, 0.12, 0.3)
			Kind.BAD -> tone(880.0, 0.3, 0.1, 0.3, sweepTo = 700.0)
		}
		// Мягкие: приглушённые «пуфы»
		5 -> when (k) {
			Kind.MOVE -> tone(260.0, 0.1, 0.035, 0.5)
			Kind.CAPTURE -> mix(tone(200.0, 0.14, 0.05, 0.55), knock(0.1, 0.03, 100.0, 0.3, 0.1))
			Kind.CHECK -> concat(tone(330.0, 0.1, 0.04, 0.45), 0.03, tone(392.0, 0.12, 0.05, 0.45))
			Kind.CASTLE -> concat(tone(260.0, 0.08, 0.03, 0.45), 0.05, tone(290.0, 0.08, 0.03, 0.45))
			Kind.END -> mix(tone(262.0, 0.7, 0.3, 0.3), tone(330.0, 0.7, 0.3, 0.22), tone(392.0, 0.7, 0.3, 0.18))
			Kind.GOOD -> tone(440.0, 0.2, 0.08, 0.4)
			Kind.BAD -> tone(180.0, 0.22, 0.08, 0.45)
		}
		// Космос: свипы
		6 -> when (k) {
			Kind.MOVE -> tone(1200.0, 0.12, 0.06, 0.35, sweepTo = 500.0)
			Kind.CAPTURE -> tone(1600.0, 0.22, 0.1, 0.4, sweepTo = 150.0)
			Kind.CHECK -> concat(tone(400.0, 0.1, 0.06, 0.35, sweepTo = 1400.0), 0.02, tone(400.0, 0.1, 0.06, 0.35, sweepTo = 1400.0))
			Kind.CASTLE -> concat(tone(500.0, 0.08, 0.05, 0.35, sweepTo = 900.0), 0.03, tone(900.0, 0.08, 0.05, 0.35, sweepTo = 500.0))
			Kind.END -> tone(200.0, 1.0, 0.45, 0.35, sweepTo = 1200.0)
			Kind.GOOD -> tone(600.0, 0.18, 0.08, 0.35, sweepTo = 1800.0)
			Kind.BAD -> tone(900.0, 0.25, 0.1, 0.35, sweepTo = 120.0)
		}
		else -> ShortArray(0)
	}

	@Synchronized
	private fun track(style: Int, k: Kind): AudioTrack? {
		val key = "$style$k"
		cache[key]?.let { return it }
		val data = synth(style, k)
		if (data.isEmpty()) return null
		val t = AudioTrack.Builder()
			.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
			.setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
			.setTransferMode(AudioTrack.MODE_STATIC)
			.setBufferSizeInBytes(data.size * 2)
			.build()
		t.write(data, 0, data.size)
		cache[key] = t
		return t
	}

	fun play(ctx: Context, k: Kind, style: Int = Shop.sound(ctx).style) {
		if (style <= 0) return
		try {
			val t = track(style, k) ?: return
			t.stop()
			t.reloadStaticData()
			t.play()
		} catch (_: Exception) {}
	}

	// Тип звука для хода: pos — позиция ПОСЛЕ хода
	fun forMove(captured: Boolean, castle: Boolean, check: Boolean) = when {
		check -> Kind.CHECK
		castle -> Kind.CASTLE
		captured -> Kind.CAPTURE
		else -> Kind.MOVE
	}
}
