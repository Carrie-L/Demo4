// 可迁移功能库：核心规则不依赖 ARouter，Android 依赖只用于 UI、存储及生命周期适配。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.carrie.widgetguide"
    compileSdk = 35
    defaultConfig { minSdk = 29; consumerProguardFiles("consumer-rules.pro") }
    // XML 生成 ViewBinding，宿主额外使用 BuildConfig.DEBUG 控制调试能力。
    buildFeatures { viewBinding = true }
    // Java/Kotlin 统一生成 JVM 17 字节码。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Robolectric 的 SP/Fragment 测试需要 Android 资源参与 JVM 单元测试。
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
