# CollabAgent 1.0

Chat agent for Android with a real terminal, skills, web search and an on-device model (llama.cpp).

## What it does
- **Real terminal** (proot Linux, from v0.9): the agent runs real `pip`, `wget`, `cat`, `echo`, `python3`. Nothing is simulated.
- **Skills**: Menu > Skills > Upload a `.md` or `.zip`. The agent sees the list, reads the ONE skill that matches the topic (`read_skill`) before it writes code or a design, and says which one it read. Two starter skills are added on first start (`apk-build-github`, `terminal-tools`).
- **Web search**: free, no key (`web_search`, `web_fetch`). Can be turned off in Settings.
- **Brain**: Settings > Brain. *Cloud API* (any OpenAI-compatible URL) or *On-device* (llama.cpp inside the app, no API key). Menu > Models downloads or imports `.gguf` files.
- **Thinking and plan**: the model's reasoning is shown in a collapsible block; the prompt makes the agent write a short plan first.
- **Streaming**, copy buttons (code, reply, tool output), long-press text selection.
- **Run buttons** on code blocks: bash/sh, python and node run in the terminal; HTML opens in a WebView. Every run has a **View code** button.
- **APK delivery**: the agent builds through GitHub Actions with git/ssh/curl in the terminal (you give it a token once) and hands the file over with `deliver_file` (Install / Share button).
- GitHub is no longer a tool or a setting. The agent uses it from the terminal.

## Build
Push to GitHub. The workflow `.github/workflows/android-apk.yml` installs NDK 26.1 and CMake 3.22.1 and builds the debug APK. llama.cpp is downloaded during the build (needs internet).
For very old phones: `gradle :app:assembleDebug -PllamaArch=armv8-a`.

## Not tested
This code was written without a compiler or a phone. Expect to fix a few compile errors on the first build. Send the GitHub Actions log and they can be fixed quickly.
