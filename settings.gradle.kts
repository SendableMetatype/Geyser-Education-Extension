rootProject.name = "EduGeyser-Extension"

val geyserForkDir = file("../GeyserFork")
if (geyserForkDir.isDirectory) {
    includeBuild(geyserForkDir) {
        dependencySubstitution {
            substitute(module("com.github.SendableMetatype.EduGeyser:api")).using(project(":api"))
            substitute(module("com.github.SendableMetatype.EduGeyser:core")).using(project(":core"))
        }
    }
}
