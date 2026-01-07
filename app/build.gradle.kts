import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
}

// Load auth config from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// Required auth properties - build will fail if any are missing or empty
val requiredAuthProperties = listOf(
    "SUPABASE_PROJECT_ID",
    "SUPABASE_PUBLIC_KEY",
    "GOOGLE_WEB_CLIENT_ID"
)

fun getAuthProperty(key: String, required: Boolean = true): String {
    val value = localProperties.getProperty(key, "")
    if (required && value.isEmpty()) {
        throw GradleException(
            """
            |
            |==========================================================
            | MISSING REQUIRED AUTH PROPERTY: $key
            |==========================================================
            | Please add '$key' to your local.properties file.
            | See auth.properties.template for reference.
            |==========================================================
            """.trimMargin()
        )
    }
    return value
}

android {
    namespace = "com.splitease"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.splitease"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Auth BuildConfig fields (loaded from local.properties)
        buildConfigField("String", "SUPABASE_PROJECT_ID", "\"${getAuthProperty("SUPABASE_PROJECT_ID")}\"")
        buildConfigField("String", "SUPABASE_PUBLIC_KEY", "\"${getAuthProperty("SUPABASE_PUBLIC_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${getAuthProperty("GOOGLE_WEB_CLIENT_ID")}\"")
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Add KSP configuration block to prevent false validation errors
ksp {
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.google.gson)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)

    // Google Sign-In
    implementation(libs.google.play.services.auth)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
