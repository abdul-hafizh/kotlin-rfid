package com.example.newrfid

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ProductAdapter(
    private val items: MutableList<ProductRow>
) : RecyclerView.Adapter<ProductAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNo: TextView = v.findViewById(R.id.tvNo)
        val tvItemCode: TextView = v.findViewById(R.id.tvItemCode)
        val tvItemName: TextView = v.findViewById(R.id.tvItemName)
        val tvEndStock: TextView = v.findViewById(R.id.tvEndStock)
        val tvScanQty: TextView = v.findViewById(R.id.tvScanQty)
        val tvDiff: TextView = v.findViewById(R.id.tvDiff)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]

        holder.tvNo.text = (position + 1).toString()
        holder.tvItemCode.text = it.itemCode
        holder.tvItemName.text = it.itemName
        holder.tvEndStock.text = it.endStock.toString()
        holder.tvScanQty.text = it.scanQty.toString()
        holder.tvDiff.text = it.diff().toString()
        holder.tvStatus.text = it.status()

        val mismatch = it.isMismatch()

        if (mismatch) {
            // ✅ baris merah + text putih
            holder.itemView.setBackgroundColor(Color.parseColor("#D32F2F"))
            setRowTextColor(holder, Color.WHITE)
        } else {
            // ✅ baris normal
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            setRowTextColor(holder, Color.BLACK)
        }
    }

    private fun setRowTextColor(holder: VH, color: Int) {
        holder.tvNo.setTextColor(color)
        holder.tvItemCode.setTextColor(color)
        holder.tvItemName.setTextColor(color)
        holder.tvEndStock.setTextColor(color)
        holder.tvScanQty.setTextColor(color)
        holder.tvDiff.setTextColor(color)
        holder.tvStatus.setTextColor(color)
    }

    override fun getItemCount(): Int = items.size

    fun setData(newItems: List<ProductRow>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateScanQtyAt(index: Int, newQty: Int) {
        if (index !in 0 until items.size) return
        items[index].scanQty = newQty
        notifyItemChanged(index)
    }

    fun getItems(): List<ProductRow> = items
}