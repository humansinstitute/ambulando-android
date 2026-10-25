package studio.otherstuff.ambulando.app

import android.os.Bundle
import androidx.compose.ui.graphics.luminance
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import studio.otherstuff.ambulando.app.ui.theme.AmbulandoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val environment = AppEnvironment.select(applicationContext, intent)
        val model = (application as AmbulandoApplication).model(environment)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { model.foreground(true) }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                model.foreground(false)
                if (!environment.isSample) studio.otherstuff.ambulando.app.background.BackgroundScheduler.requestFinishSending(applicationContext)
            }
        })
        setContent { AmbulandoTheme(model.appearance) {
            val light = studio.otherstuff.ambulando.app.ui.theme.Amb.colors.background.luminance() > 0.5f
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
            AmbulandoApp(model)
        } }
    }
}
