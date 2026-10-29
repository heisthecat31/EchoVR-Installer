import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The release key lives outside the project (never in the repo). Override the location with
// -PechovrSigningProps=<path>. Without it, builds fall back to the standard debug key.
val signingPropsFile = file(
    (findProperty("echovrSigningProps") as String?)
        ?: "${System.getProperty("user.home")}/keys/echovr-installer/keystore.properties"
)
val signingProps = Properties().apply {
    if (signingPropsFile.isFile) signingPropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.echovr.installer"
    compileSdk = 36

    defaultConfig {
        // Not "com.echovr.installer": that package was signed with the debug key, so the new key gets a
        // new package and the app removes the old one itself (MainActivity.offerOldInstallerRemoval).
        applicationId = "com.echovr.questinstaller"
        minSdk = 26
        targetSdk = 36
        versionCode = 50
        versionName = "5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!signingProps.isEmpty) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug builds use the release key too, so a test build installs over a published one.
        val releaseSigning = signingConfigs.findByName("release")
        debug {
            if (releaseSigning != null) signingConfig = releaseSigning
        }
        release {
            if (releaseSigning != null) signingConfig = releaseSigning
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
