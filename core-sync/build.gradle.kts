import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Keys live in local.properties (git-ignored). The anon key is public by design: RLS and the
// security-definer functions in supabase/schema.sql are what protect the data.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.reeltracker.sync"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "SUPABASE_URL", "\"${local.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${local.getProperty("supabase.anonKey", "")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core-model"))
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
}
