package com.example.newrfid

import android.app.ProgressDialog
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class RegistrasiFragment : Fragment(R.layout.fragment_registrasi) {

    private val tagLog = "REGIS"

    private lateinit var rvProducts: RecyclerView
    private lateinit var rvUnregisteredTags: RecyclerView
    private lateinit var btnCheckAll: Button
    private lateinit var btnUncheckAll: Button
    private lateinit var btnRegisterSelected: Button
    private lateinit var btnLoopRegis: Button
    private lateinit var btnClearScanRegis: Button
    private lateinit var tvSelectedCount: TextView
    private lateinit var etNotesRegis: EditText
    private lateinit var spinnerRegisPower: Spinner

    private lateinit var productAdapter: ProductSelectAdapter
    private lateinit var tagAdapter: UnregisteredTagAdapter

    private var selectedProduct: MasterProduct? = null

    private val api by lazy { ApiClient.create() }

    private var reader: RFIDWithUHFUART? = null
    private var loopFlag = false
    private val seenEpc = HashSet<String>()

    private var regisPower = 1
    private var toneGenerator: ToneGenerator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvProducts = view.findViewById(R.id.rvProducts)
        rvUnregisteredTags = view.findViewById(R.id.rvUnregisteredTags)
        btnCheckAll = view.findViewById(R.id.btnCheckAll)
        btnUncheckAll = view.findViewById(R.id.btnUncheckAll)
        btnRegisterSelected = view.findViewById(R.id.btnRegisterSelected)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        etNotesRegis = view.findViewById(R.id.etNotesRegis)
        btnLoopRegis = view.findViewById(R.id.btnLoopRegis)
        btnClearScanRegis = view.findViewById(R.id.btnClearScanRegis)
        spinnerRegisPower = view.findViewById(R.id.spinnerRegisPower)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        setupProductRecycler()
        setupTagRecycler()
        setupPowerSpinner()
        setupActions()
        updateLoopButton()

        initReaderAsync()
        loadMasterProducts()
        loadUnregisteredTags()
    }

    private fun setupProductRecycler() {
        productAdapter = ProductSelectAdapter(mutableListOf()) { product ->
            selectedProduct = product
        }

        rvProducts.layoutManager = LinearLayoutManager(requireContext())
        rvProducts.adapter = productAdapter
    }

    private fun setupTagRecycler() {
        tagAdapter = UnregisteredTagAdapter(mutableListOf()) {
            updateSelectedCount()
        }

        rvUnregisteredTags.layoutManager = LinearLayoutManager(requireContext())
        rvUnregisteredTags.adapter = tagAdapter
    }

    private fun setupPowerSpinner() {
        val listPower = listOf("1", "2", "3")

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listPower
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRegisPower.adapter = adapter

        spinnerRegisPower.setSelection(0)

        spinnerRegisPower.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                regisPower = listPower[position].toInt()
                applyRegisPower()
                Log.d(tagLog, "Power regis diubah ke $regisPower")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupActions() {
        btnCheckAll.setOnClickListener {
            tagAdapter.setAllSelected(true)
        }

        btnUncheckAll.setOnClickListener {
            tagAdapter.setAllSelected(false)
        }

        btnLoopRegis.setOnClickListener {
            toggleLoop()
        }

        btnClearScanRegis.setOnClickListener {
            clearScannedTags()
        }

        btnRegisterSelected.setOnClickListener {
            val product = selectedProduct
            val selectedTags = tagAdapter.getSelectedItems()
            val notes = etNotesRegis.text.toString().trim()

            if (product == null) {
                Toast.makeText(requireContext(), "Pilih product terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selectedTags.isEmpty()) {
                Toast.makeText(requireContext(), "Pilih minimal 1 tag", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val request = RegisterTagRequest(
                device_id = getDeviceId(),
                data = selectedTags.map {
                    RegisterTagItem(
                        epc = it.epc,
                        item_code = product.itemcode,
                        notes = if (notes.isBlank()) null else notes
                    )
                }
            )

            submitRegistration(request)
        }
    }

    private fun updateSelectedCount() {
        val total = tagAdapter.getSelectedItems().size
        tvSelectedCount.text = "Dipilih: $total tag"
    }

    private fun loadMasterProducts() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = fetchMasterProductsBySource()
                if (response.isSuccessful && response.body()?.success == true) {
                    val products = response.body()?.data ?: emptyList()
                    productAdapter.setData(products)
                } else {
                    Toast.makeText(requireContext(), "Gagal memuat master product", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error product: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchMasterProductsBySource() =
        when (MasterSourceStore.load(requireContext())) {
            MasterSource.DEFAULT -> api.getMasterProductsDefault()
            MasterSource.IMPORT -> api.getMasterProductsImport(getDeviceId())
        }

    private fun loadUnregisteredTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = api.getUnregisteredTags()
                if (response.isSuccessful && response.body()?.success == true) {
                    val tags = response.body()?.data ?: emptyList()
                    tagAdapter.setData(tags)
                    updateSelectedCount()
                } else {
                    Toast.makeText(requireContext(), "Gagal memuat tag belum registrasi", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error tag: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
                        applyRegisPower()
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

    private fun applyRegisPower() {
        try {
            reader?.setPower(regisPower)
            Log.d(tagLog, "Power regis diset ke $regisPower")
        } catch (t: Throwable) {
            Log.e(tagLog, "Gagal set power regis", t)
        }
    }

    private fun updateLoopButton() {
        if (loopFlag) {
            btnLoopRegis.text = "Stop Loop"
            btnLoopRegis.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.red_500)
            )
        } else {
            btnLoopRegis.text = "Start Loop"
            btnLoopRegis.setBackgroundTintList(
                ContextCompat.getColorStateList(requireContext(), R.color.blue_500)
            )
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

        updateLoopButton()
    }

    private fun startLoop() {
        try {
            applyRegisPower()
            seenEpc.clear()
            loopFlag = true

            reader?.setInventoryCallback(IUHFInventoryCallback { info ->
                view?.post {
                    if (!loopFlag) return@post
                    if (info == null) return@post

                    val epc = (info.epc ?: "").trim()
                    if (epc.isBlank()) return@post

                    val isNew = seenEpc.add(epc)
                    if (!isNew) return@post

                    addScannedTagIfNotExists(epc)
                }
            })

            val ok = reader?.startInventoryTag() == true
            if (!ok) {
                loopFlag = false
                updateLoopButton()
                Toast.makeText(requireContext(), "Start loop gagal", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            loopFlag = false
            updateLoopButton()
            Toast.makeText(requireContext(), "Loop error: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLoop() {
        if (!loopFlag) return

        loopFlag = false
        try {
            reader?.stopInventory()
            reader?.setInventoryCallback(null)
            Log.d(tagLog, "Loop stop: true")
        } catch (t: Throwable) {
            Log.e(tagLog, "stopLoop error", t)
        }
    }

    private fun addScannedTagIfNotExists(epc: String) {
        val exists = tagAdapter.getItems().any { it.epc == epc }
        if (exists) return

        val currentItems = tagAdapter.getItems().toMutableList()
        currentItems.add(
            UnregisteredTag(
                epc = epc,
                isSelected = true
            )
        )
        tagAdapter.setData(currentItems)

        playUniqueEpcSound()
        updateSelectedCount()
        Log.d(tagLog, "Tag baru hasil scan ditambahkan: $epc")
    }

    private fun playUniqueEpcSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (t: Throwable) {
            Log.e(tagLog, "Gagal bunyikan beep", t)
        }
    }

    private fun clearScannedTags() {
        if (loopFlag) {
            Toast.makeText(requireContext(), "Stop loop dulu sebelum clear", Toast.LENGTH_SHORT).show()
            return
        }

        seenEpc.clear()
        loadUnregisteredTags()
        Toast.makeText(requireContext(), "Hasil scan dibersihkan", Toast.LENGTH_SHORT).show()
    }

    private fun submitRegistration(request: RegisterTagRequest) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnRegisterSelected.isEnabled = false

                val response = api.registerTags(request)

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!

                    if (body.success) {
                        val inserted = body.total_inserted ?: 0
                        val skipped = body.total_skipped ?: 0

                        Toast.makeText(
                            requireContext(),
                            "Berhasil register $inserted tag. Skipped: $skipped",
                            Toast.LENGTH_LONG
                        ).show()

                        etNotesRegis.setText("")
                        selectedProduct = null
                        seenEpc.clear()

                        loadUnregisteredTags()
                        loadMasterProducts()
                    } else {
                        Toast.makeText(requireContext(), body.message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Gagal register. Code: ${response.code()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error register: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                btnRegisterSelected.isEnabled = true
            }
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN_DEVICE"
    }

    override fun onResume() {
        super.onResume()
        applyRegisPower()
        loadMasterProducts()
        loadUnregisteredTags()
    }

    override fun onPause() {
        super.onPause()
        stopLoop()
        updateLoopButton()
    }

    override fun onDestroy() {
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