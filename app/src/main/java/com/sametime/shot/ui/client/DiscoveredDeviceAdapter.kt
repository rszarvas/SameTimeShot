package com.sametime.shot.ui.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sametime.shot.databinding.ItemDiscoveredDeviceBinding

class DiscoveredDeviceAdapter(
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DiscoveredDeviceAdapter.VH>() {

    private val items = mutableListOf<BluetoothDevice>()

    @SuppressLint("NotifyDataSetChanged")
    fun setDevices(devices: List<BluetoothDevice>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    inner class VH(private val b: ItemDiscoveredDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice) {
            b.tvDeviceName.text = device.name ?: "Ismeretlen eszköz"
            b.tvDeviceAddress.text = device.address
            b.root.setOnClickListener { onDeviceClick(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDiscoveredDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
}
