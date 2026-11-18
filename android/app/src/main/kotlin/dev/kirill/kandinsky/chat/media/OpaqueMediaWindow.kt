package dev.kirill.kandinsky.chat.media

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager

// Кнопки отдельно учитывают безопасные отступы, поэтому при движении медиа под ними не появляются
// светлые
// края.
internal fun Window.prepareOpaqueMedia() {
    setBackgroundDrawable(ColorDrawable(Color.BLACK))
    clearFlags(
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
    )
    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    attributes =
        attributes.apply {
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    @Suppress("DEPRECATION")
    decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
    @Suppress("DEPRECATION")
    statusBarColor = Color.BLACK
    @Suppress("DEPRECATION")
    navigationBarColor = Color.BLACK
    if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
}
