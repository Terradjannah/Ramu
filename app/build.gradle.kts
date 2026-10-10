plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.assistant.adi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.assistant.adi"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.51-beta"
        // The endpoint and SPKI key are provisioned together after Pages is configured.
        val catalogBaseUrl = providers.gradleProperty("catalogBaseUrl").orNull.orEmpty()
        val catalogPublicKey = providers.gradleProperty("catalogPublicKeySpkiBase64").orNull.orEmpty()
        buildConfigField("String", "CATALOG_BASE_URL", "\"$catalogBaseUrl\"")
        buildConfigField("String", "CATALOG_PUBLIC_KEY_SPKI_BASE64", "\"$catalogPublicKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Activity and Fragment extensions
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Room Database (KSP)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Google AI Edge LiteRT-LM for on-device AI inference (.litertlm models)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")

    // OkHttp for model download with pause/resume support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.13.2")

    // DataStore for AI settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // EncryptedSharedPreferences for HuggingFace token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

