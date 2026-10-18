package com.trindade.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trindade.app.MainActivity

/**
 * BroadcastReceiver triggered when the application package has been replaced/updated.
 * Cleanly restarts MainActivity so the updated version immediately runs.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}
