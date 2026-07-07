plugins {
    alias(libs.plugins.dotenv)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.moddev)
    alias(libs.plugins.mod.publish)
}

val id = project.property("mod_id") as String
group = project.property("maven_group") as String
version = project.property("mod_version") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

neoForge {
    version = libs.versions.neoforge.asProvider().get()
    parchment {
        mappingsVersion = libs.versions.parchment.get()
        minecraftVersion = libs.versions.minecraft.asProvider().get()
    }
    validateAccessTransformers = true

    runs {
        create("client") {
            client()
            gameDirectory = file("run")
        }
    }

    mods {
        create(id) {
            sourceSet(sourceSets["main"])
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    "additionalRuntimeClasspath"(jarJar(libs.kotlin.stdlib.get()) { })
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val properties = mapOf(
        "id" to id,
        "version" to project.version,
        "name" to project.property("mod_name") as String,
        "minecraft_version" to libs.versions.minecraft.range.get(),
        "loader_version" to libs.versions.neoforge.range.get()
    )
    filteringCharset = "UTF-8"
    inputs.properties(properties)
    filesMatching("META-INF/neoforge.mods.toml") { expand(properties) }
}

publishMods {
    displayName = "${project.property("mod_name")} ${project.version}"
    changelog = providers.fileContents(layout.projectDirectory.file("changelog.md")).asText
    file = tasks.jar.get().archiveFile
    type = STABLE
    modLoaders.add("neoforge")

    modrinth {
        projectId = project.property("modrinth_id") as String
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
            .orElse(provider { env.fetch("MODRINTH_TOKEN") })
        minecraftVersions.add("1.21.1")
    }

    curseforge {
        projectId = project.property("curseforge_id") as String
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
            .orElse(provider { env.fetch("CURSEFORGE_TOKEN") })
        minecraftVersions.add("1.21.1")
    }

    github {
        repository = project.property("repository") as String
        accessToken = providers.environmentVariable("GITHUB_TOKEN")
            .orElse(provider { env.fetch("GITHUB_TOKEN") })
        commitish = "main"
        tagName = "v${project.version}"
    }
}