package com.yuna.os.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.view.KeyEvent
import java.io.File

object YunaSystemTools {

    fun setTimer(ctx: Context, seconds: Int, label: String = "YUNA Timer"): String {
        return runCatching {
            val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(5))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            "${seconds / 60} நிமிடம் ${seconds % 60} வினாடி டைமர் செட் செய்யப்பட்டது."
        }.getOrDefault("டைமர் செட் செய்ய முடியவில்லை.")
    }

    fun mediaKey(ctx: Context, keyCode: Int): String {
        return runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            "மீடியா கட்டளை செயல்படுத்தப்பட்டது."
        }.getOrDefault("மீடியா கட்டளை தோல்வி.")
    }

    fun adjustVolume(ctx: Context, dir: Int): String {
        return runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            if (dir > 0) "சத்தம் கூட்டப்பட்டது." else if (dir < 0) "சத்தம் குறைக்கப்பட்டது." else "மியூட் செய்யப்பட்டது."
        }.getOrDefault("சத்தம் மாற்ற முடியவில்லை.")
    }

    fun dialContactByName(ctx: Context, nameQuery: String): String {
        val q = nameQuery.trim()
        if (q.isEmpty()) return "பெயர் தெளிவாக இல்லை."
        val number = runCatching {
            var found: String? = null
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val dn = c.getString(0) ?: ""
                    val num = c.getString(1) ?: ""
                    if (dn.contains(q, ignoreCase = true) && num.isNotBlank()) {
                        found = num; break
                    }
                }
            }
            found
        }.getOrNull()

        return if (!number.isNullOrBlank()) {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$q ($number)-க்கு டயல் செய்யப்படுகிறது."
        } else {
            "$q என்ற பெயரில் தொடர்பு கிடைக்கவில்லை."
        }
    }

    // Strictly Read-Only: NEVER deletes any file!
    fun getReadOnlyStorageReport(ctx: Context): String {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = (stat.availableBytes / (1024.0 * 1024.0 * 1024.0))
            val totalGb = (stat.totalBytes / (1024.0 * 1024.0 * 1024.0))

            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val availRamMb = mi.availMem / (1024 * 1024)

            val bigFiles = mutableListOf<String>()
            val dirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            )
            for (d in dirs) {
                d?.listFiles()?.forEach { f: File ->
                    if (f.isFile && f.length() > 50L * 1024L * 1024L && bigFiles.size < 4) {
                        val mb = f.length() / (1024 * 1024)
                        bigFiles.add("${f.name} (${mb}MB)")
                    }
                }
            }
            val filesTxt = if (bigFiles.isNotEmpty()) "\nபெரிய கோப்புகள் (>50MB): ${bigFiles.joinToString(", ")}" else "\n50MB-க்கு மேல் பெரிய கோப்புகள் இல்லை."
            String.format("Storage: %.1f GB காலி / %.1f GB மொத்தம் | Free RAM: %d MB%s", freeGb, totalGb, availRamMb, filesTxt)
        }.getOrDefault("Storage ரிப்போர்ட் எடுக்க முடியவில்லை (Storage Permission சரிபார்க்கவும்).")
    }
}
