package ru.cersat.chessics.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import ru.cersat.chessics.bots.Player
import java.io.File
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToInt

// Всё, что копится: алмазы, серия, заморозки, покупки, статистика, достижения.
class Store private constructor(private val ctx: Context) {
	private val sp: SharedPreferences = ctx.getSharedPreferences("chessics", Context.MODE_PRIVATE)
	private val listeners = ArrayList<() -> Unit>()

	init { Shop.init(ctx) }

	fun onChange(l: () -> Unit) { listeners.add(l) }
	fun removeListener(l: () -> Unit) { listeners.remove(l) }
	private fun changed() {
		listeners.toList().forEach { it() }
		Widgets.updateAll(ctx)
	}

	private fun int(k: String, d: Int = 0) = sp.getInt(k, d)
	private fun setInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

	// ---------- валюта ----------
	var diamonds: Int
		get() = int("diamonds", 30)
		private set(v) = setInt("diamonds", v)

	fun addDiamonds(n: Int) { diamonds += n; setInt("diamondsEarned", int("diamondsEarned") + maxOf(0, n)); changed() }

	fun spend(n: Int): Boolean {
		if (diamonds < n) return false
		diamonds -= n
		changed()
		return true
	}

	// ---------- серия (ударный режим) ----------
	val streak get() = int("streak")
	val bestStreak get() = int("bestStreak")
	val freezes get() = int("freezes", 1)
	val lastActive: Long get() = sp.getLong("lastActive", -10)
	val today: Long get() = LocalDate.now().toEpochDay()
	val activeToday get() = lastActive == today
	val maxFreezes = 3

	fun daySet(key: String): MutableSet<Long> =
		sp.getStringSet(key, emptySet())!!.mapNotNull { it.toLongOrNull() }.toMutableSet()

	private fun saveDaySet(key: String, s: Set<Long>) {
		val keep = s.filter { it > today - 120 }.map { it.toString() }.toSet()
		sp.edit().putStringSet(key, keep).apply()
	}

	// Проверка пропущенных дней. Возвращает сообщение для пользователя или null.
	fun checkStreak(): String? {
		val last = lastActive
		if (streak == 0 || last >= today - 1) return null
		val missed = (today - 1 - last).toInt()
		return if (freezes >= missed) {
			setInt("freezes", freezes - missed)
			val frozen = daySet("frozenDays")
			for (d in last + 1..today - 1) frozen.add(d)
			saveDaySet("frozenDays", frozen)
			sp.edit().putLong("lastActive", today - 1).apply()
			setInt("freezesUsed", int("freezesUsed") + missed)
			changed()
			"Заморозк${if (missed == 1) "а спасла" else "и спасли"} серию: пропущено дней — $missed ❄"
		} else {
			val old = streak
			setInt("streak", 0)
			changed()
			"Серия в $old ${days(old)} прервалась. Начни новую сегодня!"
		}
	}

	class Reward(val lines: MutableList<String> = ArrayList(), var diamonds: Int = 0)

	// Засчитать день. Вызывается после партии или решённой задачи.
	private fun markActive(r: Reward) {
		checkStreak()
		if (activeToday) return
		val s = if (lastActive == today - 1) streak + 1 else 1
		setInt("streak", s)
		if (s > bestStreak) setInt("bestStreak", s)
		sp.edit().putLong("lastActive", today).apply()
		val act = daySet("activeDays"); act.add(today); saveDaySet("activeDays", act)
		r.diamonds += 1
		r.lines.add("🔥 Серия: $s ${days(s)} (+1 💎)")
		val bonus = when (s) { 3 -> 3; 7 -> 8; 14 -> 12; 30 -> 30; 50 -> 45; 100 -> 90; 180 -> 120; 365 -> 300; else -> 0 }
		if (bonus > 0) { r.diamonds += bonus; r.lines.add("🏅 Рубеж серии $s дней: +$bonus 💎") }
	}

	fun buyFreeze(): Boolean {
		if (freezes >= maxFreezes) return false
		if (!spend(Shop.FREEZE_PRICE)) return false
		setInt("freezes", freezes + 1)
		changed()
		return true
	}

