import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Signature release : credentials chargés depuis `keystore.properties` (racine du projet,
// GITIGNORÉ — gabarit dans keystore.properties.example). Le keystore lui-même vit hors du
// dépôt. Sans ce fichier, `assembleRelease` produit un APK non signé et la config debug
// reste inchangée — la CI et les postes sans keystore buildent donc normalement.
val keystoreProperties = Properties().apply {
    val fichier = rootProject.file("keystore.properties")
    if (fichier.exists()) fichier.inputStream().use { load(it) }
}
val signatureDisponible = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "fr.ariegenature.geomys"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.ariegenature.geomys"
        minSdk = 24
        targetSdk = 36
        versionCode = 145
        versionName = "1.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signatureDisponible) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Minification volontairement désactivée pour la première génération signée :
            // R8 + reflexion Gson (stores JSON) demanderaient des règles proguard à valider
            // sur le terrain — à activer dans un second temps, séparément de la bascule de
            // signature (un changement de comportement à la fois).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signatureDisponible) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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