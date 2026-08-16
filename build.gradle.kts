import org.gradle.api.plugins.JavaPluginExtension

plugins {
    base
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "com.dirges.fxchat"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.momirealms.net/releases/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        maven("https://repo.incredibleplugins.com/releases")
        maven("https://repo.codemc.io/repository/maven-releases/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

}

tasks.named("build") {
    dependsOn(":bukkit:shadowJar", ":velocity:shadowJar")
}
