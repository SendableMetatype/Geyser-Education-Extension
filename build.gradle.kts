plugins {
    java
}

group = "org.geysermc.extension"
version = "3.0.0"

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
    compileOnly("com.github.SendableMetatype.EduGeyser:api:master-SNAPSHOT")
    compileOnly("com.github.SendableMetatype.EduGeyser:core:master-SNAPSHOT")
    implementation("org.spongepowered:configurate-yaml:4.1.2")

    // Nethernet / WebRTC for join code system.
    // Forked version with long-running stability fixes.
    //
    // Netty handling: we bundle the full kastle-pinned Netty 4.1.130 stack
    // without shading or exclusions — same approach as MCXboxBroadcast.
    // Geyser/Spigot/Velocity all ship compatible Netty 4.1.x themselves, and
    // classloader resolution picks one consistent version at runtime. The
    // cloudburstmc Bedrock protocol classes (loaded from Geyser's classpath)
    // reference unshaded io.netty types, so we can't shade without breaking
    // the Nethernet pipeline — see v2.2.2 post-mortem.
    implementation("com.github.SendableMetatype.NetworkCompatible:netty-transport-nethernet:1.7.0-edugeyser.1")

    implementation("dev.kastle.webrtc:webrtc-java:1.0.3")
    listOf("windows-x86_64", "windows-aarch64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64").forEach { platform ->
        runtimeOnly("dev.kastle.webrtc:webrtc-java:1.0.3:$platform")
    }

    // Bedrock protocol classes provided by EduGeyser at runtime.
    compileOnly("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta12-20260316.225858-10")
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta12-20260316.225858-10")
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
