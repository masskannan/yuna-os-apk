package com.puyal.os.vision

import android.content.Context
import com.puyal.os.service.PuyalAccessService

object YunaScreenCapture {
    fun captureOrFallbackDom(ctx: Context): String {
        val dom = PuyalAccessService.instance?.scanNow().orEmpty()
        if (dom.isNotBlank()) return dom
        return "Screen frame hook ready (grant MediaProjection for non-DOM video/Flutter screens)"
    }
}
