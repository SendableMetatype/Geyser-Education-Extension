rootProject.name = "EduGeyser-Extension"

val geyserForkDir = file("../GeyserFork")
if (geyserForkDir.isDirectory) {
    includeBuild(geyserForkDir) {
        dependencySubstitution {
            substitute(module("org.geysermc.geyser:api")).using(project(":api"))
            substitute(module("org.geysermc.geyser:core")).using(project(":core"))
        }
    }
}
