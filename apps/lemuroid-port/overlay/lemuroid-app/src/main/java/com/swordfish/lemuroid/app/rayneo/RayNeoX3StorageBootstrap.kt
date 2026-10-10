package com.swordfish.lemuroid.app.rayneo

import android.content.Context
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import timber.log.Timber
import java.io.File

object RayNeoX3StorageBootstrap {
    private const val ROMS_SIGNATURE_KEY = "rayneo_x3_roms_signature"

    fun maybeUseInternalRomsDirectory(context: Context) {
        val appContext = context.applicationContext
        val preferences = SharedPreferencesHelper.getLegacySharedPreferences(appContext)
        val safFolderKey = appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder)
        val localFolderKey = appContext.getString(com.swordfish.lemuroid.lib.R.string.pref_key_legacy_external_folder)

        val romsDirectory = File(appContext.getExternalFilesDir(null), "roms").apply { mkdirs() }
        val romsSignature = runCatching {
            romsDirectory
                .walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .sortedBy { it.relativeTo(romsDirectory).path }
                .joinToString(separator = "|") {
                    "${it.relativeTo(romsDirectory).path}:${it.length()}:${it.lastModified()}"
                }
        }.getOrDefault("")

        val currentLocalFolder = preferences.getString(localFolderKey, null)
        val currentSignature = preferences.getString(ROMS_SIGNATURE_KEY, null)
        if (currentLocalFolder == romsDirectory.absolutePath && currentSignature == romsSignature) {
            return
        }

        preferences.edit()
            .remove(safFolderKey)
            .putString(localFolderKey, romsDirectory.absolutePath)
            .putString(ROMS_SIGNATURE_KEY, romsSignature)
            .apply()
        LibraryIndexScheduler.scheduleLibrarySync(appContext)
        Timber.i("RayNeo X3 using internal ROMs directory: ${romsDirectory.absolutePath}")
    }
}
