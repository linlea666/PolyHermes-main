# Version Management Guide

> 📖 **中文版本**: [版本号管理说明（中文）](../zh/VERSION_MANAGEMENT.md)

## Overview

This project supports automatic version number management and display. When creating a release tag on GitHub, it automatically triggers GitHub Actions to build Docker images and push them to Docker Hub, while displaying the version number after the frontend title.

## Features

1. **Auto Build**: Automatically triggers GitHub Actions when creating release tags
2. **Version Display**: Displays version number after frontend title (small font)
3. **Click to Navigate**: Click version number to jump to corresponding GitHub tag page
4. **Docker Push**: Automatically builds and pushes to Docker Hub
5. **Auto Delete**: Automatically deletes corresponding Docker image tags when releases are deleted
6. **Version Validation**: Strictly matches version format `v数字.数字.数字` or `v数字.数字.数字-后缀` (e.g., `v1.0.0`, `v1.0.0-beta`)
7. **Independent Scripts**: Build and delete functions separated into different workflow files for easier management and maintenance

## Workflow File Description

The project uses two independent GitHub Actions workflow files:

- **`.github/workflows/docker-build.yml`**: Responsible for building and pushing Docker images
  - Trigger: `release: published` (when creating release)
  - Functions: Extract version number, build multi-architecture images, push to Docker Hub

- **`.github/workflows/docker-delete.yml`**: Responsible for deleting Docker images
  - Trigger: `release: deleted` (when deleting release)
  - Functions: Validate version format, delete corresponding Docker image tags

## Usage

### 1. Configure Docker Hub Credentials

Add the following Secrets in GitHub repository settings:

- `DOCKER_USERNAME`: Docker Hub username (e.g., `wrbug`)
- `DOCKER_PASSWORD`: Docker Hub access token (recommended) or password

**Setup Steps**:

1. **Create Docker Hub Access Token** (Recommended):
   - Visit: https://hub.docker.com/settings/security
   - Click "New Access Token"
   - Fill in description (e.g., `GitHub Actions PolyHermes`)
   - **Important**: Check the following permissions:
     - ✅ `Read & Write` (for pushing images)
     - ✅ `Delete repository tags` (for deleting images)
   - Click "Generate"
   - **Copy the token immediately** (only shown once)

2. **Add Secrets in GitHub**:
   - Visit GitHub repository → Settings → Secrets and variables → Actions
   - Click "New repository secret"
   - Add `DOCKER_USERNAME`: Your Docker Hub username
   - Add `DOCKER_PASSWORD`: The Access Token you just created (not password)

**Note**:
- ⚠️ If using password instead of Access Token, the delete image function may not work properly
- ✅ Recommended to use Access Token and ensure `Delete repository tags` permission

### 2. Create Release (Must be via GitHub Releases Page)

