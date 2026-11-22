package ru.cersat.chessics.ui

import ru.cersat.chessics.R
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher
import ru.cersat.chessics.data.Reminder
import ru.cersat.chessics.data.Store
import ru.cersat.chessics.data.Widgets

abstract class Screen(val a: MainActivity) {
	val ui = a.ui
	abstract fun build(): View
	open val tab: Int = -1 // >= 0 — экран вкладки, показывать нижнюю навигацию
	open fun onBack(): Boolean = false
	open fun dispose() {}
	open fun onPause() {}
}

open class MainActivity : Activity() {
	// открыто для настройки виджета быстрого старта
	private var configWidget = -1
	lateinit var ui: Ui
	private lateinit var content: FrameLayout
	private lateinit var nav: LinearLayout
	private val stack = ArrayList<Screen>()
	private var navTab = 0
	val store by lazy { Store.get(this) }

	override fun onCreate(saved: Bundle?) {
		super.onCreate(saved)
		ui = Ui(this)
		if (Build.VERSION.SDK_INT >= 30) {
			val light = if (ui.t.light) (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) else 0
			window.decorView.post {
				window.insetsController?.setSystemBarsAppearance(light,
					android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
			}
		} else if (ui.t.light) {
			@Suppress("DEPRECATION")
			window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
			@Suppress("DEPRECATION")
			window.statusBarColor = ui.t.bg
			@Suppress("DEPRECATION")
			window.navigationBarColor = ui.t.bg
		} else {
			@Suppress("DEPRECATION")
			window.statusBarColor = ui.t.bg
			@Suppress("DEPRECATION")
			window.navigationBarColor = ui.t.bg
		}
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(ui.t.bg)
		}
		content = FrameLayout(this)
		root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		nav = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setBackgroundColor(ui.t.card)
			setPadding(0, ui.dp(6), 0, ui.dp(6))
		}
		root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		root.setOnApplyWindowInsetsListener { v, ins ->
			if (Build.VERSION.SDK_INT >= 30) {
				val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
				v.setPadding(b.left, b.top, b.right, b.bottom)
			} else {
				@Suppress("DEPRECATION")
				v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
			}
			WindowInsets.CONSUMED
		}
		setContentView(root)

		if (Build.VERSION.SDK_INT >= 33) {
			onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
		}

		if (intent?.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
			configWidget = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
			setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configWidget))
			if (configWidget < 0) { finish(); return }
			stack.add(WidgetConfigScreen(this, configWidget))
			render()
			return
		}
		val restoreTab = saved?.getInt("tab", 0) ?: 0
		showTab(restoreTab)
		handleIntent(intent)

		val msg = store.checkStreak()
		if (msg != null) toast(msg)
		Reminder.schedule(this)
		if (Build.VERSION.SDK_INT >= 33 && store.flag("remind", true) && !store.flag("askedNotif", false)) {
			store.setFlag("askedNotif", true)
			if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
				requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
		}
	}

	override fun onSaveInstanceState(out: Bundle) {
		super.onSaveInstanceState(out)
		out.putInt("tab", navTab)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(i: Intent?) {
		if (i?.action == "ru.cersat.chessics.LAUNCH") {
			val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
			if (id >= 0) WidgetSetup.launch(this, id)
			return
		}
		if (i?.action == "ru.cersat.chessics.DAILY") {
			val pz = ru.cersat.chessics.data.Puzzles.daily(this) ?: return
			push(PuzzleScreen(this, pz, true))
		}
	}

	override fun onPause() {
		super.onPause()
		stack.lastOrNull()?.onPause()
		Widgets.updateAll(this)
	}

	override fun onDestroy() {
		super.onDestroy()
		stack.forEach { it.dispose() }
	}

	@Deprecated("Для Android < 13")
	override fun onBackPressed() { goBack() }

	fun goBack() {
		val top = stack.lastOrNull()
		if (top != null && top.onBack()) return
		if (stack.size > 1) pop()
		else if (configWidget >= 0) finish() // выход из настройки виджета без выбора — виджет не добавится
		else if (navTab != 0) showTab(0)
		else finish()
	}

	private fun render() {
		val top = stack.last()
		content.removeAllViews()
		content.addView(top.build(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		nav.visibility = if (top.tab >= 0) View.VISIBLE else View.GONE
		if (top.tab >= 0) buildNav(top.tab)
	}

	fun push(s: Screen) {
		stack.lastOrNull()?.onPause()
		stack.add(s)
		render()
	}

	fun replace(s: Screen) {
		stack.removeLastOrNull()?.dispose()
		stack.add(s)
		render()
	}

	fun pop() {
		stack.removeLastOrNull()?.dispose()
		if (stack.isEmpty()) showTab(0) else render()
	}

	fun refresh() { if (stack.isNotEmpty()) render() }

	fun showTab(i: Int) {
		stack.forEach { it.dispose() }
		stack.clear()
		navTab = i
		stack.add(when (i) {
			1 -> PuzzlesScreen(this)
			2 -> VariantListScreen(this)
			3 -> ShopScreen(this)
			4 -> ProfileScreen(this)
			else -> HomeScreen(this)
		})
		render()
	}

	// После смены темы пересоздаём активность (цвета берутся при создании)
	fun restyle() {
		recreate()
	}

	private fun buildNav(active: Int) {
		nav.removeAllViews()
		val items = listOf(R.drawable.ic_nav_play to "Играть", R.drawable.ic_nav_puzzle to "Задачи", R.drawable.ic_nav_variants to "Варианты",
			R.drawable.ic_nav_shop to "Магазин", R.drawable.ic_nav_profile to "Профиль")
		items.forEachIndexed { i, (ic, label) ->
			val sel = i == active
			val c = if (sel) ui.t.accent else ui.t.sub
			val icon = android.widget.ImageView(this).apply { setImageResource(ic); setColorFilter(c) }
			val box = ui.col(0, 0)
			box.addView(icon, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { gravity = Gravity.CENTER_HORIZONTAL })
			box.addView(ui.text(label, 11f, c, sel, Gravity.CENTER))
			box.gravity = Gravity.CENTER
			box.setPadding(0, ui.dp(4), 0, ui.dp(4))
			box.setOnClickListener { if (!sel) showTab(i) }
			nav.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		}
	}

	fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

	fun dialog(title: String, body: View, vararg buttons: Pair<String, () -> Unit>, cancelable: Boolean = true): AlertDialog {
		val box = ui.col(20, 12)
		box.background = ui.round(ui.t.card, 22f)
		box.addView(ui.text(title, 20f, ui.t.text, true))
		box.addView(body)
		val d = AlertDialog.Builder(this).setView(box).setCancelable(cancelable).create()
		val btns = ui.col(0, 8)
		for ((label, act) in buttons) btns.addView(ui.button(label, btns.childCount == 0) { d.dismiss(); act() }, ui.lp())
		box.addView(btns)
		d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
		d.show()
		return d
	}
}
