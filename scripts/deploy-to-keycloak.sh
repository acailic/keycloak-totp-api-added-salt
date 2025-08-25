#!/bin/bash

# Keycloak TOTP API Extension Deployment Script
# This script deploys the keycloak-totp-api.jar extension to a Keycloak installation

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_NAME="keycloak-totp-api.jar"
JAR_PATH="$PROJECT_DIR/build/libs/$JAR_NAME"
BACKUP_SUFFIX=".backup.$(date +%Y%m%d_%H%M%S)"

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show usage
show_usage() {
    cat << EOF
Usage: $0 [OPTIONS] KEYCLOAK_PATH

Deploy the Keycloak TOTP API extension to a Keycloak installation.

Arguments:
  KEYCLOAK_PATH    Path to Keycloak installation directory

Options:
  -t, --type TYPE     Deployment type: standalone, docker, or kubernetes (default: standalone)
  -b, --build         Build the JAR before deployment
  -v, --verify        Verify deployment after copying
  -r, --restart       Restart Keycloak service after deployment
  -s, --service NAME  Keycloak service name for restart (default: keycloak)
  -h, --help          Show this help message

Examples:
  $0 /opt/keycloak
  $0 --type docker --build /opt/keycloak
  $0 --verify --restart /usr/local/keycloak
  $0 --type kubernetes --build ./keycloak

Deployment Types:
  standalone    Standard Keycloak installation (default)
  docker        Keycloak running in Docker container
  kubernetes    Keycloak running in Kubernetes cluster
EOF
}

# Function to check if JAR exists and build if needed
check_and_build_jar() {
    if [[ ! -f "$JAR_PATH" ]]; then
        print_warning "JAR file not found at $JAR_PATH"
        if [[ "$BUILD_JAR" == "true" ]]; then
            print_info "Building JAR file..."
            cd "$PROJECT_DIR"
            ./gradlew clean shadowJar
            if [[ $? -eq 0 ]]; then
                print_success "JAR built successfully"
            else
                print_error "Failed to build JAR"
                exit 1
            fi
        else
            print_error "JAR file not found. Use --build option to build it first."
            exit 1
        fi
    else
        print_info "Found JAR file: $JAR_PATH"
    fi
}

# Function to validate Keycloak installation
validate_keycloak_path() {
    local kc_path="$1"
    
    if [[ ! -d "$kc_path" ]]; then
        print_error "Keycloak path does not exist: $kc_path"
        exit 1
    fi
    
    # Check for common Keycloak directory structure
    if [[ ! -d "$kc_path/providers" ]]; then
        if [[ -d "$kc_path/bin" && -f "$kc_path/bin/kc.sh" ]]; then
            # Keycloak 17+ structure
            mkdir -p "$kc_path/providers"
            print_info "Created providers directory in Keycloak installation"
        else
            print_error "Invalid Keycloak installation: providers directory not found and cannot determine Keycloak version"
            exit 1
        fi
    fi
    
    print_success "Validated Keycloak installation at: $kc_path"
}

# Function to backup existing extension
backup_existing_extension() {
    local providers_dir="$1"
    local existing_jar="$providers_dir/$JAR_NAME"
    
    if [[ -f "$existing_jar" ]]; then
        local backup_path="${existing_jar}${BACKUP_SUFFIX}"
        print_info "Backing up existing extension to: $backup_path"
        cp "$existing_jar" "$backup_path"
        print_success "Backup created successfully"
        echo "$backup_path" > "$providers_dir/.last_backup"
    else
        print_info "No existing extension found to backup"
    fi
}

# Function to copy JAR to providers directory
deploy_jar() {
    local providers_dir="$1"
    local target_path="$providers_dir/$JAR_NAME"
    
    print_info "Copying JAR to providers directory..."
    cp "$JAR_PATH" "$target_path"
    
    if [[ -f "$target_path" ]]; then
        print_success "Extension deployed successfully to: $target_path"
        
        # Set appropriate permissions
        chmod 644 "$target_path"
        print_info "Set file permissions to 644"
    else
        print_error "Failed to copy JAR file"
        exit 1
    fi
}

