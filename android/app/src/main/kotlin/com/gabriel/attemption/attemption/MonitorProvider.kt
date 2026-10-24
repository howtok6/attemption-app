package com.gabriel.attemption.attemption

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Puerta de entrada al proceso ":monitor" desde la pantalla (proceso principal).
 * No está exportado: solo la propia app puede llamarlo. Además de `call`, su URI de estado
 * sirve para avisar cambios con `notifyChange`, que la pantalla escucha con un ContentObserver.
 */
class MonitorProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        return when (method) {
            METHOD_STATUS -> MonitorController.toBundle(MonitorController.status(context))
            METHOD_START -> Bundle().apply { putString(KEY_RESULT, MonitorController.start(context).name) }
            METHOD_STOP -> Bundle().apply { putString(KEY_RESULT, MonitorController.stop(context).name) }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_STATUS = "status"
        const val METHOD_START = "start"
        const val METHOD_STOP = "stop"
        const val KEY_RESULT = "result"

        fun authority(packageName: String) = "$packageName.monitor"
        fun statusUri(packageName: String): Uri = Uri.parse("content://${authority(packageName)}/status")
    }
}
