# Keycloak TOTP API Extension - Deployment Guide

This document provides comprehensive deployment instructions for the Keycloak TOTP API extension, including troubleshooting for common issues like `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics`.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Building the Extension](#building-the-extension)
- [Deployment Methods](#deployment-methods)
- [Verification Steps](#verification-steps)
- [Troubleshooting](#troubleshooting)
- [Alternative Deployment Strategies](#alternative-deployment-strategies)
- [JAR Content Verification](#jar-content-verification)
- [Keycloak Logs Interpretation](#keycloak-logs-interpretation)

## Prerequisites

### Environment Requirements

- **Java**: OpenJDK 21 or later (required for Keycloak 26.0.0+)
- **Gradle**: 8.0+ (wrapper included in project)
- **Keycloak**: Version 26.0.0 or compatible
- **Operating System**: Linux, macOS, or Windows

### Verify Java Installation

```bash
java -version
# Should show Java 21 or later
```

### Verify Gradle Installation

```bash
./gradlew --version
# Should show Gradle 8.0+ and Java 21+
```

## Building the Extension

### Step 1: Clone and Navigate to Project

```bash
git clone https://github.com/acailic/keycloak-totp-api-added-salt.git
cd keycloak-totp-api-added-salt
```

### Step 2: Clean Previous Builds

```bash
./gradlew clean
```

### Step 3: Build the Extension JAR

```bash
./gradlew shadowJar
```

This command will:
- Compile the Kotlin source code
- Bundle all Kotlin runtime dependencies
- Create a self-contained JAR file
- Automatically verify JAR contents
- Generate build verification report

### Step 4: Verify Build Success

The build should complete with output similar to:

```
=== JAR Inspection Report ===
JAR file: /path/to/build/libs/keycloak-totp-api.jar
JAR size: 3,456,789 bytes

=== Critical Kotlin Classes Check ===
✓ kotlin/jvm/internal/Intrinsics.class
✓ kotlin/Metadata.class
✓ kotlin/reflect/KClass.class
✓ kotlin/collections/CollectionsKt.class

=== Service Files Check ===
✓ META-INF/services/ directory

=== Build Verification ===
✓ JAR appears ready for Keycloak deployment
```

The final JAR will be located at: `build/libs/keycloak-totp-api.jar`

## Deployment Methods

### Method 1: Standalone Keycloak Installation

#### Step 1: Copy Extension JAR

```bash
# Copy the JAR to Keycloak providers directory
cp build/libs/keycloak-totp-api.jar $KEYCLOAK_HOME/providers/
```

#### Step 2: Build Keycloak with Extension

```bash
# Navigate to Keycloak installation
cd $KEYCLOAK_HOME

# Build Keycloak with the new extension
./bin/kc.sh build
```

#### Step 3: Start Keycloak

```bash
# Development mode
./bin/kc.sh start-dev

# Production mode (requires additional configuration)
./bin/kc.sh start --hostname=your-hostname
```

### Method 2: Docker Deployment

#### Option A: Volume Mount

```bash
# Run Keycloak with extension mounted
docker run -d \
  --name keycloak \
  -p 8080:8080 \
  -v $(pwd)/build/libs/keycloak-totp-api.jar:/opt/keycloak/providers/keycloak-totp-api.jar \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.0.0 \
  start-dev
```

#### Option B: Custom Docker Image

Create a `Dockerfile`:

```dockerfile
FROM quay.io/keycloak/keycloak:26.0.0

# Copy the extension
COPY build/libs/keycloak-totp-api.jar /opt/keycloak/providers/

# Build Keycloak with the extension
RUN /opt/keycloak/bin/kc.sh build

# Set the entrypoint
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Build and run:

```bash
# Build custom image
docker build -t keycloak-with-totp-api .

# Run the custom image
docker run -d \
  --name keycloak \
  -p 8080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  keycloak-with-totp-api \
  start-dev
```

#### Option C: Docker Compose

Create `docker-compose.yml`:

```yaml
version: '3.8'
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.0.0
    container_name: keycloak
    ports:
      - "8080:8080"
    volumes:
      - ./build/libs/keycloak-totp-api.jar:/opt/keycloak/providers/keycloak-totp-api.jar
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    command: start-dev
```

Deploy:

```bash
docker-compose up -d
```

### Method 3: Kubernetes Deployment

#### Step 1: Create ConfigMap with Extension

```bash
kubectl create configmap keycloak-totp-extension \
  --from-file=keycloak-totp-api.jar=build/libs/keycloak-totp-api.jar
```

#### Step 2: Deploy Keycloak with Extension

Create `keycloak-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
spec:
  replicas: 1
  selector:
    matchLabels:
      app: keycloak
  template:
    metadata:
      labels:
        app: keycloak
    spec:
      containers:
      - name: keycloak
        image: quay.io/keycloak/keycloak:26.0.0
        ports:
        - containerPort: 8080
        env:
        - name: KEYCLOAK_ADMIN
          value: "admin"
        - name: KEYCLOAK_ADMIN_PASSWORD
          value: "admin"
        volumeMounts:
        - name: extension-volume
          mountPath: /opt/keycloak/providers/keycloak-totp-api.jar
          subPath: keycloak-totp-api.jar
        command: ["/opt/keycloak/bin/kc.sh"]
        args: ["start-dev"]
      volumes:
      - name: extension-volume
        configMap:
          name: keycloak-totp-extension
---
apiVersion: v1
kind: Service
metadata:
  name: keycloak-service
spec:
  selector:
    app: keycloak
  ports:
  - port: 8080
    targetPort: 8080
  type: LoadBalancer
```

Deploy:

```bash
kubectl apply -f keycloak-deployment.yaml
```

## Verification Steps

### Step 1: Check Keycloak Startup Logs

```bash
# For standalone installation
tail -f $KEYCLOAK_HOME/data/log/keycloak.log

# For Docker
docker logs -f keycloak

# For Kubernetes
kubectl logs -f deployment/keycloak
```

Look for successful extension loading:

```
INFO  [org.keycloak.services] (main) KC-SERVICES0050: Initializing provider [totp-api] for service [rest-resource]
```

### Step 2: Test Extension Endpoint

```bash
# Get admin token
ADMIN_TOKEN=$(curl -s -X POST "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r '.access_token')

# Test extension endpoint (replace USER_ID with actual user ID)
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/realms/master/totp-api/USER_ID/generate"
```

### Step 3: Verify Extension in Admin Console

1. Log into Keycloak Admin Console
2. Navigate to Realm Settings → Provider Info
3. Look for `totp-api` in the REST Resource providers list

## Troubleshooting

### Common Issue: NoClassDefFoundError: kotlin.jvm.internal.Intrinsics

**Symptoms:**
```
java.lang.NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
    at id.medihause.keycloak.totp.TotpApiResourceProvider.<init>
```

**Root Cause:** Kotlin runtime dependencies are missing from the deployed JAR.

**Solutions:**

1. **Verify JAR Contents:**
   ```bash
   jar -tf build/libs/keycloak-totp-api.jar | grep "kotlin/jvm/internal/Intrinsics"
   ```
   Should return: `kotlin/jvm/internal/Intrinsics.class`

2. **Rebuild with Clean:**
   ```bash
   ./gradlew clean shadowJar
   ```

3. **Check JAR Size:**
   ```bash
   ls -lh build/libs/keycloak-totp-api.jar
   ```
   Should be ~3-4MB (not ~50KB)

4. **Verify Gradle Task:**
   Ensure you're using `shadowJar` not `jar`:
   ```bash
   ./gradlew shadowJar  # ✓ Correct
   ./gradlew jar        # ✗ Wrong - missing dependencies
   ```

### Issue: Extension Not Loading

**Symptoms:**
- No error messages in logs
- Extension endpoints return 404

**Solutions:**

1. **Check Providers Directory:**
   ```bash
   ls -la $KEYCLOAK_HOME/providers/
   # Should show keycloak-totp-api.jar
   ```

2. **Rebuild Keycloak:**
   ```bash
   $KEYCLOAK_HOME/bin/kc.sh build
   ```

3. **Check File Permissions:**
   ```bash
   chmod 644 $KEYCLOAK_HOME/providers/keycloak-totp-api.jar
   ```

### Issue: ClassLoader Conflicts

**Symptoms:**
```
java.lang.LinkageError: loader constraint violation
```

**Solutions:**

1. **Remove Conflicting JARs:**
   Check for other Kotlin-based extensions in providers directory

2. **Verify Shadow JAR Configuration:**
   The build properly excludes Keycloak-provided dependencies

3. **Check Keycloak Version Compatibility:**
   Ensure using Keycloak 26.0.0 or compatible version

### Issue: Service Registration Problems

**Symptoms:**
- Extension loads but endpoints not available
- Missing provider in admin console

**Solutions:**

1. **Verify Service Files:**
   ```bash
   jar -tf build/libs/keycloak-totp-api.jar | grep "META-INF/services"
   ```

2. **Check Service File Contents:**
   ```bash
   unzip -p build/libs/keycloak-totp-api.jar META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory
   ```

## Alternative Deployment Strategies

### Strategy 1: Manual Dependency Management

If the shadow JAR approach fails:

1. **Extract Kotlin Dependencies:**
   ```bash
   mkdir kotlin-deps
   gradle dependencies --configuration runtimeClasspath | grep kotlin
   ```

2. **Copy Individual JARs:**
   ```bash
   cp ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/kotlin-stdlib-*.jar $KEYCLOAK_HOME/providers/
   cp build/libs/keycloak-totp-api.jar $KEYCLOAK_HOME/providers/
   ```

### Strategy 2: Keycloak Module Approach

For older Keycloak versions or specific requirements:

1. **Create Module Structure:**
   ```bash
   mkdir -p $KEYCLOAK_HOME/modules/system/layers/keycloak/id/medihause/totp/main
   ```

2. **Create module.xml:**
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <module xmlns="urn:jboss:module:1.3" name="id.medihause.totp">
       <resources>
           <resource-root path="keycloak-totp-api.jar"/>
       </resources>
       <dependencies>
           <module name="org.keycloak.keycloak-core"/>
           <module name="org.keycloak.keycloak-services"/>
           <module name="javax.ws.rs.api"/>
       </dependencies>
   </module>
   ```

### Strategy 3: Fat JAR with Relocated Dependencies

If conflicts persist, create a custom build with relocated packages:

```kotlin
// In build.gradle.kts
shadowJar {
    relocate("kotlin", "shaded.kotlin")
    relocate("kotlinx", "shaded.kotlinx")
    // ... other configurations
}
```

## JAR Content Verification

### Manual Verification Commands

```bash
# List all contents
jar -tf build/libs/keycloak-totp-api.jar

# Check for Kotlin runtime
jar -tf build/libs/keycloak-totp-api.jar | grep "kotlin/"

# Check for service files
jar -tf build/libs/keycloak-totp-api.jar | grep "META-INF/services"

# Extract and view service file
unzip -p build/libs/keycloak-totp-api.jar META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory

# Check JAR manifest
unzip -p build/libs/keycloak-totp-api.jar META-INF/MANIFEST.MF
```

### Automated Verification Script

Create `verify-jar.sh`:

```bash
#!/bin/bash
JAR_FILE="build/libs/keycloak-totp-api.jar"

echo "=== JAR Verification Report ==="
echo "File: $JAR_FILE"
echo "Size: $(stat -f%z "$JAR_FILE" 2>/dev/null || stat -c%s "$JAR_FILE") bytes"

echo -e "\n=== Critical Classes Check ==="
CRITICAL_CLASSES=(
    "kotlin/jvm/internal/Intrinsics.class"
    "kotlin/Metadata.class"
    "kotlin/reflect/KClass.class"
    "id/medihause/keycloak/totp/TotpApiResourceProvider.class"
)

for class in "${CRITICAL_CLASSES[@]}"; do
    if jar -tf "$JAR_FILE" | grep -q "$class"; then
        echo "✓ $class"
    else
        echo "✗ $class"
    fi
done

echo -e "\n=== Service Files Check ==="
if jar -tf "$JAR_FILE" | grep -q "META-INF/services/"; then
    echo "✓ Service files present"
    jar -tf "$JAR_FILE" | grep "META-INF/services/"
else
    echo "✗ No service files found"
fi
```

## Keycloak Logs Interpretation

### Successful Extension Loading

```
INFO  [org.keycloak.services] (main) KC-SERVICES0050: Initializing provider [totp-api] for service [rest-resource]
INFO  [org.keycloak.services] (main) KC-SERVICES0051: Loaded provider [totp-api] for service [rest-resource]
```

### Failed Extension Loading

```
ERROR [org.keycloak.services] (main) KC-SERVICES0001: Failed to load provider [totp-api]
java.lang.NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
```

### ClassLoader Issues

```
WARN  [org.jboss.modules] (main) Failed to define class ... in Module
java.lang.LinkageError: loader constraint violation
```

### Service Registration Issues

```
WARN  [org.keycloak.services] (main) KC-SERVICES0002: Provider [totp-api] not found for service [rest-resource]
```

### Debug Logging

To enable debug logging for extension loading:

```bash
# Add to keycloak.conf or as environment variable
KC_LOG_LEVEL=DEBUG,org.keycloak.services:TRACE
```

## Performance Considerations

### JAR Size Optimization

The shadow JAR includes all Kotlin runtime dependencies (~3-4MB). For production:

1. **Monitor startup time** - larger JARs may increase startup time
2. **Consider module approach** for multiple Kotlin extensions
3. **Use ProGuard/R8** for further size reduction if needed

### Memory Usage

Kotlin runtime adds minimal memory overhead:
- ~10-20MB additional heap usage
- No significant impact on Keycloak performance

## Security Considerations

### JAR Integrity

Verify JAR integrity in production:

```bash
# Generate checksum
sha256sum build/libs/keycloak-totp-api.jar > keycloak-totp-api.jar.sha256

# Verify checksum
sha256sum -c keycloak-totp-api.jar.sha256
```

### File Permissions

Set appropriate permissions:

```bash
chmod 644 $KEYCLOAK_HOME/providers/keycloak-totp-api.jar
chown keycloak:keycloak $KEYCLOAK_HOME/providers/keycloak-totp-api.jar
```

## Support and Additional Resources

- **Project Repository**: https://github.com/acailic/keycloak-totp-api-added-salt
- **Keycloak Documentation**: https://www.keycloak.org/docs/latest/
- **Kotlin Documentation**: https://kotlinlang.org/docs/

For issues specific to this extension, please create an issue in the project repository with:
- Keycloak version
- Java version
- Complete error logs
- JAR verification output