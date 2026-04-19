package com.example.newrfid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class UnregisteredTagAdapter(
    private val items: MutableList<UnregisteredTag>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<UnregisteredTagAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val cbSelect: CheckBox = v.findViewById(R.id.cbSelectTag)
        val tvEpc: TextView = v.findViewById(R.id.tvEpc)
        val tvTid: TextView = v.findViewById(R.id.tvTid)
        val tvReadAt: TextView = v.findViewById(R.id.tvReadAt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_unregistered_tag, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.tvNo.text = "${position + 1}."
        holder.tvEpc.text = item.epc
        holder.tvTid.text = "TID: ${item.tid ?: "-"}"
        holder.tvReadAt.text = "Read At: ${item.read_at ?: "-"}"

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

    fun setData(newItems: List<UnregisteredTag>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun setAllSelected(selected: Boolean) {
        items.forEach { it.isSelected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getSelectedItems(): List<UnregisteredTag> = items.filter { it.isSelected }

    fun getItems(): List<UnregisteredTag> {
        return items
    }
}