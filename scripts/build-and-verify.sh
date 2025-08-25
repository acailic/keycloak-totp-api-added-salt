#!/bin/bash

# Keycloak TOTP API Extension - Build and Verification Script
# This script builds the extension and verifies it contains all necessary dependencies

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    local color=$1
    local message=$2
    echo -e "${color}${message}${NC}"
}

print_header() {
    echo
    print_status $BLUE "=== $1 ==="
}

print_success() {
    print_status $GREEN "✓ $1"
}

print_warning() {
    print_status $YELLOW "⚠ $1"
}

print_error() {
    print_status $RED "✗ $1"
}

# Check if we're in the correct directory
if [ ! -f "build.gradle.kts" ]; then
    print_error "build.gradle.kts not found. Please run this script from the project root directory."
    exit 1
fi

print_header "Keycloak TOTP API Extension - Build and Verification"

# Step 1: Clean and build the project
print_header "Step 1: Cleaning and Building Project"
print_status $BLUE "Executing: ./gradlew clean shadowJar"

if ./gradlew clean shadowJar; then
    print_success "Build completed successfully"
else
    print_error "Build failed"
    exit 1
fi

# Step 2: Locate the JAR file
print_header "Step 2: Locating Extension JAR"
JAR_FILE="build/libs/keycloak-totp-api.jar"

if [ -f "$JAR_FILE" ]; then
    print_success "JAR file found: $JAR_FILE"
    JAR_SIZE=$(stat -f%z "$JAR_FILE" 2>/dev/null || stat -c%s "$JAR_FILE" 2>/dev/null)
    print_status $BLUE "JAR size: $JAR_SIZE bytes"
else
    print_error "JAR file not found at $JAR_FILE"
    exit 1
fi

# Step 3: Verify JAR contents
print_header "Step 3: Verifying JAR Contents"

# Get JAR contents
JAR_CONTENTS=$(jar -tf "$JAR_FILE")

# Critical Kotlin runtime classes to check
CRITICAL_CLASSES=(
    "kotlin/jvm/internal/Intrinsics.class"
    "kotlin/Metadata.class"
    "kotlin/reflect/KClass.class"
    "kotlin/collections/CollectionsKt.class"
    "kotlin/Unit.class"
    "kotlin/jvm/functions/Function0.class"
)

print_status $BLUE "Checking for critical Kotlin runtime classes:"
MISSING_CLASSES=0

for class in "${CRITICAL_CLASSES[@]}"; do
    if echo "$JAR_CONTENTS" | grep -q "$class"; then
        print_success "$class"
    else
        print_error "$class - MISSING"
        ((MISSING_CLASSES++))
    fi
done

# Check for Jackson classes
print_status $BLUE "Checking for Jackson dependencies:"
JACKSON_CLASSES=(
    "com/fasterxml/jackson/databind/ObjectMapper.class"
    "com/fasterxml/jackson/core/JsonParser.class"
    "com/fasterxml/jackson/module/kotlin/KotlinModule.class"
)

for class in "${JACKSON_CLASSES[@]}"; do
    if echo "$JAR_CONTENTS" | grep -q "$class"; then
        print_success "$class"
    else
        print_warning "$class - Missing (may cause JSON handling issues)"
    fi
done

# Step 4: Check META-INF/services files
print_header "Step 4: Checking Service Provider Registration"

if echo "$JAR_CONTENTS" | grep -q "META-INF/services/"; then
    print_success "META-INF/services/ directory found"
    
    # List service files
    SERVICE_FILES=$(echo "$JAR_CONTENTS" | grep "META-INF/services/" | grep -v "/$")
    if [ -n "$SERVICE_FILES" ]; then
        print_status $BLUE "Service files found:"
        echo "$SERVICE_FILES" | while read -r service; do
            print_status $BLUE "  - $service"
        done
    else
        print_warning "No service files found in META-INF/services/"
    fi
else
    print_warning "META-INF/services/ directory not found"
fi

# Step 5: Check for Keycloak SPI registration
print_header "Step 5: Checking Keycloak SPI Registration"

KEYCLOAK_SERVICES=(
    "META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory"
    "META-INF/services/org.keycloak.provider.ProviderFactory"
)

