import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
  }
}

android {
  namespace = "com.eli.suixinji"
  compileSdk {
    version = release(36)
  }
  // NDK 按项目约定放在 sdk/ndk/<版本号>（AGP 标准发现路径，mac 下载后归位到此）
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.eli.suixinji"
    minSdk = 31
    targetSdk = 36
    versionCode = 2
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      // 真机（骁龙 8 Elite）为 arm64，裁掉 x86/x86_64/armeabi-v7a 减小体积
      abiFilters += "arm64-v8a"
    }

    externalNativeBuild {
      cmake {
        arguments += "-DANDROID_STL=none"
        cppFlags += ""
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      // 用 brew 的 CMake 4.0.2（local.properties cmake.dir=/opt/homebrew）；
      // AGP 会校验版本号与这里一致。如遇兼容性问题，回退下载 AGP 自带 3.22.1 到 sdk/cmake/
      version = "4.0.2"
    }
  }

  // APK 产物带版本号，便于 GitHub Release 附件命名：suixinji-v1.0-release.apk
  applicationVariants.all {
    val vName = versionName
    outputs.all {
      (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
        "suixinji-v$vName-$name.apk"
    }
  }

  // 自签 keystore 在项目根目录（*.keystore 已被 .gitignore 挡住，不入库）；
  // 密码在 local.properties（同样不入库）：ks.store.password / ks.key.password
  // 换 keystore 或丢密码 = 老用户无法升级，务必另存备份
  val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
  }
  signingConfigs {
    create("release") {
      storeFile = file("../suixinji.keystore")
      storePassword = localProps.getProperty("ks.store.password")
      keyAlias = "suixinji"
      keyPassword = localProps.getProperty("ks.key.password")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      keepDebugSymbols +=
        setOf(
          "**/libLiteRt.so",
          "**/libLiteRtClGlAccelerator.so",
          "**/libandroidx.graphics.path.so",
          "**/libdatastore_shared_counter.so",
          "**/liblitertlm_jni.so",
        )
      // 构建期空壳 libOpenCL.so 绝不能进 APK：进了会遮蔽厂商真库，
      // litert 按名 dlopen 命中的是无符号的空壳 → GPU 照样挂（M2.9 实测踩坑）
      excludes += "**/libOpenCL.so"
    }
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
  implementation(libs.litertlm.android)
  implementation(libs.okhttp)
  // 离线语音识别：sherpa-onnx（本地 AAR，见 app/libs/）
  implementation(files("libs/sherpa-onnx-1.12.14.aar"))
  coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material.icons.extended)
  testImplementation(libs.junit)
  testImplementation(libs.json)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
