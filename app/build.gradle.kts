plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.nurbstrunner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.nurbstrunner"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.2.0"
    }

    flavorDimensions += "dist"
    productFlavors {
        create("local") {
            dimension = "dist"
            // Fully offline: no ad SDK, INTERNET permission absent from the
            // merged manifest. F-Droid compatible.
        }
        create("play") {
            dimension = "dist"
            // AdMob flavor: INTERNET + AD_ID permissions are merged in
            // automatically by play-services-ads' own manifest.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")   // real JSON for JVM tests
    playImplementation("com.google.android.gms:play-services-ads:23.3.0")
    playImplementation("com.google.android.ump:user-messaging-platform:3.0.0")
}
