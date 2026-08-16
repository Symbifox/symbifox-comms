plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bluefoxconsultant.sms"
    compileSdk = 34

    defaultConfig {
        // ⚠️ NE PAS renommer. Le libellé visible est dans strings.xml ; cet
        // identifiant est de la plomberie : le changer donnerait une SECONDE
        // app au lieu d'une mise à jour, casserait la chaîne de signature, et
        // invaliderait le schéma de lien profond enregistré côté serveur dans
        // bf_sms_archive.mobile_redirect_schemes et son équivalent courriel.
        applicationId = "com.bluefoxconsultant.sms"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "2.11.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("BF_KEYSTORE")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("BF_KS_PASS")
                keyAlias = System.getenv("BF_KEY_ALIAS")
                keyPassword = System.getenv("BF_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    lint {
        // False positive: we use ComponentActivity (not Fragment) for registerForActivityResult.
        disable += "InvalidFragmentVersionForActivityResult"
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    implementation(libs.unifiedpush.connector)

    // Wire-format tests decode real captured API responses on the JVM.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
