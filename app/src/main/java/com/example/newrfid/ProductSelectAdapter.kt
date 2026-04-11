package com.example.newrfid

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProductSelectAdapter(
    private val items: MutableList<MasterProduct>,
    private val onClick: (MasterProduct) -> Unit
) : RecyclerView.Adapter<ProductSelectAdapter.VH>() {

    private var selectedPosition = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val tvItemCode: TextView = v.findViewById(R.id.tvItemCode)
        val tvItemName: TextView = v.findViewById(R.id.tvItemName)
        val tvEndStock: TextView = v.findViewById(R.id.tvEndStock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_select, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.tvNo.text = "${position + 1}."
        holder.tvItemCode.text = "Item Code: ${item.itemcode}"
        holder.tvItemName.text = "Item Name: ${item.itemname}"
        holder.tvEndStock.text = "End Stock: ${item.endstock ?: 0}"

        holder.itemView.setBackgroundColor(
            if (position == selectedPosition) Color.parseColor("#DCEBFF")
            else Color.WHITE
        )

        holder.itemView.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener

            val oldPosition = selectedPosition
            selectedPosition = currentPosition

            if (oldPosition != -1) notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)

            onClick(items[currentPosition])
        }
    }

    override fun getItemCount(): Int = items.size

    fun setData(newItems: List<MasterProduct>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getSelectedItem(): MasterProduct? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }
}