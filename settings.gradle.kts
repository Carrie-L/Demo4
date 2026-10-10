// 统一插件/依赖仓库，禁止子模块私自增加仓库；仅包含验证宿主和可迁移功能库。
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WidgetGuide"
include(":app", ":widget-guide")
