package app.nada.player

import android.os.Bundle
import android.content.Intent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.nada.player.ui.NadaApp
import app.nada.player.ui.NadaTheme
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
  private val model: MusicViewModel by viewModels()
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.getBooleanExtra(OPEN_UPDATES, false)) model.requestUpdatesScreen()

    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    setContent {
      NadaTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { NadaApp(model) } }
    }
  }
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.getBooleanExtra(OPEN_UPDATES, false)) model.requestUpdatesScreen()
  }
  override fun onStart() { super.onStart(); model.resumeSpotify(this) }
  override fun onStop() { model.suspendSpotify(); super.onStop() }
  companion object {
    private const val OPEN_UPDATES = "openUpdates"
    fun updatesIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
      .putExtra(OPEN_UPDATES, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
}
