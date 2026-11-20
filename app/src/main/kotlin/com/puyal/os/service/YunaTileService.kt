package com.puyal.os.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.puyal.os.overlay.FloatingYunaService

@RequiresApi(Build.VERSION_CODES.N)
class YunaTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, FloatingYunaService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }
}