# Function to restart Keycloak service
restart_keycloak() {
    local service_name="$1"
    local kc_path="$2"
    
    case "$DEPLOYMENT_TYPE" in
        "standalone")
            if command -v systemctl &> /dev/null; then
                print_info "Restarting Keycloak service: $service_name"
                if sudo systemctl restart "$service_name"; then
                    print_success "Keycloak service restarted successfully"
                    sleep 5
                    sudo systemctl status "$service_name" --no-pager -l
                else
                    print_warning "Failed to restart service automatically"
                    print_info "Manual restart instructions:"
                    echo "  sudo systemctl restart $service_name"
                fi
            else
                print_info "Manual restart required. Run one of the following:"
                echo "  $kc_path/bin/kc.sh start-dev"
                echo "  $kc_path/bin/kc.sh start"
                echo "  Or restart your Keycloak service manually"
            fi
            ;;
        "docker")
            print_info "For Docker deployment, restart your Keycloak container:"
            echo "  docker restart <keycloak-container-name>"
            echo "  Or rebuild your Docker image if the JAR is copied during build"
            ;;
        "kubernetes")
            print_info "For Kubernetes deployment, restart your Keycloak pods:"
            echo "  kubectl rollout restart deployment/keycloak"
            echo "  Or update your deployment to trigger a restart"
            ;;
    esac
}

