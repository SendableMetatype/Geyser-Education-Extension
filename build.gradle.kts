plugins {
    java
}

group = "org.geysermc.extension"
version = "1.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main")
    maven("https://repo.opencollab.dev/maven-snapshots")
    maven("https://repo.viaversion.com")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("org.geysermc.geyser:api:2.6.1-SNAPSHOT")
    compileOnly("org.geysermc.geyser:core:2.6.1-SNAPSHOT")
    compileOnly("org.spongepowered:configurate-yaml:4.1.2")

    // Nethernet / WebRTC for join code system.
    // Forked version with long-running stability fixes (ping task exception handling,
    // isChannelAlive() accessor, last-message-received tracking).
    implementation("com.github.SendableMetatype.NetworkCompatible:netty-transport-nethernet:1.7.0-edugeyser.1") {
        exclude(group = "io.netty")  // Use Geyser's bundled Netty
    }
    implementation("dev.kastle.webrtc:webrtc-java:1.0.3")
    listOf("windows-x86_64", "windows-aarch64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64").forEach { platform ->
        runtimeOnly("dev.kastle.webrtc:webrtc-java:1.0.3:$platform")
    }

    // Bedrock protocol for the redirect handler (match Geyser's version)
    compileOnly("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta12-20260316.225858-10")
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta12-20260316.225858-10")
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
