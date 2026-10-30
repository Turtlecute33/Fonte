import com.android.build.api.variant.SigningConfigInfo
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties (not in git) names the keystore and the Keychain items
// that hold its passwords. See scripts/make-release-key.sh.
val signingProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

// A lazy Keychain read: `security` runs only when a release signing task needs the password. Debug builds
// never run it, and the configuration cache keeps the command, not the password.
fun keychain(service: String): Provider<String> =
    providers.exec { commandLine("security", "find-generic-password", "-s", service, "-w") }
        .standardOutput.asText.map { it.trim() }

android {
    namespace = "app.fonte"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fonte"
        minSdk = 35
        targetSdk = 37
        versionCode = 8
        versionName = "0.1.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo { include = false }
            // No DSL signing config: androidComponents below adds the release key, so the debug key never signs.
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The UI is English only. Do not ship the library translations, so that all text is in English.
        localeFilters += "en"
    }

    packaging {
        resources {
            // Licence files also in the library folders (META-INF/androidx/...): the README names the licences.
            excludes += setOf(
                "META-INF/versions/**", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/**/LICENSE*", "META-INF/**/NOTICE*", "DebugProbesKt.bin", "kotlin/**",
            )
        }
    }

    testOptions {
        // JVM unit tests run against android.jar stubs; Log and friends return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

// No native code in the release APK. The only library with some, androidx.graphics.path, loads it only below
// Android 14 (its own PathIterator); from Android 14 the platform's PathIterator does the work, and minSdk is 35.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("lib/*/libandroidx.graphics.path.so")
        // Without a release key, the release APK stays unsigned. Local values only in the lambda, so that
        // the configuration cache can store it without the build script.
        signingProps?.let { props ->
            val storeFile = file(props.getProperty("storeFile"))
            val alias = props.getProperty("keyAlias")
            variant.signingConfig.from(
                keychain(props.getProperty("storePasswordKeychain"))
                    .zip(keychain(props.getProperty("keyPasswordKeychain"))) { store, key ->
                        SigningConfigInfo(storeFile, store, alias, key, "PKCS12")
                    },
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    // Installs the Compose baseline profiles on sideloaded installs: faster first start and scrolling.
    implementation(libs.profileinstaller)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json is part of Android; JVM unit tests need a real copy.
    testImplementation(libs.json)
}
