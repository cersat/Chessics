package ru.cersat.chessics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import ru.cersat.chessics.chess.M
import ru.cersat.chessics.chess.P
import ru.cersat.chessics.chess.Position
import ru.cersat.chessics.chess.San
import ru.cersat.chessics.chess.V
import ru.cersat.chessics.chess.VM
import ru.cersat.chessics.chess.VPos
import ru.cersat.chessics.chess.Variant

// Игра с нейросетью через генератор промптов: приложение собирает промпт, человек отправляет его
// в любой чат-бот и вставляет ответ. Ход выбирается номером из списка легальных — нелегальный сделать нельзя.
object Neural {
	private const val ANSWER = "Ответь строго одной строкой JSON без markdown: {\"move\": <номер хода из списка>, \"comment\": \"<коротко, зачем>\"}."

	// номер хода из ответа: JSON {"move": N}, иначе — запись хода из списка в тексте
	fun pick(answer: String, names: List<String>): Int? {
		val t = answer.trim()
		val a = t.indexOf('{'); val b = t.lastIndexOf('}')
		if (a >= 0 && b > a) try {
			val o = JSONObject(t.substring(a, b + 1))
			val n = o.opt("move")
			when (n) {
				is Number -> return n.toInt().takeIf { it in 1..names.size }?.minus(1)
				is String -> {
					n.trim().toIntOrNull()?.let { k -> return k.takeIf { it in 1..names.size }?.minus(1) }
					names.indexOfFirst { it.equals(n.trim(), true) }.takeIf { it >= 0 }?.let { return it }
				}
			}
		} catch (_: Exception) {}
		// без JSON: ищем запись хода целиком как отдельное слово
		val words = t.split(Regex("[\\s,;:()\"'«»]+")).map { it.trimEnd('.', '!', '?') }
		for ((i, n) in names.withIndex()) if (words.any { it.equals(n, true) }) return i
		return null
	}

	// ----- обычные шахматы и встроенные варианты -----

	fun prompt(pos: Position, variant: Int, history: List<String>, legal: List<Int>): String {
		val side = if (pos.side == P.WHITE) "белых" else "чёрных"
		val sb = StringBuilder()
		sb.append("Мы играем в шахматы").append(when (variant) {
			V.CHESS960 -> " (Chess960: рокировка — король на ладью, ходы записаны как обычно)"
			V.THREE_CHECK -> " «Три шаха»: побеждает тот, кто первым объявит три шаха, или мат"
			V.KOTH -> " «Царь горы»: побеждает тот, чей король встанет на d4, e4, d5 или e5, или мат"
			else -> ""
		}).append(".\n")
		sb.append("Ты играешь за $side. Сейчас твой ход.\n\n")
		sb.append("Доска (белые фигуры — заглавные буквы KQRBNP, чёрные — строчные, точка — пустая клетка). Сверху 8-я горизонталь, снизу 1-я, слева вертикаль a:\n")
		for (r in 7 downTo 0) {
			sb.append("${r + 1} ")
			for (f in 0..7) {
				val pc = pos.sq[r * 8 + f]
				sb.append(if (pc == 0) "." else "?PNBRQK"[P.type(pc)].let { if (P.color(pc) == P.WHITE) it else it.lowercaseChar() }).append(' ')
			}
			sb.append('\n')
		}
		sb.append("  a b c d e f g h\n")
		sb.append("FEN: ${pos.fen()}\n\n")
		if (history.isNotEmpty()) {
			sb.append("Ходы партии: ").append(history.chunked(2).mapIndexed { i, p -> "${i + 1}. ${p.joinToString(" ")}" }.joinToString(" ")).append('\n')
			sb.append("Последний ход соперника: ${history.last()}\n\n")
		}
		sb.append("Твои легальные ходы:\n")
		names(pos, legal).forEachIndexed { i, n -> sb.append("${i + 1}. $n\n") }
		sb.append('\n').append(ANSWER)
		return sb.toString()
	}

	fun names(pos: Position, legal: List<Int>) = legal.map { San.of(pos, it, false) }

	// ----- варианты из конструктора -----

	fun prompt(p: VPos, history: List<String>): String {
		val v = p.v
		val side = if (p.turn == 0) "белых" else "чёрных"
		val sb = StringBuilder()
		sb.append("Мы играем в шахматный вариант «${v.name}». Доска ${v.w}×${v.h}.\n")
		sb.append("Ты играешь за $side. Сейчас твой ход.\n\n")
		sb.append("Фигуры (белые — заглавными, чёрные — строчными):\n")
		val letters = letters(v)
		for ((i, d) in v.pieces.withIndex()) sb.append("${letters[i]} — ${d.name}${if (d.royal) " (король)" else ""}${if (d.pawn) " (пешка)" else ""}: ${describe(d)}\n")
		sb.append("Для чёрных «вперёд» — вниз по доске, «вправо» — к вертикали a.\n\n")
		sb.append("Правила: ").append(rules(v)).append("\n\n")
		sb.append("Доска. Сверху ${v.h}-я горизонталь, снизу 1-я, слева вертикаль a. Точка — пусто, # — заблокированная клетка:\n")
		for (y in v.h - 1 downTo 0) {
			sb.append("${(y + 1).toString().padStart(2)} ")
			for (x in 0 until v.w) {
				val i = v.idx(x, y); val c = p.sq[i]
				sb.append(when { v.locked[i] -> '#'; c == 0 -> '.'; else -> letters[p.type(c)].let { if (p.color(c) == 0) it.uppercaseChar() else it } }).append(' ')
			}
			sb.append('\n')
		}
		sb.append("   ").append((0 until v.w).joinToString(" ") { "${'a' + it}" }).append("\n\n")
		if (history.isNotEmpty()) {
			sb.append("Ходы партии: ").append(history.chunked(2).mapIndexed { i, pr -> "${i + 1}. ${pr.joinToString(" ")}" }.joinToString(" ")).append('\n')
			sb.append("Последний ход соперника: ${history.last()}\n\n")
		}
		sb.append("Твои легальные ходы:\n")
		names(p).forEachIndexed { i, n -> sb.append("${i + 1}. $n\n") }
		sb.append('\n').append(ANSWER)
		return sb.toString()
	}

