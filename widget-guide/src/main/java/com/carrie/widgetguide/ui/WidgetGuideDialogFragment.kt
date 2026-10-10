package com.carrie.widgetguide.ui

import android.app.Dialog
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.carrie.widgetguide.R
import com.carrie.widgetguide.core.PendingShow
import com.carrie.widgetguide.databinding.DialogWidgetGuideBinding
import com.carrie.widgetguide.runtime.PresentationCallbacks

class WidgetGuideDialogFragment : DialogFragment() {
    internal var callbacks: PresentationCallbacks? = null
    internal var navigator: WidgetGuideNavigator? = null
    val presentationId: String get() = requireArguments().getString("id").orEmpty()
    private var reported = false
    private var closed = false
    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restored fragments have no live reservation owner. Suppress their window before it appears.
        // A fresh controller handles persisted frequency; it never restores incomplete behavior paths.
        if (callbacks == null) showsDialog = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogWidgetGuideBinding.inflate(layoutInflater)
        val args = requireArguments()
        binding.guideTitle.text = args.getString("title")
        binding.guideDescription.text = args.getString("description")
        binding.guidePreview.setImageResource(args.getInt("preview"))
        binding.guidePreview.contentDescription = args.getString("previewDescription")
        binding.addDesktop.setOnClickListener { navigate(args.getString("desktop").orEmpty()) }
        binding.addAssistant.setOnClickListener { navigate(args.getString("assistant").orEmpty()) }
        binding.closeGuide.setOnClickListener { dismiss() }
        return AlertDialog.Builder(requireContext()).setView(binding.root).create()
    }

    override fun onStart() {
        super.onStart()
        if (callbacks == null) { dismissAllowingStateLoss(); return }
        if (reported) return
        val decor = dialog?.window?.decorView ?: return
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!reported && decor.isShown && dialog?.isShowing == true) {
                    reported = true
                    callbacks?.onShown()
                    decor.post { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun navigate(link: String) {
        if (navigating) return
        navigating = true
        val opened = try { navigator?.open(link) == true } catch (_: Exception) { false }
        if (opened) dismissAllowingStateLoss() else {
            navigating = false
            Toast.makeText(requireContext(), R.string.guide_navigation_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (!closed) { closed = true; callbacks?.onClosed() }
        super.onDismiss(dialog)
    }

    companion object {
        fun create(request: PendingShow, content: GuideContent) = WidgetGuideDialogFragment().apply {
            arguments = bundleOf("id" to request.id, "title" to content.title,
                "description" to content.description, "preview" to content.previewRes,
                "previewDescription" to content.previewDescription,
                "desktop" to content.desktopDeeplink, "assistant" to content.assistantDeeplink)
        }
    }
}
