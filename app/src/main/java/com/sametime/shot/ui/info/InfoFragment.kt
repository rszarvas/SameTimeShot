package com.sametime.shot.ui.info

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.sametime.shot.databinding.FragmentInfoBinding

class InfoFragment : Fragment() {

    private var _b: FragmentInfoBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentInfoBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnInfoBack.setOnClickListener {
            findNavController().popBackStack()
        }

        b.tvContactEmail.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:szarvasrichard@gmail.com")
            }
            startActivity(intent)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
