import java.net.URI
import java.security.MessageDigest

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

android {
	namespace = "ru.cersat.chessics"
	compileSdk = 36
	ndkVersion = "28.2.13676358"

	defaultConfig {
		applicationId = "ru.cersat.chessics"
		minSdk = 26
		targetSdk = 36
		versionCode = 6
		versionName = "1.0.5"
		ndk { abiFilters += listOf("arm64-v8a") }
	}

	// full — все наборы фигур (релизы на GitHub); fdroid — только со свободными лицензиями
	flavorDimensions += "dist"
	productFlavors {
		create("full") { dimension = "dist" }
		create("fdroid") { dimension = "dist" }
	}

	signingConfigs {
		create("release") {
			storeFile = file("../chessics.jks")
			storePassword = "chessics"
			keyAlias = "chessics"
			keyPassword = "chessics"
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			if (file("../chessics.jks").exists()) signingConfig = signingConfigs.getByName("release")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	packaging {
		jniLibs { useLegacyPackaging = true }
	}

	androidResources {
		noCompress += listOf("nnue")
	}

	sourceSets {
		getByName("main") {
			jniLibs.srcDir(layout.buildDirectory.dir("stockfish/jniLibs"))
		}
	}

	dependenciesInfo {
		// F-Droid не принимает зашифрованный блок зависимостей Google
		includeInApk = false
		includeInBundle = false
	}
}

kotlin {
	compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Нейросети Stockfish (75 МБ) не храним в git — скачиваем перед сборкой.
// Имя файла — начало его SHA-256, его и проверяем.
val nets = listOf("nn-1c0000000000.nnue", "nn-37f18f62d772.nnue")
val netsDir = file("src/main/assets")

fun sha256(f: File): String {
	val md = MessageDigest.getInstance("SHA-256")
	f.inputStream().use { inp ->
		val buf = ByteArray(1 shl 16)
		while (true) { val n = inp.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
	}
	return md.digest().joinToString("") { "%02x".format(it) }
}

fun netOk(f: File) = f.exists() && sha256(f).startsWith(f.name.removePrefix("nn-").removeSuffix(".nnue"))

val downloadNets by tasks.registering {
	outputs.files(nets.map { File(netsDir, it) })
	doLast {
		netsDir.mkdirs()
		for (name in nets) {
			val f = File(netsDir, name)
			if (netOk(f)) continue
			logger.lifecycle("Скачиваю $name")
			URI("https://tests.stockfishchess.org/api/nn/$name").toURL().openStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
			check(netOk(f)) { "$name: контрольная сумма не совпала" }
		}
	}
}

// Stockfish 17.1 из подмодуля external/stockfish, собирается NDK под arm64: обычная версия и с dotprod
val sfSrc = rootProject.file("external/stockfish/src")
val sfOut = layout.buildDirectory.dir("stockfish/jniLibs/arm64-v8a")
val ndkDir = androidComponents.sdkComponents.ndkDirectory

val buildStockfish by tasks.registering {
	dependsOn(downloadNets)
	inputs.dir(sfSrc)
	inputs.dir(File(sfSrc.parentFile, "scripts"))
	outputs.dir(sfOut)
	doLast {
		check(File(sfSrc, "Makefile").exists()) { "Нет исходников Stockfish: выполни git submodule update --init" }
		val host = if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
		val bin = File(ndkDir.get().asFile, "toolchains/llvm/prebuilt/$host/bin")
		val out = sfOut.get().asFile.apply { mkdirs() }
		val jobs = Runtime.getRuntime().availableProcessors().toString()
		for ((arch, lib) in listOf("armv8" to "libstockfish.so", "armv8-dotprod" to "libstockfish_dp.so")) {
			val root = layout.buildDirectory.dir("stockfish/work/$arch").get().asFile
			root.deleteRecursively()
			sfSrc.copyRecursively(File(root, "src"))
			File(sfSrc.parentFile, "scripts").copyRecursively(File(root, "scripts"))
			val work = File(root, "src")
			for (n in nets) File(netsDir, n).copyTo(File(work, n), true)
			fun run(vararg cmd: String) {
				val pb = ProcessBuilder(*cmd).directory(work).redirectErrorStream(true)
				pb.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
				val p = pb.start()
				val log = ArrayList<String>()
				p.inputStream.bufferedReader().forEachLine { logger.info(it); log.add(it) }
				check(p.waitFor() == 0) { "Ошибка: ${cmd.joinToString(" ")}\n" + log.takeLast(40).joinToString("\n") }
			}
			logger.lifecycle("Собираю Stockfish ($arch)")
			run("make", "-j$jobs", "build", "ARCH=$arch", "COMP=ndk", "EXTRACXXFLAGS=-DNNUE_EMBEDDING_OFF")
			run(File(bin, "llvm-strip").absolutePath, "stockfish")
			File(work, "stockfish").copyTo(File(out, lib), true)
		}
	}
}

tasks.named("preBuild") { dependsOn(downloadNets, buildStockfish) }
