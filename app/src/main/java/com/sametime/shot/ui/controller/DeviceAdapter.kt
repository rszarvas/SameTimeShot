package com.sametime.shot.ui.controller

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sametime.shot.R
import com.sametime.shot.databinding.ItemDeviceBinding
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferState
import com.sametime.shot.model.TransferStatus

class DeviceAdapter : ListAdapter<ConnectedDevice, DeviceAdapter.VH>(Differ()) {

    var transferStates: Map<String, TransferState> = emptyMap()
        set(value) { field = value; notifyDataSetChanged() }

    inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(device: ConnectedDevice) {
            b.tvDeviceName.text = device.name
            val state = transferStates[device.name]
            val status = state?.status
            val progress = state?.progress ?: 0

            b.tvDeviceStatus.text = when (status) {
                TransferStatus.SHOOTING     -> "Fotózás..."
                TransferStatus.TRANSFERRING -> "Átvitel"
                TransferStatus.DONE         -> "Kész ✓"
                TransferStatus.ERROR        -> "Hiba ✗"
                else                        -> "Csatlakozva"
            }

            b.statusIndicator.setBackgroundResource(
                when (status) {
                    TransferStatus.DONE         -> R.drawable.indicator_done
                    TransferStatus.ERROR        -> R.drawable.indicator_error
                    TransferStatus.TRANSFERRING,
                    TransferStatus.SHOOTING     -> R.drawable.indicator_active
                    else                        -> R.drawable.indicator_idle
                }
            )

            // Progress bar: csak TRANSFERRING közben látszik
            val showProgress = status == TransferStatus.TRANSFERRING
            b.progressTransfer.visibility = if (showProgress) View.VISIBLE else View.GONE
            b.tvProgressPercent.visibility = if (showProgress) View.VISIBLE else View.GONE
            if (showProgress) {
                b.progressTransfer.progress = progress
                b.tvProgressPercent.text = "$progress%"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class Differ : DiffUtil.ItemCallback<ConnectedDevice>() {
        override fun areItemsTheSame(a: ConnectedDevice, b: ConnectedDevice) = a.name == b.name
        override fun areContentsTheSame(a: ConnectedDevice, b: ConnectedDevice) = a.name == b.name
    }
}
