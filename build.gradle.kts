    plugins {
    id("java")
    id("maven-publish")
    alias(libs.plugins.loom)
    alias(libs.plugins.ploceus)
}

group = "re.lilith"
version = "1.0-SNAPSHOT"

ploceus.setIntermediaryGeneration(2)

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

repositories {
    mavenCentral()

    exclusiveContent {
        forRepository {
            mavenCentral()
        }

        filter {
            includeGroup("org.lwjgl")
        }
    }

    maven("https://maven.taumc.org/releases")
    maven("https://maven.cloverclient.com/releases")
    maven("https://jitpack.io")
    maven("https://maven.legacyfabric.net")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.legacy.yarn) { classifier("v2") })

    modImplementation(libs.fabric.loader)
    modImplementation(libs.pylon)
    modImplementation(libs.devauth.fabric)

    modImplementation(libs.argentum)

    implementation(libs.celeritas)
    implementation(libs.fastutil)
    implementation(libs.joml)

    bundled(libs.jcpp)
    bundled(libs.glsl.transformation.lib) {
        exclude(module = "antlr4")
    }
    bundled(libs.antlr4.runtime)

    ploceus.dependOsl(libs.versions.osl.get())
}

fun DependencyHandlerScope.bundled(
    dependency: Any,
    configure: ExternalModuleDependency.() -> Unit = {}
) {
    add("include", dependency)
    add("implementation", dependency).apply {
        if (this is ExternalModuleDependency) {
            configure()
        }
    }
}

val packtest = sourceSets.create("packtest")
packtest.compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
packtest.runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath

loom {
    accessWidenerPath.set(
        file("src/main/resources/aurum.accesswidener")
    )

    mods {
        create("aurum") {
            sourceSet(sourceSets.main.get())
        }
        create("aurum-packtest") {
            sourceSet(packtest)
        }
    }

    runs.named("client") {
        jvmArguments.add("-Ddevauth.enabled=true")
        jvmArguments.add("-Ddevauth.account=alt")
    }

    runs.create("packTest") {
        inherit(runs.getByName("client"))
        jvmArguments.empty()
        sourceSet = packtest.name
        displayName = "Shader Pack Test"
        systemProperties.put("aurum.packtest", "true")
        providers.gradleProperty("packs").orNull?.let { systemProperties.put("aurum.packtest.packs", it) }
        providers.gradleProperty("packTime").orNull?.let { systemProperties.put("aurum.packtest.time", it) }
        providers.gradleProperty("glDebug").orNull?.let { systemProperties.put("aurum.packtest.glDebug", it) }
    }
}

configurations.configureEach {
    exclude(group = "org.lwjgl.lwjgl")

    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") {
            useVersion(libs.versions.lwjgl.get())
        }
    }
}

sourceSets {
    main {
        java.srcDirs("src/main/vendored")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.java.get().toInt()
}

tasks.test {
    useJUnitPlatform()
}
tasks.processResources {
    inputs.property("version", version)

    filesMatching(listOf("fabric.mod.json")) {
        expand(mapOf("version" to version))
    }
}