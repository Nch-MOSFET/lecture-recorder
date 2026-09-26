package local.lecturerecorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "Exporter"
const val DEFAULT_DIR = "講義録音"

/**
 * 録音・文字起こしファイルを科目ごとの保存先へ書き出す。
 * 書き出せなかったもの（オフライン・権限切れなど）は [Store.pending] に残し、後で再試行する。
 */
object Exporter {
    private val lock = Any()

    fun enqueue(context: Context, items: List<PendingExport>) = synchronized(lock) {
        val store = Store(context)
        store.pending = store.pending + items
    }

    /** 保留中のファイルをすべて書き出す。戻り値は残った件数。 */
    fun flush(context: Context): Int = synchronized(lock) {
        val store = Store(context)
        val remaining = mutableListOf<PendingExport>()
        for (item in store.pending) {
            val file = File(item.localPath)
            if (!file.exists()) continue
            val lecture = store.find(item.lectureId)
            val ok = runCatching { export(context, lecture, item, file) }
                .onFailure {
                    Log.e(TAG, "export failed: ${item.displayName}", it)
                    store.lastError = "保存に失敗：${item.displayName}（${it.message}）"
                }
                .getOrDefault(false)
            if (ok) file.delete() else remaining += item
        }
        store.pending = remaining
        if (remaining.isEmpty()) store.lastError = null
        remaining.size
    }

    private fun export(context: Context, lecture: Lecture?, item: PendingExport, file: File): Boolean {
        val tree = lecture?.folderUri?.let(Uri::parse)
        return if (tree != null) exportToTree(context, tree, item, file)
        else exportToDocuments(context, item, file)
    }

    private fun exportToTree(context: Context, tree: Uri, item: PendingExport, file: File): Boolean {
        val resolver = context.contentResolver
        val dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = uniqueName(childNames(context, tree), item.displayName)
        val doc = DocumentsContract.createDocument(resolver, dirDoc, item.mime, name) ?: return false
        resolver.openOutputStream(doc, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return true
    }

    private fun childNames(context: Context, tree: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val names = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                while (it.moveToNext()) names += it.getString(0)
            }
        }
        return names
    }

    private fun exportToDocuments(context: Context, item: PendingExport, file: File): Boolean {
        // 音声と文字起こしを同じ場所に置くため Documents 配下にする（Recordings にはテキストを置けない）
        val relDir = "${Environment.DIRECTORY_DOCUMENTS}/$DEFAULT_DIR/${safeName(item.lectureName)}/"
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 同名があると MediaStore は「(1)」…と付けるが上限がある。こちらで「_2」…を付けて避ける
        val existing = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relDir), null,
            )?.use { while (it.moveToNext()) existing += it.getString(0) }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName(existing, item.displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    /** 拡張子から MIME 型を決める（プロバイダーが拡張子を二重に付けないよう、拡張子と一致させる） */
    fun mimeFor(fileName: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.').lowercase())
            ?: "application/octet-stream"

    fun uniqueName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    /** ファイル名に使えない文字を置き換える */
    fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "無題" }

    /** 保存先フォルダーの表示名（例: Google ドライブ: 英語） */
    fun treeLabel(context: Context, tree: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val name = runCatching {
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        val provider = when (tree.authority) {
            "com.microsoft.skydrive.content.StorageAccessProvider" -> "OneDrive"
            "com.android.externalstorage.documents" -> "端末"
            "com.google.android.apps.docs.storage" -> "Google ドライブ"
            else -> tree.authority ?: ""
        }
        val path = docId.substringAfter(':', "").takeIf { tree.authority == "com.android.externalstorage.documents" }
        return "$provider: ${path ?: name ?: docId}"
    }
}
