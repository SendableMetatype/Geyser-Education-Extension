plugins {
    java
}

group = "org.geysermc.extension"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main")
    maven("https://repo.opencollab.dev/maven-snapshots")
}

dependencies {
    compileOnly("org.geysermc.geyser:api:2.6.1-SNAPSHOT")
    compileOnly("org.geysermc.geyser:core:2.6.1-SNAPSHOT")
    compileOnly("org.spongepowered:configurate-yaml:4.1.2")
}
