package dev.kirill.kandinsky.chat.support

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

object TestFixtures {
    val assets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().context.assets
}
