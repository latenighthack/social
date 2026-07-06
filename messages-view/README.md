# messages-view

Pure, importable **message renderers** for the `messages.v1.Component` tree, one
per client platform. Define a message once as protobuf (already done, in
`messages-api/`), render it natively on **Android** (classic `View`s), **iOS**
(UIKit `UIView`), and **web** (React).

Each library exposes the same contract: give it a `Component` plus a
`MessageTheme` (incoming/outgoing colours, fonts, spacing) and it builds a native
view. A second entry point renders a compact **message preview** (the single-line
text used in a room/conversation list row).

Ported from the cross-platform reference client at
`~/workspace/rollgames/cardboard/client` — Android's `MessageLayoutBuilder` is the
authoritative renderer; iOS/web are brought up to the same coverage here.

## Layout

```
messages-view/
  android/                 :messages-view-android — classic Android Views (reuses :messages-api)
  ios/                     MessagesView — UIKit Swift package (SwiftProtobuf)
  web/                     @social/messages-view — React renderer (protobuf-es)
  demo/
    gen-fixtures/          authors the shared binary .pb Component fixtures
    bundles/               generated .pb fixtures + manifest.json (gitignored)
    web/                   React/Vite showcase + Playwright screenshots
    ios/                   UIKit showcase + UIGraphicsImageRenderer screenshots
    android/               :messages-view-demo-android — showcase + Roborazzi screenshots
  screenshots/             generated: <platform>/<fixture>.png (gitignored)
  Makefile
```

`messages-api/proto` is the single source of truth. `../buf.yaml` + `../buf.gen.yaml`
generate the web (TypeScript) and iOS (Swift) types; Android reads the Kotlin
types straight from `:messages-api`.

## Prerequisites

- `buf`, `protoc-gen-swift` (`brew install buf swift-protobuf`)
- Node + `pnpm`
- JDK 17 + Android SDK (platform 35) for Android
- Xcode + `xcodegen` (`brew install xcodegen`) for iOS

## Quick start

```bash
make install        # pnpm deps + Playwright chromium (one-time)
make build          # proto -> fixtures -> build all three demos
make screenshots    # capture every fixture on every platform into screenshots/
make all            # build + screenshots
```

Individual pieces: `make proto`, `make fixtures`, `make web-dev`, `make android`,
`make ios`, `make screenshots-{web,android,ios}`. Override the iOS simulator with
`make screenshots-ios IOS_SIM="iPhone 16"`.

## How it fits together

1. `messages-api/proto/messages/v1/components.proto` defines the schema.
2. `make proto` generates TypeScript (`web/src/gen`) + Swift
   (`ios/Sources/MessagesView/Generated`); Android uses `:messages-api` directly.
3. `demo/gen-fixtures` builds sample `Component`s and serialises them to
   `demo/bundles/*.pb` — **one canonical set of bytes**.
4. Each demo loads those same bytes and renders them through its platform view.
5. The screenshot tasks rasterise each fixture per platform so you can confirm the
   three renderers agree.

## Adding a fixture

Add a JSON fixture under `demo/gen-fixtures/fixtures/`, list it in that dir's
`manifest.json`, then `make fixtures`. It appears in all three demos and in the
screenshot set automatically.
