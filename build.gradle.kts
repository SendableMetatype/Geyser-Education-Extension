plugins {
    java
    id("com.gradleup.shadow") version "8.3.8"
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
    // Netty is pulled in transitively and RELOCATED via Shadow to avoid classloader
    // conflicts with whatever Netty version the host server (Spigot/Velocity/etc.)
    // ships. See shadowJar task below.
    implementation("com.github.SendableMetatype.NetworkCompatible:netty-transport-nethernet:1.7.0-edugeyser.1")

    implementation("dev.kastle.webrtc:webrtc-java:1.0.3")
    listOf("windows-x86_64", "windows-aarch64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64").forEach { platform ->
        runtimeOnly("dev.kastle.webrtc:webrtc-java:1.0.3:$platform")
    }

    // Bedrock protocol for the redirect handler (match Geyser's version)
    compileOnly("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta12-20260316.225858-10")
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta12-20260316.225858-10")
}

tasks.jar {
    // Disable the default jar — we use shadowJar instead. Shadow produces the
    // final fat jar with relocated Netty; letting the default run just produces
    // an extra unused slim jar.
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")  // produces EduGeyser-Extension-<version>.jar directly
    mergeServiceFiles()

    // Relocate Netty into our own package namespace. This isolates our Netty
    // from whatever version the host server ships, so the extension works
    // identically on Spigot (4.1.x), Velocity (4.1.x/4.2.x), BungeeCord, and
    // any future platform regardless of their Netty version.
    //
    // JNI note: kastle's webrtc-java JNI bindings use kastle's own package
    // names (dev.kastle.webrtc.*), NOT Netty class names, so relocating Netty
    // does not affect JNI resolution.
    relocate("io.netty", "org.geysermc.extension.edugeyser.shaded.netty")

    // Drop native-image config files — they reference the original io.netty
    // names, not the relocated ones, and would just sit as dead weight.
    exclude("META-INF/native-image/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
