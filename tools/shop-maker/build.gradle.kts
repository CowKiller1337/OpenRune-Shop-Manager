plugins {
    id("base-conventions")
    application
}

application {
    mainClass.set("org.rsmod.tools.shopmaker.ShopMakerMainKt")
}

tasks.named<JavaExec>("run") {
    group = "application"
    description = "Opens the OpenRune shop manager desktop tool."
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(projects.orCache)
}
