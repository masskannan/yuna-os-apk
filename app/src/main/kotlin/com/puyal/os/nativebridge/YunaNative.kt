package com.puyal.os.nativebridge

object YunaNative {
    private var rustLoaded = false

    init {
        try { System.loadLibrary("yuna_rust"); rustLoaded = true } catch (_: UnsatisfiedLinkError) {}
        try { System.loadLibrary("yuna_zig") } catch (_: UnsatisfiedLinkError) {}
    }

    private external fun nativeContainsSensitive(text: String): Int
    private external fun nativeIsSuAllowed(cmd: String): Int

    fun hasSensitiveToken(text: String): Boolean {
        if (rustLoaded) {
            try { return nativeContainsSensitive(text) == 1 } catch (_: UnsatisfiedLinkError) {}
        }
        val low = text.lowercase()
        return low.contains("otp") || low.contains("cvv") || low.contains("upi") || Regex("\\b\\d{4,8}\\b").containsMatchIn(low)
    }

    fun isAllowedSuCmd(cmd: String): Boolean {
        if (rustLoaded) {
            try { return nativeIsSuAllowed(cmd) == 1 } catch (_: UnsatisfiedLinkError) {}
        }
        return cmd.startsWith("oom_score_adj") || cmd.startsWith("svc ") || cmd.startsWith("input ")
    }
}
