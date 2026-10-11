// 验证宿主：Kotlin + XML/ViewBinding + ARouter。Debug 与 Release 使用不同 applicationId 隔离数据。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.carrie.demo4"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.carrie.demo4"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // XML 生成 ViewBinding，宿主额外使用 BuildConfig.DEBUG 控制调试能力。
    buildFeatures { viewBinding = true; buildConfig = true }
    // Java/Kotlin 统一生成 JVM 17 字节码。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        // 独立包名避免调试清数据/模拟时钟污染正式安装。
        debug { applicationIdSuffix = ".debug" }
        // 开启 R8；需要同时验证混淆后的 ARouter 生成表。
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true }
    // 验证真实 Activity 返回回调与页面恢复的交接。
    testOptions { unitTests.isIncludeAndroidResources = true }
}
// ARouter 注解处理器使用模块名生成路由索引。
kapt { arguments { arg("AROUTER_MODULE_NAME", project.name) } }
dependencies {
    implementation(project(":widget-guide"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0") // 搜索激活页四类横向热搜榜。
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.alibaba:arouter-api:1.5.2") {
        // Jetifier 转换 ARouter 的旧包引用；AndroidX 依赖提供实际运行类。
        // 排除旧 support 依赖，避免资源链接后产生重复的 AndroidX R 类。
        exclude(group = "com.android.support")
    }
    kapt("com.alibaba:arouter-compiler:1.5.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2") // 验证返回链路断言。
    testImplementation("org.robolectric:robolectric:4.14.1") // 模拟实际 Activity 生命周期。
    testImplementation("androidx.test:core:1.6.1") // 取得被测应用上下文。
}
