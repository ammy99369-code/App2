package com.hiddenmedia.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    // type: 1 photo, 2 video, 3 audio, 4 document, 5 backup/other
    data class Item(val file: File, val type: Int, val reason: String)

    private val allItems = ArrayList<Item>()
    private val items = ArrayList<Item>()
    private val selected = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = LruCache<String, Bitmap>(200)
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private lateinit var scanBtn: Button
    private lateinit var backupBtn: Button
    private lateinit var allBtn: Button
    private lateinit var filterSp: Spinner
    private lateinit var sortSp: Spinner
    private val gridAdapter = GridAdapter()
    @Volatile private var scanning = false
    @Volatile private var visited = 0
    @Volatile private var foundCount = 0

    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng")
    private val VID = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v")
    private val AUD = setOf("mp3", "m4a", "wav", "ogg", "opus", "amr", "aac", "flac", "wma")
    private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "vcf", "zip", "rar", "7z")
    private val BAK = setOf("crypt12", "crypt14", "crypt15", "bak", "backup")
    private val MAYBE = setOf("", "tmp", "dat", "bin", "cache", "thumb", "thumbdata", "0", "1", "2", "3", "jpg_", "data")
    private val TRASH = listOf(
        "/.trash", "/trash/", "/recycle", "recently deleted", "recently_deleted",
        "/.deleted", "/deleted/", "/lost.dir/", "/trashbin"
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 48, 16, 16) }
        status = TextView(this).apply {
            text = "Tap SCAN to search your phone for deleted / hidden / leftover photos, videos, audio, documents and old backups."
            textSize = 15f
            setPadding(8, 8, 8, 8)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply { text = "Scan" }
        allBtn = Button(this).apply { text = "Select all" }
        backupBtn = Button(this).apply { text = "Recover" }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(scanBtn, lp); row.addView(allBtn, lp); row.addView(backupBtn, lp)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterSp = Spinner(this)
        filterSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("All types", "Photos", "Videos", "Audio", "Documents", "Backups & other"))
        sortSp = Spinner(this)
        sortSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Oldest first", "Newest first", "Largest first"))
        row2.addView(filterSp, lp); row2.addView(sortSp, lp)

        grid = GridView(this)
        grid.numColumns = 3
        grid.adapter = gridAdapter
        root.addView(status)
        root.addView(row)
        root.addView(row2)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val sel = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { applyFilter() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        filterSp.onItemSelectedListener = sel
        sortSp.onItemSelectedListener = sel

        scanBtn.setOnClickListener {
            if (scanning) { scanning = false; return@setOnClickListener }
            if (!hasAccess()) {
                Toast.makeText(this, "Allow 'All files access' for this app, then come back and tap Scan", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else startScan()
        }
        allBtn.setOnClickListener {
            val allSel = items.isNotEmpty() && items.all { it.file.absolutePath in selected }
            for (i in items) { if (allSel) selected.remove(i.file.absolutePath) else selected.add(i.file.absolutePath) }
            refreshSel()
        }
        backupBtn.setOnClickListener { recover() }
        grid.setOnItemClickListener { _, _, p, _ ->
            val path = items[p].file.absolutePath
            if (!selected.add(path)) selected.remove(path)
            refreshSel()
        }
        grid.setOnItemLongClickListener { _, _, p, _ -> preview(items[p]); true }
    }

    private fun hasAccess() = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun refreshSel() {
        backupBtn.text = if (selected.isEmpty()) "Recover" else "Recover (${selected.size})"
        gridAdapter.notifyDataSetChanged()
    }

    private fun applyFilter() {
        val f = filterSp.selectedItemPosition
        val list = allItems.filter { f == 0 || it.type == f }
        val sorted = when (sortSp.selectedItemPosition) {
            0 -> list.sortedBy { it.file.lastModified() }
            1 -> list.sortedByDescending { it.file.lastModified() }
            else -> list.sortedByDescending { it.file.length() }
        }
        items.clear(); items.addAll(sorted)
        refreshSel()
        if (!scanning && allItems.isNotEmpty()) {
            status.text = "Showing ${items.size} of ${allItems.size} recoverable files.\nTap = select, long-press = details."
        }
    }

    private fun knownPaths(): HashSet<String> {
        val s = HashSet<String>()
        val uris = listOf(
            MediaStore.Files.getContentUri("external"),
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        for (uri in uris) {
            try {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { s.add(it) }
                }
            } catch (_: Exception) {}
        }
        return s
    }

    private fun startScan() {
        allItems.clear(); items.clear(); selected.clear(); visited = 0; foundCount = 0
        refreshSel()
        scanning = true
        scanBtn.text = "Stop"
        val ticker = object : Runnable {
            override fun run() {
                if (!scanning) return
                status.text = "Scanning… $visited files checked, $foundCount recoverable found"
                ui.postDelayed(this, 400)
            }
        }
        ui.post(ticker)
        Thread {
            val known = knownPaths()
            val found = ArrayList<Item>()
            val roots = getExternalFilesDirs(null).filterNotNull()
                .map { File(it.absolutePath.substringBefore("/Android/")) }.distinct()
            for (r in roots) walk(r, known, found, false)
            ui.post {
                scanning = false
                allItems.addAll(found)
                scanBtn.text = "Scan"
                applyFilter()
                status.text = "Done: ${allItems.size} recoverable files found ($visited files checked).\n" +
                    "Tap = select, long-press = details. Use the filters below."
            }
        }.start()
    }

    private fun sniff(f: File): Int {
        if (f.extension.lowercase() in MAYBE && f.length() > 50 * 1024) {
            try {
                f.inputStream().use { s ->
                    val h = ByteArray(12)
                    if (s.read(h) < 12) return 0
                    if (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()) return 1
                    if (h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte()) return 1
                    if (String(h, 4, 4) == "ftyp") return 2
                }
            } catch (_: Exception) {}
        }
        return 0
    }

    private fun typeOf(f: File): Int {
        val ext = f.extension.lowercase()
        return when {
            ext in IMG -> 1
            ext in VID -> 2
            ext in AUD -> 3
            ext in DOC -> 4
            ext in BAK -> 5
            else -> sniff(f)
        }
    }

    private fun walk(dir: File, known: Set<String>, out: MutableList<Item>, noMedia: Boolean) {
        if (!scanning) return
        if (dir.name == "RecoveredData" || dir.name == "HiddenMediaBackup") return
        val files = dir.listFiles() ?: return
        val nm = noMedia || files.any { it.name == ".nomedia" }
        for (f in files) {
            if (!scanning) return
            if (f.isDirectory) { walk(f, known, out, nm); continue }
            visited++
            if (f.length() < 1024) continue
            val t = typeOf(f)
            if (t == 0) continue
            if (t <= 3 && f.length() < 20 * 1024) continue
            val path = f.absolutePath
            val pl = path.lowercase()
            val trashedName = f.name.startsWith(".trashed") || f.name.startsWith(".pending")
            val inTrash = TRASH.any { it in pl }
            val hiddenDir = (f.parent ?: "").split("/").any { it.startsWith(".") && it.length > 1 }
            val isKnown = path in known
            val ext = f.extension.lowercase()
            val reason: String? = when {
                ext.startsWith("crypt") -> "Old WhatsApp chat backup"
                trashedName || inTrash -> "Recycle bin / deleted"
                hiddenDir && !isKnown -> "Hidden folder"
                t <= 3 && !isKnown && nm -> "Folder with .nomedia"
                t <= 3 && !isKnown && ("cache" in pl || "thumb" in pl) -> "Cache / thumbnail"
                t <= 3 && !isKnown -> "Not shown in gallery"
                else -> null
            }
            if (reason == null) continue
            out.add(Item(f, t, reason))
            foundCount++
        }
    }

    private fun recover() {
        if (selected.isEmpty()) { Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show(); return }
        val map = allItems.associateBy { it.file.absolutePath }
        val list = selected.mapNotNull { map[it] }
        val pics = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "RecoveredData")
        val docs = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "RecoveredData")
        pics.mkdirs(); docs.mkdirs()
        Toast.makeText(this, "Recovering ${list.size} files…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            val newPaths = ArrayList<String>()
            for (item in list) {
                try {
                    val src = item.file
                    val dest = if (item.type <= 2) pics else docs
                    val clean = src.name.replace(Regex("^\\.(trashed|pending)-\\d+-"), "").trimStart('.')
                    var name = if (clean.isEmpty()) "file_${System.nanoTime()}" else clean
                    if (src.extension.lowercase() !in IMG + VID + AUD + DOC + BAK) {
                        name += when (item.type) { 1 -> ".jpg"; 2 -> ".mp4"; else -> ".bin" }
                    }
                    var t = File(dest, name)
                    var i = 1
                    while (t.exists()) { t = File(dest, "${i++}_$name") }
                    src.copyTo(t)
                    t.setLastModified(src.lastModified())
                    newPaths.add(t.absolutePath); ok++
                } catch (_: Exception) {}
            }
            MediaScannerConnection.scanFile(this, newPaths.toTypedArray(), null, null)
            ui.post {
                Toast.makeText(this, "Recovered $ok of ${list.size}.\nPhotos/videos: Pictures/RecoveredData\nOther: Documents/RecoveredData",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun preview(item: Item) {
        val d = AlertDialog.Builder(this).setPositiveButton("Close", null)
        when (item.type) {
            2 -> {
                d.setTitle(item.reason + "\n" + item.file.name)
                val vv = VideoView(this)
                vv.setVideoURI(Uri.fromFile(item.file))
                vv.setMediaController(MediaController(this))
                vv.setOnPreparedListener { vv.start() }
                d.setView(vv)
            }
            1 -> {
                d.setTitle(item.reason + "\n" + item.file.name)
                val iv = ImageView(this)
                iv.adjustViewBounds = true
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.file.absolutePath, o)
                var s = 1
                while (o.outWidth / s > 1600) s *= 2
                val bmp = BitmapFactory.decodeFile(item.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
                if (bmp != null) iv.setImageBitmap(bmp)
                d.setView(iv)
            }
            else -> {
                d.setTitle(item.file.name)
                d.setMessage("${item.reason}\n\n${item.file.absolutePath}\n\nSize: ${item.file.length() / 1024} KB\n" +
                    "Date: ${df.format(Date(item.file.lastModified()))}")
            }
        }
        d.show()
    }

    private fun label(item: Item): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF37474F.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = 56f
        val ext = item.file.extension.uppercase().take(6)
        c.drawText(if (ext.isEmpty()) "FILE" else ext, 128f, 130f, p)
        p.textSize = 22f
        c.drawText(item.file.name.trimStart('.').take(14), 128f, 190f, p)
        return bmp
    }

    inner class GridAdapter : BaseAdapter() {
        private val size get() = resources.displayMetrics.widthPixels / 3

        private fun makeCell(): FrameLayout {
            val c = FrameLayout(this@MainActivity)
            c.layoutParams = AbsListView.LayoutParams(size, size)
            c.addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(-1, -1))
            c.addView(TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); textSize = 16f
                setPadding(8, 2, 8, 2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            c.addView(TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); textSize = 11f
                setPadding(8, 2, 8, 2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            return c
        }

        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val v = (cv as? FrameLayout) ?: makeCell()
            val iv = v.getChildAt(0) as ImageView
            val tick = v.getChildAt(1) as TextView
            val dateTv = v.getChildAt(2) as TextView
            val item = items[p]
            val path = item.file.absolutePath
            iv.tag = path
            val cached = cache.get(path)
            if (cached != null) iv.setImageBitmap(cached) else {
                iv.setImageDrawable(null)
                iv.setBackgroundColor(0xFF555555.toInt())
                pool.execute {
                    val sz = Size(256, 256)
                    val bmp: Bitmap = try {
                        when (item.type) {
                            1 -> ThumbnailUtils.createImageThumbnail(item.file, sz, null)
                            2 -> ThumbnailUtils.createVideoThumbnail(item.file, sz, null)
                            else -> label(item)
                        }
                    } catch (_: Exception) { label(item) }
                    cache.put(path, bmp)
                    ui.post { if (iv.tag == path) iv.setImageBitmap(bmp) }
                }
            }
            val t = (if (item.type == 2) "▶ " else "") + (if (path in selected) "✓" else "")
            tick.text = t
            tick.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
            dateTv.text = df.format(Date(item.file.lastModified()))
            v.setBackgroundColor(if (path in selected) 0xFF2E7D32.toInt() else Color.TRANSPARENT)
            iv.alpha = if (path in selected) 0.6f else 1f
            return v
        }
    }
}
