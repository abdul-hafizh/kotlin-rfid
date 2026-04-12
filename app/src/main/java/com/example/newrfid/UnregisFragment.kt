package com.example.newrfid

import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import kotlin.concurrent.thread

class UnregisFragment : Fragment(R.layout.fragment_unregis) {

    private val tagLog = "UNREG"
    private lateinit var btnLoopUnreg: Button
    private lateinit var btnCheckAllUnreg: Button
    private lateinit var btnUncheckAllUnreg: Button
    private lateinit var btnClearUnreg: Button
    private lateinit var btnSubmitUnreg: Button
    private lateinit var tvUnregCount: TextView
    private lateinit var rvUnregItems: RecyclerView
    private var lastToastTime = 0L
    private val TOAST_INTERVAL = 1500L

    private val itemCodeToName = HashMap<String, String>()
    private val seenEpc = HashSet<String>()

    private lateinit var unregAdapter: UnregItemAdapter

    private val api: ApiService by lazy { ApiClient.create() }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var reader: RFIDWithUHFUART? = null
    private var loopFlag = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnLoopUnreg = view.findViewById(R.id.btnLoopUnreg)
        btnCheckAllUnreg = view.findViewById(R.id.btnCheckAllUnreg)
        btnUncheckAllUnreg = view.findViewById(R.id.btnUncheckAllUnreg)
        btnClearUnreg = view.findViewById(R.id.btnClearUnreg)
        btnSubmitUnreg = view.findViewById(R.id.btnSubmitUnreg)
        tvUnregCount = view.findViewById(R.id.tvUnregCount)
        rvUnregItems = view.findViewById(R.id.rvUnregItems)

