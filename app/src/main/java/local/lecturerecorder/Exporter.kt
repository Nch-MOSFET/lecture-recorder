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

    /** 保存先の親フォルダーを選び直したときは、覚えているフォルダーを捨てる */
    fun clearFolderCache(context: Context) {
        Store(context).folderCache = emptyMap()
    }

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
        val store = Store(context)
        val own = lecture?.folderUri?.let(Uri::parse)
        val base = store.baseFolderUri?.let(Uri::parse)
        val tree = own ?: base ?: return exportToDocuments(context, item, file)
        val underBase = own == null
        return try {
            exportToTree(context, tree, item, file, subFolder = if (underBase) item.lectureName else null)
        } catch (t: Throwable) {
            // 保存先が使えない（アプリが消えた・許可が外れた・同期アプリの入れ替えなど）。
            // ファイルを失わないよう端末内へ退避し、保存先の設定を外して知らせる
            Log.e(TAG, "保存先に書けないため端末内へ退避: ${item.displayName}", t)
            if (underBase) {
                store.baseFolderUri = null
                store.baseFolderLabel = null
            } else {
                store.find(item.lectureId)?.let { store.upsert(it.copy(folderUri = null, folderLabel = null)) }
            }
            store.lastError = "${item.lectureName} の保存先に書けませんでした（${t.javaClass.simpleName}）。" +
                "端末内の Documents/$DEFAULT_DIR/ に保存したので、保存先を選び直してください。"
            exportToDocuments(context, item, file)
        }
    }

    /** 保存先の許可が残っているか（状態表示用） */
    fun persistedFolders(context: Context): String =
        context.contentResolver.persistedUriPermissions.joinToString("、") {
            "${it.uri.authority}（書き込み=${it.isWritePermission}）"
        }.ifEmpty { "なし" }

    private fun exportToTree(context: Context, tree: Uri, item: PendingExport, file: File, subFolder: String?): Boolean {
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val dirDoc = if (subFolder == null) root else findOrCreateDir(context, tree, root, safeName(subFolder))
        val name = uniqueName(childNames(context, tree, dirDoc), item.displayName)
        val doc = DocumentsContract.createDocument(resolver, dirDoc, item.mime, name) ?: return false
        resolver.openOutputStream(doc, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return true
    }

    /**
     * 親フォルダーの下から科目名のフォルダーを探し、なければ作る。
     * 同じ名前のフォルダーを二重に作らないよう、見つけた場所を覚えておき、
     * 比較は全角・半角や濁点の表記ゆれ（Unicode 正規化）と大文字小文字を吸収して行う。
     */
    private fun findOrCreateDir(context: Context, tree: Uri, parent: Uri, name: String): Uri {
        val store = Store(context)
        val cacheKey = "${tree}|$name"
        store.folderCache[cacheKey]?.let { cached ->
            val uri = Uri.parse(cached)
            if (dirExists(context, uri)) return uri
            store.folderCache = store.folderCache - cacheKey
        }
        findDir(context, tree, parent, name)?.let { found ->
            store.folderCache = store.folderCache + (cacheKey to found.toString())
            return found
        }
        val created = DocumentsContract.createDocument(
            context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: error("フォルダー「$name」を作れませんでした")
        // 作成直後にもう一度探し、取り違え（別名で作られた等）がないか確かめる
        val resolved = findDir(context, tree, parent, name) ?: created
        store.folderCache = store.folderCache + (cacheKey to resolved.toString())
        Log.i(TAG, "フォルダーを作成: $name")
        return resolved
    }

    private fun findDir(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val target = normalize(name)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use {
            while (it.moveToNext()) {
                if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR &&
                    normalize(it.getString(1).orEmpty()) == target
                ) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
            }
        }
        return null
    }

    private fun dirExists(context: Context, doc: Uri): Boolean = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** 表記ゆれを吸収して比べるための正規化 */
    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFKC).lowercase()

    private fun childNames(context: Context, tree: Uri, parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
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