**Important**: Only creating releases via [GitHub Releases page](https://github.com/linlea666/PolyHermes-main/releases/new) will trigger auto build.

**Workflow Description**:
- When creating a release, it triggers `docker-build.yml` workflow, automatically building and pushing images
- When deleting a release, it triggers `docker-delete.yml` workflow, automatically deleting corresponding image tags

**Creation Steps**:
1. Visit [GitHub Releases page](https://github.com/linlea666/PolyHermes-main/releases/new)
2. Click "Choose a tag" dropdown, enter new tag name (e.g., `v1.0.0` or `v1.0.0-beta`)
   - If tag doesn't exist, GitHub will automatically create it
   - Tag format: `v数字.数字.数字` or `v数字.数字.数字-后缀` (e.g., `v1.0.0`, `v1.0.0-beta`, `v2.10.102-rc.1`)
3. Fill in Release title (e.g., `v1.0.0` or `v1.0.0-beta`)
4. Fill in Release description (optional, recommended to include update content)
5. Click "Publish release" button

**Note**:
- ⚠️ Pushing tags directly via `git push` **will not** trigger build
- ✅ Only clicking "Publish release" on Releases page will trigger build
- This ensures only officially released versions will build Docker images

### 3. Auto Build Process

After clicking "Publish release", GitHub Actions will automatically:

1. **Extract Version**: Extract version number from tag (e.g., `v1.0.0` → `1.0.0`)
2. **Build Docker Image**: Use version number as build parameter
3. **Inject Version**: Inject version number into code when building frontend
4. **Push Image**: Push to Docker Hub with tags:
   - `wrbug/polyhermes:v1.0.0` (specific version)
   - `wrbug/polyhermes:latest` (latest version)

### 4. Version Display

Frontend will display version number after title "PolyHermes", format: `PolyHermes v1.0.0`

- **Display Location**: Desktop left sidebar title, mobile top title
- **Style**: Small font, semi-transparent, normal display (no underline or special styles)
- **Click Behavior**: Click version number to jump to corresponding GitHub tag page

### 5. Delete Release and Docker Image

When deleting a release on GitHub Releases page, it will automatically delete corresponding Docker image tag:

1. Visit [GitHub Releases page](https://github.com/linlea666/PolyHermes-main/releases)
2. Find the release to delete
3. Click "Delete" button
4. GitHub Actions will automatically trigger delete process
5. Delete corresponding Docker image tag (e.g., `wrbug/polyhermes:v1.0.0`)

**Notes**:
- ⚠️ Only version numbers in format `v数字.数字.数字` or `v数字.数字.数字-后缀` will be deleted (e.g., `v1.0.0`, `v1.0.0-beta`, `v2.10.102`)
- ⚠️ If image tag doesn't exist, it will show warning but won't fail
- ⚠️ `latest` tag will not be deleted (even if deleting the latest release)

## Technical Implementation

### Version Injection Process

1. **GitHub Actions** extracts version number from tag
2. **Dockerfile** receives build parameters (`VERSION`, `GIT_TAG`, `GITHUB_REPO_URL`)
3. **Vite Build** injects version number into `window.__VERSION__` via environment variables
4. **Frontend Code** reads version number from `window.__VERSION__` and displays it

### File Description

- `.github/workflows/docker-build.yml`: GitHub Actions workflow configuration
- `Dockerfile`: Supports version number build parameters
- `frontend/vite.config.ts`: Vite configuration, injects version number into global variable
- `frontend/src/utils/version.ts`: Version number utility functions
- `frontend/src/components/Layout.tsx`: Component that displays version number

### Environment Variables

Environment variables used during build:

- `VERSION`: Version number (e.g., `1.0.0`)
- `GIT_TAG`: Git tag (e.g., `v1.0.0`)
- `GITHUB_REPO_URL`: GitHub repository URL (default: `https://github.com/linlea666/PolyHermes-main`)

## Development Environment

In development environment, version number defaults to `dev` and won't display as a link.

If you need to test version display, you can set in `.env` file:

```env
VITE_APP_VERSION=1.0.0
VITE_APP_GIT_TAG=v1.0.0
VITE_APP_GITHUB_REPO_URL=https://github.com/linlea666/PolyHermes-main
```

## FAQ

### Q1: Build not triggered after creating release?

**A:** Check the following:
1. Confirm release was created via [GitHub Releases page](https://github.com/linlea666/PolyHermes-main/releases/new), not by directly pushing tag
2. Confirm "Publish release" button was clicked (not "Save draft")
3. Check if GitHub Actions is enabled
4. View workflow runs in Actions tab
5. Confirm release status is "Published" (not "Draft" or "Prerelease")

### Q2: Docker push failed?

**A:** Check the following:
1. Confirm `DOCKER_USERNAME` and `DOCKER_PASSWORD` Secrets are correctly configured
2. Confirm Docker Hub account has permission to push images
3. Check if Docker Hub repository name is correct (`wrbug/polyhermes`)

### Q3: Frontend not displaying version number?

**A:** Check the following:
1. Confirm version number environment variables were passed during build
2. Check browser console for errors
3. Confirm using built image, not development environment

### Q4: Version number click not navigating?

**A:** Check the following:
1. Confirm `GIT_TAG` environment variable is correctly set
2. Confirm GitHub repository URL is correct
3. Check if browser is blocking popups

### Q5: Docker image not deleted after deleting release?

**A:** Check the following:
1. Confirm version format is `v数字.数字.数字` or `v数字.数字.数字-后缀` (e.g., `v1.0.0`, `v1.0.0-beta`)
2. Confirm Docker Hub credentials (`DOCKER_USERNAME` and `DOCKER_PASSWORD`) are correctly configured
3. **Confirm Docker Hub access token has permission to delete images**:
   - If using Access Token, ensure it has `Delete repository tags` permission
   - Visit Docker Hub → Account Settings → Security → Access Tokens
   - Create or edit access token, ensure `Delete repository tags` permission is checked
4. If encountering 401 error, it might be:
   - Access token expired, need to regenerate
   - Access token has insufficient permissions, need to add delete permission
   - Username or password/token is incorrect
5. View GitHub Actions logs to confirm delete operation was executed
6. If image tag doesn't exist, it will show warning but won't fail (this is normal)

### Q6: Encountered 401 unauthorized error when deleting image?

**A:** This is usually due to authentication failure, please check:

1. **If using Access Token**:
   - Ensure access token is not expired
   - Ensure access token has `Delete repository tags` permission
   - Check permissions in Docker Hub → Account Settings → Security → Access Tokens

2. **If using password**:
   - Ensure username and password are correct
   - If 2FA is enabled, need to use Access Token instead of password

3. **Create new Access Token**:
   - Visit: https://hub.docker.com/settings/security
   - Click "New Access Token"
   - Fill in description (e.g., `GitHub Actions Delete Images`)
   - **Important**: Check `Delete repository tags` permission
   - Copy generated token, update `DOCKER_PASSWORD` in GitHub Secrets

### Q7: What are the version number format requirements?

**A:** Version number must strictly match format: `v数字.数字.数字` or `v数字.数字.数字-后缀`
- ✅ Correct: `v1.0.0`, `v2.10.102`, `v1.0.0-beta`, `v1.0.0-rc.1`, `v2.10.102-alpha`
- ❌ Wrong: `v1.0`, `1.0.0`, `v1.0.0.1`, `v1.0.0_beta` (underscore not supported)

## Examples

### Create Release Example

**Step 1: Visit Releases Page**
Visit: https://github.com/linlea666/PolyHermes-main/releases/new

**Step 2: Create Release**
1. Enter `v1.0.0` in "Choose a tag" (will be created automatically if doesn't exist)
2. Fill in Release title: `v1.0.0`
3. Fill in Release description (optional)
4. Click "Publish release"

**Step 3: Auto Build**
- GitHub Actions will automatically trigger build
- After build completes, Docker image will be automatically pushed to Docker Hub
- Frontend will display "PolyHermes v1.0.0"

**Note**: Pushing tag directly via `git push` will not trigger build, must create via Releases page.

### Use Docker Image Example

```bash
# Pull specific version
docker pull wrbug/polyhermes:v1.0.0

# Pull latest version
docker pull wrbug/polyhermes:latest

# Run container
docker run -d -p 80:80 wrbug/polyhermes:v1.0.0
```

## Notes

1. **Tag Format**: Must use `v*` format (e.g., `v1.0.0`), otherwise won't trigger build
2. **Version Format**: Recommend using Semantic Versioning
3. **Docker Hub**: Ensure Docker Hub repository is created
4. **Permissions**: Ensure GitHub Actions has permission to access Docker Hub

