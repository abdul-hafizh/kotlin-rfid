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
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.entity.UHFTAGInfo
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback
import kotlinx.coroutines.*
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.LinkedHashMap
import kotlin.concurrent.thread

class ScanFragment : Fragment(R.layout.fragment_scan) {

    private val tag = "RFID"

    private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnLoop: Button
    private lateinit var btnSaveScan: Button

    private var reader: RFIDWithUHFUART? = null
    private var loopFlag = false

    private val seenEpc = HashSet<String>()
    private var totalReads = 0

    private lateinit var btnSync: Button

    private lateinit var rvProducts: RecyclerView
    private lateinit var productAdapter: ProductAdapter

    private val productRows = mutableListOf<ProductRow>()
    private val itemCodeToIndex = HashMap<String, Int>()
    private val epcToItemCode = HashMap<String, String>()
    private var unknownRegistered = 0
    private var isMappingLoaded = false

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        appendLog("Permission result diterima. Tekan INIT lagi.")
    }

    private val seenTagMap = LinkedHashMap<String, TagItem>()

    private val api: ApiService by lazy { ApiClient.create() }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvInfo = view.findViewById(R.id.tvInfo)
        tvLog = view.findViewById(R.id.tvLog)
        tvLog.movementMethod = android.text.method.ScrollingMovementMethod()
        tvLog.movementMethod = android.text.method.ScrollingMovementMethod()
        btnLoop = view.findViewById(R.id.btnLoop)
        btnSaveScan = view.findViewById(R.id.btnSaveScan)
        btnSync = view.findViewById(R.id.btnSync)

        view.findViewById<Button>(R.id.btnSingle).setOnClickListener { scanSingle() }
        btnLoop.setOnClickListener { toggleLoop() }
        view.findViewById<Button>(R.id.btnClear).setOnClickListener { clearLog() }

        view.findViewById<Button>(R.id.btnPush).setOnClickListener {
            confirmPushToServer()
        }

        btnSaveScan.setOnClickListener {
            confirmSaveScanResult()
        }

        btnSync.setOnClickListener {
            syncMasterAndMapping()
        }

        rvProducts = view.findViewById(R.id.rvProducts)
        productAdapter = ProductAdapter(productRows)
        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        rvProducts.adapter = productAdapter

        showInfo("Belum init")
        checkPermissionThenInit()

        loadMasterAndMapping()
    }

    private fun checkPermissionThenInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(requireContext(), "Aktifkan All files access dulu", Toast.LENGTH_SHORT).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
                return
            }
        } else {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
        val dialog = ProgressDialog(requireContext()).apply {
            setMessage("init...")
            setCancelable(false)
            show()
        }

        thread {
            try {
                val inst = RFIDWithUHFUART.getInstance()
                if (inst == null) {
                    view?.post {
                        dialog.dismiss()
                        showInfo("getInstance() null")
                        appendLog("SDK tidak menemukan modul UHF di device ini.")
                    }
                    return@thread
                }
                reader = inst

                val ok = reader?.init() == true

                view?.post {
                    dialog.dismiss()
                    if (!ok) {
                        Toast.makeText(requireContext(), "Init reader gagal", Toast.LENGTH_SHORT).show()
                    } else {
                        reader?.setEPCMode()

                        try {
                            reader?.setPower(25)   // (±2–5 meter)
                        } catch (e: Exception) {
                            Log.e("SCAN", "setPower error", e)
                        }
                    }
                }
            } catch (t: Throwable) {
                view?.post {
                    dialog.dismiss()
                    showInfo("init error")
                    appendLog("Init ERROR: ${t::class.java.simpleName}: ${t.message}")
                }
                Log.e(tag, "init error", t)
            }
        }
    }

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

        if (!isMappingLoaded) {
            appendLog("Mapping belum siap, tunggu...")
            return
        }

        val current = productAdapter.getItems().map { it.copy(scanQty = 0) }
        productAdapter.setData(current)

        try {
            reader?.setInventoryCallback(IUHFInventoryCallback { info ->
                view?.post { if (info != null) onTag(info) }
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
        unknownRegistered = 0

        val current = productAdapter.getItems().map { it.copy(scanQty = 0) }
        productAdapter.setData(current)

        showInfo("Cleared")
    }

    private fun showInfo(s: String) {
        tvInfo.text = s
        Log.d(tag, s)
    }

    private fun appendLog(s: String) {
        tvLog.append(s + "\n")

        val scrollAmount = tvLog.layout?.getLineTop(tvLog.lineCount) ?: 0
        if (scrollAmount > tvLog.height) {
            tvLog.scrollTo(0, scrollAmount - tvLog.height)
        }

        Log.d(tag, s)
    }

    private fun updateInfo(status: String) {
        showInfo("$status | Unique EPC: ${seenEpc.size}")
    }

    private fun confirmPushToServer() {
        if (seenTagMap.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada tag untuk dikirim.", Toast.LENGTH_SHORT).show()
            return
        }

        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum push.", Toast.LENGTH_SHORT).show()
            return
        }

        val count = seenTagMap.size

        AlertDialog.Builder(requireContext())
            .setTitle("Simpan Tag Baru")
            .setMessage("Anda yakin ingin mengirim $count tag ke server?")
            .setPositiveButton("Ya") { _, _ -> pushToServer() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pushToServer() {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Mengirim data ke server...")
            setCancelable(false)
            show()
        }

        val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
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

                    AlertDialog.Builder(requireContext())
                        .setTitle("Respon Server")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()

                    appendLog("PUSH `OK: ${body?.message ?: "success"} | inserted=${body?.inserted} dup=${body?.duplicate}")
                } else {
                    val err = try { resp.errorBody()?.string() } catch (_: Throwable) { null }
                    Toast.makeText(requireContext(), "Gagal push: ${resp.code()}", Toast.LENGTH_LONG).show()
                    appendLog("PUSH FAIL HTTP ${resp.code()}: $err")
                }
            } catch (t: Throwable) {
                progress.dismiss()
                Toast.makeText(requireContext(), "Error: ${t.message}", Toast.LENGTH_LONG).show()
                appendLog("PUSH ERROR: ${t::class.java.simpleName}: ${t.message}")
            }
        }
    }

    // ==========================
    // SIMPAN HASIL SCAN
    // ==========================
    private fun confirmSaveScanResult() {
        if (seenTagMap.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada hasil scan untuk disimpan.", Toast.LENGTH_SHORT).show()
            return
        }

        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum simpan hasil scan.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Simpan Hasil Scan")
            .setMessage("Simpan hasil scan saat ini ke database?")
            .setPositiveButton("Ya") { _, _ -> saveScanResult() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun saveScanResult() {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Menyimpan hasil scan...")
            setCancelable(false)
            show()
        }

        val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)

        val itemPayloads = productAdapter.getItems().map { row ->
            SaveScanSessionItemRequest(
                itemCode = row.itemCode,
                itemName = row.itemName,
                stockSystem = row.endStock,
                scanQty = row.scanQty
            )
        }

        val tagPayloads = seenTagMap.values.map { tagItem ->
            SaveScanSessionTagRequest(
                epc = tagItem.epc,
                tid = tagItem.tid,
                rssi = tagItem.rssi,
                itemCode = epcToItemCode[tagItem.epc],
                isRegistered = epcToItemCode.containsKey(tagItem.epc),
                readAt = tagItem.readAt
            )
        }

        val payload = SaveScanSessionRequest(
            deviceId = deviceId,
            scannedAt = if (Build.VERSION.SDK_INT >= 26) OffsetDateTime.now().toString() else null,
            totalUniqueEpc = seenTagMap.size,
            totalReads = totalReads,
            unknownEpc = unknownRegistered,
            items = itemPayloads,
            tags = tagPayloads
        )

        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.saveScanSession(payload) }
                progress.dismiss()

                if (resp.isSuccessful) {
                    val body = resp.body()

                    val msg = buildString {
                        append(body?.message ?: "Hasil scan berhasil disimpan")
                        append("\nSession ID: ").append(body?.data?.sessionId ?: "-")
                        append("\nSession Code: ").append(body?.data?.sessionCode ?: "-")
                        append("\nSaved Items: ").append(body?.data?.savedItems ?: 0)
                        append("\nSaved Tags: ").append(body?.data?.savedTags ?: 0)
                    }

                    AlertDialog.Builder(requireContext())
                        .setTitle("Simpan Berhasil")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()

                    appendLog("SAVE OK: ${body?.message ?: "success"} | session=${body?.data?.sessionCode}")
                } else {
                    val err = try { resp.errorBody()?.string() } catch (_: Throwable) { null }
                    Toast.makeText(requireContext(), "Gagal simpan: ${resp.code()}", Toast.LENGTH_LONG).show()
                    appendLog("SAVE FAIL HTTP ${resp.code()}: $err")
                }
            } catch (t: Throwable) {
                progress.dismiss()
                Toast.makeText(requireContext(), "Error: ${t.message}", Toast.LENGTH_LONG).show()
                appendLog("SAVE ERROR: ${t::class.java.simpleName}: ${t.message}")
            }
        }
    }

    private fun syncMasterAndMapping() {
        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum sync.", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Sinkron data master & mapping...")
            setCancelable(false)
            show()
        }

        uiScope.launch {
            try {
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

                    itemCodeToIndex.clear()
                    rows.forEachIndexed { idx, r -> itemCodeToIndex[r.itemCode] = idx }

                    productAdapter.setData(rows.toList())
                } else {
                    appendLog("SYNC MASTER FAIL: HTTP ${masterResp.code()}")
                }

                val mapResp = withContext(Dispatchers.IO) { api.getRfidMapping() }
                if (mapResp.isSuccessful) {
                    epcToItemCode.clear()
                    mapResp.body()?.data.orEmpty().forEach { m ->
                        epcToItemCode[m.epc] = m.item_code
                    }
                    isMappingLoaded = true
                    appendLog("SYNC OK: ${epcToItemCode.size} EPC mapping loaded")
                    Toast.makeText(requireContext(), "Sync berhasil", Toast.LENGTH_SHORT).show()
                } else {
                    appendLog("SYNC MAPPING FAIL: HTTP ${mapResp.code()}")
                    Toast.makeText(requireContext(), "Sync mapping gagal", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                appendLog("SYNC ERROR: ${t.message}")
                Toast.makeText(requireContext(), "Error sync: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun loadMasterAndMapping() {
        uiScope.launch {
            try {
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

                    itemCodeToIndex.clear()
                    rows.forEachIndexed { idx, r -> itemCodeToIndex[r.itemCode] = idx }

                    productAdapter.setData(rows.toList())
                } else {
                    appendLog("GET MASTER FAIL: HTTP ${masterResp.code()}")
                }

                val mapResp = withContext(Dispatchers.IO) { api.getRfidMapping() }
                if (mapResp.isSuccessful) {
                    epcToItemCode.clear()
                    mapResp.body()?.data.orEmpty().forEach { m ->
                        epcToItemCode[m.epc] = m.item_code
                    }
                    isMappingLoaded = true
                    appendLog("Mapping loaded: ${epcToItemCode.size} EPC")
                } else {
                    appendLog("GET MAPPING FAIL: HTTP ${mapResp.code()}")
                }
            } catch (t: Throwable) {
                appendLog("Load master/mapping ERROR: ${t.message}")
            }
        }
    }

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
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}