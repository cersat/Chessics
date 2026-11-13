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
		r.diamonds += 3
		r.lines.add("🔥 Серия: $s ${days(s)} (+3 💎)")
		val bonus = when (s) { 3 -> 10; 7 -> 25; 14 -> 40; 30 -> 100; 50 -> 150; 100 -> 300; 180 -> 400; 365 -> 1000; else -> 0 }
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
		if (hvh) {
			inc("hvh")
			if (plies >= 10) markActive(r)
		} else if (bot != null) {
			when {
				score >= 1.0 -> inc("wins")
				score > 0.0 -> inc("draws")
				else -> inc("losses")
			}
			val elo = bot.elo
			var gain = 0
			if (score >= 1.0) gain = maxOf(2, (elo / 150.0).roundToInt())
			else if (score > 0) gain = maxOf(1, (elo / 300.0).roundToInt())
			else if (plies >= 20) gain = 1
			if (takebacks > 0 && gain > 1) { gain /= 2; r.lines.add("↩ Были отмены ходов — награда вдвое меньше") }
			if (timeControl && score >= 1.0) { gain += 2; r.lines.add("⏱ Партия на время: +2 💎") }
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

	// Партия на lichess. score: 1 победа, 0.5 ничья, 0 поражение
	fun onlineFinished(score: Double, plies: Int): Reward {
		val r = Reward()
		inc("online")
		val g = when { score >= 1.0 -> { inc("onlineWins"); 6 }; score > 0 -> 3; plies >= 20 -> 1; else -> 0 }
		if (g > 0) { r.diamonds += g; r.lines.add("Онлайн-партия: +$g 💎") }
		if (plies >= 6) markActive(r)
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	// Тактика и бриллианты человека (в партиях с ботом). Возвращает награды за открытые достижения.
	fun countMotif(key: String): Reward {
		val r = Reward()
		inc(key)
		checkAchievements(r)
		if (r.diamonds > 0) addDiamonds(r.diamonds) else changed()
		return r
	}

	fun puzzleSolved(id: String, reward: Int, clean: Boolean, daily: Boolean): Reward {
		val r = Reward()
		val solved = sp.getStringSet("solved", emptySet())!!.toMutableSet()
		val first = solved.add(id)
		sp.edit().putStringSet("solved", solved).apply()
		if (first) {
			inc("puzzles")
			if (clean) inc("puzzlesClean")
			if (id.startsWith("gen_")) inc("genSolved")
			val g = if (clean) reward else 1
			r.diamonds += g
			r.lines.add("Задача решена: +$g 💎")
			if (daily) {
				r.diamonds += 5
				r.lines.add("🗓 Задача дня: +5 💎")
				sp.edit().putLong("dailySolved", today).apply()
			}
			markActive(r)
		} else r.lines.add("Эта задача уже была решена — без награды")
		checkAchievements(r)
		addDiamonds(r.diamonds)
		return r
	}

	fun isSolved(id: String) = sp.getStringSet("solved", emptySet())!!.contains(id)
	val dailySolvedToday get() = sp.getLong("dailySolved", -1) == today

	fun hintUsed() = inc("hints")

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
	fun owns(id: String) = Shop.byId(id)?.price == 0 || sp.getStringSet("owned", emptySet())!!.contains(id)

	fun buy(item: Shop.Item): Boolean {
		if (owns(item.id)) return true
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

	fun selected(cat: Shop.Cat): String = sp.getString("sel_${cat.name}", Shop.items(cat).first().id)!!
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
	class Ach(val id: String, val title: String, val desc: String, val reward: Int, val target: Int, val value: (Store) -> Int) {
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
		Ach("gen5", "Генератор", "Решить 5 сгенерированных задач", 20, 5) { it.stat("genSolved") },
		Ach("hvh5", "Живая доска", "Сыграть 5 партий на одном телефоне", 15, 5) { it.stat("hvh") },
		Ach("shop5", "Коллекционер", "Купить 5 вещей в магазине", 30, 5) { it.stat("bought") }
	)
}
