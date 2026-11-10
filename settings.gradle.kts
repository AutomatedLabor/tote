pluginManagement {
    repositories {
        // Google + Maven Central first — they're the authoritative sources and
        // GitHub Actions runners have reliable connectivity to them.
        // Aliyun mirror as fallback for local dev on constrained networks
        // (per threshold LEARNINGS, Maven Central can 429 under heavy parallel
        // load — but on CI the risk is Aliyun 502s, which are WORSE because
        // Gradle treats non-404 errors as hard failures and never falls
        // through to the next repo).
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "Tote"
include(":app")
