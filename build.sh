#!/bin/bash

###############################################################################
# Redis Messaging Patterns - Build Script
#
# This script handles the complete build process:
# 1. Cleans previous builds
# 2. Downloads and resolves all Maven dependencies
# 3. Compiles the Java code
# 4. Runs tests (optional)
# 5. Packages the application as a JAR
#
# Usage:
#   ./build.sh              # Full build with tests
#   ./build.sh --skip-tests # Build without running tests
#   ./build.sh clean        # Clean only
#   ./build.sh install      # Build and install to local Maven repository
#
# Requirements:
#   - Java 21 or higher
#   - Maven 3.6+ (or use the Maven Wrapper: ./mvnw)
#
###############################################################################

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Functions
print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Check Java version
check_java() {
    print_header "Checking Java Version"
    
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed. Please install Java 21 or higher."
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    
    if [ "$JAVA_VERSION" -lt 21 ]; then
        print_error "Java 21 or higher is required. Current version: $JAVA_VERSION"
        exit 1
    fi
    
    print_success "Java version: $(java -version 2>&1 | head -n 1)"
}

# Check Maven
check_maven() {
    print_header "Checking Maven"
    
    if command -v mvn &> /dev/null; then
        MVN_CMD="mvn"
        print_success "Maven found: $(mvn -version | head -n 1)"
    elif [ -f "./mvnw" ]; then
        MVN_CMD="./mvnw"
        print_success "Using Maven Wrapper"
    else
        print_error "Maven is not installed and Maven Wrapper not found."
        print_info "Please install Maven 3.6+ or download Maven Wrapper"
        exit 1
    fi
}

# Parse command line arguments
SKIP_TESTS=false
CLEAN_ONLY=false
INSTALL=false

for arg in "$@"; do
    case $arg in
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        clean)
            CLEAN_ONLY=true
            shift
            ;;
        install)
            INSTALL=true
            shift
            ;;
        *)
            ;;
    esac
done

# Main build process
main() {
    print_header "Redis Messaging Patterns - Build Process"
    
    check_java
    check_maven
    
    # Clean
    print_header "Cleaning Previous Builds"
    $MVN_CMD clean
    print_success "Clean completed"
    
    if [ "$CLEAN_ONLY" = true ]; then
        print_success "Clean-only mode: Build process completed"
        exit 0
    fi
    
    # Download dependencies
    print_header "Downloading Dependencies"
    $MVN_CMD dependency:resolve dependency:resolve-plugins
    print_success "Dependencies downloaded"
    
    # Compile
    print_header "Compiling Source Code"
    $MVN_CMD compile
    print_success "Compilation completed"
    
    # Test
    if [ "$SKIP_TESTS" = false ]; then
        print_header "Running Tests"
        $MVN_CMD test
        print_success "Tests completed"
    else
        print_info "Skipping tests"
    fi
    
    # Package
    print_header "Packaging Application"
    if [ "$SKIP_TESTS" = true ]; then
        $MVN_CMD package -DskipTests
    else
        $MVN_CMD package
    fi
    print_success "Packaging completed"
    
    # Install (optional)
    if [ "$INSTALL" = true ]; then
        print_header "Installing to Local Maven Repository"
        if [ "$SKIP_TESTS" = true ]; then
            $MVN_CMD install -DskipTests
        else
            $MVN_CMD install
        fi
        print_success "Installation completed"
    fi
    
    # Summary
    print_header "Build Summary"
    echo -e "${GREEN}Build completed successfully!${NC}"
    echo ""
    echo "Artifacts:"
    ls -lh target/*.jar 2>/dev/null || echo "No JAR files found"
    echo ""
    print_info "To run the application:"
    echo "  java -jar target/redis-messaging-patterns-1.0.0.jar"
    echo ""
    print_info "Or use Maven:"
    echo "  $MVN_CMD spring-boot:run"
}

# Run main function
main

