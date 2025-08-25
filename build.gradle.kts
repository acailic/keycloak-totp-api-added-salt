import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.0"
    id("com.gradleup.shadow") version "8.3.0"
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
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
    jvmToolchain(21)
}

tasks {
    val shadowJar by existing(ShadowJar::class) {
        // Explicitly set configurations to include runtime dependencies
        configurations = listOf(project.configurations.runtimeClasspath.get())
        
        mergeServiceFiles()
        dependsOn(build)
        archiveFileName.set("keycloak-totp-api.jar")
        archiveClassifier.set("")
        
        // Exclude server-provided libraries to avoid classloader conflicts
        exclude("org/keycloak/**")
        exclude("jakarta/ws/rs/**")
        
        // Exclude Keycloak and JAX-RS dependencies from being bundled
        dependencies {
            exclude(dependency("org.keycloak:.*"))
            exclude(dependency("jakarta.ws.rs:.*"))
            // Explicitly include all Kotlin runtime dependencies and their transitive dependencies
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk8"))
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk7"))
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib-common"))
            include(dependency("org.jetbrains.kotlin:kotlin-reflect"))
            include(dependency("org.jetbrains:annotations"))
            // Include Jackson dependencies for JSON handling
            include(dependency("com.fasterxml.jackson.core:jackson-databind"))
            include(dependency("com.fasterxml.jackson.core:jackson-core"))
            include(dependency("com.fasterxml.jackson.core:jackson-annotations"))
            include(dependency("com.fasterxml.jackson.module:jackson-module-kotlin"))
        }
        
        // Ensure proper merging of META-INF/services files
        mergeServiceFiles {
            setPath("META-INF/services")
        }
    }
    
    // Add JAR inspection task to verify contents during build
    register("inspectJar") {
        dependsOn(shadowJar)
        doLast {
            val jarFile = shadowJar.get().archiveFile.get().asFile
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
            
            exec {
                commandLine("jar", "-tf", jarFile.absolutePath)
                standardOutput = ByteArrayOutputStream()
                val jarContents = standardOutput.toString()
                
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
    }
    
    // Make shadowJar depend on inspectJar for automatic verification
    shadowJar {
        finalizedBy("inspectJar")
    }
}
