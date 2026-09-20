# AGENTS.md

## Project Overview

**Bookmarks (Dynamic)** (`ai.rever.boss.plugin.dynamic.bookmarks`) is a dynamic plugin for the BOSS desktop application.

Self-contained bookmark management with global search integration

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.bookmarks`
- **Main Class**: `ai.rever.boss.plugin.dynamic.bookmarks.BookmarksDynamicPlugin`
- **API Version**: 1.0.22

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Collection identity
- **`isFavorite` is what makes a collection Favorites, not its name.** The name is the user's to change; `withSingleFavorites` repairs a file on load by promoting a collection named `Favorites` that lacks the flag and demoting any flagged one after the first (which the panel cannot show and `deleteCollection` refuses to remove). Never test for Favorites by name (#10).
- Adds resolve by name (`addBookmark` / `addBookmarks`), so `createCollection` and `renameCollection` suffix a taken name (`Work (2)`) rather than allowing two collections to share one. Duplicates already on disk are logged by `withDistinctIds`, not renamed.

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.
