plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.beck.bleradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.beck.bleradar"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.ar:core:1.57.0")
    testImplementation("junit:junit:4.13.2")
}

// Endurspilun upptöku: ./gradlew testDebugUnitTest --tests "*SessionReplay*" --rerun -Psession=<slóð>
tasks.withType<Test>().configureEach {
    systemProperty("ble3d.session", (project.findProperty("session") ?: "").toString())
    systemProperty("ble3d.bench", (project.findProperty("bench") ?: "").toString())
}
