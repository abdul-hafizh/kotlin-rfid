package com.example.newrfid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class UnregItemAdapter(
    private val items: MutableList<UnregItem>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<UnregItemAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val cbSelect: CheckBox = v.findViewById(R.id.cbSelectItem)
        val tvItemCode: TextView = v.findViewById(R.id.tvItemCode)
        val tvItemName: TextView = v.findViewById(R.id.tvItemName)
        val tvEpc: TextView = v.findViewById(R.id.tvEpc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_unreg_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.tvNo.text = "${position + 1}."
        holder.tvItemCode.text = "Item Code: ${item.itemCode}"
        holder.tvItemName.text = "Item Name: ${item.itemName}"
        holder.tvEpc.text = "EPC: ${item.epc}"

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = item.isSelected

        holder.cbSelect.setOnCheckedChangeListener { _, checked ->
            item.isSelected = checked
            onSelectionChanged()
        }

        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            notifyItemChanged(position)
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    fun setData(newItems: List<UnregItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun addIfNotExists(newItem: UnregItem): Boolean {
        val exists = items.any { it.epc == newItem.epc }
        if (exists) return false
        items.add(newItem)
        notifyItemInserted(items.lastIndex)
        onSelectionChanged()
        return true
    }

    fun clearAll() {
        items.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getItems(): List<UnregItem> = items

    fun getSelectedItems(): List<UnregItem> = items.filter { it.isSelected }

    fun setAllSelected(selected: Boolean) {
        items.forEach { it.isSelected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }
}