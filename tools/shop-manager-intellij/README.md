# OpenRune Shop Manager IntelliJ Plugin

This module packages the OpenRune Shop Manager as an IntelliJ IDEA tool window.

It is intended to be copied into an OpenRune server checkout beside `tools/shop-maker`.

## Build

From the OpenRune server root:

```powershell
.\gradlew.bat --no-daemon :tools:shop-manager-intellij:buildPlugin
```

The plugin ZIP is written to:

```text
tools/shop-manager-intellij/build/distributions
```

Install it through IntelliJ IDEA with **Settings > Plugins > Install Plugin from Disk...**.
