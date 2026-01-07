#!/bin/bash
# =============================================================================
# Clean Docker - Stop services, remove images and volumes
# =============================================================================

set -e

echo "🧹 Cleaning Redis Messaging Patterns Docker resources..."
echo ""

# Stop and remove containers, networks, and volumes
echo "📦 Stopping and removing containers, networks, volumes..."
docker-compose down -v --remove-orphans

# Remove project images
echo ""
echo "🗑️  Removing project images..."
docker rmi redismessagingpatternswithjedis-backend 2>/dev/null || true
docker rmi redismessagingpatternswithjedis-frontend 2>/dev/null || true
docker rmi redis:latest 2>/dev/null || true
docker rmi redis/redisinsight:latest 2>/dev/null || true

echo ""
echo "✅ Docker cleanup complete!"
echo ""
