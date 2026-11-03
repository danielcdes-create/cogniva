plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}

android {
    namespace = "br.com.concursoprep.app"
    // Paparazzi 1.3.5 layoutlib crashes on API 36 (android.os.Build.VERSION_CODES_FULL).
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.concursoprep.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.4.7"

        val doApiBaseUrl = (project.findProperty("DO_API_BASE_URL") as? String)
            ?: "https://198.199.65.103/api/v1/"
        require(doApiBaseUrl.startsWith("https://")) { "A API de distribuição deve usar HTTPS" }
        buildConfigField("String", "API_BASE_URL", "\"$doApiBaseUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "4096m"
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

android.sourceSets.getByName("main").resources.srcDir(rootProject.file("../shared"))

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room (Offline-First)
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Rede: Retrofit + Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("io.github.sergio-sastre.ComposablePreviewScanner:android:0.7.1")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Paparazzi 1.3.5 replaces Gradle's test reporter with one that calls a Gradle 8
// internal method removed in Gradle 9.3. Put the stock reporter back so verify
// still fails the task when a screenshot test fails.
tasks.withType<Test>().configureEach {
    // Runs after Paparazzi's doFirst (which installs its Gradle 8 reporter) and
    // before the test action reads the reporter back.
    actions.add(
        0,
        Action<Task> {
            val reporterType = Class.forName("org.gradle.api.internal.tasks.testing.report.TestReporter")
            val setter = org.gradle.api.tasks.testing.AbstractTestTask::class.java.getDeclaredMethod(
                "setTestReporter",
                reporterType,
            )
            setter.isAccessible = true
            val noop = Class.forName("cogniva.NoopTestReporter")
                .getMethod("create", Class::class.java)
                .invoke(null, reporterType)
            setter.invoke(this, noop)
        },
    )
}