for service in "${KEYCLOAK_SERVICES[@]}"; do
    if echo "$JAR_CONTENTS" | grep -q "$service"; then
        print_success "$service"
        
        # Extract and show service content
        if command -v unzip >/dev/null 2>&1; then
            SERVICE_CONTENT=$(unzip -p "$JAR_FILE" "$service" 2>/dev/null || echo "Could not extract service content")
            print_status $BLUE "  Content: $SERVICE_CONTENT"
        fi
    else
        print_warning "$service - Not found"
    fi
done

# Step 6: Validate JAR structure
print_header "Step 6: Validating JAR Structure"

# Check for main extension classes
EXTENSION_CLASSES=$(echo "$JAR_CONTENTS" | grep "\.class$" | grep -E "(Provider|Factory|Resource)" | head -5)
if [ -n "$EXTENSION_CLASSES" ]; then
    print_success "Extension classes found:"
    echo "$EXTENSION_CLASSES" | while read -r class; do
        print_status $BLUE "  - $class"
    done
else
    print_warning "No obvious extension classes found"
fi

# Check for excluded Keycloak classes (should not be present)
KEYCLOAK_CLASSES=$(echo "$JAR_CONTENTS" | grep "org/keycloak/" | head -3)
if [ -n "$KEYCLOAK_CLASSES" ]; then
    print_warning "Keycloak classes found in JAR (may cause conflicts):"
    echo "$KEYCLOAK_CLASSES" | while read -r class; do
        print_status $YELLOW "  - $class"
    done
else
    print_success "No Keycloak classes bundled (good - avoiding conflicts)"
fi

# Step 7: Generate build report
print_header "Step 7: Build Report Summary"

# Count classes by package
TOTAL_CLASSES=$(echo "$JAR_CONTENTS" | grep "\.class$" | wc -l | tr -d ' ')
KOTLIN_CLASSES=$(echo "$JAR_CONTENTS" | grep "kotlin/" | grep "\.class$" | wc -l | tr -d ' ')
JACKSON_CLASSES_COUNT=$(echo "$JAR_CONTENTS" | grep "com/fasterxml/jackson/" | grep "\.class$" | wc -l | tr -d ' ')

print_status $BLUE "Total classes: $TOTAL_CLASSES"
print_status $BLUE "Kotlin runtime classes: $KOTLIN_CLASSES"
print_status $BLUE "Jackson classes: $JACKSON_CLASSES_COUNT"

# Step 8: Final deployment readiness check
print_header "Step 8: Deployment Readiness Assessment"

DEPLOYMENT_READY=true

if [ $MISSING_CLASSES -gt 0 ]; then
    print_error "$MISSING_CLASSES critical Kotlin classes are missing"
    DEPLOYMENT_READY=false
fi

if [ "$JAR_SIZE" -lt 1000000 ]; then  # Less than 1MB might indicate missing dependencies
    print_warning "JAR size seems small ($JAR_SIZE bytes) - may be missing dependencies"
fi

if [ "$KOTLIN_CLASSES" -lt 100 ]; then  # Kotlin stdlib should have many classes
    print_warning "Few Kotlin classes found ($KOTLIN_CLASSES) - Kotlin runtime may be incomplete"
    DEPLOYMENT_READY=false
fi

# Final status
print_header "Final Status"

if [ "$DEPLOYMENT_READY" = true ]; then
    print_success "JAR appears ready for Keycloak deployment!"
    print_status $GREEN "You can deploy $JAR_FILE to your Keycloak providers directory."
    
    echo
    print_status $BLUE "Next steps:"
    print_status $BLUE "1. Copy $JAR_FILE to \$KEYCLOAK_HOME/providers/"
    print_status $BLUE "2. Restart Keycloak"
    print_status $BLUE "3. Check Keycloak logs for successful loading"
    print_status $BLUE "4. Test the TOTP API endpoint"
    
    exit 0
else
    print_error "JAR has issues that may prevent successful deployment"
    print_status $RED "Please review the warnings and errors above before deploying."
    
    echo
    print_status $BLUE "Troubleshooting suggestions:"
    print_status $BLUE "1. Check build.gradle.kts shadow jar configuration"
    print_status $BLUE "2. Ensure all Kotlin dependencies are properly included"
    print_status $BLUE "3. Verify no problematic relocations are applied"
    print_status $BLUE "4. Run './gradlew dependencies' to check dependency tree"
    
    exit 1
fi