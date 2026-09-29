pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://download.linphone.org/maven_repository") }
    maven { url = uri("https://linphone.org/maven_repository") }
    maven { url = uri("https://gitlab.linphone.org/BC/public/maven_repository/raw/master") }
  }
}

rootProject.name = "Dialer"

include(":app")
include(":shared")
include(":desktopApp")

