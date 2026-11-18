package com.puyal.os.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

class WasmHostBridge {
    companion object {
        init { System.loadLibrary("puyal_ffi") }
    }

    private val sharedBuffer: ByteBuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)

    external fun nativeDispatchZeroCopy(buffer: ByteBuffer, length: Int): Int

    fun sendZeroCopyPacket(rawBytes: ByteArray): Boolean {
        if (rawBytes.size > 4096) return false
        sharedBuffer.clear()
        sharedBuffer.put(rawBytes)
        val status = nativeDispatchZeroCopy(sharedBuffer, rawBytes.size)
        return status == rawBytes.size && sharedBuffer.get(0) == 0xAA.toByte()
    }
}
