import java.util.Properties

// Release 签名材料放在仓库外（keystore.properties 已 gitignore）。
// 没有这个文件时回退到 debug 签名，保证别人 clone 下来也能直接构建。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // ⚠ 必须按 UTF-8 读。Properties.load(InputStream) 是按 ISO-8859-1 解码的，
        //   而本机的密钥库放在中文目录（05-签名密钥（勿外发））下，
        //   走 InputStream 会把路径读成一串乱码 → 文件"不存在" → 静默回退到 debug 签名。
        keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}
// 只有当 storeFile 指向的密钥库**真的存在**时才算配好了正式签名。
// 只判断属性非空是不够的：换机器后 keystore.properties 还在、.jks 却留在旧盘上，
// 此时 release 会直接以 "Keystore file ... not found" 失败，
// 而按上面的约定这种情况本应优雅回退到 debug 签名。
val releaseStoreFile = keystoreProps.getProperty("storeFile")
val hasReleaseKey = releaseStoreFile != null && rootProject.file(releaseStoreFile).exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.amusic.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amusic.player"
        minSdk = 24
        targetSdk = 36
        // 对外版本号固定 1.0：即使内容有增改也保持 1.0。
        // versionCode 作为"内部构建序号"递增 —— 它必须比设备上已装的更大，
        // 否则覆盖安装会被系统拒绝（INSTALL_FAILED_VERSION_DOWNGRADE）。
        versionCode = 84
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 发布包开混淆 + 资源压缩：体积明显变小，也更难被反编译
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有正式签名就用正式签名（GitHub Releases 分发的包必须是它，
            // 否则换一次构建环境签名就变了，已安装用户没法覆盖升级）
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    testImplementation(libs.junit)
}
