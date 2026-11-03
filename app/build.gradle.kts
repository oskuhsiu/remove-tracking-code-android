import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

// The repo ships a public self-signed keystore at app/signing/release.jks so that
// CI can produce a signed APK without any secrets. Env vars override the
// committed defaults so forks / CI can plug in their own credentials. Blank env
// values (e.g. GitHub Actions expanding an unset secret to "") are ignored so
// they don't clobber the working defaults.
val releaseKeystore = rootProject.file("app/signing/release.jks")
fun envOrDefault(name: String, default: String): String =
    System.getenv(name)?.takeUnless { it.isBlank() } ?: default
val releaseStorePassword = envOrDefault("URLCLEANER_KEYSTORE_PASSWORD", "urlcleaner")
val releaseKeyAlias = envOrDefault("URLCLEANER_KEY_ALIAS", "urlcleaner")
val releaseKeyPassword = envOrDefault("URLCLEANER_KEY_PASSWORD", "urlcleaner")

android {
    namespace = "app.urlcleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.urlcleaner"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Android's test stub android.jar has no org.json impl — provide the real one on the
    // JVM test classpath so UrlCleaner tests can parse rules without Robolectric.
    testImplementation(libs.org.json)
}
