plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.openrent.cambridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openrent.cambridge"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
        compose = true
    }
    sourceSets {
        // Unit tests validate the shipped timetable asset, so put the real assets
        // directory on the test classpath rather than symlinking the file across.
        named("test") { resources.srcDir("src/main/assets") }
    }
    testOptions {
        unitTests {
            // android.util.Log is a stub in JVM tests; return defaults rather than throw.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.jsoup)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    // Real org.json so parsers can be tested off-device.
    testImplementation(libs.json.unit.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// compose-version-skew guard.
//
// The Compose BOM pins the compile classpath, but navigation-compose drags a much
// newer foundation onto the runtime classpath. When the two disagree, code compiles
// against one signature and runs against another, which surfaces only on-device as
// NoSuchMethodError -- it cost us a crash in FlowRow. This reports the divergence at
// build time instead.
tasks.register("checkComposeVersionSkew") {
    group = "verification"
    description = "Warns when Compose resolves to different versions at compile and runtime."
    doLast {
        fun versionOf(configuration: String, module: String): String? =
            configurations.getByName(configuration)
                .resolvedConfiguration.lenientConfiguration.allModuleDependencies
                .firstOrNull { it.moduleGroup == "androidx.compose.foundation" && it.moduleName == module }
                ?.moduleVersion

        listOf("foundation", "foundation-layout").forEach { module ->
            val compile = versionOf("debugCompileClasspath", module)
            val runtime = versionOf("debugRuntimeClasspath", module)
            if (compile != null && runtime != null && compile != runtime) {
                logger.warn(
                    "WARNING [compose-version-skew] androidx.compose.foundation:$module " +
                        "compiles against $compile but runs against $runtime. " +
                        "Avoid experimental Compose APIs whose signatures change between them."
                )
            }
        }
    }
}
