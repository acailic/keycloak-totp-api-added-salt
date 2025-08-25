import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "id.medihause"
version = "1.0.1-kc26"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.keycloak:keycloak-services:26.0.0")
    compileOnly("org.keycloak:keycloak-server-spi:26.0.0")
    compileOnly("org.keycloak:keycloak-server-spi-private:26.0.0")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

// Add JAR inspection task to verify contents during build
tasks.register("inspectJar") {
    dependsOn(tasks.shadowJar)
    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        println("=== JAR Inspection Report ===")
        println("JAR file: ${jarFile.absolutePath}")
        println("JAR size: ${jarFile.length()} bytes")
        
        // Check for critical Kotlin runtime classes
        val criticalClasses = listOf(
            "kotlin/jvm/internal/Intrinsics.class",
            "kotlin/Metadata.class",
            "kotlin/reflect/KClass.class",
            "kotlin/collections/CollectionsKt.class"
        )
        
        val output = ByteArrayOutputStream()
        exec {
            commandLine("jar", "-tf", jarFile.absolutePath)
            standardOutput = output
        }
        val jarContents = output.toString()
        
        println("\n=== Critical Kotlin Classes Check ===")
        criticalClasses.forEach { className ->
            val found = jarContents.contains(className)
            println("${if (found) "✓" else "✗"} $className")
            if (!found) {
                logger.warn("Missing critical class: $className")
            }
        }
        
        // Check for service files
        val hasServices = jarContents.contains("META-INF/services/")
        println("\n=== Service Files Check ===")
        println("${if (hasServices) "✓" else "✗"} META-INF/services/ directory")
        
        println("\n=== Build Verification ===")
        val allCriticalPresent = criticalClasses.all { jarContents.contains(it) }
        if (allCriticalPresent) {
            println("✓ JAR appears ready for Keycloak deployment")
        } else {
            println("✗ JAR may have missing dependencies - check warnings above")
        }
    }
}
