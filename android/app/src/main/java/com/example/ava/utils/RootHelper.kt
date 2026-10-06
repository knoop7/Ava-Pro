package com.example.ava.utils

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object RootHelper {
    
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun executeCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun startServiceWithRoot(packageName: String, serviceName: String): Boolean {
        val command = "am startservice -n $packageName/$serviceName"
        return executeCommand(command)
    }
    
    fun installBootScript(packageName: String, serviceName: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            
            
            os.writeBytes("echo '#!/system/bin/sh' > /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo 'sleep 45' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo '/system/bin/am start -n $packageName/.MainActivity' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo 'sleep 20' >> /data/adb/service.d/ava_boot.sh\n")
            
            os.writeBytes("echo 'for i in 1 2; do' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo '  /system/bin/am startservice -n com.example.ava/com.example.ava.services.VoiceSatelliteService' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo '  sleep 5' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo '  pgrep -f com.example.ava > /dev/null && break' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("echo 'done' >> /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("chmod 755 /data/adb/service.d/ava_boot.sh\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()
            
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun removeBootScript(): Boolean {
        return executeCommand("test -f /data/adb/service.d/ava_boot.sh && rm /data/adb/service.d/ava_boot.sh")
    }
}
