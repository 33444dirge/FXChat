import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

val javaSourceSets = extensions.getByType<SourceSetContainer>()
val testSourceSet = javaSourceSets.getByName("test")

val protocolCheck = tasks.register<JavaExec>("protocolCheck") {
    group = "verification"
    description = "Runs the protocol round-trip self-check."
    classpath = testSourceSet.runtimeClasspath
    mainClass.set("com.dirges.fxchat.common.protocol.PacketCodecSelfCheck")
}

tasks.check {
    dependsOn(protocolCheck)
}

tasks.test {
    failOnNoDiscoveredTests = false
}
