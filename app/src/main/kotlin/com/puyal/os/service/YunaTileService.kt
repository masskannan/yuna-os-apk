package com.puyal.os.service

import android.annotation.TargetApi
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.puyal.os.overlay.FloatingYunaService

@TargetApi(Build.VERSION_CODES.N)
class YunaTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, FloatingYunaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }
}
