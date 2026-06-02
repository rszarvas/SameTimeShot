package com.sametime.shot.ui.gallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.sametime.shot.R
import com.sametime.shot.databinding.FragmentGalleryBinding

class GalleryFragment : Fragment() {

    private var _b: FragmentGalleryBinding? = null
    private val b get() = _b!!
    private val vm: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentGalleryBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = GalleryAdapter(
            onPhotoClick = { session, clickedIndex ->
                val uris  = ArrayList(session.photos.map { it.uri.toString() })
                val names = ArrayList(session.photos.map { it.filename })
                val bundle = Bundle().apply {
                    putStringArrayList(PhotoViewerFragment.ARG_URIS,  uris)
                    putStringArrayList(PhotoViewerFragment.ARG_NAMES, names)
                    putInt(PhotoViewerFragment.ARG_INDEX, clickedIndex)
                }
                findNavController().navigate(R.id.action_galleryFragment_to_photoViewerFragment, bundle)
            },
            onDeleteClick = { session ->
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle("Sorozat törlése")
                    .setMessage(
                        "Biztosan törli a(z) ${session.displayDate} sorozatot?\n" +
                        "(${session.photos.size} kép)"
                    )
                    .setPositiveButton("Törlés") { _, _ -> vm.deleteSession(session) }
                    .setNegativeButton("Mégse", null)
                    .show()

                // Fehér háttér, lekerekítés és sötétszürke szöveg
                dialog.window?.setBackgroundDrawableResource(R.drawable.bg_rounded_white)

                // DecorView-ben keresni az összes TextView-t
                val decorView = dialog.window?.decorView
                val textViews = mutableListOf<android.widget.TextView>()
                fun findTextViews(view: android.view.View) {
                    if (view is android.widget.TextView) {
                        textViews.add(view)
                    }
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) {
                            findTextViews(view.getChildAt(i))
                        }
                    }
                }
                if (decorView != null) {
                    findTextViews(decorView)
                    textViews.forEach { it.setTextColor(android.graphics.Color.parseColor("#333333")) }
                }
            }
        )

        b.rvSessions.layoutManager = LinearLayoutManager(requireContext())
        b.rvSessions.adapter = adapter

        // Fejléc: status bar magassága + alap padding, hogy a nyíl ne kerüljön a rendszerikonok alá
        ViewCompat.setOnApplyWindowInsetsListener(b.header) { v, insets ->
            val bars  = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePx = (20 * resources.displayMetrics.density).toInt()
            v.updatePadding(top = bars.top + basePx)
            insets
        }

        // RecyclerView alsó padding a navigation bar miatt
        ViewCompat.setOnApplyWindowInsetsListener(b.rvSessions) { v, insets ->
            val bars  = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = (16 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = bars.bottom + extra)
            insets
        }

        b.btnBack.setOnClickListener { findNavController().popBackStack() }

        vm.sessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            b.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        }

        vm.loadPhotos()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
