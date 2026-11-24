plugins {
    id("com.android.application") version "9.5.2"
}

android {
    namespace = "work.localize.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "work.localize.demo"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // For the flow in src/androidTest, which flow.sh runs on a device or emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.github.localize-work:localize-work-android:0.1.0")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
