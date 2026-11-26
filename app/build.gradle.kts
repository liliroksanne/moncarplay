plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lilyroksanne.mon"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lilyroksanne.mon"
        minSdk = 27
        targetSdk = 37
        versionCode = 6
        versionName = "2.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

base {
    archivesName.set("Mon-v${android.defaultConfig.versionName}")
}

dependencies {
    // Corrutinas (requeridas para usar delay y llamadas asíncronas)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // DocumentFile para manejar SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
// Room (SQLite)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")



    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
