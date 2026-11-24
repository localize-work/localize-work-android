pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "example"

// The app names the library as an app of your own does (see build.gradle.kts).
// Here it is built from the source in the folder above instead of being
// downloaded from JitPack, so the example always runs the code next to it.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("com.github.localize-work:localize-work-android")).using(project(":"))
    }
}
