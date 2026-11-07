package com.spacelens.app.core

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Android 11+ 系统回收站优先路径。
 * 只处理 MediaStore 能定位到的媒体/文件；定位失败或系统拒绝时返回 false，
 * 调用方回退到自建 Trash。系统回收站由系统管 30 天，还原走 MediaStore 的 IS_TRASHED。
 */
object SystemTrash {

    data class Entry(val uri: Uri, val name: String, val size: Long)

    const val REQUEST = 4411
    private const val TAG = "SpaceLens"

    fun request(activity: Activity, files: List<File>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (files.isEmpty()) return false
        val uris = ArrayList<Uri>(files.size)
        for (f in files) {
            val uri = findUri(activity, f) ?: return false
            uris.add(uri)
        }
        return try {
            val pi = MediaStore.createTrashRequest(activity.contentResolver, uris, true)
            activity.startIntentSenderForResult(pi.intentSender, REQUEST, null, 0, 0, 0)
            Log.i(TAG, "trash path=system count=${uris.size}")
            true
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "trash path=app fallback: createTrashRequest unsupported")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "trash path=app fallback: createTrashRequest denied")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "trash path=app fallback: " + e.javaClass.simpleName)
            false
        }
    }

    fun entries(context: Context): List<Entry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val out = ArrayList<Entry>()
        try {
            val collection = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE),
                    MediaStore.MediaColumns.IS_TRASHED + "=?",
                    arrayOf("1"),
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 100) {
                    out.add(Entry(
                            ContentUris.withAppendedId(collection, c.getLong(0)),
                            c.getString(1) ?: "未命名",
                            c.getLong(2),
                    ))
                }
            }
        } catch (_: Throwable) {
            Log.w(TAG, "trash list path=system unavailable")
        }
        return out
    }

    /** 系统回收站条目还原：把 IS_TRASHED 置回 0。需要 MediaStore 可写权限。 */
    fun restore(activity: Activity, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 0)
            }
            val n = activity.contentResolver.update(uri, values, null, null)
            Log.i(TAG, "trash restore path=system updated=$n")
            n > 0
        } catch (_: Throwable) {
            Log.w(TAG, "trash restore path=system failed")
            false
        }
    }

    fun findUri(activity: Activity, file: File): Uri? {
        return try {
            val collection = MediaStore.Files.getContentUri("external")
            activity.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.DATA + "=?",
                    arrayOf(file.absolutePath),
                    null
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
