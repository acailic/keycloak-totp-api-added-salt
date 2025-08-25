import java.util.zip.ZipFile
import java.io.ByteArrayOutputStream

/**
 * Gradle script to verify that the built JAR contains all necessary Kotlin runtime classes
 * and is ready for Keycloak deployment.
 */

// Configuration
val jarFileName = "keycloak-totp-api.jar"
val buildDir = layout.buildDirectory.get().asFile
val jarFile = File(buildDir, "libs/$jarFileName")

// Critical Kotlin runtime classes that must be present
val criticalKotlinClasses = listOf(
    "kotlin/jvm/internal/Intrinsics.class",
    "kotlin/jvm/internal/Reflection.class",
    "kotlin/jvm/internal/ClassBasedDeclarationContainer.class",
    "kotlin/jvm/internal/KotlinReflectionInternalError.class",
    "kotlin/Metadata.class",
    "kotlin/Unit.class",
    "kotlin/collections/CollectionsKt.class",
    "kotlin/collections/ArraysKt.class",
    "kotlin/text/StringsKt.class",
    "kotlin/reflect/KClass.class",
    "kotlin/reflect/KFunction.class",
    "kotlin/reflect/KProperty.class",
    "kotlin/reflect/full/KClasses.class"
)

// Essential Kotlin stdlib packages that should be present
val essentialKotlinPackages = listOf(
    "kotlin/",
    "kotlin/jvm/",
    "kotlin/jvm/internal/",
    "kotlin/collections/",
    "kotlin/text/",
    "kotlin/reflect/",
    "kotlin/io/",
    "kotlin/ranges/"
)

// Jackson classes for JSON handling
val jacksonClasses = listOf(
    "com/fasterxml/jackson/databind/ObjectMapper.class",
    "com/fasterxml/jackson/core/JsonParser.class",
    "com/fasterxml/jackson/module/kotlin/KotlinModule.class"
)

// Keycloak SPI service files that should be present
val expectedServiceFiles = listOf(
    "META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory"
)

// Classes that should NOT be present (Keycloak provided)
val excludedClasses = listOf(
    "org/keycloak/",
    "jakarta/ws/rs/"
)

tasks.register("verifyJar") {
    group = "verification"
    description = "Verify that the built JAR contains all necessary dependencies for Keycloak deployment"
    
    doLast {
        if (!jarFile.exists()) {
            throw GradleException("JAR file not found: ${jarFile.absolutePath}. Run 'shadowJar' task first.")
        }
        
        println("=" * 80)
        println("JAR VERIFICATION REPORT")
        println("=" * 80)
        println("JAR File: ${jarFile.absolutePath}")
        println("JAR Size: ${String.format("%,d", jarFile.length())} bytes")
        println("Timestamp: ${java.time.LocalDateTime.now()}")
        println()
        
        val jarContents = extractJarContents(jarFile)
        val allClasses = jarContents.filter { it.endsWith(".class") }
        val allFiles = jarContents.toSet()
        
        // 1. JAR Content Overview
        printJarContentOverview(jarContents, allClasses)
        
        // 2. Verify Critical Kotlin Runtime Classes
        verifyCriticalKotlinClasses(allFiles)
        
        // 3. Verify Kotlin Package Coverage
        verifyKotlinPackageCoverage(allClasses)
        
        // 4. Verify Jackson Dependencies
        verifyJacksonDependencies(allFiles)
        
        // 5. Check Service Files
        verifyServiceFiles(allFiles)
        
        // 6. Validate Exclusions
        validateExclusions(allClasses)
        
        // 7. Generate Deployment Readiness Report
        generateDeploymentReadinessReport(allFiles, allClasses)
        
        println("=" * 80)
        println("VERIFICATION COMPLETE")
        println("=" * 80)
    }
}

fun extractJarContents(jarFile: File): List<String> {
    val contents = mutableListOf<String>()
    ZipFile(jarFile).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            if (!entry.isDirectory) {
                contents.add(entry.name)
            }
        }
    }
    return contents.sorted()
}

fun printJarContentOverview(jarContents: List<String>, allClasses: List<String>) {
    println("JAR CONTENT OVERVIEW")
    println("-" * 40)
    println("Total files: ${jarContents.size}")
    println("Class files: ${allClasses.size}")
    println("Resource files: ${jarContents.size - allClasses.size}")
    
    val packageCounts = allClasses
        .map { it.substringBeforeLast("/").replace("/", ".") }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }
        .take(10)
    
    println("\nTop packages by class count:")
    packageCounts.forEach { (pkg, count) ->
        println("  $pkg: $count classes")
    }
    println()
}

fun verifyCriticalKotlinClasses(allFiles: Set<String>) {
    println("CRITICAL KOTLIN RUNTIME CLASSES")
    println("-" * 40)
    
    var missingCount = 0
    criticalKotlinClasses.forEach { className ->
        val present = allFiles.contains(className)
        val status = if (present) "✓" else "✗"
        println("$status $className")
        if (!present) {
            missingCount++
        }
    }
    
    println()
    if (missingCount == 0) {
        println("✓ All critical Kotlin runtime classes are present")
    } else {
        println("✗ $missingCount critical Kotlin runtime classes are missing")
        println("  This may cause NoClassDefFoundError at runtime!")
    }
    println()
}

fun verifyKotlinPackageCoverage(allClasses: List<String>) {
    println("KOTLIN PACKAGE COVERAGE")
    println("-" * 40)
    
    essentialKotlinPackages.forEach { pkg ->
        val classesInPackage = allClasses.filter { it.startsWith(pkg) }
        val status = if (classesInPackage.isNotEmpty()) "✓" else "✗"
        println("$status $pkg (${classesInPackage.size} classes)")
        
        if (classesInPackage.isEmpty()) {
            println("  WARNING: No classes found in essential package $pkg")
        }
    }
    
    val totalKotlinClasses = allClasses.count { it.startsWith("kotlin/") }
    println("\nTotal Kotlin runtime classes: $totalKotlinClasses")
    println()
}

