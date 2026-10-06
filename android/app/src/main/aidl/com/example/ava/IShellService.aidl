package com.example.ava;

interface IShellService {
    int executeCommand(String command) = 1;
    boolean setDisplayPower(int mode) = 2;
    /** Stream an APK into `pm install -r -d -S <size>` (stdin). Returns process exit code. */
    int installApk(in ParcelFileDescriptor apkFd, long size) = 3;
    /**
     * Run `sh -c command` (stderr merged into stdout) and return "<exitCode>\n<output>".
     * Captured in-process so callers never depend on a world-readable temp file.
     */
    String executeCommandForOutput(String command) = 4;
    /**
     * Connect (in the shell UID/SELinux domain) to an abstract-namespace
     * LocalSocket and hand the connected fd back to the caller. Lets an
     * untrusted_app read a shell-owned scrcpy socket that SELinux would forbid
     * it to `connectto` directly — the fd is passed over binder, which the app
     * may then read/write as its own. Null when the connect failed.
     */
    ParcelFileDescriptor openLocalSocket(String name) = 5;
    void destroy() = 16777114;
}
