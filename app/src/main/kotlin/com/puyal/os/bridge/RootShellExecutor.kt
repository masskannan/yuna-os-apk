package com.puyal.os.bridge

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object RootShellExecutor {
    private var suProcess: Process? = null
    private var os: DataOutputStream? = null

    @Synchronized
    fun execRoot(cmd: String): Boolean {
        return try {
            if (suProcess == null || os == null) {
                suProcess = Runtime.getRuntime().exec("su")
                os = DataOutputStream(suProcess!!.outputStream)
            }
            os!!.writeBytes("$cmd\n")
            os!!.flush()
            true
        } catch (e: Exception) {
            suProcess = null
            os = null
            false
        }
    }

    fun toggleTorch(enable: Boolean): Boolean {
        val valStr = if (enable) "1" else "0"
        return execRoot("echo $valStr > /sys/class/leds/flashlight/brightness 2>/dev/null || cmd statusbar expand-settings")
    }

    fun lockScreen(): Boolean = execRoot("input keyevent 26")

    // Kernel-Level Root Screen Unlock via raw /dev/input/eventX touch injection
    fun kernelUnlockScreen(eventDev: String = "/dev/input/event2", swipeY1: Int = 1800, swipeY2: Int = 600, pinDigits: String = ""): Boolean {
        val wakeOk = execRoot("input keyevent 224")
        val swipeCmd = "sendevent $eventDev 3 57 1; sendevent $eventDev 3 53 540; sendevent $eventDev 3 54 $swipeY1; sendevent $eventDev 0 0 0; " +
                "sendevent $eventDev 3 54 $swipeY2; sendevent $eventDev 0 0 0; sendevent $eventDev 3 57 -1; sendevent $eventDev 0 0 0"
        val swipeOk = execRoot(swipeCmd)
        if (pinDigits.isNotEmpty()) {
            execRoot("sleep 0.3 && input text $pinDigits && input keyevent 66")
        }
        return wakeOk && swipeOk
    }
}