# Function to verify deployment
verify_deployment() {
    local kc_path="$1"
    local providers_dir="$kc_path/providers"
    local target_path="$providers_dir/$JAR_NAME"
    
    print_info "Verifying deployment..."
    
    # Check if file exists and has correct size
    if [[ -f "$target_path" ]]; then
        local original_size=$(stat -f%z "$JAR_PATH" 2>/dev/null || stat -c%s "$JAR_PATH" 2>/dev/null)
        local deployed_size=$(stat -f%z "$target_path" 2>/dev/null || stat -c%s "$target_path" 2>/dev/null)
        
        if [[ "$original_size" == "$deployed_size" ]]; then
            print_success "File copied successfully (size: $deployed_size bytes)"
        else
            print_warning "File size mismatch - original: $original_size, deployed: $deployed_size"
        fi
    else
        print_error "Deployed file not found"
        return 1
    fi
    
    # Check JAR contents
    print_info "Verifying JAR contents..."
    if jar -tf "$target_path" | grep -q "kotlin/jvm/internal/Intrinsics.class"; then
        print_success "Kotlin runtime classes found in JAR"
    else
        print_warning "Kotlin runtime classes not found - this may cause deployment issues"
    fi
    
    # Provide verification instructions
    cat << EOF

${BLUE}=== Deployment Verification Instructions ===${NC}

1. Check Keycloak logs for extension loading:
   tail -f $kc_path/data/log/keycloak.log
   
2. Look for these log entries:
   - "Deploying keycloak-totp-api.jar"
   - "Started keycloak-totp-api"
   - No ClassNotFoundException or NoClassDefFoundError

3. Test the extension endpoint (after Keycloak restart):
   curl -X GET "http://localhost:8080/realms/{realm}/totp-api/generate"
   
4. Check Keycloak admin console:
   - Login to admin console
   - Go to Realm Settings > Events
   - Verify no deployment errors

${BLUE}=== Troubleshooting ===${NC}

If you see "NoClassDefFoundError: kotlin.jvm.internal.Intrinsics":
1. Rebuild the JAR: ./gradlew clean shadowJar
2. Verify JAR contents: jar -tf build/libs/keycloak-totp-api.jar | grep kotlin
3. Check this script's verification output above

If deployment fails:
1. Check Keycloak logs for specific errors
2. Verify file permissions: ls -la $target_path
3. Restore backup if needed: cp $providers_dir/*.backup.* $target_path

EOF
}

# Function to rollback deployment
rollback_deployment() {
    local providers_dir="$1"
    local backup_file_path="$providers_dir/.last_backup"
    
    if [[ -f "$backup_file_path" ]]; then
        local backup_path=$(cat "$backup_file_path")
        if [[ -f "$backup_path" ]]; then
            print_info "Rolling back to previous version..."
            cp "$backup_path" "$providers_dir/$JAR_NAME"
            print_success "Rollback completed"
            rm -f "$backup_file_path"
        else
            print_error "Backup file not found: $backup_path"
        fi
    else
        print_warning "No backup information found for rollback"
    fi
}

# Parse command line arguments
DEPLOYMENT_TYPE="standalone"
BUILD_JAR="false"
VERIFY_DEPLOYMENT="false"
RESTART_SERVICE="false"
SERVICE_NAME="keycloak"
KEYCLOAK_PATH=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -t|--type)
            DEPLOYMENT_TYPE="$2"
            shift 2
            ;;
        -b|--build)
            BUILD_JAR="true"
            shift
            ;;
        -v|--verify)
            VERIFY_DEPLOYMENT="true"
            shift
            ;;
        -r|--restart)
            RESTART_SERVICE="true"
            shift
            ;;
        -s|--service)
            SERVICE_NAME="$2"
            shift 2
            ;;
        -h|--help)
            show_usage
            exit 0
            ;;
        --rollback)
            if [[ -n "$2" && ! "$2" =~ ^- ]]; then
                KEYCLOAK_PATH="$2"
                shift 2
            else
                print_error "Rollback requires Keycloak path"
                exit 1
            fi
            rollback_deployment "$KEYCLOAK_PATH/providers"
            exit 0
            ;;
        -*)
            print_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
        *)
            if [[ -z "$KEYCLOAK_PATH" ]]; then
                KEYCLOAK_PATH="$1"
            else
                print_error "Multiple paths specified"
                show_usage
                exit 1
            fi
            shift
            ;;
    esac
done

# Validate required arguments
if [[ -z "$KEYCLOAK_PATH" ]]; then
    print_error "Keycloak path is required"
    show_usage
    exit 1
fi

# Validate deployment type
case "$DEPLOYMENT_TYPE" in
    "standalone"|"docker"|"kubernetes")
        ;;
    *)
        print_error "Invalid deployment type: $DEPLOYMENT_TYPE"
        print_error "Valid types: standalone, docker, kubernetes"
        exit 1
        ;;
esac

# Main deployment process
print_info "Starting Keycloak TOTP API Extension deployment..."
print_info "Deployment type: $DEPLOYMENT_TYPE"
print_info "Keycloak path: $KEYCLOAK_PATH"

# Step 1: Check and build JAR if needed
check_and_build_jar

# Step 2: Validate Keycloak installation
validate_keycloak_path "$KEYCLOAK_PATH"

# Step 3: Backup existing extension
PROVIDERS_DIR="$KEYCLOAK_PATH/providers"
backup_existing_extension "$PROVIDERS_DIR"

# Step 4: Deploy the JAR
deploy_jar "$PROVIDERS_DIR"

# Step 5: Restart Keycloak if requested
if [[ "$RESTART_SERVICE" == "true" ]]; then
    restart_keycloak "$SERVICE_NAME" "$KEYCLOAK_PATH"
fi

# Step 6: Verify deployment if requested
if [[ "$VERIFY_DEPLOYMENT" == "true" ]]; then
    verify_deployment "$KEYCLOAK_PATH"
fi

print_success "Deployment completed successfully!"

# Final instructions
cat << EOF

${GREEN}=== Next Steps ===${NC}

1. Restart Keycloak if not done automatically:
   ${DEPLOYMENT_TYPE == "standalone" && echo "sudo systemctl restart $SERVICE_NAME" || echo "See restart instructions above"}

2. Monitor Keycloak logs during startup:
   tail -f $KEYCLOAK_PATH/data/log/keycloak.log

3. Test the extension:
   curl -X GET "http://localhost:8080/realms/{realm}/totp-api/generate"

4. If issues occur, check troubleshooting section above or rollback:
   $0 --rollback $KEYCLOAK_PATH

EOF