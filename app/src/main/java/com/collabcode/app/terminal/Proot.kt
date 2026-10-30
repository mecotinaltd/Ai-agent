package com.collabcode.app.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/** Builds the proot command lines. The proot files come from the app's native library folder. */
object Proot {
    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun nativeDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    fun storageAllowed(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun hasScript(ctx: Context): Boolean {
        val root = RootfsManager.rootfsDir(ctx)
        return File(root, "usr/bin/script").exists() || File(root, "bin/script").exists()
    }

    /** The full command that starts [guestCommand] inside Debian with a clean environment. */
    fun hostCommand(ctx: Context, guestCommand: List<String>): List<String> {
        val root = RootfsManager.rootfsDir(ctx)
        val project = File(ctx.filesDir, "project").apply { mkdirs() }
        val cmd = mutableListOf(
            File(nativeDir(ctx), "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", root.path,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${project.path}:/root/project",
        )
        if (storageAllowed(ctx) && File("/storage/emulated/0").exists()) {
            cmd += listOf("-b", "/storage/emulated/0:/sdcard")
        }
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/bin/bash",
            "PATH=$GUEST_PATH",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "PIP_BREAK_SYSTEM_PACKAGES=1",
            "PIP_ROOT_USER_ACTION=ignore",
            "PYTHONUNBUFFERED=1",
        )
        cmd += guestCommand
        return cmd
    }

    fun shellCommand(ctx: Context, cols: Int, rows: Int, usePty: Boolean): List<String> =
        hostCommand(
            ctx,
            if (usePty) {
                listOf("/usr/bin/script", "-qfc", "stty cols $cols rows $rows 2>/dev/null; exec /bin/bash -i", "/dev/null")
            } else {
                listOf("/bin/bash", "-i")
            }
        )

    fun configure(ctx: Context, pb: ProcessBuilder) {
        val env = pb.environment()
        val tmp = File(RootfsManager.baseDir(ctx), "proot-tmp").apply { mkdirs() }
        val libs = nativeDir(ctx)
        env["PROOT_LOADER"] = File(libs, "libprootloader.so").path
        env["PROOT_TMP_DIR"] = tmp.path
        env["PROOT_NO_SECCOMP"] = "1"
        val old = env["LD_LIBRARY_PATH"]
        env["LD_LIBRARY_PATH"] = if (old.isNullOrEmpty()) libs else "$libs:$old"
        pb.directory(RootfsManager.baseDir(ctx))
    }
}
