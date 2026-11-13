import java.net.URI

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

android {
	namespace = "ru.cersat.chessics"
	compileSdk = 36

	defaultConfig {
		applicationId = "ru.cersat.chessics"
		minSdk = 26
		targetSdk = 36
		versionCode = 1
		versionName = "1.0.0"
		ndk { abiFilters += listOf("arm64-v8a") }
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
			signingConfig = signingConfigs.getByName("release")
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
}

kotlin {
	compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Нейросети Stockfish (75 МБ) не храним в git — скачиваем перед сборкой
val nets = mapOf(
	"nn-1c0000000000.nnue" to 74874478L,
	"nn-37f18f62d772.nnue" to 3519630L
)
val downloadNets by tasks.registering {
	val dir = file("src/main/assets")
	outputs.files(nets.keys.map { File(dir, it) })
	doLast {
		dir.mkdirs()
		for ((name, size) in nets) {
			val f = File(dir, name)
			if (f.exists() && f.length() == size) continue
			logger.lifecycle("Скачиваю $name")
			URI("https://tests.stockfishchess.org/api/nn/$name").toURL().openStream().use { inp -> f.outputStream().use { inp.copyTo(it) } }
			check(f.length() == size) { "$name: неверный размер ${f.length()}" }
		}
	}
}
tasks.named("preBuild") { dependsOn(downloadNets) }
