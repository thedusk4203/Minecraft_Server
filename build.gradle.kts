plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.thedusk.minecraft"
version = "0.4.2-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://jitpack.io") {
        name = "jitpack"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.218-stable")
    compileOnly("com.github.GriefPrevention:GriefPrevention:16.18.7") {
        isTransitive = false
    }
    compileOnly("dev.aurelium:auraskills-api-bukkit:2.4.0")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.flywaydb:flyway-core:13.11.1")
    implementation("org.flywaydb:flyway-database-postgresql:13.11.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs PostgreSQL-backed integration tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("minecraft-server-core")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("org/slf4j/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// E2E-only Paper fault-injection helper. These tasks are intentionally NOT
// dependencies of assemble, test or shadowJar: the production jar remains clean.
val compileStoneVaultE2EFault = tasks.register<JavaCompile>("compileStoneVaultE2EFault") {
    description = "Compile the isolated E2E-only Stone Vault fault helper."
    group = "verification"
    source(fileTree("tools/e2e/fault-plugin") { include("**/*.java") })
    classpath = sourceSets.main.get().compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("e2e-fault-classes"))
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    options.release.set(25)
}

tasks.register<Jar>("packageStoneVaultE2EFault") {
    description = "Package the isolated fault helper for ephemeral Paper E2E only."
    group = "verification"
    dependsOn(compileStoneVaultE2EFault)
    archiveFileName.set("stone-vault-e2e-fault.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(compileStoneVaultE2EFault.map { it.destinationDirectory })
    from("tools/e2e/fault-plugin/plugin.yml")
}

