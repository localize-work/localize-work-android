import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library") version "9.5.2"
    `maven-publish`
}

// JitPack builds this repository and serves it as
// com.github.localize-work:localize-work-android:<tag>. It passes its own
// group and version (the Git tag); the values here are for local builds.
if (group.toString().isEmpty()) group = "com.github.localize-work"
if (version.toString() == "unspecified") version = "0.1.0"

android {
    namespace = "work.localize.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // The library uses nothing newer than its minSdk, so an app need not
        // compile against the platform this library happens to be built with.
        aarMetadata {
            minCompileSdk = 21
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        // Compiled against an older language level than the compiler's own, so
        // apps that are a Kotlin release or two behind can still use the library.
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
    coreLibrariesVersion = "2.2.21"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "localize-work-android"
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name = "localize.work Android SDK"
                description = "Loads translations published with localize.work into an Android app at run time."
                url = "https://github.com/localize-work/localize-work-android"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
            }
        }
    }
}
