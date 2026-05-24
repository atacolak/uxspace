package com.uxspace.apps

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Lists [InstalledApp]s as icon + label rows for the control panel's app picker. */
class AppListAdapter(
    context: Context,
    apps: List<InstalledApp>,
) : ArrayAdapter<InstalledApp>(context, 0, apps) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView as? LinearLayout ?: newRow(parent.context)
        val app = getItem(position) ?: return row
        (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
        (row.getChildAt(1) as TextView).text = app.label
        return row
    }

    private fun newRow(context: Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))

            addView(
                ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                },
            )
            addView(
                TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply { marginStart = dp(16) }
                    textSize = 16f
                    setTextColor(ROW_TEXT_COLOR)
                },
            )
        }
    }

    private companion object {
        const val ROW_TEXT_COLOR = 0xFFECEFF4.toInt()
    }
}
