package com.example.newrfid

import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class RegistrasiFragment : Fragment() {

    private lateinit var rvProducts: RecyclerView
    private lateinit var rvUnregisteredTags: RecyclerView
    private lateinit var btnCheckAll: Button
    private lateinit var btnUncheckAll: Button
    private lateinit var btnRegisterSelected: Button
    private lateinit var tvSelectedCount: TextView
    private lateinit var etNotesRegis: EditText

    private lateinit var btnSyncRegis: Button
    private lateinit var productAdapter: ProductSelectAdapter
    private lateinit var tagAdapter: UnregisteredTagAdapter

    private var selectedProduct: MasterProduct? = null

    private val api by lazy { ApiClient.create() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_registrasi, container, false)

        rvProducts = view.findViewById(R.id.rvProducts)
        rvUnregisteredTags = view.findViewById(R.id.rvUnregisteredTags)
        btnCheckAll = view.findViewById(R.id.btnCheckAll)
        btnUncheckAll = view.findViewById(R.id.btnUncheckAll)
        btnRegisterSelected = view.findViewById(R.id.btnRegisterSelected)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        etNotesRegis = view.findViewById(R.id.etNotesRegis)
        btnSyncRegis = view.findViewById(R.id.btnSyncRegis)

        setupProductRecycler()
        setupTagRecycler()
        setupActions()

        loadMasterProducts()
        loadUnregisteredTags()

        return view
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

    private fun setupActions() {
        btnCheckAll.setOnClickListener {
            tagAdapter.setAllSelected(true)
        }

        btnUncheckAll.setOnClickListener {
            tagAdapter.setAllSelected(false)
        }

        btnSyncRegis.setOnClickListener {
            syncRegistrasiData()
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
            MasterSource.IMPORT -> api.getMasterProductsImport()
        }

    private fun syncRegistrasiData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnSyncRegis.isEnabled = false
                btnRegisterSelected.isEnabled = false

                val productResponse = fetchMasterProductsBySource()
                if (productResponse.isSuccessful && productResponse.body()?.success == true) {
                    val products = productResponse.body()?.data ?: emptyList()
                    productAdapter.setData(products)
                }

                val tagResponse = api.getUnregisteredTags()
                if (tagResponse.isSuccessful && tagResponse.body()?.success == true) {
                    val tags = tagResponse.body()?.data ?: emptyList()
                    tagAdapter.setData(tags)
                }

                updateSelectedCount()

                Toast.makeText(
                    requireContext(),
                    "Data registrasi berhasil disinkronkan",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Gagal sync: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                btnSyncRegis.isEnabled = true
                btnRegisterSelected.isEnabled = true
            }
        }
    }

    private fun loadUnregisteredTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = api.getUnregisteredTags()
                if (response.isSuccessful && response.body()?.success == true) {
                    val tags = response.body()?.data ?: emptyList()
                    tagAdapter.setData(tags)
                } else {
                    Toast.makeText(requireContext(), "Gagal memuat tag belum registrasi", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error tag: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadMasterProducts()
        loadUnregisteredTags()
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

                        loadUnregisteredTags()
                        loadMasterProducts()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            body.message,
                            Toast.LENGTH_LONG
                        ).show()
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
}