package com.example.newrfid

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var btnSync: Button
    private lateinit var btnChooseMasterSource: Button
    private lateinit var spinnerScanPower: Spinner

    private lateinit var tvDeviceIdValue: TextView
    private lateinit var btnCopyDeviceId: Button
    private lateinit var btnPushDeviceId: Button

    private var reader: RFIDWithUHFUART? = null
    private var loopFlag = false

    private val seenEpc = HashSet<String>()
    private var totalReads = 0

    private var scanPower = 10
    private var toneGenerator: ToneGenerator? = null

    private var selectedMasterSource: MasterSource = MasterSource.DEFAULT

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

        selectedMasterSource = MasterSourceStore.load(requireContext())

        tvInfo = view.findViewById(R.id.tvInfo)
        tvLog = view.findViewById(R.id.tvLog)
        tvLog.movementMethod = android.text.method.ScrollingMovementMethod()

        btnLoop = view.findViewById(R.id.btnLoop)
        btnSaveScan = view.findViewById(R.id.btnSaveScan)
        btnSync = view.findViewById(R.id.btnSync)
        btnChooseMasterSource = view.findViewById(R.id.btnChooseMasterSource)
        spinnerScanPower = view.findViewById(R.id.spinnerScanPower)
        tvDeviceIdValue = view.findViewById(R.id.tvDeviceIdValue)
        btnCopyDeviceId = view.findViewById(R.id.btnCopyDeviceId)
        btnPushDeviceId = view.findViewById(R.id.btnPushDeviceId)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        btnLoop.setOnClickListener {
            toggleLoop()
            updateLoopButton()
        }

        view.findViewById<Button>(R.id.btnClear).setOnClickListener { clearLog() }

        btnSaveScan.setOnClickListener {
            confirmSaveScanResult()
        }

        tvDeviceIdValue.text = getDeviceId()

        btnCopyDeviceId.setOnClickListener {
            copyDeviceId()
        }

        btnPushDeviceId.setOnClickListener {
            confirmPushDeviceId()
        }

        btnSync.setOnClickListener {
            syncMasterAndMapping()
        }

        updateMasterSourceButtonText()

        btnChooseMasterSource.setOnClickListener {
            chooseMasterSource()
        }

        setupPowerSpinner()

        rvProducts = view.findViewById(R.id.rvProducts)
        productAdapter = ProductAdapter(productRows)
        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        rvProducts.adapter = productAdapter

        showInfo("Belum init")
        checkPermissionThenInit()
        loadMasterAndMapping()
    }

    private fun setupPowerSpinner() {
        val listPower = listOf("10", "20", "30", "40", "50")

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listPower
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerScanPower.adapter = adapter

        spinnerScanPower.setSelection(0)

        spinnerScanPower.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                scanPower = listPower[position].toInt()
                applyScanPower()
                appendLog("Power scan diubah ke $scanPower")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun copyDeviceId() {
        try {
            val deviceId = getDeviceId()
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("device_id", deviceId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Device ID berhasil disalin", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), "Gagal menyalin Device ID", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun fetchMasterProductsBySource() =
        when (selectedMasterSource) {
            MasterSource.DEFAULT -> api.getMasterProductsDefault()
            MasterSource.IMPORT -> api.getMasterProductsImport(getDeviceId())
        }

    private fun updateMasterSourceButtonText() {
        val label = when (selectedMasterSource) {
            MasterSource.DEFAULT -> "Sumber Master: Produk Pusat"
            MasterSource.IMPORT -> "Sumber Master: Hasil Import"
        }
        btnChooseMasterSource.text = label
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN_DEVICE"
    }

    private fun chooseMasterSource() {
        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum ganti sumber master", Toast.LENGTH_SHORT).show()
            return
        }

        val items = arrayOf("Master Produk Biasa", "Master Produk Import")
        val checkedItem = if (selectedMasterSource == MasterSource.DEFAULT) 0 else 1

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Pilih Sumber Master Produk")
            .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                val newSource = if (which == 0) MasterSource.DEFAULT else MasterSource.IMPORT

                if (newSource != selectedMasterSource) {
                    selectedMasterSource = newSource
                    MasterSourceStore.save(requireContext(), newSource)
                    updateMasterSourceButtonText()
                    reloadMasterForScan()
                }

                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmPushDeviceId() {
        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum push device id.", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceId = getDeviceId()
        if (deviceId.isBlank() || deviceId == "UNKNOWN_DEVICE") {
            Toast.makeText(requireContext(), "Device ID tidak valid", Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Push Device ID")
            .setMessage("Kirim device id ini ke server?\n\n$deviceId")
            .setPositiveButton("Ya") { _, _ ->
                pushDeviceIdToServer()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pushDeviceIdToServer() {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Mengirim device id ke server...")
            setCancelable(false)
            show()
        }

        val deviceId = getDeviceId()
        val payload = DeviceIdRequest(device_id = deviceId)

        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.postDeviceId(payload)
                }

                progress.dismiss()

                if (resp.isSuccessful) {
                    val body = resp.body()
                    val msg = body?.message ?: "Device ID berhasil dikirim ke server"

                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    appendLog("PUSH DEVICE ID OK: $deviceId | $msg")
                } else {
                    val err = try { resp.errorBody()?.string() } catch (_: Throwable) { null }
                    Toast.makeText(requireContext(), "Gagal push device id: HTTP ${resp.code()}", Toast.LENGTH_LONG).show()
                    appendLog("PUSH DEVICE ID FAIL HTTP ${resp.code()}: $err")
                }
            } catch (t: Throwable) {
                progress.dismiss()
                Toast.makeText(requireContext(), "Error: ${t.message}", Toast.LENGTH_LONG).show()
                appendLog("PUSH DEVICE ID ERROR: ${t::class.java.simpleName}: ${t.message}")
            }
        }
    }

    private fun reloadMasterForScan() {
        clearScanStateOnly()

        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Memuat master produk...")
            setCancelable(false)
            show()
        }

        uiScope.launch {
            try {
                val masterResp = withContext(Dispatchers.IO) { fetchMasterProductsBySource() }

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
                    appendLog("Master produk dimuat dari sumber: ${selectedMasterSource.apiValue}")
                } else {
                    appendLog("GET MASTER FAIL: HTTP ${masterResp.code()}")
                }
            } catch (t: Throwable) {
                appendLog("Reload master ERROR: ${t.message}")
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun updateLoopButton() {
        if (loopFlag) {
            btnLoop.text = "Stop Loop"
            btnLoop.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.red_500)
            )
        } else {
            btnLoop.text = "Start Loop"
            btnLoop.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.blue_500)
            )
        }
    }

    private fun clearScanStateOnly() {
        seenEpc.clear()
        totalReads = 0
        seenTagMap.clear()
        unknownRegistered = 0

        val current = productAdapter.getItems().map { it.copy(scanQty = 0) }
        productAdapter.setData(current)

        updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
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

    private fun applyScanPower() {
        try {
            reader?.setPower(scanPower)
        } catch (t: Throwable) {
            appendLog("Gagal set power scan: ${t.message}")
        }
    }

    private fun playUniqueEpcSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (t: Throwable) {
            Log.e(tag, "Gagal bunyikan beep", t)
        }
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
                        applyScanPower()
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

    override fun onResume() {
        super.onResume()
        applyScanPower()
    }

    private fun toggleLoop() {
        if (reader == null) {
            appendLog("Belum init reader.")
            return
        }

        if (!loopFlag) {
            startLoop()
        } else {
            stopLoop()
        }

        updateLoopButton()
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
            applyScanPower()

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

        playUniqueEpcSound()

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

        val itemCode = epcToItemCode[epc]
        if (itemCode == null) {
            unknownRegistered += 1
            updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
            return
        }

        val idx = itemCodeToIndex[itemCode]
        if (idx == null) {
            updateInfo(if (loopFlag) "Loop: ON" else "Loop: OFF")
            return
        }

        val newQty = productAdapter.getItems()[idx].scanQty + 1
        productAdapter.updateScanQtyAt(idx, newQty)

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
        tvLog.append("$s\n")

        val scrollAmount = tvLog.layout?.getLineTop(tvLog.lineCount) ?: 0
        if (scrollAmount > tvLog.height) {
            tvLog.scrollTo(0, scrollAmount - tvLog.height)
        }

        Log.d(tag, s)
    }

    private fun updateInfo(status: String) {
        showInfo("$status | Unique EPC: ${seenEpc.size}")
    }

    private fun confirmSaveScanResult() {
        if (seenTagMap.isEmpty()) {
            Toast.makeText(requireContext(), "Belum ada hasil scan untuk disimpan.", Toast.LENGTH_SHORT).show()
            return
        }

        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum simpan hasil scan.", Toast.LENGTH_SHORT).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
                    }

                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
                val masterResp = withContext(Dispatchers.IO) { fetchMasterProductsBySource() }
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

                val mapResp = withContext(Dispatchers.IO) { api.getRfidMapping(getDeviceId()) }
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
                val masterResp = withContext(Dispatchers.IO) { fetchMasterProductsBySource() }
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

                val mapResp = withContext(Dispatchers.IO) { api.getRfidMapping(getDeviceId()) }
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
        updateLoopButton()
    }

    override fun onDestroy() {
        uiScope.cancel()
        try {
            stopLoop()
            reader?.free()
            reader = null
        } catch (_: Throwable) {
        }

        toneGenerator?.release()
        toneGenerator = null

        super.onDestroy()
    }
}