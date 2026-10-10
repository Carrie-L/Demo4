// 根项目统一锁定 Android/Kotlin 插件版本；具体插件由 app 和 widget-guide 启用。
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.kapt") version "2.1.20" apply false
}
