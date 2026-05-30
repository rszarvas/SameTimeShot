package com.sametime.shot.ui.gallery

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.sametime.shot.databinding.FragmentPhotoViewerBinding

class PhotoViewerFragment : Fragment() {

    private var _b: FragmentPhotoViewerBinding? = null
    private val b get() = _b!!

    // A szessziós képek listája és az aktuális index lapozáshoz
    private var uris: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var currentIndex = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentPhotoViewerBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Argumentumok kiolvasása
        uris  = arguments?.getStringArrayList(ARG_URIS)  ?: emptyList()
        names = arguments?.getStringArrayList(ARG_NAMES) ?: emptyList()
        currentIndex = arguments?.getInt(ARG_INDEX, 0) ?: 0

        // Status bar padding
        ViewCompat.setOnApplyWindowInsetsListener(b.viewerTopBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + (12 * resources.displayMetrics.density).toInt())
            insets
        }

        b.btnViewerBack.setOnClickListener { findNavController().popBackStack() }

        // Képernyő érintésre felső sáv ki/be
        b.ivFullPhoto.setOnClickListener {
            val visible = b.viewerTopBar.visibility == View.VISIBLE
            b.viewerTopBar.visibility = if (visible) View.GONE else View.VISIBLE
            b.btnPrev.visibility = if (!visible && currentIndex > 0) View.VISIBLE else View.GONE
            b.btnNext.visibility = if (!visible && currentIndex < uris.lastIndex) View.VISIBLE else View.GONE
        }

        b.btnPrev.setOnClickListener {
            if (currentIndex > 0) { currentIndex--; showPhoto() }
        }
        b.btnNext.setOnClickListener {
            if (currentIndex < uris.lastIndex) { currentIndex++; showPhoto() }
        }

        showPhoto()
    }

    private fun showPhoto() {
        val uri = Uri.parse(uris.getOrNull(currentIndex) ?: return)
        Glide.with(this).load(uri).into(b.ivFullPhoto)
        b.tvViewerFilename.text = names.getOrElse(currentIndex) { "kép" }
        b.tvViewerIndex.text = "${currentIndex + 1} / ${uris.size}"
        b.btnPrev.visibility = if (currentIndex > 0) View.VISIBLE else View.GONE
        b.btnNext.visibility = if (currentIndex < uris.lastIndex) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        const val ARG_URIS  = "uris"
        const val ARG_NAMES = "names"
        const val ARG_INDEX = "index"
    }
}
