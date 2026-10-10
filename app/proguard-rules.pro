# 保留 ARouter 生成的路由表和运行时使用的接口/注入实现，防止 Release 裁剪导致路由失效。
-keep public class com.alibaba.android.arouter.routes.** { *; }
-keep public class com.alibaba.android.arouter.facade.** { *; }
-keep class * implements com.alibaba.android.arouter.facade.template.ISyringe { *; }
-keep class * implements com.alibaba.android.arouter.facade.template.IProvider { *; }
# ARouter RouteMeta 与注解处理器共用，Element 只出现在编译器相关签名中。
# Android 生成路由使用 Class 版本的 build，仅忽略这一个编译器类型的缺失告警。
-dontwarn javax.lang.model.element.Element
