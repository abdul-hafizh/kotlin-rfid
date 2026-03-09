package com.example.newrfid

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.entity.UHFTAGInfo
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback
import kotlinx.coroutines.*
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.LinkedHashMap
import kotlin.concurrent.thread
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager

class MainActivity : AppCompatActivity() {

    private val tag = "RFID"

    private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnLoop: Button

    private var reader: RFIDWithUHFUART? = null
    private var loopFlag = false

    private val seenEpc = HashSet<String>()
    private var totalReads = 0

    private lateinit var rvProducts: RecyclerView
    private lateinit var productAdapter: ProductAdapter

    private val productRows = mutableListOf<ProductRow>()
    private val itemCodeToIndex = HashMap<String, Int>() // item_code -> index row
    private val epcToItemCode = HashMap<String, String>() // EPC -> item_code
    private var unknownRegistered = 0

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        appendLog("Permission result diterima. Tekan INIT lagi.")
    }

    private val seenTagMap = LinkedHashMap<String, TagItem>() // key = epc

    private val api: ApiService by lazy { ApiClient.create() }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        tvInfo = findViewById(R.id.tvInfo)
        tvLog = findViewById(R.id.tvLog)
        btnLoop = findViewById(R.id.btnLoop)

        findViewById<Button>(R.id.btnSingle).setOnClickListener { scanSingle() }
        btnLoop.setOnClickListener { toggleLoop() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearLog() }

        findViewById<Button>(R.id.btnPush).setOnClickListener {
            confirmPushToServer()
        }

        rvProducts = findViewById(R.id.rvProducts)
        productAdapter = ProductAdapter(productRows)
        rvProducts.layoutManager = LinearLayoutManager(this)
        rvProducts.adapter = productAdapter

        showInfo("Belum init")
        checkPermissionThenInit()

        loadMasterAndMapping()
    }

    // ==========================
    // PERMISSION + INIT
    // ==========================
    private fun checkPermissionThenInit() {
        // Android 11+ (R): minta All files access seperti demo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Aktifkan All files access dulu", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                return
            }
        } else {
            // Android 6-10: minta READ/WRITE runtime
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (need.isNotEmpty()) {
                requestPerms.launch(need.toTypedArray())
                return
            }
        }

        initReaderAsync()
    }

    private fun initReaderAsync() {
        val dialog = ProgressDialog(this).apply {
            setMessage("init...")
            setCancelable(false)
            show()
        }

        thread {
            try {
                val inst = RFIDWithUHFUART.getInstance()
                if (inst == null) {
                    runOnUiThread {
                        dialog.dismiss()
                        showInfo("getInstance() null")
                        appendLog("SDK tidak menemukan modul UHF di device ini.")
                    }
                    return@thread
                }
                reader = inst

                val ok = reader?.init() == true

                runOnUiThread {
                    dialog.dismiss()
                    showInfo("init: $ok")
                    appendLog("init result: $ok")
                    if (ok) reader?.setEPCMode()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    dialog.dismiss()
                    showInfo("init error")
                    appendLog("Init ERROR: ${t::class.java.simpleName}: ${t.message}")
                }
                Log.e(tag, "init error", t)
            }
        }
    }

    // ==========================
    // SCAN SINGLE
    // ==========================
    private fun scanSingle() {
        if (reader == null) {
            appendLog("Belum init reader.")
            return
        }
        if (loopFlag) {
            appendLog("Stop loop dulu sebelum single scan.")
            return
        }

        try {
            val info: UHFTAGInfo? = reader?.inventorySingleTag()
            if (info != null) onTag(info) else appendLog("Single: tidak ada tag kebaca.")
        } catch (t: Throwable) {
            appendLog("Single ERROR: ${t.message}")
            Log.e(tag, "scanSingle error", t)
        }
    }

    // ==========================
    // LOOP INVENTORY
    // ==========================
    private fun toggleLoop() {
        if (reader == null) {
            appendLog("Belum init reader.")
            return
        }

        if (!loopFlag) startLoop() else stopLoop()
        btnLoop.text = if (loopFlag) "Stop Loop" else "Start Loop"
    }

    private fun startLoop() {
        seenEpc.clear()
        seenTagMap.clear()
        totalReads = 0

        unknownRegistered = 0

        val current = productAdapter.getItems().map { it.copy(scanQty = 0) }
        productAdapter.setData(current)

        try {
            reader?.setInventoryCallback(IUHFInventoryCallback { info ->
                runOnUiThread { if (info != null) onTag(info) }
            })

            val ok = reader?.startInventoryTag() == true
            loopFlag = ok

            appendLog(if (ok) "Loop started..." else "startInventoryTag gagal.")

            if (ok) {
                updateInfo("Loop: ON")
            } else {
                showInfo("Loop: gagal start | Unique EPC: 0 | Total reads: 0")
            }
        } catch (t: Throwable) {
            loopFlag = false
            appendLog("StartLoop ERROR: ${t.message}")
            Log.e(tag, "startLoop error", t)
            showInfo("Loop: error | Unique EPC: 0 | Total reads: 0")
        }
    }

    private fun stopLoop() {
        if (!loopFlag) return
        try {
            val ok = reader?.stopInventory() == true
            loopFlag = false
            appendLog("Loop stop: $ok")

            updateInfo("Loop: OFF")
        } catch (t: Throwable) {
            loopFlag = false
            appendLog("StopLoop ERROR: ${t.message}")
            Log.e(tag, "stopLoop error", t)

            updateInfo("Loop: OFF")
        }
    }

    // ==========================
    // TAG HANDLING
    // ==========================
    private fun onTag(info: UHFTAGInfo) {
        val epc = (info.epc ?: "").trim()
        if (epc.isEmpty()) return

        val isNew = seenEpc.add(epc)
        totalReads += 1

        if (!isNew) {
            updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
            return
        }

        val tid = (info.tid ?: "").trim()
        val rssi = (info.rssi ?: "").trim()

        val nowIso = if (Build.VERSION.SDK_INT >= 26) {
            OffsetDateTime.now(ZoneOffset.UTC).toString()
        } else null

        seenTagMap[epc] = TagItem(
            epc = epc,
            tid = tid.takeIf { it.isNotBlank() && it != "0000000000000000" && it != "000000000000000000000000" },
            rssi = rssi.takeIf { it.isNotBlank() },
            readAt = nowIso
        )

        val merged = buildString {
            append("EPC: ").append(epc)
            if (tid.isNotEmpty() && tid != "0000000000000000" && tid != "000000000000000000000000") {
                append("\nTID: ").append(tid)
            }
            if (rssi.isNotEmpty()) append("\nRSSI: ").append(rssi)
        }

        val itemCode = epcToItemCode[epc]
        if (itemCode == null) {
            unknownRegistered += 1
            appendLog("EPC belum terdaftar: $epc")
            updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
            return
        }

        val idx = itemCodeToIndex[itemCode]
        if (idx == null) {
            appendLog("ItemCode $itemCode tidak ada di master produk.")
            updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
            return
        }

        val newQty = productAdapter.getItems()[idx].scanQty + 1
        productAdapter.updateScanQtyAt(idx, newQty)

        appendLog("\n-------- TAG (NEW) --------\n$merged")
        updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
    }

    private fun clearLog() {
        tvLog.text = ""
        seenEpc.clear()
        totalReads = 0
        seenTagMap.clear()
        showInfo("Cleared")
    }

    private fun showInfo(s: String) {
        tvInfo.text = s
        Log.d(tag, s)
    }

    private fun appendLog(s: String) {
        tvLog.append(s + "\n")
        Log.d(tag, s)
    }

    private fun updateInfo(status: String) {
        showInfo("$status | Unique EPC: ${seenEpc.size} | Total reads: $totalReads | Unknown EPC: $unknownRegistered")
    }

    // ==========================
    // PUSH SERVER
    // ==========================
    private fun confirmPushToServer() {
        if (seenTagMap.isEmpty()) {
            Toast.makeText(this, "Belum ada tag untuk dikirim.", Toast.LENGTH_SHORT).show()
            return
        }

        if (loopFlag) {
            Toast.makeText(this, "Stop loop dulu sebelum push.", Toast.LENGTH_SHORT).show()
            return
        }

        val count = seenTagMap.size

        AlertDialog.Builder(this)
            .setTitle("Push ke Server")
            .setMessage("Anda yakin ingin mengirim $count tag ke server?")
            .setPositiveButton("Ya") { _, _ -> pushToServer() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pushToServer() {
        val progress = ProgressDialog(this).apply {
            setMessage("Mengirim data ke server...")
            setCancelable(false)
            show()
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val payload = PushTagsRequest(
            deviceId = deviceId,
            tags = seenTagMap.values.toList()
        )

        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.pushTags(payload) }
                progress.dismiss()

                if (resp.isSuccessful) {
                    val body = resp.body()

                    val msg = buildString {
                        append(body?.message ?: "Berhasil.")
                        append("\n\nInserted: ").append(body?.inserted ?: 0)
                        append("\nDuplicate: ").append(body?.duplicate ?: 0)

                        val dupList = body?.duplicateEpc
                        if (!dupList.isNullOrEmpty()) {
                            append("\n\nContoh EPC duplikat:\n")
                            append(dupList.take(10).joinToString("\n"))
                        }
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Respon Server")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()

                    appendLog("PUSH OK: ${body?.message ?: "success"} | inserted=${body?.inserted} dup=${body?.duplicate}")
                } else {
                    val err = try { resp.errorBody()?.string() } catch (_: Throwable) { null }
                    Toast.makeText(this@MainActivity, "Gagal push: ${resp.code()}", Toast.LENGTH_LONG).show()
                    appendLog("PUSH FAIL HTTP ${resp.code()}: $err")
                }
            } catch (t: Throwable) {
                progress.dismiss()
                Toast.makeText(this@MainActivity, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                appendLog("PUSH ERROR: ${t::class.java.simpleName}: ${t.message}")
            }
        }
    }

    private fun loadMasterAndMapping() {
        uiScope.launch {
            try {
                // 1) get master products
                val masterResp = withContext(Dispatchers.IO) { api.getMasterProducts() }
                if (masterResp.isSuccessful) {
                    val master = masterResp.body()?.data.orEmpty()
                    val rows = master.map {
                        ProductRow(
                            itemCode = it.itemcode,
                            itemName = it.itemname,
                            endStock = it.endstock ?: 0,
                            scanQty = 0
                        )
                    }

                    // build index map
                    itemCodeToIndex.clear()
                    rows.forEachIndexed { idx, r -> itemCodeToIndex[r.itemCode] = idx }

                    productAdapter.setData(rows.toList())
                } else {
                    appendLog("GET MASTER FAIL: HTTP ${masterResp.code()}")
                }

                // 2) get RFID mapping EPC->item_code
                val mapResp = withContext(Dispatchers.IO) { api.getRfidMapping() }
                if (mapResp.isSuccessful) {
                    epcToItemCode.clear()
                    mapResp.body()?.data.orEmpty().forEach { m ->
                        epcToItemCode[m.epc] = m.item_code
                    }
                    appendLog("Mapping loaded: ${epcToItemCode.size} EPC")
                } else {
                    appendLog("GET MAPPING FAIL: HTTP ${mapResp.code()}")
                }
            } catch (t: Throwable) {
                appendLog("Load master/mapping ERROR: ${t.message}")
            }
        }
    }

    // ==========================
    // LIFECYCLE
    // ==========================
    override fun onPause() {
        super.onPause()
        stopLoop()
        btnLoop.text = "Loop"
    }

    override fun onDestroy() {
        uiScope.cancel()
        try {
            stopLoop()
            reader?.free()
            reader = null
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}