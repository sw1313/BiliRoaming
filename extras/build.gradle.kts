plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.lsplugin.cmaker)
}

cmaker {
    default {
        targets("biliroaming")
        abiFilters("armeabi-v7a", "arm64-v8a")
        arguments += arrayOf(
            "-DANDROID_STL=none",
            "-DCMAKE_CXX_STANDARD=23",
            "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
        )
        cFlags += "-flto"
        cppFlags += "-flto"
    }

    buildTypes {
        arguments += "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.file("symbols/${it.name}").get().asFile.absolutePath}"
    }
}

android {
    namespace = "me.custom.biliextras"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    defaultConfig {
        applicationId = "me.custom.biliextras"
        minSdk = 24
        targetSdk = 35
        versionCode = 83
        versionName = "1.0.90"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    androidResources {
        additionalParameters += arrayOf("--allow-reserved-package-id", "--package-id", "0x24")
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    externalNativeBuild {
        cmake {
            path("../app/src/main/jni/CMakeLists.txt")
            version = "4.1.0+"
        }
    }
}

dependencies {
    compileOnly(libs.xposed)
    implementation(libs.xposed.service)
    implementation(libs.kotlin.stdlib)
    compileOnly("androidx.annotation:annotation:1.9.1")
    implementation(libs.cxx)
}

// Android Studio/IntelliJ may request this task during Gradle sync for Kotlin script models.
// The standalone extras module does not need extra model preparation, so keep it as a no-op.
if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel")
}
