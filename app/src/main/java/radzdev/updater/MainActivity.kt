package radzdev.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.radzdev.radzupdater.Updater

class MainActivity : ComponentActivity() {
    private lateinit var updater: Updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updater = Updater(
            this,
            "https://raw.githubusercontent.com/Radzdevteam/test/refs/heads/main/updatertest"
        )
        updater.checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        updater.checkForUpdates()
    }
}
