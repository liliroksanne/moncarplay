package com.lilyroksanne.mon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lilyroksanne.mon.util.MonLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            MonLogger.i("BootReceiver", "Evento de arranque recibido: $action")
            runCatching { DriveSyncJob.start(context.applicationContext) }
            runCatching {
                val launch = Intent(context, Skull::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                context.startActivity(launch)
            }.onFailure { error ->
                MonLogger.w("BootReceiver", "No se pudo lanzar Skull desde boot (usa Mon como Launcher por defecto): ${error.message}")
            }
        }
    }
}
