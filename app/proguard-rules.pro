-keep public class com.alibaba.android.arouter.routes.** { *; }
-keep public class com.alibaba.android.arouter.facade.** { *; }
-keep class * implements com.alibaba.android.arouter.facade.template.ISyringe { *; }
-keep class * implements com.alibaba.android.arouter.facade.template.IProvider { *; }
# ARouter RouteMeta is shared with its annotation processor. Element is a compiler-only
# signature; generated Android routes use RouteMeta.build(Class, ...), never the Element API.
-dontwarn javax.lang.model.element.Element
