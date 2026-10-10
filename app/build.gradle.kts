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
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true }
}
kapt { arguments { arg("AROUTER_MODULE_NAME", project.name) } }
dependencies {
    implementation(project(":widget-guide"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.alibaba:arouter-api:1.5.2") {
        // Jetifier rewrites ARouter references; AndroidX dependencies above supply those classes.
        // Keeping support-v4 as well creates duplicate AndroidX R classes after resource linking.
        exclude(group = "com.android.support")
    }
    kapt("com.alibaba:arouter-compiler:1.5.2")
}
