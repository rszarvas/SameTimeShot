package com.sametime.shot.utils

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.sametime.shot.R

object ToastUtils {
    fun showCustomToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast(context)
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.custom_toast, null)

        val textView = layout.findViewById<android.widget.TextView>(android.R.id.message)
        textView.text = message

        toast.view = layout
        toast.duration = duration
        toast.show()
    }
}
