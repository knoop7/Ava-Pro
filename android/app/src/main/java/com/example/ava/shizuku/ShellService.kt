package com.example.ava.shizuku

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.ava.IShellService
import java.io.FileInputStream
import kotlin.system.exitProcess

class ShellService : IShellService.Stub() {
    
    companion object {
        private const val TAG = "ShellService"
    }
    
    override fun executeCommand(command: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    override fun executeCommandForOutput(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true) // merge stderr → stdout; avoids pipe-buffer deadlock
                .start()
            // Drain before waitFor so a chatty command can't block on a full pipe.
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            "$code\n$output"
        } catch (e: Exception) {
            Log.e(TAG, "executeCommandForOutput failed", e)
            "-1\n${e.message ?: "exec_exception"}"
        }
    }

    override fun openLocalSocket(name: String): ParcelFileDescriptor? {
        return try {
            // Runs in the shell domain, so connecting to a shell-owned scrcpy
            // abstract socket is a same-domain connectto that SELinux permits —
            // the very thing an untrusted_app is denied. We dup the connected
            // fd into a ParcelFileDescriptor and let binder carry it back; the
            // dup keeps the socket alive after this LocalSocket is closed, and
            // AIDL closes the returned pfd once it has been transferred.
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            val pfd = ParcelFileDescriptor.dup(socket.fileDescriptor)
            socket.close()
            pfd
        } catch (e: Exception) {
            Log.w(TAG, "openLocalSocket($name) failed", e)
            null
        }
    }

    override fun installApk(apkFd: ParcelFileDescriptor, size: Long): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm install -r -d -S $size"))
            FileInputStream(apkFd.fileDescriptor).use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                }
            }
            val code = process.waitFor()
            val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (code != 0) {
                Log.w(TAG, "pm install failed code=$code err=$err")
            } else {
                Log.i(TAG, "pm install ok")
            }
            code
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            -1
        } finally {
            runCatching { apkFd.close() }
        }
    }
    
    @Suppress("BlockedPrivateApi")
    override fun setDisplayPower(mode: Int): Boolean {
        return try {
            
            if (mode == 2) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 224")).waitFor() 
            }
            
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod(
                    "createClassLoader",
                    String::class.java, String::class.java, String::class.java,
                    ClassLoader::class.java, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, String::class.java
                )
                val classLoader = createClassLoaderMethod.invoke(
                    null, "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null
                ) as ClassLoader
                val displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl")
                
                val loadLibraryMethod = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                loadLibraryMethod.isAccessible = true
                loadLibraryMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers")
                
                val getPhysicalDisplayIdsMethod = displayControlClass.getMethod("getPhysicalDisplayIds")
                val getPhysicalDisplayTokenMethod = displayControlClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                val setDisplayPowerModeMethod = surfaceControlClass.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                
                val displayIds = getPhysicalDisplayIdsMethod.invoke(null) as? LongArray
                displayIds?.forEach { displayId ->
                    val token = getPhysicalDisplayTokenMethod.invoke(null, displayId) as? android.os.IBinder
                    token?.let { setDisplayPowerModeMethod.invoke(null, it, mode) }
                }
            } else {
                val displayToken = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    surfaceControlClass.getMethod("getInternalDisplayToken").invoke(null)
                } else {
                    surfaceControlClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0)
                } ?: return false
                
                surfaceControlClass.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, displayToken, mode)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun destroy() {
        Log.d(TAG, "ShellService destroy called")
        exitProcess(0)
    }
}
