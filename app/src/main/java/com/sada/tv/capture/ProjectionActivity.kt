package com.sada.tv.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.sada.tv.SadaService
import com.sada.tv.util.SLog

/**
 * Invisible activity that asks for the one-time "capture audio" consent and hands
 * the result to [SadaService], which then captures the channel's sound.
 */
@UnstableApi
class ProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(mpm.createScreenCaptureIntent(), REQ) }
            .onFailure {
                SLog.e("projection intent", it)
                Toast.makeText(this, "جهازك لا يدعم التقاط الصوت: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    @Deprecated("Activity result API is not available on plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, SadaService::class.java)
                    .setAction(SadaService.ACTION_PROJECTION)
                    .putExtra(SadaService.EXTRA_CODE, resultCode)
                    .putExtra(SadaService.EXTRA_DATA, data)
                startForegroundService(i)
            } else {
                Toast.makeText(this, "لم تُمنح صلاحية التقاط صوت القناة", Toast.LENGTH_LONG).show()
                SLog.i("projection consent refused")
            }
        }
        finish()
    }

    private companion object { const val REQ = 4711 }
}
