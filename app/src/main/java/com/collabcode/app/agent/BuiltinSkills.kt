package com.collabcode.app.agent

import android.content.Context
import java.io.File

/** Two starter skills, written once on first start. The user can delete or replace them. */
object BuiltinSkills {
    fun seedOnce(ctx: Context) {
        val mark = File(ctx.filesDir, "skills_seeded_v1")
        if (mark.exists()) return
        try {
            val root = File(ctx.filesDir, "skills").apply { mkdirs() }
            for ((id, body) in listOf("apk-build-github" to APK, "terminal-tools" to TERMINAL)) {
                val dir = File(root, id)
                if (dir.exists()) continue
                dir.mkdirs()
                File(dir, "SKILL.md").writeText(body.replace("@@", "$"))
            }
            mark.writeText("1")
        } catch (e: Exception) {
            // Skills are optional. The app works without them.
        }
    }

    private val APK = """---
name: apk-build-github
description: Build an Android APK for the user for free with GitHub Actions and hand the APK to them. Use only when the user wants an app or APK built or installed.
---
# Build an APK for free with GitHub

GitHub Actions is free for this. The user gives you a GitHub token (API key). You do the rest in the terminal.

## 1. Ask for the token once
Ask: "Please send a GitHub personal access token (classic) with the scopes repo and workflow. It is free." Do not repeat the token in chat. Pass it inline in each command, for example `GH_TOKEN=xxxx curl ...`, because every run_command is a new shell.

## 2. Write the app
- Work in /root/project/APPNAME/.
- Use Kotlin and Jetpack Compose unless the user wants something else.
- Create files with `cat > path <<'EOF'` or `echo`. Check with `cat` and `ls -R`.
- Files: settings.gradle.kts, build.gradle.kts (plugins com.android.application 8.7.2, org.jetbrains.kotlin.android 2.0.21, org.jetbrains.kotlin.plugin.compose 2.0.21), gradle.properties (android.useAndroidX=true), app/build.gradle.kts (compileSdk 35, minSdk 26, compose BOM 2024.09.00), app/src/main/AndroidManifest.xml, app/src/main/java/.../MainActivity.kt.

## 3. Add .github/workflows/apk.yml
name: APK
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: {distribution: temurin, java-version: '17'}
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
        with: {gradle-version: '8.9'}
      - run: gradle :app:assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: app/build/outputs/apk/debug/app-debug.apk

## 4. Create the repository and push
- User name: `curl -s -H "Authorization: Bearer @@GH_TOKEN" https://api.github.com/user`
- Create repo: `curl -s -H "Authorization: Bearer @@GH_TOKEN" -d '{"name":"REPO","private":true}' https://api.github.com/user/repos`
- Push with git over https: `git init && git add -A && git commit -m init && git branch -M main && git remote add origin https://x-access-token:@@GH_TOKEN@github.com/OWNER/REPO.git && git push -u origin main`
- SSH also works and is free: `ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519`, add the public key with POST https://api.github.com/user/keys, then use git@github.com:OWNER/REPO.git with `GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=accept-new"`.
- If git or ssh is missing, try pip or a release file with wget first. Use the system package manager only as the last choice.

## 5. Wait for the build
Poll `https://api.github.com/repos/OWNER/REPO/actions/runs?per_page=1` every 30 seconds until status is completed. If it failed, tell the user the error and ask what to do. Do not fix it on your own.

## 6. Download and hand over
- `curl -s -H "Authorization: Bearer @@GH_TOKEN" https://api.github.com/repos/OWNER/REPO/actions/runs/RUN_ID/artifacts` gives archive_download_url.
- `mkdir -p /root/project/out && curl -L -H "Authorization: Bearer @@GH_TOKEN" -o /root/project/out/apk.zip URL`
- `python3 -m zipfile -e /root/project/out/apk.zip /root/project/out/`
- Call deliver_file with path out/app-debug.apk. The user taps Install.
"""

    private val TERMINAL = """---
name: terminal-tools
description: How to get programs and handle files in the real terminal with pip, wget, cat and echo. Use when you need a library or program that is not installed.
---
# Real terminal: tools and files

The terminal is real. Nothing is simulated.

- Check first: `which python3 pip wget curl git` and `python3 --version`.
- Python libraries: `pip install NAME`. Try pip first.
- A program that is not on pip: `wget -O file URL` (a release file), then `chmod +x file`. Tar files: `tar -xf file`. Zip files: `python3 -m zipfile -e file.zip dir`.
- Use the system package manager only when pip and wget cannot do it.
- Write files: `cat > path <<'EOF'`, then the text, then EOF alone on a line. Append with `>>`. Short text: `echo "text" > path`.
- Read files: `cat path`, `ls -la`, `head -50 path`.
- Run Python: `python3 file.py`. Long jobs: `nohup cmd > log.txt 2>&1 &`, then read the log.
- Project files live in /root/project. Phone files are in /sdcard when storage access is allowed.
"""
}
