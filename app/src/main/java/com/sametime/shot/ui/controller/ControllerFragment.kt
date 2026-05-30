package com.sametime.shot.ui.controller

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.sametime.shot.MainActivity
import com.sametime.shot.R
import com.sametime.shot.camera.CameraHelper
import com.sametime.shot.databinding.FragmentControllerBinding
import com.sametime.shot.model.TransferStatus

class ControllerFragment : Fragment() {

    private var _b: FragmentControllerBinding? = null
    private val b get() = _b!!
    private val vm: ControllerViewModel by viewModels()
    private lateinit var cameraHelper: CameraHelper
    private val preLockAdapter = DeviceAdapter()
    private val postLockAdapter = DeviceAdapter()

    companion object { private const val TAG = "STS-Controller" }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentControllerBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraHelper = CameraHelper(requireContext())

        b.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        b.rvDevices.adapter = preLockAdapter
        b.rvDevicesOverlay.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.rvDevicesOverlay.adapter = postLockAdapter

        // ---- INSETS: pre-lock képernyő ----
        // A "Kapcsolatok lezárása" gomb ne kerüljön a navigációs sáv alá
        ViewCompat.setOnApplyWindowInsetsListener(b.layoutPreLock) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePx = (24 * resources.displayMetrics.density).toInt()
            b.layoutPreLock.updatePadding(
                left   = basePx,
                top    = basePx,
                right  = basePx,
                bottom = bars.bottom + basePx
            )
            insets
        }

        // ---- INSETS: post-lock kamera nézet ----
        ViewCompat.setOnApplyWindowInsetsListener(b.layoutPostLock) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padPx = (16 * resources.displayMetrics.density).toInt()
            b.topOverlay.updatePadding(top = bars.top + padPx)
            b.camHudBottom.updatePadding(bottom = bars.bottom + padPx)
            insets
        }

        b.btnLock.setOnClickListener {
            if (preLockAdapter.currentList.isEmpty()) {
                Toast.makeText(requireContext(), "Nincs csatlakozott telefon!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.lockConnections()
        }

        b.btnShoot.setOnClickListener {
            val filename = vm.prepareShoot()
            Log.d(TAG, "Fénykép: $filename")
            cameraHelper.takePhotoAndSave(filename) { success ->
                if (success) vm.onControllerPhotoSaved()
                else Toast.makeText(requireContext(), "Hiba a fényképezésnél!", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnGallery.setOnClickListener {
            findNavController().navigate(R.id.action_controllerFragment_to_galleryFragment)
        }
        b.btnExit.setOnClickListener { vm.disconnectAll() }

        vm.devices.observe(viewLifecycleOwner) { devices ->
            preLockAdapter.submitList(devices)
            postLockAdapter.submitList(devices)
            b.tvDeviceCount.text = "${devices.size} / 7 telefon csatlakozva"
            b.btnLock.isEnabled = devices.isNotEmpty()
        }

        vm.isLocked.observe(viewLifecycleOwner) { locked ->
            if (locked) {
                b.layoutPreLock.visibility = View.GONE
                b.layoutPostLock.visibility = View.VISIBLE
                cameraHelper.startCamera(b.cameraPreview, viewLifecycleOwner)
                ViewCompat.requestApplyInsets(b.layoutPostLock)
            }
        }

        vm.status.observe(viewLifecycleOwner) { msg ->
            b.tvStatus.text = msg
            b.tvStatusOverlay.text = msg
        }

        vm.transferStates.observe(viewLifecycleOwner) { states ->
            preLockAdapter.transferStates = states
            postLockAdapter.transferStates = states

            // Amíg bármelyik eszköz fotóját fogadja, az exponáló gomb inaktív
            val receiving = states.values.any {
                it.status == TransferStatus.SHOOTING || it.status == TransferStatus.TRANSFERRING
            }
            b.btnShoot.isEnabled = !receiving
            b.btnShoot.alpha = if (receiving) 0.35f else 1.0f
            b.layoutTransferWarning.visibility = if (receiving) View.VISIBLE else View.GONE
        }

        vm.navigateToStart.observe(viewLifecycleOwner) { navigate ->
            if (navigate) {
                vm.onNavigatedToStart()
                findNavController().navigate(R.id.action_controllerFragment_to_startFragment)
            }
        }

        vm.startServer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraHelper.shutdown()
        _b = null
    }
}
