#!/bin/bash
# =============================================================================
# Launch Docker Compose - Build, Start, and optionally Follow Logs
# =============================================================================
# Usage:
#   ./launch-docker.sh              # Start (rebuild only if image missing)
#   ./launch-docker.sh --build      # Force rebuild frontend & backend images
#   ./launch-docker.sh --follow     # Start and follow logs
#   ./launch-docker.sh -f           # Start and follow logs (short)
#   ./launch-docker.sh --build -f   # Rebuild + follow logs
# =============================================================================

set -e

# Parse arguments
FOLLOW_LOGS=false
FORCE_BUILD=false
for arg in "$@"; do
    case $arg in
        --follow|-f)
            FOLLOW_LOGS=true
            ;;
        --build|-b)
            FORCE_BUILD=true
            ;;
    esac
done

echo "🚀 Starting Redis Messaging Patterns..."
echo ""

# -------------------------------------------------------------------------
# Step 1: Pull latest Redis images
# -------------------------------------------------------------------------
echo "📥 Pulling latest Redis images..."
docker pull redis:latest --quiet
docker pull redis/redisinsight:latest --quiet
echo "   ✅ Redis images up to date"
echo ""

# -------------------------------------------------------------------------
# Step 2: Build backend/frontend images
# -------------------------------------------------------------------------
BACKEND_IMAGE="redismessagingpatternswithjedis-backend"
FRONTEND_IMAGE="redismessagingpatternswithjedis-frontend"

BUILD_ARGS=""

if [ "$FORCE_BUILD" = true ]; then
    echo "🔨 Force rebuild requested"
    BUILD_ARGS="--build"
else
    if ! docker image inspect "$BACKEND_IMAGE" > /dev/null 2>&1; then
        echo "🔨 Backend image not found, will build..."
        BUILD_ARGS="--build"
    else
        echo "   ✅ Backend image exists"
    fi

    if ! docker image inspect "$FRONTEND_IMAGE" > /dev/null 2>&1; then
        echo "🔨 Frontend image not found, will build..."
        BUILD_ARGS="--build"
    else
        echo "   ✅ Frontend image exists"
    fi
fi

echo ""

# -------------------------------------------------------------------------
# Step 3: Start all services
# -------------------------------------------------------------------------
echo "🐳 Starting containers..."
docker compose up -d $BUILD_ARGS

echo ""
echo "✅ All services started!"
echo ""
echo "📍 Access URLs:"
echo "   • Frontend:      http://localhost:4200"
echo "   • Backend API:   http://localhost:8080/api"
echo "   • Redis Insight: http://localhost:5540"
echo "   • Redis:         redis://default@redis-messaging-redis:6379"
echo ""

# Follow logs if requested
if [ "$FOLLOW_LOGS" = true ]; then
    echo "📋 Following logs (Ctrl+C to stop)..."
    echo ""
    docker compose logs -f
else
    echo "💡 To follow logs, run: docker compose logs -f"
    echo "   Or restart with: ./launch-docker.sh --follow"
fi
