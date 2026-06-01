plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "fr.ariegenature.geonat"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.ariegenature.geonat"
        minSdk = 24
        targetSdk = 36
        versionCode = 115
        versionName = "0.9.89"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric a besoin des ressources Android fusionnées pour démarrer un Context.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.osmdroid)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.gson)
    implementation(libs.security.crypto)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.activity.ktx)
    testImplementation(libs.junit)
    // org.json est seulement stubbé dans l'android.jar de test (renvoie des valeurs par
    // défaut) : on fournit une vraie implémentation sur le classpath de test pour pouvoir
    // tester le parsing du schéma serveur et la construction des payloads JSON.
    testImplementation("org.json:json:20240303")
    // Robolectric : exécute en JVM le code dépendant d'un Context Android (SharedPreferences,
    // stores…). @Config(sdk=[34]) — SDK émulé indépendant du compileSdk.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // MockWebServer : teste les flux réseau (auth, GET/POST GeoNature) sans serveur réel,
    // en pointant l'URL de base sur le serveur mock local.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}