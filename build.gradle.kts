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
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
