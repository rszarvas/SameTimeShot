package com.sametime.shot.ui.start

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.sametime.shot.MainActivity
import com.sametime.shot.R
import com.sametime.shot.databinding.FragmentStartBinding

class StartFragment : Fragment() {

    private var _b: FragmentStartBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentStartBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Cím pozíciója: status bar magassága + alap margin
        ViewCompat.setOnApplyWindowInsetsListener(b.titleArea) { v, insets ->
            val bars  = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePx = (52 * resources.displayMetrics.density).toInt()
            v.updatePadding(top = bars.top + basePx / 4)
            (v.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
                ?.topMargin = bars.top + basePx / 4
            v.requestLayout()
            insets
        }

        // Vezérlő gomb - WiFi hotspot automatikusan indul
        b.btnController.setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_controllerFragment)
        }

        // Kliens gomb - WiFi hotspot keresése automatikusan indul
        b.btnClient.setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_clientFragment)
        }

        b.btnInfo.setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_infoFragment)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
