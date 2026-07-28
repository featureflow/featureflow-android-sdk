package io.featureflow.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.featureflow.android.FeatureflowClient
import io.featureflow.android.FeatureflowConfig
import io.featureflow.android.FeatureflowUser
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * On-device harness for the Featureflow Android SDK.
 *
 * Covers what the JVM harness in `../harness` cannot: SharedPreferences caching, foreground
 * refresh via `ProcessLifecycleOwner`, the polling loop, and the `features` StateFlow driving
 * recomposition.
 *
 * Put your client SDK key in `local.properties` (which is git-ignored):
 *
 *     featureflow.clientKey=sdk-js-env-xxxx
 *
 * Then run the app, flip a flag in the dashboard, and watch the list update without a restart —
 * that is the behaviour the previous React Native SDK lacked and the thing most worth verifying.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HarnessScreen()
                }
            }
        }
    }
}

@Composable
private fun HarnessScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var client by remember { mutableStateOf<FeatureflowClient?>(null) }
    var status by remember { mutableStateOf("Starting…") }
    var userId by remember { mutableStateOf("harness-user") }

    LaunchedEffect(Unit) {
        if (BuildConfig.FEATUREFLOW_CLIENT_KEY.isEmpty()) {
            status = "No key. Add featureflow.clientKey=sdk-js-env-… to local.properties."
            return@LaunchedEffect
        }
        val started = System.currentTimeMillis()
        val instance = FeatureflowClient.initialize(
            context = context.applicationContext,
            apiKey = BuildConfig.FEATUREFLOW_CLIENT_KEY,
            user = FeatureflowUser(userId),
            // 15s rather than the 60s default so a dashboard change shows up while you watch.
            config = FeatureflowConfig(
                baseUrl = BuildConfig.FEATUREFLOW_BASE_URL,
                pollingIntervalMillis = 15_000
            )
        )
        client = instance
        status = "Ready in ${System.currentTimeMillis() - started}ms · polling every 15s"
    }

    val current = client
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Text(status, modifier = Modifier.padding(top = 16.dp))
        }
        return
    }

    // The whole point: this recomposes when a rollout changes, with no restart.
    val features: State<Map<String, String>> = current.features.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Featureflow harness", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(
            "user: ${current.user.id}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = {
                scope.launch {
                    status = if (current.refresh()) "Refreshed" else "Refresh failed"
                }
            }) { Text("Refresh") }

            Button(onClick = {
                current.track("harness-goal", value = 49.95)
                status = "Goal queued — flushes within 30s or on background"
            }) { Text("Track goal") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                label = { Text("User id") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                scope.launch {
                    current.updateUser(FeatureflowUser(userId))
                    status = "Switched user — re-evaluated"
                }
            }) { Text("Switch") }
        }

        Text(
            "${features.value.size} features",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        if (features.value.isEmpty()) {
            Text(
                "No features. Check the key is for the right environment and that the project " +
                    "has features with 'in client API' enabled.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(features.value.keys.sorted()) { key ->
                val variant = features.value[key] ?: "off"
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(key, fontFamily = FontFamily.Monospace)
                        Text(
                            variant,
                            fontFamily = FontFamily.Monospace,
                            color = if (variant == "off") {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}
