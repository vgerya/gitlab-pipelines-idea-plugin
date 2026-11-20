import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ 2024.2 bundles Kotlin stdlib 1.9.x: do not use newer stdlib APIs.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }

    // Gson and the Kotlin stdlib are bundled with the platform (compile-only for main code),
    // but the core unit tests run on a plain JVM classpath.
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("stdlib", "2.0.21"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    // The IDE test runtime classpath (added by the IntelliJ Platform Gradle Plugin) contains
    // listeners that reference JUnit 4 classes; without it JUnit 5 tests may fail with
    // NoClassDefFoundError: junit/framework/TestCase (IJPL-159134).
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    // No searchable options to index and it needs a headless IDE run: skip it (less CI fragility).
    buildSearchableOptions = false
    // No GUI forms and no Java sources: bytecode instrumentation is unnecessary.
    instrumentCode = false

    pluginConfiguration {
        id = "com.github.vgerya.gitlabpipelines"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // recommended() resolves IC 2025.3+, which no longer exists (IDEA became a single
            // unified distribution in 2025.3), so list the targets explicitly:
            // the lowest supported build, the last Community release and the unified IDEA.
            // 2024.2.5 rather than 2024.2.4: an ide() entry equal to the compile platform is
            // silently dropped by the Gradle plugin, which would leave the 242 branch unverified.
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.5")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"
    }
}
