plugins {
    id("com.android.application")
}

// GitHub 自动打包时，签名密钥从仓库的 KEYSTORE_B64 秘密里取出来，路径放在这个环境变量里。
// 有密钥就打正式版（签名固定，更新可以直接覆盖安装）；没有就打调试版。
val zbzKeystore: String? = System.getenv("ZBZ_KEYSTORE")
// 每次打包版本号自动加一，手机才允许覆盖安装
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.zhibuzhi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zhibuzhi.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.2.$buildNumber"
    }

    signingConfigs {
        if (zbzKeystore != null) {
            create("zbz") {
                storeFile = file(zbzKeystore)
                storePassword = "zhibuzhi"
                keyAlias = "zhibuzhi"
                keyPassword = "zhibuzhi"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (zbzKeystore != null) {
                signingConfig = signingConfigs.getByName("zbz")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
