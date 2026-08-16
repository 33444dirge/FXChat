plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    filesMatching("velocity-plugin.json") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("FXChat-Velocity")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
