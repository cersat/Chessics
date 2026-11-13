package ru.cersat.chessics.data

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

// Клиент Lichess Board API. Токен — личный (lichess.org/account/oauth/token), права board:play и challenge:write.
object Lichess {
	const val HOST = "https://lichess.org"
	const val TOKEN_URL = "$HOST/account/oauth/token/create?scopes[]=board:play&scopes[]=challenge:read&scopes[]=challenge:write&description=Chessics"

	val exec = Executors.newCachedThreadPool { r -> Thread(r, "lichess").apply { isDaemon = true } }

	class Error(val code: Int, msg: String) : Exception(msg)

	fun token(ctx: Context) = Store.get(ctx).str("lichessToken", "")
	fun setToken(ctx: Context, t: String) { Store.get(ctx).setStr("lichessToken", t.trim()); Store.get(ctx).setStr("lichessUser", "") }
	fun user(ctx: Context) = Store.get(ctx).str("lichessUser", "")

	private fun form(f: Map<String, String>) = f.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

	fun open(ctx: Context, method: String, path: String, f: Map<String, String>? = null, timeout: Int = 15000): HttpURLConnection {
		val c = URL(HOST + path).openConnection() as HttpURLConnection
		c.requestMethod = method
		c.connectTimeout = 15000
		c.readTimeout = timeout
		c.setRequestProperty("Authorization", "Bearer ${token(ctx)}")
		c.setRequestProperty("Accept", "application/json")
		c.setRequestProperty("User-Agent", "Chessics (github.com/cersat/Chessics)")
		if (f != null) {
			c.doOutput = true
			c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
			c.outputStream.use { it.write(form(f).toByteArray()) }
		}
		return c
	}

	private fun errorText(c: HttpURLConnection): String = try {
		val body = c.errorStream?.bufferedReader()?.readText() ?: ""
		JSONObject(body).optString("error", body)
	} catch (e: Exception) { "HTTP ${c.responseCode}" }

	// Обычный запрос → JSON
	fun call(ctx: Context, method: String, path: String, f: Map<String, String>? = null): JSONObject {
		val c = open(ctx, method, path, f)
		try {
			val code = c.responseCode
			if (code !in 200..299) throw Error(code, errorText(c))
			val body = c.inputStream.bufferedReader().readText()
			return if (body.isBlank()) JSONObject() else JSONObject(body)
		} finally { c.disconnect() }
	}

	// Поток ndjson: вызывает onEvent на каждую строку, пока соединение открыто. Закрыть — handle.close()
	class Stream { @Volatile var conn: HttpURLConnection? = null; @Volatile var closed = false
		fun close() { closed = true; try { conn?.disconnect() } catch (_: Exception) {} }
	}

	fun stream(ctx: Context, method: String, path: String, f: Map<String, String>?, handle: Stream, onEvent: (JSONObject) -> Unit): Exception? {
		return try {
			val c = open(ctx, method, path, f, 0)
			handle.conn = c
			val code = c.responseCode
			if (code !in 200..299) return Error(code, errorText(c))
			val r = BufferedReader(InputStreamReader(c.inputStream))
			while (!handle.closed) {
				val line = r.readLine() ?: break
				if (line.isBlank()) continue
				try { onEvent(JSONObject(line)) } catch (_: org.json.JSONException) {}
			}
			null
		} catch (e: Exception) { if (handle.closed) null else e }
	}

	fun account(ctx: Context): JSONObject {
		val a = call(ctx, "GET", "/api/account")
		Store.get(ctx).setStr("lichessUser", a.optString("username"))
		Store.get(ctx).setStr("lichessId", a.optString("id"))
		return a
	}

	fun myId(ctx: Context) = Store.get(ctx).str("lichessId", "")
}
