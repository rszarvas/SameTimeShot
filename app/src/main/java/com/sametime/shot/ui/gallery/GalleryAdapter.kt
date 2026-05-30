package com.sametime.shot.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sametime.shot.databinding.ItemGallerySessionBinding
import com.sametime.shot.model.PhotoSession

class GalleryAdapter(
    private val onPhotoClick:  (session: PhotoSession, index: Int) -> Unit,
    private val onDeleteClick: (session: PhotoSession) -> Unit
) : ListAdapter<PhotoSession, GalleryAdapter.VH>(Differ()) {

    inner class VH(private val b: ItemGallerySessionBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(session: PhotoSession) {
            b.tvSessionDate.text = session.displayDate
            b.tvSessionId.text   = "sts_${session.sessionId}_*"
            b.tvPhotoCount.text  = "${session.photos.size} kép"

            fun loadThumb(iv: android.widget.ImageView, index: Int) {
                session.photos.getOrNull(index)?.uri?.let { uri ->
                    Glide.with(iv).load(uri).centerCrop().into(iv)
                }
            }

            loadThumb(b.ivThumb1, 0)
            b.ivThumb1.setOnClickListener { onPhotoClick(session, 0) }

            if (session.photos.getOrNull(1) != null) {
                b.ivThumb2.visibility = View.VISIBLE
                b.divider1.visibility = View.VISIBLE
                loadThumb(b.ivThumb2, 1)
                b.ivThumb2.setOnClickListener { onPhotoClick(session, 1) }
            } else {
                b.ivThumb2.visibility = View.GONE
                b.divider1.visibility = View.GONE
            }

            if (session.photos.getOrNull(2) != null) {
                b.ivThumb3.visibility = View.VISIBLE
                b.divider2.visibility = View.VISIBLE
                loadThumb(b.ivThumb3, 2)
                b.ivThumb3.setOnClickListener { onPhotoClick(session, 2) }
            } else {
                b.ivThumb3.visibility = View.GONE
                b.divider2.visibility = View.GONE
            }

            b.btnDelete.setOnClickListener { onDeleteClick(session) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGallerySessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class Differ : DiffUtil.ItemCallback<PhotoSession>() {
        override fun areItemsTheSame(a: PhotoSession, b: PhotoSession) = a.sessionId == b.sessionId
        override fun areContentsTheSame(a: PhotoSession, b: PhotoSession) = a == b
    }
}
