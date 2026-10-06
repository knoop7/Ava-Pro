package com.example.ava.webcompat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class HostSidebarSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri != HostSidebarSettingsContract.SNAPSHOT_URI) return null
        val encoded = HostSidebarSettingsSnapshotCoordinator.peekEncodedJson()
            ?: HostSidebarSettingsSnapshotCodec.encode(HostSidebarSettingsSnapshot())
        return MatrixCursor(arrayOf(HostSidebarSettingsContract.COLUMN_JSON)).apply {
            addRow(arrayOf(encoded))
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri == HostSidebarSettingsContract.SNAPSHOT_URI) {
            "vnd.android.cursor.item/vnd.ava.browser_sidebar_settings"
        } else {
            null
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
