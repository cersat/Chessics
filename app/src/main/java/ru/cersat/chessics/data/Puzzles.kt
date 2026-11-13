package ru.cersat.chessics.data

import android.content.Context
import org.json.JSONArray

// Задачи на мат: сгенерированы из партий слабого Stockfish, решения проверены полной силой
// (на каждом ходе решающего — единственный ход, ведущий к мату за нужное число ходов).
object Puzzles {
	class Puzzle(val id: String, val fen: String, val moves: List<String>, val mate: Int, val number: Int)

	private var all: List<Puzzle>? = null

	fun all(ctx: Context): List<Puzzle> {
		all?.let { return it }
		val arr = JSONArray(ctx.assets.open("puzzles.json").bufferedReader().readText())
		val counters = IntArray(4)
		val list = ArrayList<Puzzle>()
		for (i in 0 until arr.length()) {
			val o = arr.getJSONObject(i)
			val mv = o.getJSONArray("moves")
			val mate = o.getInt("mate")
			counters[mate]++
			list.add(Puzzle(o.getString("id"), o.getString("fen"), List(mv.length()) { mv.getString(it) }, mate, counters[mate]))
		}
		all = list
		return list
	}

	fun byMate(ctx: Context, mate: Int) = all(ctx).filter { it.mate == mate }

	fun daily(ctx: Context): Puzzle? {
		val pool = all(ctx).filter { it.mate >= 2 }
		if (pool.isEmpty()) return null
		val day = Store.get(ctx).today
		// перемешанный, но стабильный порядок по дням
		val idx = ((day * 7919L) % pool.size).toInt().let { if (it < 0) it + pool.size else it }
		return pool[idx]
	}

	fun byId(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }
}
