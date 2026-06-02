package com.sametime.shot.ui.controller

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sametime.shot.databinding.ItemConnectedDeviceBinding
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferState
import com.sametime.shot.model.TransferStatus
import com.sametime.shot.model.WifiClient

/**
 * Adapter a csatlakoztatott WiFi kliensek és Bluetooth eszközök listázásához
 */
class ConnectedDevicesAdapter : ListAdapter<ConnectedDevicesAdapter.DeviceItem, ConnectedDevicesAdapter.ViewHolder>(DiffCallback()) {

    var transferStates: Map<String, TransferState> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    sealed class DeviceItem {
        abstract val name: String

        data class BluetoothItem(val device: ConnectedDevice) : DeviceItem() {
            override val name: String = device.name
        }

        data class WifiItem(val client: WifiClient) : DeviceItem() {
            override val name: String = client.name
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConnectedDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemConnectedDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DeviceItem) {
            val deviceName = when (item) {
                is DeviceItem.BluetoothItem -> {
                    item.device.type?.let { type ->
                        "${item.device.name} ($type)"
                    } ?: item.device.name
                }
                is DeviceItem.WifiItem -> {
                    item.client.type?.let { type ->
                        "${item.client.name} ($type)"
                    } ?: item.client.name
                }
            }

            binding.tvDeviceName.text = deviceName

            // Állapot és progress bar frissítése
            val transferState = transferStates[item.name]
            if (transferState != null) {
                val statusText = when (transferState.status) {
                    TransferStatus.IDLE -> "Várakozás..."
                    TransferStatus.SHOOTING -> "Fotózás..."
                    TransferStatus.TRANSFERRING -> "Küldés (${transferState.progress}%)"
                    TransferStatus.DONE -> "✓ Képe megérkezett"
                    TransferStatus.ERROR -> "✗ Küldési hiba"
                }
                binding.tvDeviceStatus.text = statusText
                binding.pbTransfer.progress = transferState.progress
            } else {
                binding.tvDeviceStatus.text = "Várakozás..."
                binding.pbTransfer.progress = 0
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DeviceItem>() {
        override fun areItemsTheSame(oldItem: DeviceItem, newItem: DeviceItem): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: DeviceItem, newItem: DeviceItem): Boolean {
            return oldItem == newItem
        }
    }
}
