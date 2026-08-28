plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.echovr.installer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.echovr.installer"
        minSdk = 26
        targetSdk = 36
        versionCode = 43
        versionName = "4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    packaging {
        resources.excludes.add("META-INF/*") // Prevents signing conflicts
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
