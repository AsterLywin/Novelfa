plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp"); id("org.jetbrains.kotlin.plugin.compose") }

// Release signing (docs/PLAY-RELEASE.md §3): the keystore is NEVER committed.
// Configure it via environment variables or ~/.gradle/gradle.properties:
//   NOVELFA_KEYSTORE_PATH / novelfa.keystore.path         -> path to the .jks / .keystore file
//   NOVELFA_KEYSTORE_PASSWORD / novelfa.keystore.password -> keystore password
//   NOVELFA_KEY_ALIAS / novelfa.key.alias                 -> key alias
//   NOVELFA_KEY_PASSWORD / novelfa.key.password            -> key password
// Without a keystore, :app:assembleRelease keeps emitting app-release-unsigned.apk
// (which Android refuses to install — it must be signed before sideloading).
val releaseKeystorePath: String? =
    System.getenv("NOVELFA_KEYSTORE_PATH") ?: findProperty("novelfa.keystore.path") as String?
val releaseKeystorePassword: String? =
    System.getenv("NOVELFA_KEYSTORE_PASSWORD") ?: findProperty("novelfa.keystore.password") as String?
val releaseKeyAlias: String? =
    System.getenv("NOVELFA_KEY_ALIAS") ?: findProperty("novelfa.key.alias") as String?
val releaseKeyPassword: String? =
    System.getenv("NOVELFA_KEY_PASSWORD") ?: findProperty("novelfa.key.password") as String?

android {
    namespace = "com.novelfa.reader"
    compileSdk = 35
    defaultConfig { applicationId = "com.novelfa.reader"; minSdk = 29; targetSdk = 35; versionCode = 2; versionName = "1.0.1"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); if (releaseKeystorePath != null) { signingConfig = signingConfigs.getByName("release") } } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.room:room-runtime:2.6.1"); ksp("androidx.room:room-compiler:2.6.1"); implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// App UI fonts (Vazirmatn): the TTFs already ship in src/main/assets/fonts,
// so a build-time task copies + renames them into a generated res/font dir.
// R.font.vazirmatn_regular / R.font.vazirmatn_bold then resolve like normal
// resources, with no binary duplication in the repo (the GitHub connector
// can't push binaries, so res/font can't be checked in directly).
android {
    sourceSets {
        getByName("main") {
            res.srcDirs("${layout.buildDirectory.get()}/generated/ui-fonts/res")
        }
    }
}

val copyUiFonts by tasks.registering(Copy::class) {
    from("src/main/assets/fonts") {
        include("Vazirmatn-Regular.ttf")
        rename("Vazirmatn-Regular.ttf", "vazirmatn_regular.ttf")
    }
    from("src/main/assets/fonts") {
        include("Vazirmatn-Bold.ttf")
        rename("Vazirmatn-Bold.ttf", "vazirmatn_bold.ttf")
    }
    into(layout.buildDirectory.dir("generated/ui-fonts/res/font"))
}

tasks.named("preBuild") {
    dependsOn(copyUiFonts)
}