	fun names(p: VPos) = p.legal().map { name(p, it) }

	// запись хода латинскими буквами фигур, как на доске в промпте
	fun name(p: VPos, m: Int): String {
		val f = VM.from(m); val t = VM.to(m)
		if (VM.flags(m) and VM.CASTLE != 0) return if (p.x(t) > p.x(f)) "0-0" else "0-0-0"
		val l = letters(p.v)
		val d = p.def(p.sq[f])
		val cap = p.sq[t] != 0 || VM.flags(m) and VM.EP != 0
		return (if (d.pawn) "" else l[p.type(p.sq[f])].uppercaseChar().toString()) + p.sqName(f) + (if (cap) "x" else "-") + p.sqName(t) +
			(if (VM.promo(m) != 0) "=" + l[VM.promo(m) - 1].uppercaseChar() else "")
	}

	private fun letters(v: Variant): CharArray {
		val used = HashSet<Char>()
		return CharArray(v.pieces.size) { i ->
			val base = when (v.pieces[i].art) { P.PAWN -> 'p'; P.KNIGHT -> 'n'; P.BISHOP -> 'b'; P.ROOK -> 'r'; P.QUEEN -> 'q'; P.KING -> 'k'; else -> ' ' }
			val c = if (base != ' ' && base !in used) base else "acdefghijlmostuvwxyzpnbrqk".first { it !in used }
			used.add(c); c
		}
	}

	private fun describe(d: ru.cersat.chessics.chess.PieceDef): String {
		if (d.moves.isEmpty()) return "не ходит"
		return d.moves.joinToString("; ") { r ->
			val dir = "(${if (r.dx > 0) "+" else ""}${r.dx} вправо, ${if (r.dy > 0) "+" else ""}${r.dy} вперёд)"
			(if (r.slide) "луч $dir до препятствия" else "прыжок $dir") + when (r.mode) { 1 -> " только тихий ход"; 2 -> " только взятие"; else -> "" }
		}
	}

	private fun rules(v: Variant): String {
		val parts = ArrayList<String>()
		parts.add(when (v.win) {
			Variant.WIN_CAPTURE_ROYAL -> "побеждает тот, кто возьмёт короля соперника (шаха нет, под бой ходить можно)"
			Variant.WIN_ALL -> "побеждает тот, кто заберёт все фигуры соперника"
			Variant.WIN_HILL -> "побеждает тот, чей король встанет на клетку холма (${v.hillCells().joinToString(", ") { "${'a' + it % v.w}${it / v.w + 1}" }}), или мат"
			Variant.WIN_PROMO -> "побеждает тот, чья пешка первой дойдёт до последней горизонтали, или мат"
			Variant.WIN_CHECKS -> "побеждает тот, кто первым объявит ${v.nChecks} шахов, или мат"
			else -> "побеждает мат"
		})
		parts.add(Variant.STALEMATE_NAMES[v.stalemate].lowercase())
		parts.add(if (v.castling) "рокировка есть (король идёт на две клетки к ладье)" else "рокировки нет")
		if (v.pieces.any { it.pawn }) {
			parts.add(if (v.pawnDouble) "пешка с места может пойти на две клетки" else "двойного хода пешки нет")
			parts.add(if (v.enPassant) "взятие на проходе есть" else "взятия на проходе нет")
			parts.add(if (v.promo.isEmpty()) "пешки не превращаются" else "пешка на последней горизонтали превращается в: ${v.promo.joinToString(", ")}")
		}
		return parts.joinToString("; ") + "."
	}

	fun copy(ctx: Context, s: String) = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chessics prompt", s))

	// Панель хода нейросети: скопировать промпт → вставить ответ → ход. try вернёт текст ошибки или null, если ход сделан.
	fun panel(a: MainActivity, side: String, prompt: () -> String, tryAnswer: (String) -> String?, fallback: () -> Unit): View {
		val ui = a.ui
		val c = ui.card()
		c.addView(ui.text("🤖 Ход нейросети ($side)", 16f, bold = true))
		c.addView(ui.sub("Скопируй промпт в любой чат-бот и вставь сюда его ответ. Ход проверяется по правилам — сделать неправильный нейросеть не сможет."))
		c.addView(ui.space(8))
		c.addView(ui.button("📋 Скопировать промпт") { copy(a, prompt()); a.toast("Промпт скопирован") })
		c.addView(ui.space(8))
		val field = EditText(a).apply {
			hint = "ответ нейросети"; setTextColor(ui.t.text); setHintTextColor(ui.t.sub); minLines = 2
			background = ui.round(ui.t.card2, 12f); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
		}
		c.addView(field, ui.lp())
		c.addView(ui.space(8))
		val r = ui.row(8)
		r.addView(ui.button("Вставить и сходить") {
			val clip = (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
			val text = field.text.toString().ifBlank { clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString() ?: "" }
			if (text.isBlank()) { a.toast("Вставь ответ нейросети"); return@button }
			tryAnswer(text)?.let { a.toast(it) }
		}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		r.addView(ui.button("Сходит Материал", false) { fallback() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		c.addView(r)
		c.addView(ui.sub("Если поле пустое, кнопка берёт ответ из буфера обмена.", 11f))
		return c
	}
}