        setupRecycler()
        initReaderAsync()
        setupActions()
        loadMasterProducts()
    }

    private fun setupRecycler() {
        unregAdapter = UnregItemAdapter(mutableListOf()) {
            updateSelectedCount()
        }
        rvUnregItems.layoutManager = LinearLayoutManager(requireContext())
        rvUnregItems.adapter = unregAdapter
    }

    private fun setupActions() {
        btnLoopUnreg.setOnClickListener { toggleLoop() }
        btnCheckAllUnreg.setOnClickListener { unregAdapter.setAllSelected(true) }
        btnUncheckAllUnreg.setOnClickListener { unregAdapter.setAllSelected(false) }
        btnClearUnreg.setOnClickListener { clearAll() }
        btnSubmitUnreg.setOnClickListener { confirmBulkUnregister() }
    }

    private fun initReaderAsync() {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("init...")
            setCancelable(false)
            show()
        }

        thread {
            try {
                val inst = RFIDWithUHFUART.getInstance()
                if (inst == null) {
                    view?.post {
                        progress.dismiss()
                        Toast.makeText(requireContext(), "Reader tidak ditemukan", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                reader = inst
                val ok = reader?.init() == true

                view?.post {
                    progress.dismiss()
                    if (!ok) {
                        Toast.makeText(requireContext(), "Init reader gagal", Toast.LENGTH_SHORT).show()
                    } else {
                        reader?.setEPCMode()

                        try {
                            reader?.setPower(10) // (±10–50 cm)
                        } catch (e: Exception) {
                            Log.e("UNREG", "setPower error", e)
                        }
                    }
                }
            } catch (t: Throwable) {
                view?.post {
                    progress.dismiss()
                    Toast.makeText(requireContext(), "Init error: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleLoop() {
        if (reader == null) {
            Toast.makeText(requireContext(), "Reader belum init", Toast.LENGTH_SHORT).show()
            return
        }

        if (!loopFlag) {
            startLoop()
        } else {
            stopLoop()
        }
    }

    private fun startLoop() {
        try {
            seenEpc.clear()
            loopFlag = true
            btnLoopUnreg.text = "Stop Loop"

            reader?.setInventoryCallback(IUHFInventoryCallback { info ->
                view?.post {
                    if (!loopFlag) return@post
                    if (info != null) {
                        val epc = (info.epc ?: "").trim()
                        if (epc.isBlank()) return@post

                        val isNew = seenEpc.add(epc)
                        if (!isNew) return@post

                        handleScannedEpc(epc)
                    }
                }
            })

            val ok = reader?.startInventoryTag() == true
            if (!ok) {
                loopFlag = false
                btnLoopUnreg.text = "Start Loop"
                Toast.makeText(requireContext(), "Start loop gagal", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            loopFlag = false
            btnLoopUnreg.text = "Start Loop"
            Toast.makeText(requireContext(), "Loop error: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLoop() {
        if (!loopFlag) return

        loopFlag = false
        btnLoopUnreg.text = "Start Loop"

        try {
            reader?.stopInventory()
            reader?.setInventoryCallback(null)
            Log.d(tagLog, "Loop stop: true")
        } catch (t: Throwable) {
            Log.e(tagLog, "stopLoop error", t)
        }
    }

    private fun loadMasterProducts() {
        uiScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.getMasterProducts()
                }

                if (resp.isSuccessful) {
                    itemCodeToName.clear()

                    resp.body()?.data?.forEach {
                        itemCodeToName[it.itemcode] = it.itemname
                    }

                    Log.d(tagLog, "Master loaded: ${itemCodeToName.size}")
                } else {
                    Log.e(tagLog, "Load master gagal: ${resp.code()}")
                }
            } catch (t: Throwable) {
                Log.e(tagLog, "Error load master", t)
            }
        }
    }

    private fun handleScannedEpc(epc: String) {
        if (epc.isBlank()) return

        val allExists = getCurrentItems().any { it.epc == epc }
        if (allExists) {
            return
        }

        uiScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.getActiveByEpc(ActiveByEpcRequest(epc = epc))
                }

                if (!loopFlag) return@launch

                if (response.isSuccessful && response.body()?.success == true) {
                    val data = response.body()?.data
                    if (data != null) {
                        val itemName = itemCodeToName[data.item_code] ?: "-"
                        val added = unregAdapter.addIfNotExists(
                            UnregItem(
                                epc = data.epc,
                                itemCode = data.item_code,
                                itemName = itemName,
                                isSelected = true
                            )
                        )

                        if (added) {
                            Log.d(tagLog, "\"Item ditemukan: \${data.item_code}")
                        }
                    }
                } else {
                    Log.d(tagLog, "EPC tidak aktif / tidak ditemukan: $epc")
                }
            } catch (t: Throwable) {
                Log.e(tagLog, "lookup error", t)
            }
        }
    }

    private fun confirmBulkUnregister() {
        val selected = unregAdapter.getSelectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), "Pilih minimal 1 item", Toast.LENGTH_SHORT).show()
            return
        }

        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum unregister", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Konfirmasi Unregister")
            .setMessage("Yakin ingin unregister ${selected.size} tag?")
            .setPositiveButton("Ya") { _, _ -> submitBulkUnregister(selected) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun submitBulkUnregister(selected: List<UnregItem>) {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Proses unregister...")
            setCancelable(false)
            show()
        }

        val deviceId = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val request = BulkUnregisterRequest(
            device_id = deviceId,
            data = selected.map { BulkUnregisterItem(epc = it.epc) }
        )

        uiScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.bulkUnregister(request)
                }

                progress.dismiss()

                if (response.isSuccessful && response.body()?.success == true) {
                    val body = response.body()!!
                    Toast.makeText(
                        requireContext(),
                        "Berhasil unregister ${body.total_unregistered ?: 0} tag",
                        Toast.LENGTH_LONG
                    ).show()

                    clearAll()
                } else {
                    Toast.makeText(
                        requireContext(),
                        response.body()?.message ?: "Gagal unregister",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                progress.dismiss()
                Toast.makeText(requireContext(), "Error: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearAll() {
        stopLoop()
        seenEpc.clear()
        unregAdapter.clearAll()
    }

    private fun updateSelectedCount() {
        val total = unregAdapter.getSelectedItems().size
        tvUnregCount.text = "Dipilih: $total tag"
    }

    private fun getCurrentItems(): List<UnregItem> {
        return unregAdapter.getItems()
    }

    override fun onPause() {
        super.onPause()
        stopLoop()
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