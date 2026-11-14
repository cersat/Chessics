package ru.cersat.chessics.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

// Соединение двух телефонов: строки JSON по TCP (Wi-Fi) или RFCOMM (Bluetooth)
class Link(private val input: InputStream, private val output: OutputStream, private val closer: () -> Unit) {
	private val main = Handler(Looper.getMainLooper())
	private val sendExec = Executors.newSingleThreadExecutor()
	@Volatile var closed = false
		private set
	var onMessage: (JSONObject) -> Unit = {}
	var onClosed: () -> Unit = {}
	private val early = ArrayList<JSONObject>()
	private var attached = false

	fun start() {
		Thread({
			try {
				val r = BufferedReader(InputStreamReader(input))
				while (!closed) {
					val line = r.readLine() ?: break
					val o = try { JSONObject(line) } catch (_: Exception) { continue }
					main.post { if (attached) onMessage(o) else early.add(o) }
				}
			} catch (_: Exception) {}
			if (!closed) { closed = true; main.post { onClosed() } }
		}, "link").apply { isDaemon = true }.start()
	}

	// подписка: накопленные до подписки сообщения тоже доставляются
	fun attach(handler: (JSONObject) -> Unit) {
		onMessage = handler
		attached = true
		val list = early.toList(); early.clear()
		list.forEach(handler)
	}

	fun send(o: JSONObject) {
		if (closed) return
		sendExec.execute {
			try { output.write((o.toString() + "\n").toByteArray()); output.flush() }
			catch (_: Exception) { if (!closed) { closed = true; main.post { onClosed() } } }
		}
	}

	fun close() {
		if (closed) return
		closed = true
		try { closer() } catch (_: Exception) {}
	}

	companion object {
		@Volatile var current: Link? = null
	}
}

object Lan {
	const val SERVICE = "_chessics._tcp."
	val BT_UUID: UUID = UUID.fromString("6f1d2c8e-5a3b-4c7d-9e21-c0ffee5c4e55")
	private val exec = Executors.newCachedThreadPool { r -> Thread(r, "lan").apply { isDaemon = true } }
	private val main = Handler(Looper.getMainLooper())

	class Waiting(val stop: () -> Unit)

	fun localIp(): String? = try {
		NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
			.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
	} catch (_: Exception) { null }

	// ---------- Wi-Fi ----------

	// Хост: открывает порт и объявляет себя в локальной сети (NSD), ждёт одного гостя
	fun hostWifi(ctx: Context, name: String, onReady: (ip: String?, port: Int) -> Unit, onLink: (Link) -> Unit, onError: (String) -> Unit): Waiting {
		val server = ServerSocket(0)
		val nsd = ctx.getSystemService(NsdManager::class.java)
		val reg = object : NsdManager.RegistrationListener {
			override fun onServiceRegistered(info: NsdServiceInfo) {}
			override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
			override fun onServiceUnregistered(info: NsdServiceInfo) {}
			override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
		}
		try {
			nsd.registerService(NsdServiceInfo().apply { serviceName = "Chessics · $name"; serviceType = SERVICE; port = server.localPort },
				NsdManager.PROTOCOL_DNS_SD, reg)
		} catch (_: Exception) {}
		main.post { onReady(localIp(), server.localPort) }
		var stopped = false
		exec.execute {
			try {
				val s = server.accept()
				server.close()
				try { nsd.unregisterService(reg) } catch (_: Exception) {}
				val l = Link(s.getInputStream(), s.getOutputStream()) { s.close() }
				main.post { if (!stopped) onLink(l) else l.close() }
			} catch (e: Exception) { if (!stopped) main.post { onError(e.message ?: "ошибка сети") } }
		}
		return Waiting { stopped = true; try { server.close() } catch (_: Exception) {}; try { nsd.unregisterService(reg) } catch (_: Exception) {} }
	}

	// Поиск хостов в сети
	fun discover(ctx: Context, onFound: (name: String, host: String, port: Int) -> Unit): Waiting {
		val nsd = ctx.getSystemService(NsdManager::class.java)
		val listener = object : NsdManager.DiscoveryListener {
			override fun onStartDiscoveryFailed(t: String, c: Int) {}
			override fun onStopDiscoveryFailed(t: String, c: Int) {}
			override fun onDiscoveryStarted(t: String) {}
			override fun onDiscoveryStopped(t: String) {}
			override fun onServiceLost(info: NsdServiceInfo) {}
			override fun onServiceFound(info: NsdServiceInfo) {
				if (!info.serviceType.contains("chessics")) return
				@Suppress("DEPRECATION")
				nsd.resolveService(info, object : NsdManager.ResolveListener {
					override fun onResolveFailed(i: NsdServiceInfo, c: Int) {}
					override fun onServiceResolved(i: NsdServiceInfo) {
						@Suppress("DEPRECATION")
						val h = i.host?.hostAddress ?: return
						main.post { onFound(i.serviceName, h, i.port) }
					}
				})
			}
		}
		try { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) {}
		return Waiting { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {} }
	}

	fun joinWifi(host: String, port: Int, onLink: (Link) -> Unit, onError: (String) -> Unit) {
		exec.execute {
			try {
				val s = Socket()
				s.connect(InetSocketAddress(host, port), 8000)
				val l = Link(s.getInputStream(), s.getOutputStream()) { s.close() }
				main.post { onLink(l) }
			} catch (e: Exception) { main.post { onError(e.message ?: "не удалось подключиться") } }
		}
	}

	// ---------- Bluetooth ----------

	fun bt(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

	@SuppressLint("MissingPermission")
	fun paired(ctx: Context): List<BluetoothDevice> = try { bt(ctx)?.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }

	@SuppressLint("MissingPermission")
	fun deviceName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: SecurityException) { d.address }

	@SuppressLint("MissingPermission")
	fun hostBt(ctx: Context, onLink: (Link) -> Unit, onError: (String) -> Unit): Waiting {
		var server: BluetoothServerSocket? = null
		var stopped = false
		exec.execute {
			try {
				val srv = bt(ctx)!!.listenUsingRfcommWithServiceRecord("Chessics", BT_UUID)
				server = srv
				val s = srv.accept()
				srv.close()
				val l = Link(s.inputStream, s.outputStream) { s.close() }
				main.post { if (!stopped) onLink(l) else l.close() }
			} catch (e: Exception) { if (!stopped) main.post { onError(e.message ?: "ошибка Bluetooth") } }
		}
		return Waiting { stopped = true; try { server?.close() } catch (_: Exception) {} }
	}

	@SuppressLint("MissingPermission")
	fun joinBt(ctx: Context, d: BluetoothDevice, onLink: (Link) -> Unit, onError: (String) -> Unit) {
		exec.execute {
			try {
				try { bt(ctx)?.cancelDiscovery() } catch (_: Exception) {}
				val s = d.createRfcommSocketToServiceRecord(BT_UUID)
				s.connect()
				val l = Link(s.inputStream, s.outputStream) { s.close() }
				main.post { onLink(l) }
			} catch (e: Exception) { main.post { onError(e.message ?: "не удалось подключиться") } }
		}
	}
}
