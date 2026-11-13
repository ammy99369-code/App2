package com.hiddenmedia.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import java.util.concurrent.Executors

class MainActivity : Activity() {

    data class Item(val file: File, val video: Boolean, val reason: String)

    private val items = ArrayList<Item>()
    private val selected = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = LruCache<String, Bitmap>(200)
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private lateinit var scanBtn: Button
    private lateinit var backupBtn: Button
    private lateinit var allBtn: Button
    private val adapter = Adapter()
    @Volatile private var scanning = false
    @Volatile private var visited = 0
    @Volatile private var foundCount = 0

    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "dng")
    private val VID = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v")
    private val MAYBE = setOf("", "tmp", "dat", "bin", "cache", "thumb", "thumbdata", "0", "1", "2", "3", "jpg_", "data")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 48, 16, 16) }
        status = TextView(this).apply {
            text = "Tap SCAN to find photos & videos hidden from your gallery."
            textSize = 15f
            setPadding(8, 8, 8, 8)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanBtn = Button(this).apply { text = "Scan" }
        allBtn = Button(this).apply { text = "Select all" }
        backupBtn = Button(this).apply { text = "Backup" }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(scanBtn, lp); row.addView(allBtn, lp); row.addView(backupBtn, lp)
        grid = GridView(this).apply { numColumns = 3; adapter = this@MainActivity.adapter }
        root.addView(status)
        root.addView(row)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        scanBtn.setOnClickListener {
            if (scanning) { scanning = false; return@setOnClickListener }
            if (!hasAccess()) {
                Toast.makeText(this, "Allow 'All files access' for this app, then come back and tap Scan", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else startScan()
        }
        allBtn.setOnClickListener {
            if (selected.size == items.size) selected.clear() else items.forEach { selected.add(it.file.absolutePath) }
            refreshSel()
        }
        backupBtn.setOnClickListener { backup() }
        grid.setOnItemClickListener { _, _, p, _ ->
            val path = items[p].file.absolutePath
            if (!selected.add(path)) selected.remove(path)
            refreshSel()
        }
        grid.setOnItemLongClickListener { _, _, p, _ -> preview(items[p]); true }
    }

    private fun hasAccess() = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun refreshSel() {
        backupBtn.text = if (selected.isEmpty()) "Backup" else "Backup (${selected.size})"
        adapter.notifyDataSetChanged()
    }

    private fun knownPaths(): HashSet<String> {
        val s = HashSet<String>()
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            try {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { s.add(it) }
                }
            } catch (_: Exception) {}
        }
        return s
    }

    private fun startScan() {
        items.clear(); selected.clear(); visited = 0; foundCount = 0
        refreshSel()
        scanning = true
        scanBtn.text = "Stop"
        val ticker = object : Runnable {
            override fun run() {
                if (!scanning) return
                status.text = "Scanning… $visited files checked, $foundCount hidden media found"
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
            found.sortByDescending { it.file.lastModified() }
            ui.post {
                scanning = false
                items.addAll(found)
                scanBtn.text = "Scan"
                status.text = "Done: ${items.size} hidden / not-in-gallery files found ($visited files checked).\n" +
                    "Tap = select, long-press = preview."
                refreshSel()
            }
        }.start()
    }

    private fun kind(f: File): Int { // 0 none, 1 image, 2 video
        val ext = f.extension.lowercase()
        if (ext in IMG) return 1
        if (ext in VID) return 2
        if (ext in MAYBE && f.length() > 50 * 1024) {
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

    private fun walk(dir: File, known: Set<String>, out: MutableList<Item>, noMedia: Boolean) {
        if (!scanning) return
        if (dir.name == "HiddenMediaBackup") return
        val files = dir.listFiles() ?: return
        val nm = noMedia || files.any { it.name == ".nomedia" }
        for (f in files) {
            if (!scanning) return
            if (f.isDirectory) { walk(f, known, out, nm); continue }
            visited++
            if (f.length() < 20 * 1024) continue
            if (f.absolutePath in known) continue
            val k = kind(f)
            if (k == 0) continue
            val pathLower = f.absolutePath.lowercase()
            val hiddenDir = (f.parent ?: "").split("/").any { it.startsWith(".") && it.length > 1 }
            val reason = when {
                f.name.startsWith(".trashed") || f.name.startsWith(".pending") -> "Recycle bin / deleted"
                hiddenDir -> "Hidden folder"
                nm -> "Folder with .nomedia"
                "cache" in pathLower || "thumb" in pathLower -> "Cache / thumbnail"
                else -> "Not shown in gallery"
            }
            out.add(Item(f, k == 2, reason))
            foundCount++
        }
    }

    private fun backup() {
        if (selected.isEmpty()) { Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show(); return }
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HiddenMediaBackup")
        dest.mkdirs()
        val list = selected.toList()
        Toast.makeText(this, "Backing up ${list.size} files…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            val newPaths = ArrayList<String>()
            for (p in list) {
                try {
                    val src = File(p)
                    val clean = src.name.replace(Regex("^\\.(trashed|pending)-\\d+-"), "").trimStart('.')
                    var name = if (clean.isEmpty()) "file_${System.nanoTime()}" else clean
                    if (src.extension.isEmpty() || src.extension.lowercase() !in IMG + VID) {
                        name += if (kind(src) == 2) ".mp4" else ".jpg"
                    }
                    var t = File(dest, name)
                    var i = 1
                    while (t.exists()) { t = File(dest, "${i++}_$name") }
                    src.copyTo(t)
                    newPaths.add(t.absolutePath); ok++
                } catch (_: Exception) {}
            }
            MediaScannerConnection.scanFile(this, newPaths.toTypedArray(), null, null)
            ui.post {
                Toast.makeText(this, "Backed up $ok of ${list.size} to Pictures/HiddenMediaBackup", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun preview(it: Item) {
        val d = AlertDialog.Builder(this).setTitle(it.reason + "\n" + it.file.name).setPositiveButton("Close", null)
        if (it.video) {
            val vv = VideoView(this)
            vv.setVideoURI(Uri.fromFile(it.file))
            vv.setMediaController(MediaController(this))
            vv.setOnPreparedListener { vv.start() }
            d.setView(vv)
        } else {
            val iv = ImageView(this)
            iv.adjustViewBounds = true
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(it.file.absolutePath, o)
            var s = 1
            while (o.outWidth / s > 1600) s *= 2
            val bmp = BitmapFactory.decodeFile(it.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            if (bmp != null) iv.setImageBitmap(bmp)
            d.setView(iv)
        }
        d.show()
    }

    inner class Adapter : BaseAdapter() {
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
            return c
        }

        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val v = (cv as? FrameLayout) ?: makeCell()
            val iv = v.getChildAt(0) as ImageView
            val tick = v.getChildAt(1) as TextView
            val item = items[p]
            val path = item.file.absolutePath
            iv.tag = path
            val cached = cache.get(path)
            if (cached != null) iv.setImageBitmap(cached) else {
                iv.setImageDrawable(null)
                iv.setBackgroundColor(0xFF555555.toInt())
                pool.execute {
                    val bmp = try {
                        val sz = Size(256, 256)
                        if (item.video) ThumbnailUtils.createVideoThumbnail(item.file, sz, null)
                        else ThumbnailUtils.createImageThumbnail(item.file, sz, null)
                    } catch (_: Exception) { null }
                    if (bmp != null) {
                        cache.put(path, bmp)
                        ui.post { if (iv.tag == path) iv.setImageBitmap(bmp) }
                    }
                }
            }
            val t = (if (item.video) "▶ " else "") + (if (path in selected) "✓" else "")
            tick.text = t
            tick.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
            v.setBackgroundColor(if (path in selected) 0xFF2E7D32.toInt() else Color.TRANSPARENT)
            iv.alpha = if (path in selected) 0.6f else 1f
            return v
        }
    }
}
