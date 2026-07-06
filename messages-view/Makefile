# messages-view: one message Component proto -> three native renderers.
# proto -> generated types (TS + Swift) -> shared binary fixtures -> renderers -> screenshots.
#
# Android reuses :messages-api's Kotlin types (no codegen here) and runs via Gradle.
# Web (React) and iOS (UIKit) generate their types from ../buf.yaml + ../buf.gen.yaml.

SHELL := /bin/bash
CURDIR := $(shell pwd)
REPO := $(patsubst %/,%,$(dir $(CURDIR)))

# protoc-gen-es lives in the web package; protoc-gen-swift comes from brew (PATH).
WEB_BIN := $(CURDIR)/web/node_modules/.bin

# iOS simulator to run the screenshot test on (override: make screenshots-ios IOS_SIM="iPhone 16").
IOS_SIM ?= iPhone 17

ANDROID_ASSETS := demo/android/src/main/assets/bundles
IOS_FIXTURES := demo/ios/Fixtures
WEB_PUBLIC := demo/web/public/bundles

.PHONY: all build install proto fixtures sync-assets \
        web web-dev android ios \
        screenshots screenshots-web screenshots-android screenshots-ios clean

## Full build plus every platform's screenshots.
all: build screenshots

## Generate types + fixtures and build all three demo apps.
build: proto fixtures web android ios

## One-time-ish setup: JS deps + Playwright browser.
install:
	pnpm install
	cd demo/web && npx playwright install chromium

## Generate web (TS) + iOS (Swift) types from the shared message protos.
proto:
	cd $(REPO) && PATH="$(WEB_BIN):$$PATH" buf generate

## Author the shared binary Component fixtures and sync them into each app.
fixtures: proto
	pnpm --filter @social/messages-view-fixtures start
	$(MAKE) sync-assets

## Copy demo/bundles into the Android assets, iOS fixtures, and web public dirs.
sync-assets:
	mkdir -p $(ANDROID_ASSETS) $(IOS_FIXTURES) $(WEB_PUBLIC)
	cp demo/bundles/*.pb demo/bundles/manifest.json $(ANDROID_ASSETS)/
	cp demo/bundles/*.pb demo/bundles/manifest.json $(IOS_FIXTURES)/
	cp demo/bundles/*.pb demo/bundles/manifest.json $(WEB_PUBLIC)/

## Build the web demo (React/Vite).
web: fixtures
	pnpm --filter @social/messages-view-demo-web build

## Run the web demo dev server.
web-dev: fixtures
	pnpm --filter @social/messages-view-demo-web dev

## Build the Android demo APK.
android: fixtures
	cd $(REPO) && ./gradlew :messages-view-demo-android:assembleDebug

## Generate the Xcode project and build the iOS demo app.
ios: fixtures
	cd demo/ios && xcodegen generate
	cd demo/ios && xcodebuild build -project MessagesViewDemo.xcodeproj -scheme MessagesViewDemo \
		-destination 'platform=iOS Simulator,name=$(IOS_SIM)' -quiet

## Collect screenshots of every fixture on every platform into screenshots/.
screenshots: screenshots-web screenshots-android screenshots-ios

screenshots-web: fixtures
	pnpm --filter @social/messages-view-demo-web screenshots

screenshots-android: fixtures
	cd $(REPO) && ./gradlew :messages-view-demo-android:recordRoborazziDebug --rerun-tasks

screenshots-ios: fixtures
	cd demo/ios && xcodegen generate
	cd demo/ios && xcodebuild test -project MessagesViewDemo.xcodeproj -scheme MessagesViewDemoTests \
		-destination 'platform=iOS Simulator,name=$(IOS_SIM)' -quiet

clean:
	rm -rf web/src/gen ios/Sources/MessagesView/Generated
	rm -rf demo/bundles $(ANDROID_ASSETS) $(IOS_FIXTURES) $(WEB_PUBLIC)
	rm -rf demo/web/dist demo/ios/MessagesViewDemo.xcodeproj
	rm -rf screenshots
	cd $(REPO) && ./gradlew :messages-view-demo-android:clean || true
