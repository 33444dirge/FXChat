plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.h2database:h2:2.2.224")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("net.kyori:adventure-text-minimessage:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-gson:4.26.1")
    compileOnly("com.incredibleplugins:lands-api:8.0.0")
    compileOnly("net.momirealms:craft-engine-core:26.7.4")
    compileOnly("net.momirealms:craft-engine-bukkit:26.7.4")
    compileOnly("net.momirealms:custom-nameplates:3.0.33")
    compileOnly("nl.rutgerkok:blocklocker:1.13")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("FXChat-Bukkit")
    archiveClassifier.set("")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
