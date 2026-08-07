plugins {
    java
}

group = "org.geysermc.extension"
version = "3.7.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main")
    maven("https://repo.opencollab.dev/maven-snapshots")
    maven("https://repo.viaversion.com")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.SendableMetatype.EduGeyser:api:49511769")
    compileOnly("com.github.SendableMetatype.EduGeyser:core:49511769")
    implementation("org.spongepowered:configurate-yaml:4.2.0")

    testImplementation(libs.junit)
    testImplementation(libs.gson.runtime)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
