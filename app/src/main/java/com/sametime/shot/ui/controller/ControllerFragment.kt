package com.sametime.shot.ui.controller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.sametime.shot.MainActivity
import com.sametime.shot.R
import com.sametime.shot.camera.CameraHelper
import com.sametime.shot.databinding.FragmentControllerBinding
import com.sametime.shot.model.ConnectedDevice
import com.sametime.shot.model.TransferStatus
import com.sametime.shot.model.WifiClient
import com.sametime.shot.utils.ToastUtils
import kotlinx.coroutines.launch

class ControllerFragment : Fragment() {

    private var _b: FragmentControllerBinding? = null
    private val b get() = _b!!
    private val vm: ControllerViewModel by viewModels()
    private lateinit var cameraHelper: CameraHelper
    private val preLockAdapter = DeviceAdapter()
    private val postLockAdapter = DeviceAdapter()
    private val connectedDevicesAdapter = ConnectedDevicesAdapter()
    private var serverStartAttempted = false

    companion object {
        private const val TAG = "STS-Controller"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentControllerBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraHelper = CameraHelper(requireContext())

        b.rvConnectedDevices.layoutManager = LinearLayoutManager(requireContext())
        b.rvConnectedDevices.adapter = connectedDevicesAdapter
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

        b.btnStartHotspot.setOnClickListener {
            vm.openHotspotSettings()
        }

        b.btnLock.setOnClickListener {
            vm.lockConnections()
        }

        b.btnShoot.setOnClickListener {
            val filename = vm.prepareShoot()
            val shootTime = System.currentTimeMillis() + 1000L
            Log.d(TAG, "Fénykép: $filename, shootTime=$shootTime (postAtTime + Choreographer sync)")

            scheduleShootWithChoreographer(shootTime, filename)
        }

        b.btnGallery.setOnClickListener {
            findNavController().navigate(R.id.action_controllerFragment_to_galleryFragment)
        }
        b.btnExit.setOnClickListener { vm.disconnectAll() }

        vm.hotspotActive.observe(viewLifecycleOwner) { active ->
            if (active) {
                b.layoutHotspotActive.visibility = View.VISIBLE
                b.btnStartHotspot.visibility = View.GONE
                b.tvInstructions.visibility = View.GONE  // Instrukciós szöveg rejtése
            } else {
                b.layoutHotspotActive.visibility = View.GONE
                b.btnStartHotspot.visibility = View.VISIBLE
                b.tvInstructions.visibility = View.VISIBLE  // Instrukciós szöveg megjelenítése
            }
        }

        vm.devices.observe(viewLifecycleOwner) { devices ->
            preLockAdapter.submitList(devices)
            postLockAdapter.submitList(devices)
            // WiFi klienseket is hozzáadjuk
            val wifiClients = vm.wifiClients.value.orEmpty()
            val totalClients = devices.size + wifiClients.size
            b.tvDeviceCount.text = "$totalClients telefon csatlakozva"
            b.btnLock.isEnabled = totalClients > 0
            updateConnectedDevicesList(devices, wifiClients)
        }

        vm.wifiClients.observe(viewLifecycleOwner) { wifiClients ->
            // Frissítjük a Bluetooth eszközöket is
            val devices = vm.devices.value.orEmpty()
            val totalClients = devices.size + wifiClients.size
            b.tvDeviceCount.text = "$totalClients telefon csatlakozva"
            // btnLock aktív, ha van legalább 1 kliens (Bluetooth vagy WiFi)
            b.btnLock.isEnabled = totalClients > 0
            updateConnectedDevicesList(devices, wifiClients)
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
            // Csak az overlay szövegét frissítjük (kamera nézet alatt)
            b.tvStatusOverlay.text = msg
        }

        vm.transferStates.observe(viewLifecycleOwner) { states ->
            preLockAdapter.transferStates = states
            postLockAdapter.transferStates = states
            connectedDevicesAdapter.transferStates = states

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
    }

    private fun updateConnectedDevicesList(
        devices: List<ConnectedDevice>,
        wifiClients: List<WifiClient> = emptyList()
    ) {
        val items = mutableListOf<ConnectedDevicesAdapter.DeviceItem>()

        // Bluetooth eszközök hozzáadása
        items.addAll(devices.map { ConnectedDevicesAdapter.DeviceItem.BluetoothItem(it) })

        // WiFi kliensek hozzáadása
        items.addAll(wifiClients.map { ConnectedDevicesAdapter.DeviceItem.WifiItem(it) })

        connectedDevicesAdapter.submitList(items)
    }

    override fun onResume() {
        super.onResume()
        if (!serverStartAttempted) {
            serverStartAttempted = true
            requestWiFiPermissionAndStartServer()
        }
    }

    private fun requestWiFiPermissionAndStartServer() {
        // Android 13+ szükséges az engedély
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "✓ NEARBY_WIFI_DEVICES engedély megadva")
                vm.startServer()
            } else {
                Log.d(TAG, "Engedély kérése: NEARBY_WIFI_DEVICES")
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            // Android 12 és alatta
            vm.startServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "✓ Engedély megadva, szerver indítása...")
                vm.startServer()
            } else {
                Log.e(TAG, "✗ Engedély megtagadva!")
                ToastUtils.showCustomToast(requireContext(), "WiFi engedély szükséges!", Toast.LENGTH_LONG)
            }
        }
    }


    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Busy-wait szinkronizáció az utolsó milliszekundumban
     * Az utolsó 20-30ms-ben aktívan várunk az exponálásra
     */
    private fun scheduleShootWithChoreographer(shootTime: Long, filename: String) {
        val now = System.currentTimeMillis()
        val delayMs = shootTime - now

        if (delayMs <= 0) {
            // Azonnal
            Log.d(TAG, "Exponálás azonnal")
            cameraHelper.takePhotoAndSave(filename) { success ->
                if (success) vm.onControllerPhotoSaved()
                else ToastUtils.showCustomToast(requireContext(), "Hiba a fényképezésnél!", Toast.LENGTH_SHORT)
            }
        } else if (delayMs <= 30) {
            // Nagyon rövid delay - Thread-el busy-wait az exponálásra
            Log.d(TAG, "Exponálás busy-wait: delayMs=$delayMs")
            Thread {
                val startNano = System.nanoTime()
                val delayNano = delayMs * 1_000_000L
                while (System.nanoTime() - startNano < delayNano) {
                    // Aktív várakozás
                }
                Log.d(TAG, "Busy-wait vége – exponálás now!")
                cameraHelper.takePhotoAndSave(filename) { success ->
                    if (success) vm.onControllerPhotoSaved()
                    else ToastUtils.showCustomToast(requireContext(), "Hiba a fényképezésnél!", Toast.LENGTH_SHORT)
                }
            }.start()
        } else if (delayMs <= 100) {
            // Rövid delay - postDelayed + busy-wait az utolsó 20ms-ben
            Log.d(TAG, "Exponálás postDelayed + busy-wait: delayMs=$delayMs")
            mainHandler.postDelayed({
                // Busy-wait az utolsó 20ms-ben
                val shootTimeMs = shootTime
                val remainMs = shootTimeMs - System.currentTimeMillis()
                if (remainMs > 0) {
                    val startNano = System.nanoTime()
                    val remainNano = remainMs * 1_000_000L
                    while (System.nanoTime() - startNano < remainNano) {
                        // Aktív várakozás
                    }
                }
                Log.d(TAG, "Busy-wait vége – exponálás now!")
                cameraHelper.takePhotoAndSave(filename) { success ->
                    if (success) vm.onControllerPhotoSaved()
                    else ToastUtils.showCustomToast(requireContext(), "Hiba a fényképezésnél!", Toast.LENGTH_SHORT)
                }
            }, (delayMs - 20).coerceAtLeast(0L))
        } else {
            // Hosszabb delay - postDelayed a shootTime előtt 20ms-el, majd busy-wait
            Log.d(TAG, "Exponálás postDelayed + busy-wait: delayMs=$delayMs")
            mainHandler.postDelayed({
                // Busy-wait az utolsó 20ms-ben
                val shootTimeMs = shootTime
                val startNano = System.nanoTime()
                val targetMs = shootTimeMs
                while (System.currentTimeMillis() < targetMs) {
                    // Aktív várakozás
                }
                Log.d(TAG, "Busy-wait vége – exponálás most!")
                cameraHelper.takePhotoAndSave(filename) { success ->
                    if (success) vm.onControllerPhotoSaved()
                    else ToastUtils.showCustomToast(requireContext(), "Hiba a fényképezésnél!", Toast.LENGTH_SHORT)
                }
            }, (delayMs - 20).coerceAtLeast(0L))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
        cameraHelper.shutdown()
        _b = null
    }
}
