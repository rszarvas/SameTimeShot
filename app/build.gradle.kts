plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sametime.shot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sametime.shot"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }

    // APK kimeneti fájl neve
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "sametimeshot.apk"
        }
    }
}

// APK automatikus másolása a főkönyvtárba (SameTimeShotApp/) minden build után
tasks.whenTaskAdded {
    if (name == "assembleDebug" || name == "assembleRelease") {
        doLast {
            val buildType = if (name.contains("Release")) "release" else "debug"
            val apkDir = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
            val apkFile = apkDir.listFiles()?.firstOrNull { it.name == "sametimeshot.apk" }
            if (apkFile != null) {
                apkFile.copyTo(
                    target = rootProject.file("sametimeshot.apk"),
                    overwrite = true
                )
                println("✓ APK másolva: ${rootProject.projectDir}/sametimeshot.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.glide)
    implementation("androidx.cardview:cardview:1.0.0")
}
