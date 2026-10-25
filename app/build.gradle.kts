// File: app/build.gradle.kts
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.ygggram.vxkprm"
    minSdk = 24
    targetSdk = 36
    versionCode = 18
    // The suffix is the only thing that tells a tester whether the node on the screen can
    // be the shipped one: an emulator build carries the x86_64 core, a release build arm64.
    versionName = "1.7.1" + if (providers.gradleProperty("emuX86").isPresent) "-emu-x86_64" else "-arm64-v8a"

    ndk {
      abiFilters += listOf("arm64-v8a")
      // x86_64 AVDs cannot execute the arm64 Go core (ndk_translation SIGILLs on the
      // ID_AA64PFR0_EL1 read in Go's arm64 init), so emulator builds pull the native
      // x86_64 libygggram.so via: gradlew assembleDebug -PemuX86
      if (providers.gradleProperty("emuX86").isPresent) {
        abiFilters += listOf("x86_64")
      }
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // -PupdateRepo=owner/Name lets a fork check its own releases without editing source; the
    // updater trusts nothing but this slug plus the pinned signing certificate.
    buildConfigField(
      "String",
      "UPDATE_REPO",
      "\"" + providers.gradleProperty("updateRepo").getOrElse("mx57/YGramm") + "\""
    )
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      // CI has no upload key: without KEYSTORE_PATH the release build stays unsigned and
      // the shippable APK is signed locally. The apk-releases/ entry in git is a signed build.
      val keystorePath = System.getenv("KEYSTORE_PATH")
      if (keystorePath != null) {
        storeFile = file(keystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove this signingConfig assignment: it is necessary for Android apps in
      // AI Studio. AGP refuses to package a release whose config has no storeFile, so it is
      // only attached when KEYSTORE_PATH points at the upload key — CI builds unsigned.
      if (System.getenv("KEYSTORE_PATH") != null) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

abstract class ExportSingleArm64ReleaseApkTask : DefaultTask() {
  @get:Internal
  abstract val releaseApkPath: Property<String>

  @get:Internal
  abstract val rootDirPath: Property<String>

  @TaskAction
  fun exportReleaseApk() {
    val root = File(rootDirPath.get())
    val signedRelease = File(releaseApkPath.get())
    if (!signedRelease.exists() || signedRelease.length() == 0L) {
      // CI has no upload key, so AGP emits app-release-unsigned.apk there. An unsigned build
      // must never reach apk-releases/, and the APK already committed stays untouched.
      val unsigned = File(signedRelease.parentFile, "app-release-unsigned.apk")
      if (unsigned.exists()) {
        println("Release APK is unsigned (KEYSTORE_PATH not set): apk-releases/ left unchanged")
        return
      }
      throw GradleException("Signed release APK not found at ${signedRelease.absolutePath}")
    }

    // Validate before touching apk-releases/: the cleanup below deletes the published
    // checksum, so a build that is about to be refused must not have run it first.
    ZipFile(signedRelease).use { zip ->
      val entries = zip.entries().asSequence().map { it.name }.toSet()
      if (!entries.contains("lib/arm64-v8a/libygggram.so")) {
        // libygggram.so is not tracked in git; a release built without the
        // native core silently ships «НЕТ ЯДРА» to users.
        throw GradleException(
          "Release APK is missing lib/arm64-v8a/libygggram.so. Rebuild the Go core first: " +
            "cd core && GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=\"\$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android24-clang\" " +
            "go build -buildmode=c-shared -o ../app/src/main/jniLibs/arm64-v8a/libygggram.so ./cmd/libygggram"
        )
      }
      // The file is published under an arm64-v8a name: an -PemuX86 build would also carry
      // x86_64 libs and would be mislabelled (and twice the size) on the download page.
      if (entries.any { it.startsWith("lib/x86_64/") }) {
        throw GradleException(
          "Refusing to export an APK that contains x86_64 libs. Re-run assembleRelease without -PemuX86."
        )
      }
    }

    // Remove all extra/legacy APKs from repository root and apk-releases
    listOf(
      File(root, "YGramm-main.apk"),
      File(root, "YGramm-clone-node2.apk")
    ).forEach { if (it.exists()) it.delete() }

    val releaseDir = File(root, "apk-releases").apply { mkdirs() }
    releaseDir.listFiles()?.forEach { file ->
      if (file.name != "YGramm-arm64-v8a.apk") {
        file.delete()
      }
    }

    val targetFile = File(releaseDir, "YGramm-arm64-v8a.apk")
    signedRelease.copyTo(targetFile, overwrite = true)
    println("Exported signed release APK (${signedRelease.length()} bytes) -> ${targetFile.absolutePath}")

    // The cleanup above deletes every other file in apk-releases/, including a checksum
    // published by an earlier release: write the new one here or the download page ships
    // an APK with no way to verify it.
    val digest = MessageDigest.getInstance("SHA-256")
    targetFile.inputStream().use { stream ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    val checksum = digest.digest().joinToString("") { "%02x".format(it) }
    File(releaseDir, "${targetFile.name}.sha256").writeText("$checksum  ${targetFile.name}\n")
    println("Published sha256 -> ${targetFile.name}.sha256 ($checksum)")
  }
}

val exportSingleArm64ReleaseApk = tasks.register<ExportSingleArm64ReleaseApkTask>("exportSingleArm64ReleaseApk") {
  releaseApkPath.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk").map { it.asFile.absolutePath })
  rootDirPath.set(rootDir.absolutePath)
}

tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
  finalizedBy(exportSingleArm64ReleaseApk)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.zxing.core)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.sqlcipher.android)
  implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.firestore)
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  // debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
