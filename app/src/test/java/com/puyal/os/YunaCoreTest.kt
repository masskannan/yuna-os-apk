package com.puyal.os

import com.puyal.os.audio.PuyalAudioGuardian
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class YunaCoreTest {
    @Test
    fun voiceCosineThresholdTest() {
        val owner = floatArrayOf(1f, 0.5f, 0.25f)
        val same = floatArrayOf(0.99f, 0.51f, 0.24f)
        val stranger = floatArrayOf(-1f, 0.2f, 0.9f)
        assertTrue(PuyalAudioGuardian.isOwnerVoice(same, owner))
        assertFalse(PuyalAudioGuardian.isOwnerVoice(stranger, owner))
    }
}