fun verifyJacksonDependencies(allFiles: Set<String>) {
    println("JACKSON DEPENDENCIES")
    println("-" * 40)
    
    var missingCount = 0
    jacksonClasses.forEach { className ->
        val present = allFiles.contains(className)
        val status = if (present) "✓" else "✗"
        println("$status $className")
        if (!present) {
            missingCount++
        }
    }
    
    val jacksonClassCount = allFiles.count { it.startsWith("com/fasterxml/jackson/") }
    println("\nTotal Jackson classes: $jacksonClassCount")
    
    if (missingCount == 0) {
        println("✓ Jackson dependencies appear to be properly included")
    } else {
        println("✗ Some Jackson classes are missing - JSON handling may not work")
    }
    println()
}

fun verifyServiceFiles(allFiles: Set<String>) {
    println("SERVICE PROVIDER FILES")
    println("-" * 40)
    
    val serviceFiles = allFiles.filter { it.startsWith("META-INF/services/") }
    println("Found ${serviceFiles.size} service files:")
    
    serviceFiles.forEach { serviceFile ->
        println("  ✓ $serviceFile")
    }
    
    if (serviceFiles.isEmpty()) {
        println("  ✗ No service files found!")
        println("  WARNING: Keycloak may not discover this extension")
    }
    
    // Check for expected service files
    expectedServiceFiles.forEach { expectedFile ->
        val present = allFiles.contains(expectedFile)
        val status = if (present) "✓" else "✗"
        println("$status Expected: $expectedFile")
    }
    println()
}

fun validateExclusions(allClasses: List<String>) {
    println("EXCLUSION VALIDATION")
    println("-" * 40)
    
    var violationCount = 0
    excludedClasses.forEach { excludedPackage ->
        val violatingClasses = allClasses.filter { it.startsWith(excludedPackage) }
        if (violatingClasses.isNotEmpty()) {
            println("✗ Found ${violatingClasses.size} classes in excluded package: $excludedPackage")
            violatingClasses.take(5).forEach { className ->
                println("    $className")
            }
            if (violatingClasses.size > 5) {
                println("    ... and ${violatingClasses.size - 5} more")
            }
            violationCount++
        } else {
            println("✓ No classes found in excluded package: $excludedPackage")
        }
    }
    
    if (violationCount == 0) {
        println("✓ All exclusions are properly enforced")
    } else {
        println("✗ $violationCount exclusion violations found")
        println("  This may cause classloader conflicts in Keycloak")
    }
    println()
}

fun generateDeploymentReadinessReport(allFiles: Set<String>, allClasses: List<String>) {
    println("DEPLOYMENT READINESS ASSESSMENT")
    println("-" * 40)
    
    val checks = mutableListOf<Pair<String, Boolean>>()
    
    // Check 1: Critical Kotlin classes
    val hasCriticalKotlin = criticalKotlinClasses.all { allFiles.contains(it) }
    checks.add("Critical Kotlin runtime classes" to hasCriticalKotlin)
    
    // Check 2: Service files
    val hasServiceFiles = allFiles.any { it.startsWith("META-INF/services/") }
    checks.add("Service provider files" to hasServiceFiles)
    
    // Check 3: No excluded classes
    val hasNoExcludedClasses = excludedClasses.none { pkg -> 
        allClasses.any { it.startsWith(pkg) } 
    }
    checks.add("Proper exclusions" to hasNoExcludedClasses)
    
    // Check 4: Reasonable JAR size (should be > 1MB for Kotlin + Jackson)
    val hasReasonableSize = jarFile.length() > 1_000_000
    checks.add("Reasonable JAR size (>1MB)" to hasReasonableSize)
    
    // Check 5: Jackson classes
    val hasJackson = jacksonClasses.any { allFiles.contains(it) }
    checks.add("Jackson JSON handling" to hasJackson)
    
    checks.forEach { (check, passed) ->
        val status = if (passed) "✓" else "✗"
        println("$status $check")
    }
    
    val passedChecks = checks.count { it.second }
    val totalChecks = checks.size
    
    println()
    println("OVERALL ASSESSMENT: $passedChecks/$totalChecks checks passed")
    
    when {
        passedChecks == totalChecks -> {
            println("🎉 JAR IS READY FOR KEYCLOAK DEPLOYMENT")
            println("   The extension should work without NoClassDefFoundError issues")
        }
        passedChecks >= totalChecks * 0.8 -> {
            println("⚠️  JAR IS MOSTLY READY - MINOR ISSUES DETECTED")
            println("   Review the warnings above before deployment")
        }
        else -> {
            println("❌ JAR IS NOT READY FOR DEPLOYMENT")
            println("   Fix the issues above before attempting to deploy")
        }
    }
    
    println()
    println("DEPLOYMENT COMMANDS:")
    println("  Copy to Keycloak: cp ${jarFile.name} \$KEYCLOAK_HOME/providers/")
    println("  Restart Keycloak: \$KEYCLOAK_HOME/bin/kc.sh build && \$KEYCLOAK_HOME/bin/kc.sh start")
    println("  Verify deployment: Check Keycloak logs for extension loading")
}

// Helper function for string repetition
operator fun String.times(count: Int): String = this.repeat(count)

// Make verifyJar depend on shadowJar if it exists
tasks.named("verifyJar") {
    val shadowJarTask = tasks.findByName("shadowJar")
    if (shadowJarTask != null) {
        dependsOn(shadowJarTask)
    }
}