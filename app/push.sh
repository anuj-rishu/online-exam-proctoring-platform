#!/usr/bin/env bash
set -e

# --- ANSI Color Codes for Professional Output ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}=====================================${NC}"
echo -e "${CYAN}🚀 Pre-push Verification & Release    ${NC}"
echo -e "${CYAN}=====================================${NC}"

# Step 1: Compile Code
echo -e "\n${YELLOW}[1/6] ⚙️  Compiling code with Maven...${NC}"
if ! ./mvnw clean compile; then
    echo -e "\n${RED}❌ ERROR: Maven compilation failed! Please fix the errors above.${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Compilation successful!${NC}"

# Step 2: Build Docker Images
echo -e "\n${YELLOW}[2/6] 🐳 Building Sandbox Docker images...${NC}"
echo "Building sandbox-python..."
if ! docker build -t sandbox-python docker/sandbox-python/ > /dev/null 2>&1; then
    echo -e "\n${RED}❌ ERROR: Failed to build sandbox-python!${NC}"
    exit 1
fi

echo "Building sandbox-node..."
if ! docker build -t sandbox-node docker/sandbox-node/ > /dev/null 2>&1; then
    echo -e "\n${RED}❌ ERROR: Failed to build sandbox-node!${NC}"
    exit 1
fi

echo "Building sandbox-java..."
if ! docker build -t sandbox-java docker/sandbox-java/ > /dev/null 2>&1; then
    echo -e "\n${RED}❌ ERROR: Failed to build sandbox-java!${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Docker images built successfully!${NC}"

# Step 3: Git Add
echo -e "\n${YELLOW}[3/6] 📦 Staging changes...${NC}"
git add .
echo -e "${GREEN}✅ All changes staged!${NC}"

# Step 4: Versioning
echo -e "\n${YELLOW}[4/6] 🏷️  Versioning...${NC}"
if [ ! -f "VERSION" ]; then
    echo "1.0.0" > VERSION
fi

CURRENT_VERSION=$(cat VERSION)
echo -e "Current Version: ${CYAN}$CURRENT_VERSION${NC}"

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

# Fallbacks for malformed VERSION file
MAJOR=${MAJOR:-1}
MINOR=${MINOR:-0}
PATCH=${PATCH:-0}

echo "Select version bump type:"
echo "  [1] Major ($((MAJOR+1)).0.0) - Breaking changes or massive overhauls"
echo "  [2] Minor (${MAJOR}.$((MINOR+1)).0) - New features added (backward compatible)"
echo "  [3] Patch (${MAJOR}.${MINOR}.$((PATCH+1))) - Bug fixes or small tweaks"
read -p "Choice (1/2/3) [3]: " BUMP_CHOICE
BUMP_CHOICE=${BUMP_CHOICE:-3}

if [ "$BUMP_CHOICE" == "1" ]; then
    NEW_VERSION="$((MAJOR+1)).0.0"
elif [ "$BUMP_CHOICE" == "2" ]; then
    NEW_VERSION="${MAJOR}.$((MINOR+1)).0"
else
    NEW_VERSION="${MAJOR}.${MINOR}.$((PATCH+1))"
fi

echo "$NEW_VERSION" > VERSION
git add VERSION
echo -e "${GREEN}✅ Version bumped to $NEW_VERSION!${NC}"

# Step 5: Commit & Tag
echo -e "\n${YELLOW}[5/6] 📝 Commit & Git Tag...${NC}"
read -r -p "Enter commit message (optional): " COMMIT_MSG

if [ -z "$COMMIT_MSG" ]; then
    FINAL_MSG="v${NEW_VERSION}"
else
    FINAL_MSG="v${NEW_VERSION} - ${COMMIT_MSG}"
fi

echo -e "Committing with message: \"${CYAN}$FINAL_MSG${NC}\""
ALLOW_SCRIPT=1 git commit -m "$FINAL_MSG"

# Create a Git Tag ONLY for Major releases
if [ "$BUMP_CHOICE" == "1" ]; then
    git tag -a "v$NEW_VERSION" -m "Major Release v$NEW_VERSION"
    echo -e "${GREEN}✅ Commit created and tagged as Major Release v$NEW_VERSION!${NC}"
else
    echo -e "${GREEN}✅ Commit created! (No GitHub tag created since it's not a Major version)${NC}"
fi

# Step 6: Push
echo -e "\n${YELLOW}[6/6] 🚀 Pushing to GitHub...${NC}"
# Push current branch bypassing the pre-push hook
ALLOW_SCRIPT=1 git push origin HEAD

# Push the new tag to GitHub ONLY for Major releases
if [ "$BUMP_CHOICE" == "1" ]; then
    ALLOW_SCRIPT=1 git push origin "v$NEW_VERSION"
fi

echo -e "\n${GREEN}🎉 RELEASE COMPLETE! Version $NEW_VERSION is fully deployed to GitHub!${NC}"
