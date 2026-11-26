# Build entry points for the HiBy Remote apps. Outputs are collected in dist/.
#
#   make android    Android APKs (debug + release)
#   make linux      Linux desktop app (.deb + portable .tar.gz)
#   make windows    Windows desktop app (.msi + portable .zip)
#   make all        android + linux (+ windows when run on Windows)
#   make test       unit tests of the shared code
#   make clean      remove build outputs
#
# Without a JDK on the host, builds run in Docker (image built from
# apps/docker/Dockerfile on first use). Force with USE_DOCKER=1 or USE_DOCKER=0.
# Windows packages can only be built on Windows (jpackage bundles the host
# runtime): on Linux, `make windows` runs the GitHub Actions workflow on the
# current branch and downloads its result (needs `gh` and a pushed branch).

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
APPS := $(ROOT)/apps
DIST := $(ROOT)/dist

# Version stamped into the packages (CI passes the tag). Desktop packages need
# a numeric MAJOR.MINOR.BUILD; anything else falls back to 1.0.0 locally.
VERSION_NAME ?= 0.1.0
VERSION_CODE ?= 1
export VERSION_NAME VERSION_CODE

ifeq ($(OS),Windows_NT)
  HOST_OS := windows
  USE_DOCKER ?= 0
else
  HOST_OS := $(shell uname -s | tr '[:upper:]' '[:lower:]')
  USE_DOCKER ?= $(if $(shell command -v java 2>/dev/null),0,1)
endif

IMAGE ?= hiby-remote-build:1
GRADLE_CACHE ?= $(HOME)/.cache/hiby-remote-build/gradle

ifeq ($(USE_DOCKER),1)
# Runs as root inside the container (the SDK image needs it), then gives the
# files back to the calling user.
gradle = docker run --rm -e GRADLE_USER_HOME=/gh -e VERSION_NAME -e VERSION_CODE \
	-v "$(GRADLE_CACHE)":/gh -v "$(APPS)":/p -w /p $(IMAGE) \
	bash -c './gradlew --no-daemon --console=plain $(1); rc=$$?; chown -R $(shell id -u):$(shell id -g) /p /gh; exit $$rc'
else
gradle = cd "$(APPS)" && ./gradlew --console=plain $(1)
endif

DESKTOP_OUT := $(APPS)/desktopApp/build/compose/binaries/main

.PHONY: help all android linux windows windows-local windows-ci test clean image

help:
	@sed -n '1,14p' $(MAKEFILE_LIST) | sed 's/^# \{0,1\}//'

all: android linux $(if $(filter windows,$(HOST_OS)),windows)

image:
ifeq ($(USE_DOCKER),1)
	@mkdir -p "$(GRADLE_CACHE)"
	@docker image inspect $(IMAGE) > /dev/null 2>&1 || \
		docker build -t $(IMAGE) "$(APPS)/docker"
endif

test: image
	$(call gradle,:shared:desktopTest)

android: image
	$(call gradle,:shared:desktopTest :androidApp:assembleDebug :androidApp:assembleRelease)
	@mkdir -p "$(DIST)"
	cp "$(APPS)/androidApp/build/outputs/apk/debug/androidApp-debug.apk" "$(DIST)/hiby-remote-$(VERSION_NAME)-debug.apk"
	cp "$(APPS)/androidApp/build/outputs/apk/release/androidApp-release.apk" "$(DIST)/hiby-remote-$(VERSION_NAME).apk"
	@ls -l "$(DIST)"/*.apk

linux: image
ifeq ($(HOST_OS),windows)
	$(error The Linux packages must be built on Linux (or in Docker on a Linux host))
endif
	rm -rf "$(DESKTOP_OUT)"
	$(call gradle,:shared:desktopTest :desktopApp:createDistributable :desktopApp:packageDeb)
	@mkdir -p "$(DIST)"
	deb=$$(ls "$(DESKTOP_OUT)"/deb/*.deb); \
	ver=$$(basename "$$deb" | sed -E 's/^hiby-remote_([^_-]+).*/\1/'); \
	cp "$$deb" "$(DIST)/hiby-remote_$${ver}_amd64.deb"; \
	tar -czf "$(DIST)/hiby-remote-$${ver}-linux.tar.gz" -C "$(DESKTOP_OUT)/app" hiby-remote; \
	ls -l "$(DIST)"/hiby-remote*$${ver}*

windows: $(if $(filter windows,$(HOST_OS)),windows-local,windows-ci)

# On Windows: needs JDK 17 and GNU make (e.g. from Git Bash / MSYS2).
windows-local:
	rm -rf "$(DESKTOP_OUT)"
	$(call gradle,:shared:desktopTest :desktopApp:createDistributable :desktopApp:packageMsi)
	@mkdir -p "$(DIST)"
	msi=$$(ls "$(DESKTOP_OUT)"/msi/*.msi); \
	ver=$$(basename "$$msi" .msi | sed 's/^hiby-remote-//'); \
	cp "$$msi" "$(DIST)/hiby-remote-$${ver}.msi"; \
	(cd "$(DESKTOP_OUT)/app" && tar -a -c -f "$(DIST)/hiby-remote-$${ver}-windows.zip" hiby-remote); \
	ls -l "$(DIST)"/hiby-remote-$${ver}*

# On Linux/macOS: build on a GitHub Windows runner, then download the result.
windows-ci:
	@command -v gh > /dev/null || { echo "make windows on $(HOST_OS) needs the GitHub CLI (gh)"; exit 1; }
	@branch=$$(git rev-parse --abbrev-ref HEAD); \
	if [ -n "$$(git status --porcelain -- apps .github)" ] || \
	   [ "$$(git rev-parse HEAD)" != "$$(git rev-parse "@{u}" 2>/dev/null)" ]; then \
		echo "Warning: uncommitted or unpushed changes; CI builds what is pushed on '$$branch'."; \
	fi; \
	echo "Starting the desktop workflow on '$$branch'..."; \
	gh workflow run desktop.yml --ref "$$branch"; \
	sleep 10; \
	id=$$(gh run list --workflow desktop.yml --branch "$$branch" --event workflow_dispatch \
		--limit 1 --json databaseId -q '.[0].databaseId'); \
	gh run watch "$$id" --exit-status; \
	mkdir -p "$(DIST)"; \
	gh run download "$$id" -n hiby-remote-desktop-Windows -D "$(DIST)"; \
	ls -l "$(DIST)"/*windows* "$(DIST)"/*.msi

clean:
	-$(call gradle,clean)
	rm -rf "$(DIST)"
