package app.nada.player

import android.os.Bundle
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

    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
    setContent {
      NadaTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { NadaApp(model) } }
    }
  }
  override fun onStart() { super.onStart(); model.resumeSpotify(this) }
  override fun onStop() { model.suspendSpotify(); super.onStop() }
}
