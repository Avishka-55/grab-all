package com.graball

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Shared dark GrabAll bottom-sheet helper used across Settings, Music Player,
 * Video Player, and Sleep Timer.
 *
 * Always calls [Dialog.setContentView] with explicit MATCH_PARENT width BEFORE
 * configuring window gravity/layout/decor padding so Android's PhoneWindow never
 * resets the sheet to a narrow WRAP_CONTENT vertical strip.
 */
object DarkBottomSheet {

    fun createDialog(context: Context): Dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    /**
     * Attaches [content] to [dialog] with explicit full-width sizing, clears any
     * system dialog decor padding, applies smooth slide-up animation, and shows it.
     * In landscape, caps width cleanly at 480dp centered at the bottom.
     */
    fun show(
        dialog: Dialog,
        content: View,
        onDismiss: (() -> Unit)? = null
    ) {
        val ctx = dialog.context
        val dm = ctx.resources.displayMetrics
        val dp = dm.density
        val isLandscape = dm.widthPixels > dm.heightPixels
        val targetWidth = if (isLandscape) {
            minOf(dm.widthPixels - (32 * dp).toInt(), (480 * dp).toInt())
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }

        content.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.decorView.setPadding(0, 0, 0, 0)
            win.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            win.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            win.setWindowAnimations(R.style.GrabAllBottomSheetAnim)
        }
        if (onDismiss != null) {
            dialog.setOnDismissListener { onDismiss() }
        }
        dialog.show()
    }

    fun createContainer(ctx: Context, horizontalPadDp: Int = 16): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18192B"))
                cornerRadii = floatArrayOf(22 * dp, 22 * dp, 22 * dp, 22 * dp, 0f, 0f, 0f, 0f)
            }
            setPadding(d(horizontalPadDp), d(10), d(horizontalPadDp), d(18))
            // Drag handle pill
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3A3D58"))
                    cornerRadius = 3 * dp
                }
                layoutParams = LinearLayout.LayoutParams(d(38), d(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = d(12)
                }
            })
        }
    }

    fun createHeader(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        badge: String? = null,
        onClose: () -> Unit
    ): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val orange = ContextCompat.getColor(ctx, R.color.graball_orange)

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(12) }
        }
        val leftCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = d(8)
            }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = title
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!badge.isNullOrBlank()) {
            titleRow.addView(TextView(ctx).apply {
                text = badge
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(orange)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2B1E1B"))
                    cornerRadius = 10 * dp
                }
                setPadding(d(8), d(2), d(8), d(2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = d(8) }
            })
        }
        leftCol.addView(titleRow)

        if (!subtitle.isNullOrBlank()) {
            leftCol.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#9DA2BD"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = d(2) }
            })
        }
        header.addView(leftCol)
        header.addView(createCloseButton(ctx, onClose))
        return header
    }

    fun createCloseButton(ctx: Context, onClick: () -> Unit): TextView {
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        return TextView(ctx).apply {
            text = "✕"
            textSize = 14.5f
            setTextColor(Color.parseColor("#B0B4CC"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#24263B"))
                cornerRadius = 16 * dp
            }
            layoutParams = LinearLayout.LayoutParams(d(32), d(32))
            setOnClickListener { onClick() }
        }
    }

    fun createOptionRow(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        badge: String? = null,
        selected: Boolean = false,
        trailingText: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val dp = ctx.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val orange = ContextCompat.getColor(ctx, R.color.graball_orange)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(14), d(11), d(14), d(11))
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(if (selected) Color.parseColor("#2B1E1B") else Color.parseColor("#202236"))
                if (selected) setStroke(d(1), Color.parseColor("#66FF6B35"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = d(6) }
            setOnClickListener { onClick() }
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = d(8)
            }
        }
        val titleLine = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLine.addView(TextView(ctx).apply {
            text = title
            textSize = 14f
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (selected) Color.WHITE else Color.parseColor("#E2E5F2"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!badge.isNullOrBlank()) {
            titleLine.addView(TextView(ctx).apply {
                text = badge
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else orange)
                background = GradientDrawable().apply {
                    setColor(if (selected) orange else Color.parseColor("#33FF6B35"))
                    cornerRadius = 6 * dp
                }
                setPadding(d(6), d(1), d(6), d(1))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = d(7) }
            })
        }
        textCol.addView(titleLine)

        if (!subtitle.isNullOrBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(if (selected) Color.parseColor("#E3B4A4") else Color.parseColor("#8F95B2"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = d(1) }
            })
        }
        row.addView(textCol)

        if (selected) {
            row.addView(TextView(ctx).apply {
                text = "✓"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(orange)
            })
        } else if (!trailingText.isNullOrBlank()) {
            row.addView(TextView(ctx).apply {
                text = trailingText
                textSize = 15f
                setTextColor(Color.parseColor("#9DA2BD"))
            })
        }
        return row
    }

    /** Wraps a list container in a ScrollView capped neatly for both portrait and landscape. */
    fun wrapScrollable(
        ctx: Context,
        child: View,
        maxHeightFraction: Float = 0.52f
    ): ScrollView {
        val maxPx = (ctx.resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        return ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(
                child,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (height > maxPx) {
                    val lp = layoutParams
                    if (lp.height != maxPx) {
                        lp.height = maxPx
                        layoutParams = lp
                    }
                }
            }
        }
    }
}
