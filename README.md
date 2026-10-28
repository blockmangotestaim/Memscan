# MemScan – read-only memory scanner (Cheat-Engine style) for rooted Android

## Build the APK
1. Install Android Studio (Koala or newer). Open this folder as a project, let Gradle sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s)
3. APK: app/build/outputs/apk/debug/app-debug.apk  (debug-signed, installs directly)

## Use
1. Phone must be ROOTED (Magisk etc.). Grant MemScan superuser when asked.
2. Start the game, then open MemScan > Select > pick the game.
3. Type the value, pick the type (1/2/4/8 bytes, Float, Double), tap First Scan.
4. You get the match count + the first 1000 addresses with their values.

## How it works
Scanning runs as root: `su -c "app_process ... com.memscan.Scanner"` reads /proc/<pid>/maps
and /proc/<pid>/mem. It never writes. Skips code/system mappings for speed; fast-scan
aligns to value size like Cheat Engine.
