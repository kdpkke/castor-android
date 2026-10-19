import com.google.protobuf.gradle.proto

plugins {
  alias(libs.plugins.protobuf)
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.stupside.castor"
    buildToolsVersion = "37.0.0"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.stupside.castor"
        minSdk = 28
        targetSdk = 37
        versionCode = 15
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      jniLibs.useLegacyPackaging = true
      resources {
        merges += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
      }
    }
}

android.sourceSets {
  named("main") {
    proto { srcDir("../../proto") }
    kotlin.directories.add(rootProject.file("client/build/bufbuild/generated/kotlin").path)
  }
}

tasks.named("preBuild") { dependsOn(":client:bufGenerate") }

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
  generateProtoTasks { all().configureEach { builtins { create("java") } } }
}

val checkNativeRuntime by tasks.registering {
  val nativeFiles = layout.projectDirectory.dir("src/main/jniLibs")
  val notices = layout.projectDirectory.file("src/main/assets/native-licenses/FFmpeg-GPL3.txt")
  inputs.dir(nativeFiles).optional()
  inputs.file(notices).optional()
  doLast {
    for (abi in listOf("arm64-v8a", "x86_64")) {
      for (name in listOf("libcastor.so", "libffmpeg.so", "libffprobe.so", "libc++_shared.so")) {
        check(nativeFiles.file("$abi/$name").asFile.let { it.isFile && it.length() > 0 }) {
          "Missing $abi/$name. Run android/scripts/build-native.sh for both ABIs before packaging."
        }
      }
    }
    check(notices.asFile.isFile) { "Native license notices are missing. Complete the native build before packaging." }
  }
}

tasks.matching { it.name in setOf("packageDebug", "packageRelease") }.configureEach {
  dependsOn(checkNativeRuntime)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
  implementation(libs.protobuf.java)
  implementation(libs.protovalidate)
  implementation(libs.connect.okhttp)
  implementation(libs.connect.google.java)
  implementation(libs.okhttp.core)
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.mockwebserver)
  androidTestImplementation(libs.okhttp.tls)

}