	// ---------- рейтинг и статистика ----------
	val rating get() = int("rating", 800)
	fun stat(k: String) = int("st_$k")
	private fun inc(k: String, by: Int = 1) = setInt("st_$k", stat(k) + by)
	private fun max(k: String, v: Int) { if (v > stat(k)) setInt("st_$k", v) }

	// Итог партии с ботом. score: 1 победа, 0.5 ничья, 0 поражение (для человека).
	fun gameFinished(bot: Player?, score: Double, plies: Int, takebacks: Int, hvh: Boolean, mateByHuman: Boolean, timeControl: Boolean): Reward {
		val r = Reward()
		inc("games")
		event("game")
		if (hvh) {
			inc("hvh")
			if (plies >= 10) markActive(r)
		} else if (bot != null) {
			when {
				score >= 1.0 -> { inc("wins"); event("win"); if (bot.kind == Player.Kind.STOCKFISH) event("winSf") }
				score > 0.0 -> inc("draws")
				else -> inc("losses")
			}
			val elo = bot.elo
			var gain = 0
			if (score >= 1.0) gain = maxOf(1, (elo / 500.0).roundToInt())
			else if (score > 0) gain = if (elo >= 1000) 1 else 0
			if (takebacks > 0 && gain > 1) { gain /= 2; r.lines.add("↩ Были отмены ходов — награда вдвое меньше") }
			if (timeControl && score >= 1.0 && elo >= 1000) { gain += 1; r.lines.add("⏱ Партия на время: +1 💎") }
			if (gain > 0) { r.diamonds += gain; r.lines.add("Партия: +$gain 💎") }
			// рейтинг по формуле Эло, K = 32
			val exp = 1.0 / (1 + 10.0.pow((elo - rating) / 400.0))
			val delta = (32 * (score - exp)).roundToInt()
			setInt("rating", maxOf(100, rating + delta))
			r.lines.add("Рейтинг: $rating (${if (delta >= 0) "+" else ""}$delta)")
			if (score >= 1.0) {
				max("bestWinElo", elo)
				when (bot.kind) {
					Player.Kind.STOCKFISH -> { inc("sfWins"); max("sfBest", elo) }
					Player.Kind.MATERIAL -> { inc("matWins"); max("matBest", bot.level) }
					Player.Kind.ALGO -> { inc("algoWins"); max("algoBest", bot.level) }
					else -> inc("rndWins")
				}
				if (mateByHuman && plies <= 40) inc("fastMates")
			}
			if (plies >= 6) markActive(r)
		}
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	// Шахматы на четверых: считается, если человек играет за одну команду, а в другой есть бот
	fun fourFinished(win: Boolean, draw: Boolean, plies: Int): Reward {
		val r = Reward()
		inc("fourGames")
		event("game")
		if (win) { inc("fourWins"); event("win"); r.diamonds += 2; r.lines.add("Победа команды: +2 💎") }
		else if (draw) r.lines.add("Ничья")
		if (plies >= 12) markActive(r)
		val rainbow = Shop.byId("wg_rainbow")?.req
		if (win && rainbow != null && stat("fourWins") <= rainbow.target)
			r.lines.add(if (rainbow.test(this)) "🌈 Открыт скин виджета «Радуга»!" else "🌈 До «Радуги»: ${rainbow.progress(this)}/${rainbow.target} побед")
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	// Партия по Wi-Fi или Bluetooth — награды как за онлайн
	fun lanFinished(score: Double, plies: Int): Reward {
		inc("lan")
		return onlineFinished(score, plies, true)
	}

	// Партия на lichess. score: 1 победа, 0.5 ничья, 0 поражение
	fun onlineFinished(score: Double, plies: Int, lan: Boolean = false): Reward {
		val r = Reward()
		if (!lan) inc("online")
		event("game"); event("online")
		if (score >= 1.0) event("win")
		val g = when { score >= 1.0 -> { inc("onlineWins"); 3 }; score > 0 -> 1; else -> 0 }
		if (g > 0) { r.diamonds += g; r.lines.add("${if (lan) "Партия по сети" else "Онлайн-партия"}: +$g 💎") }
		if (plies >= 6) markActive(r)
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	// Тактика и бриллианты человека (в партиях с ботом). Возвращает награды за открытые достижения.
	fun countMotif(key: String): Reward {
		val r = Reward()
		inc(key)
		event(if (key == "brilliant" || key == "great") key else "tactic")
		checkAchievements(r)
		if (r.diamonds > 0) addDiamonds(r.diamonds) else changed()
		return r
	}

	fun puzzleSolved(id: String, reward: Int, clean: Boolean, daily: Boolean): Reward {
		val r = Reward()
		val solved = sp.getStringSet("solved", emptySet())!!.toMutableSet()
		val first = solved.add(id)
		sp.edit().putStringSet("solved", solved).apply()
		event("puzzle")
		if (clean) event("puzzleClean")
		val rep = repeatDueToday(id)
		updateRepeat(id, clean)
		if (rep && !first) {
			r.lines.add(if (clean) "🔁 Повторение засчитано" else "🔁 Задача вернётся завтра")
		}
		if (first) {
			inc("puzzles")
			if (clean) inc("puzzlesClean")
			if (id.startsWith("gen_")) inc("genSolved")
			val g = if (clean) reward else 0
			r.diamonds += g
			r.lines.add(if (g > 0) "Задача решена: +$g 💎" else "Задача решена (с ошибкой или подсказкой — без алмазов)")
			if (daily) {
				r.diamonds += 2
				r.lines.add("🗓 Задача дня: +2 💎")
				sp.edit().putLong("dailySolved", today).apply()
			}
			markActive(r)
		} else if (!rep) r.lines.add("Эта задача уже была решена — без награды")
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	fun isSolved(id: String) = sp.getStringSet("solved", emptySet())!!.contains(id)
	val dailySolvedToday get() = sp.getLong("dailySolved", -1) == today

	fun hintUsed() = inc("hints")

	// ---------- рейтинг в задачах (Эло, K = 24; засчитывается только первая попытка) ----------
	val puzzleRating get() = int("puzzleRating", 1200)

	fun puzzleRated(id: String, rating: Int, success: Boolean): Int {
		if (rating <= 0) return 0
		val done = sp.getStringSet("ratedPuzzles", emptySet())!!.toMutableSet()
		if (!done.add(id)) return 0
		sp.edit().putStringSet("ratedPuzzles", done).apply()
		val exp = 1.0 / (1 + 10.0.pow((rating - puzzleRating) / 400.0))
		val d = (24 * ((if (success) 1.0 else 0.0) - exp)).roundToInt()
		setInt("puzzleRating", (puzzleRating + d).coerceAtLeast(100))
		max("puzzleRatingBest", puzzleRating)
		changed()
		return d
	}

	fun wasRated(id: String) = sp.getStringSet("ratedPuzzles", emptySet())!!.contains(id)

	// ---------- повтор ошибок: интервалы 1, 3, 7, 14 дней ----------
	private val intervals = intArrayOf(1, 3, 7, 14)

	private fun repeatMap(): JSONObject = try { JSONObject(sp.getString("repeat", "{}")!!) } catch (_: Exception) { JSONObject() }

	fun repeatIds(): List<String> {
		val m = repeatMap()
		return m.keys().asSequence().filter { m.getJSONObject(it).optLong("due") <= today }.toList()
	}

	fun repeatCount() = repeatMap().length()

	private fun repeatDueToday(id: String): Boolean {
		val o = repeatMap().optJSONObject(id) ?: return false
		return o.optLong("due") <= today
	}

	// ошибка в задаче — ставим в очередь; чистое решение в срок — следующий интервал
	fun updateRepeat(id: String, clean: Boolean) {
		val m = repeatMap()
		val o = m.optJSONObject(id)
		if (!clean) m.put(id, JSONObject().put("due", today + 1).put("stage", 0))
		else if (o != null && o.optLong("due") <= today) {
			val st = o.optInt("stage") + 1
			if (st >= intervals.size) m.remove(id) else m.put(id, JSONObject().put("due", today + intervals[st]).put("stage", st))
		}
		sp.edit().putString("repeat", m.toString()).apply()
	}

	// ---------- штурм ----------
	val stormBest get() = stat("stormBest")

	fun stormFinished(score: Int): Reward {
		val r = Reward()
		inc("storms")
		val best = score > stormBest
		max("stormBest", score)
		event("storm", score)
		val g = score / 5
		if (g > 0) { r.diamonds += g; r.lines.add("Штурм: +$g 💎") }
		if (best && score > 0) r.lines.add("🏅 Новый рекорд: $score")
		if (score >= 5) markActive(r)
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	// ---------- эндшпиль и дебюты ----------
	fun endgameDone(id: String) = sp.getStringSet("endgames", emptySet())!!.contains(id)

	fun endgameFinished(id: String, success: Boolean, reward: Int): Reward {
		val r = Reward()
		if (!success) return r
		event("endgame")
		val set = sp.getStringSet("endgames", emptySet())!!.toMutableSet()
		if (set.add(id)) {
			sp.edit().putStringSet("endgames", set).apply()
			setInt("st_endgames", set.size)
			r.diamonds += reward; r.lines.add("Эндшпиль пройден: +$reward 💎")
		} else r.lines.add("Пройдено снова — награда только за первый раз")
		markActive(r)
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	fun openingDone(name: String) = sp.getStringSet("openings", emptySet())!!.contains(name)

	fun openingFinished(name: String): Reward {
		val r = Reward()
		event("opening")
		val set = sp.getStringSet("openings", emptySet())!!.toMutableSet()
		if (set.add(name)) {
			sp.edit().putStringSet("openings", set).apply()
			setInt("st_openings", set.size)
			r.diamonds += 1; r.lines.add("Линия выучена: +1 💎")
		}
		markActive(r)
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	// ---------- цели на день и неделю ----------
	val week: Long get() = (today + 3) / 7 // недели с понедельника

	fun event(key: String, amount: Int = 1) {
		for ((period, list) in listOf("d$today" to Goals.daily(today), "w$week" to Goals.weekly(week))) {
			list.forEachIndexed { i, g ->
				if (g.key != key) return@forEachIndexed
				val k = "goal_${period}_$i"
				val cur = int(k)
				setInt(k, if (g.max) maxOf(cur, amount) else cur + amount)
			}
		}
	}

	fun goalProgress(daily: Boolean, i: Int) = int("goal_${if (daily) "d$today" else "w$week"}_$i")
	fun goalClaimed(daily: Boolean, i: Int) = sp.getBoolean("goalc_${if (daily) "d$today" else "w$week"}_$i", false)

	fun claimGoal(daily: Boolean, i: Int): Int {
		val list = if (daily) Goals.daily(today) else Goals.weekly(week)
		val g = list[i]
		if (goalClaimed(daily, i) || goalProgress(daily, i) < g.target) return 0
		sp.edit().putBoolean("goalc_${if (daily) "d$today" else "w$week"}_$i", true).apply()
		var total = g.reward
		if (daily && list.indices.all { goalClaimed(true, it) }) total += 3
		inc("goals")
		val r = Reward()
		checkAchievements(r)
		addDiamonds(total + r.diamonds)
		return total + r.diamonds
	}

	// ---------- достижения ----------
	fun achieved(id: String) = sp.getBoolean("ach_$id", false)

	private fun checkAchievements(r: Reward) {
		for (a in Achievements.ALL) {
			if (achieved(a.id) || !a.test(this)) continue
			sp.edit().putBoolean("ach_${a.id}", true).apply()
			r.diamonds += a.reward
			r.lines.add("🏆 ${a.title}: +${a.reward} 💎")
		}
	}

	fun progressOf(a: Achievements.Ach) = a.progress(this)

	// ---------- магазин ----------
	fun owns(id: String): Boolean {
		val item = Shop.byId(id) ?: return false
		item.req?.let { return it.test(this) } // предмет за достижение — условие проверяется каждый раз
		return item.price == 0 || sp.getStringSet("owned", emptySet())!!.contains(id)
	}

	fun buy(item: Shop.Item): Boolean {
		if (owns(item.id)) return true
		if (item.req != null) return false
		if (!spend(item.price)) return false
		val o = sp.getStringSet("owned", emptySet())!!.toMutableSet()
		o.add(item.id)
		sp.edit().putStringSet("owned", o).apply()
		inc("bought")
		val r = Reward()
		checkAchievements(r)
		if (r.diamonds > 0) addDiamonds(r.diamonds) else changed()
		return true
	}

	fun selected(cat: Shop.Cat): String = sp.getString("sel_${cat.name}", if (cat == Shop.Cat.SOUND) "sn_classic" else Shop.items(cat).first().id)!!
	fun select(cat: Shop.Cat, id: String) { sp.edit().putString("sel_${cat.name}", id).apply(); changed() }

	// ---------- настройки ----------
	fun flag(k: String, d: Boolean) = sp.getBoolean("set_$k", d)
	fun setFlag(k: String, v: Boolean) { sp.edit().putBoolean("set_$k", v).apply(); changed() }
	fun str(k: String, d: String) = sp.getString("set_$k", d)!!
	fun setStr(k: String, v: String) = sp.edit().putString("set_$k", v).apply()

	// ---------- история партий ----------
	private val gamesFile = File(ctx.filesDir, "games.json")

	fun saveGame(o: JSONObject) {
		val arr = loadGames()
		arr.put(o)
		val out = JSONArray()
		val from = maxOf(0, arr.length() - 300)
		for (i in from until arr.length()) out.put(arr.get(i))
		gamesFile.writeText(out.toString())
	}

	fun loadGames(): JSONArray = try { JSONArray(gamesFile.readText()) } catch (e: Exception) { JSONArray() }

	fun deleteGame(index: Int) {
		val arr = loadGames()
		arr.remove(index)
		gamesFile.writeText(arr.toString())
	}

	companion object {
		@Volatile private var inst: Store? = null
		fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }

		fun days(n: Int): String {
			val m10 = n % 10; val m100 = n % 100
			return when {
				m10 == 1 && m100 != 11 -> "день"
				m10 in 2..4 && m100 !in 12..14 -> "дня"
				else -> "дней"
			}
		}
	}
}

object Achievements {
	class Ach(val id: String, val title: String, val desc: String, base: Int, val target: Int, val value: (Store) -> Int) {
		val reward = maxOf(2, base / 2)
		fun test(s: Store) = value(s) >= target
		fun progress(s: Store) = minOf(value(s), target)
	}

	val ALL = listOf(
		Ach("first_game", "Первый шаг", "Сыграть первую партию", 5, 1) { it.stat("games") },
		Ach("first_win", "Первая победа", "Победить любого бота", 10, 1) { it.stat("wins") },
		Ach("wins10", "Десятка", "10 побед над ботами", 25, 10) { it.stat("wins") },
		Ach("wins50", "Полсотни", "50 побед над ботами", 80, 50) { it.stat("wins") },
		Ach("algo5", "Перехитрить правила", "Победить Алгоритм уровня «Мастер»", 20, 5) { it.stat("algoBest") },
		Ach("mat4", "Глубже материала", "Победить Материал глубины 4 и выше", 20, 4) { it.stat("matBest") },
		Ach("mat6", "Бездна материала", "Победить Материал глубины 6 и выше", 40, 6) { it.stat("matBest") },
		Ach("sf1350", "Рыбалка", "Победить Stockfish 1350", 25, 1350) { it.stat("sfBest") },
		Ach("sf1700", "Крупная рыба", "Победить Stockfish 1700", 40, 1700) { it.stat("sfBest") },
		Ach("sf2100", "Кандидат", "Победить Stockfish 2100", 60, 2100) { it.stat("sfBest") },
		Ach("sf2400", "Мастер", "Победить Stockfish 2400", 100, 2400) { it.stat("sfBest") },
		Ach("sf3000", "Невозможное", "Победить Stockfish 3000", 300, 3000) { it.stat("sfBest") },
		Ach("fastmate", "Блиц-мат", "Поставить мат боту за 20 ходов или быстрее", 15, 1) { it.stat("fastMates") },
		Ach("streak3", "Разогрев", "Серия 3 дня", 10, 3) { it.bestStreak },
		Ach("streak7", "Неделя огня", "Серия 7 дней", 25, 7) { it.bestStreak },
		Ach("streak30", "Месяц огня", "Серия 30 дней", 80, 30) { it.bestStreak },
		Ach("streak100", "Сотня", "Серия 100 дней", 250, 100) { it.bestStreak },
		Ach("puz10", "Решатель", "Решить 10 задач", 10, 10) { it.stat("puzzles") },
		Ach("puz50", "Тактик", "Решить 50 задач", 30, 50) { it.stat("puzzles") },
		Ach("puz150", "Гроза матов", "Решить 150 задач", 80, 150) { it.stat("puzzles") },
		Ach("brill1", "Бриллиант", "Сыграть бриллиантовый ход (!!) против бота", 30, 1) { it.stat("brilliant") },
		Ach("brill5", "Ювелир", "5 бриллиантовых ходов", 80, 5) { it.stat("brilliant") },
		Ach("brill20", "Алмазный фонд", "20 бриллиантовых ходов", 250, 20) { it.stat("brilliant") },
		Ach("great10", "Единственный ход", "10 отличных ходов (!) — когда остальные проигрывают", 30, 10) { it.stat("great") },
		Ach("fork1", "Вилка", "Взять фигуру после своей вилки (fork)", 15, 1) { it.stat("fork") },
		Ach("fork10", "Вилочник", "10 реализованных вилок", 50, 10) { it.stat("fork") },
		Ach("skewer1", "Шампур", "Взять фигуру после своего шампура (skewer)", 20, 1) { it.stat("skewer") },
		Ach("skewer5", "Мастер шампура", "5 реализованных шампуров", 60, 5) { it.stat("skewer") },
		Ach("qtrap1", "Ловушка для ферзя", "Поймать ферзя соперника (queen trap) и забрать его", 40, 1) { it.stat("queenTrap") },
		Ach("qtrap3", "Охотник на ферзей", "Поймать ферзя 3 раза", 100, 3) { it.stat("queenTrap") },
		Ach("online1", "Выход в свет", "Сыграть партию на lichess", 10, 1) { it.stat("online") },
		Ach("onlineWin1", "Победа в сети", "Выиграть партию на lichess", 25, 1) { it.stat("onlineWins") },
		Ach("onlineWin10", "Гроза lichess", "10 побед на lichess", 80, 10) { it.stat("onlineWins") },
		Ach("storm10", "Штурмовик", "Набрать 10 в штурме задач", 15, 10) { it.stat("stormBest") },
		Ach("storm25", "Ураган", "Набрать 25 в штурме задач", 50, 25) { it.stat("stormBest") },
		Ach("prate1600", "Тактик 1600", "Рейтинг в задачах 1600", 30, 1600) { it.stat("puzzleRatingBest") },
		Ach("prate2000", "Тактик 2000", "Рейтинг в задачах 2000", 100, 2000) { it.stat("puzzleRatingBest") },
		Ach("eg3", "Эндшпильщик", "Пройти 3 позиции тренажёра эндшпиля", 20, 3) { it.stat("endgames") },
		Ach("egAll", "Техника", "Пройти все позиции тренажёра эндшпиля", 120, Endgames.ALL.size) { it.stat("endgames") },
		Ach("op10", "Теоретик", "Выучить 10 дебютных линий", 30, 10) { it.stat("openings") },
		Ach("goals20", "Целеустремлённый", "Выполнить 20 целей", 40, 20) { it.stat("goals") },
		Ach("lan1", "По соседству", "Сыграть партию по Wi-Fi или Bluetooth", 15, 1) { it.stat("lan") },
		Ach("four1", "Командный игрок", "Победить в шахматах на четверых", 20, 1) { it.stat("fourWins") },
		Ach("gen5", "Генератор", "Решить 5 сгенерированных задач", 20, 5) { it.stat("genSolved") },
		Ach("hvh5", "Живая доска", "Сыграть 5 партий на одном телефоне", 15, 5) { it.stat("hvh") },
		Ach("shop5", "Коллекционер", "Купить 5 вещей в магазине", 30, 5) { it.stat("bought") }
	)
}
