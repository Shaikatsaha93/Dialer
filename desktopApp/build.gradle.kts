import java.net.URI
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(project(":shared"))
  implementation(compose.desktop.currentOs)
  implementation(libs.jna)
  implementation(libs.jna.platform)
  // Firebase REST (approval and admin panel) replies
  implementation(libs.kotlinx.serialization.json)
}

// Linphone for Windows (liblinphone + mediastreamer DLLs). Not kept in git: the
// fetchLinphoneWindows task downloads the official SDK once and copies what the app needs into
// linphone/windows-x64 (bin = DLLs, lib/mediastreamer/plugins, share = SIP grammars and sounds).
val linphoneDir = layout.projectDirectory.dir("linphone")
val linphoneSdkVersion = "5.5.23"
val linphoneZip = layout.buildDirectory.file("linphone/linphone-sdk-win64-$linphoneSdkVersion.zip")

val downloadLinphoneSdk by tasks.registering {
  val out = linphoneZip
  val url = "https://download.linphone.org/releases/windows/sdk/linphone-sdk-win64-$linphoneSdkVersion.zip"
  outputs.file(out)
  onlyIf { !out.get().asFile.exists() }
  doLast {
    val file = out.get().asFile
    file.parentFile.mkdirs()
    val part = File(file.path + ".part")
    URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    check(part.renameTo(file)) { "Could not save $file" }
  }
}

// liblinphone.dll and everything it loads (worked out from the DLL import tables)
val linphoneRuntimeDlls = listOf(
  "bctoolbox", "belcard", "belle-sip", "belr", "bv16", "bzrtp", "decaf", "gsm", "hidapi", "jpeg62",
  "jsoncpp", "liblinphone", "lime", "mbedcrypto", "mbedtls", "mbedx509", "mediastreamer2", "opus", "ortp",
  "soci_core_4_0", "soci_sqlite3_4_0", "speex", "speexdsp", "sqlite3", "srtp2", "turbojpeg", "xerces-c",
  "xml2", "yuv", "zlib1", "ZXing"
)

val fetchLinphoneWindows by tasks.registering(Sync::class) {
  dependsOn(downloadLinphoneSdk)
  val sdk = "linphone-sdk/win64"
  from(zipTree(linphoneZip)) {
    linphoneRuntimeDlls.forEach { include("$sdk/bin/$it.dll") }
    // WASAPI = Windows audio devices; webrtc = echo canceller
    include("$sdk/lib/mediastreamer/plugins/libmswasapi.dll", "$sdk/lib/mediastreamer/plugins/libmswebrtc.dll")
    include("$sdk/share/belr/grammars/*.belr", "$sdk/share/linphone/rootca.pem")
    include("$sdk/share/sounds/linphone/ringback.wav", "$sdk/share/sounds/linphone/toy-mono.wav")
    include("$sdk/share/sounds/linphone/rings/oldphone-mono.wav")
    eachFile { path = path.removePrefix("$sdk/") }
    includeEmptyDirs = false
  }
  // Visual C++ runtime the DLLs are built against, so the installer also works on a bare Windows
  from(File(System.getenv("SystemRoot") ?: "C:/Windows", "System32")) {
    include("msvcp140.dll", "vcruntime140.dll", "vcruntime140_1.dll")
    into("bin")
  }
  into(linphoneDir.dir("windows-x64"))
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchLinphoneWindows) }

compose.desktop {
  application {
    mainClass = "com.example.desktop.MainKt"
    jvmArgs += listOf("-Dfile.encoding=UTF-8")

    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Exe)
      packageName = "Dialer"
      packageVersion = "1.0.0"
      description = "SIP softphone"
      vendor = "Dialer"
      // The DLLs are copied next to the app (see appResourcesRootDir below)
      appResourcesRootDir.set(linphoneDir)
      modules("java.naming", "java.net.http", "java.prefs", "java.sql", "jdk.unsupported")
      windows {
        iconFile.set(project.file("icons/dialer.ico"))
        menu = true
        shortcut = true
        dirChooser = true
        perUserInstall = true
        menuGroup = "Dialer"
        upgradeUuid = "6f0a3c64-2a53-4d57-9a3f-7a1f2f8f5d11"
      }
    }
  }
}
