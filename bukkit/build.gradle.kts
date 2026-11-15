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
    compileOnly("net.momirealms:craft-engine-core:26.8.2")
    compileOnly("net.momirealms:craft-engine-bukkit:26.8.2")
    compileOnly("net.momirealms:custom-nameplates:3.0.33")
    compileOnly("nl.rutgerkok:blocklocker:1.13")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("net.kyori:adventure-text-minimessage:4.26.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("net.momirealms:custom-nameplates:3.0.33")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    configurations = project.configurations.runtimeClasspath.map { setOf(it) }
    archiveBaseName.set("FXChat-Bukkit")
    archiveClassifier.set("")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Windows test workers need an ASCII classpath when the checkout path contains CJK.
if (System.getProperty("os.name").lowercase().contains("windows") &&
    !projectDir.path.all { it.code < 128 }) {
    val workerClasses = tasks.register<Jar>("testWorkerClasses") {
        dependsOn(tasks.testClasses, ":common:classes")
        from(sourceSets.main.get().output, sourceSets.test.get().output)
        from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output)
        archiveFileName.set("test-classes.jar")
        destinationDirectory.set(file(System.getProperty("java.io.tmpdir") +
            "/fxchat-test-worker/" + projectDir.path.hashCode().toUInt().toString(16)))
    }
    tasks.test {
        dependsOn(workerClasses)
        classpath = files(workerClasses.flatMap { it.archiveFile }) + configurations.testRuntimeClasspath.get()
    }
}
