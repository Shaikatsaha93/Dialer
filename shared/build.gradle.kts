plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

// Code shared by every platform: UI screens, models, database, chat, settings.
// Platform parts (the SIP engine, push, notifications, approval backend) live in the apps
// (:app for Android, :desktopApp for Windows) and are handed over through AppGraph.
kotlin {
  androidLibrary {
    namespace = "com.example.shared"
    compileSdk = 36
    minSdk = 24
  }
  jvm("desktop")

  jvmToolchain(17)

  applyDefaultHierarchyTemplate()

  sourceSets {
    // Android and Windows both run on the JVM: helpers that need java.* go here, so that
    // commonMain stays free of JVM APIs for a later iOS/web target.
    val jvmShared by creating { dependsOn(commonMain.get()) }
    androidMain.get().dependsOn(jvmShared)
    val desktopMain by getting { dependsOn(jvmShared) }

    commonMain.dependencies {
      api(compose.runtime)
      api(compose.foundation)
      api(compose.material3)
      api(compose.ui)
      api(compose.materialIconsExtended)
      api(libs.jetbrains.lifecycle.viewmodel.compose)
      api(libs.jetbrains.lifecycle.runtime.compose)
      api(libs.jetbrains.navigation.compose)
      api(libs.androidx.room.runtime)
      api(libs.kotlinx.coroutines.core)
    }
    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
      implementation(libs.androidx.core.ktx)
    }
    desktopMain.dependencies {
      api(libs.androidx.sqlite.bundled)
      api(libs.kotlinx.coroutines.swing)
    }
  }
}

dependencies {
  add("kspAndroid", libs.androidx.room.compiler)
  add("kspDesktop", libs.androidx.room.compiler)
}
