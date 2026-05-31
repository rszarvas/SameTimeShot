package com.sametime.shot.ui.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.sametime.shot.R
import com.sametime.shot.camera.CameraHelper
import com.sametime.shot.databinding.FragmentClientBinding

class ClientFragment : Fragment() {

    private var _b: FragmentClientBinding? = null
    private val b get() = _b!!
    private val vm: ClientViewModel by viewModels()
    private lateinit var cameraHelper: CameraHelper
    private lateinit var deviceAdapter: DiscoveredDeviceAdapter
    private var discoveryStartAttempted = false

    companion object {
        private const val TAG = "STS-Client"
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentClientBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraHelper = CameraHelper(requireContext())
        deviceAdapter = DiscoveredDeviceAdapter { device -> vm.connect(device) }
        b.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        b.rvDevices.adapter = deviceAdapter

        b.btnRefresh.setOnClickListener { vm.startDiscovery() }

        // Rendszer sávok a kamera nézetben
        ViewCompat.setOnApplyWindowInsetsListener(b.layoutCamera) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16 * resources.displayMetrics.density).toInt()
            b.clientTopOverlay.updatePadding(top = bars.top + pad)
            b.layoutSendProgress.updatePadding(bottom = bars.bottom + pad)
            insets
        }

        vm.discoveredDevices.observe(viewLifecycleOwner) { devices ->
            deviceAdapter.setDevices(devices)
            b.tvEmptyHint.visibility =
                if (devices.isEmpty() && vm.isDiscovering.value == false) View.VISIBLE else View.GONE
        }

        vm.isDiscovering.observe(viewLifecycleOwner) { d ->
            b.progressDiscovery.visibility = if (d) View.VISIBLE else View.GONE
            b.btnRefresh.isEnabled = !d
        }

        vm.isConnected.observe(viewLifecycleOwner) { connected ->
            if (connected) {
                b.layoutDiscovery.visibility = View.GONE
                b.layoutConnected.visibility = View.VISIBLE
                b.layoutWaiting.visibility = View.VISIBLE
                b.layoutCamera.visibility = View.GONE
            }
        }

        vm.myName.observe(viewLifecycleOwner) { name ->
            b.tvMyName.text = name               // nagy betűs várakozó képernyőn marad "telefon2"
            // tvClientName mostanában LinearLayout, így a child TextView-t kell frissíteni
            val badgeLayout = b.tvClientName
            val phoneTextView = badgeLayout.getChildAt(1) as? android.widget.TextView
            phoneTextView?.text = formatBadge(name)
        }

        vm.isLocked.observe(viewLifecycleOwner) { locked ->
            if (locked) {
                Log.d(TAG, "LOCK – kamera indítása")
                b.layoutWaiting.visibility = View.GONE
                b.layoutCamera.visibility = View.VISIBLE
                cameraHelper.startCamera(b.cameraPreview, viewLifecycleOwner)
                ViewCompat.requestApplyInsets(b.layoutCamera)
            }
        }

        vm.status.observe(viewLifecycleOwner) { b.tvStatus.text = it }

        // Küldési progress – progress bar frissítése
        vm.sendProgress.observe(viewLifecycleOwner) { progress ->
            when {
                progress == null || progress < 0 -> {
                    b.layoutSendProgress.visibility = View.GONE
                }
                progress >= 100 -> {
                    b.progressSend.progress = 100
                    b.tvSendPercent.text = "100%"
                    // 500ms után eltüntetjük
                    b.layoutSendProgress.postDelayed({
                        b.layoutSendProgress.visibility = View.GONE
                    }, 500)
                }
                else -> {
                    b.layoutSendProgress.visibility = View.VISIBLE
                    b.progressSend.progress = progress
                    b.tvSendPercent.text = "$progress%"
                }
            }
        }

        // SHOOT esemény
        vm.shootEvent.observe(viewLifecycleOwner) { event ->
            event ?: return@observe
            val (ts, counter) = event
            val myName = vm.myName.value ?: "ismeretlen"
            val filename = "sts_${ts}_${myName}_$counter.jpg"
            Log.d(TAG, "SHOOT → $filename")
            cameraHelper.takePhotoBytes { bytes ->
                // Főszálon fut (CameraHelper garantálja)
                if (bytes != null) vm.sendPhoto(filename, bytes)
                else Log.e(TAG, "Képkészítés sikertelen!")
                vm.onShootHandled()
            }
        }

        vm.navigateToStart.observe(viewLifecycleOwner) { navigate ->
            if (navigate) { vm.onNavigatedToStart(); findNavController().navigate(R.id.action_clientFragment_to_startFragment) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!discoveryStartAttempted) {
            discoveryStartAttempted = true
            requestWiFiPermissionAndStartDiscovery()
        }
    }

    private fun requestWiFiPermissionAndStartDiscovery() {
        // Android 13+ szükséges az engedély
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "✓ ACCESS_FINE_LOCATION engedély megadva")
                vm.startDiscovery()
            } else {
                Log.d(TAG, "Engedély kérése: ACCESS_FINE_LOCATION")
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            // Android 12 és alatta
            vm.startDiscovery()
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
                Log.d(TAG, "✓ Engedély megadva, keresés indítása...")
                vm.startDiscovery()
            } else {
                Log.e(TAG, "✗ Engedély megtagadva!")
                Toast.makeText(requireContext(), "WiFi engedély szükséges!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraHelper.shutdown()
        _b = null
    }

    /** "telefon2" → "Telefon 2",  "telefon12" → "Telefon 12" */
    private fun formatBadge(name: String): String {
        val num = name.removePrefix("telefon")
        return "Telefon $num"
    }
}
