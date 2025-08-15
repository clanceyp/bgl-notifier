package com.example.myfirstnotificationapp

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState // Import this
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll // Import this
import androidx.compose.material3.* // Import all Material3 components
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// No more imports for ViewModel, AuthorizationService, DataStoreManager here.
// These are concerns of the Activity and ViewModel.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel, // Receive the ViewModel
    onLoginClick: (Intent) -> Unit, // Callback to launch the intent
    onCloseApp: () -> Unit // Callback to close the app
) {
    val context = LocalContext.current

// Observe states from ViewModel
    val dexcomLoginStatus by settingsViewModel.dexcomLoginStatus.collectAsState()
    val dexcomAccessToken by settingsViewModel.dexcomAccessToken.collectAsState()
    val isServiceConfigReady by settingsViewModel.isServiceConfigReady.collectAsState()

// Nightscout settings (local state for UI, initialized from ViewModel's StateFlows)
// These are now initialized from the ViewModel's StateFlows, but still mutable for user input
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var eventFrequency by remember { mutableStateOf(10) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var prioritiseNightscout by remember { mutableStateOf(true) }

    val useForegroundService by settingsViewModel.useForegroundService.collectAsState()

// Use LaunchedEffect to update local mutable states when ViewModel's StateFlows change
    LaunchedEffect(settingsViewModel.baseUrl) {
        settingsViewModel.baseUrl.collect { baseUrl = it }
    }
    LaunchedEffect(settingsViewModel.apiKey) {
        settingsViewModel.apiKey.collect { apiKey = it }
    }
    LaunchedEffect(settingsViewModel.eventFrequency) {
        settingsViewModel.eventFrequency.collect { eventFrequency = it }
    }
    LaunchedEffect(settingsViewModel.notificationsEnabled) {
        settingsViewModel.notificationsEnabled.collect { notificationsEnabled = it }
    }
    LaunchedEffect(settingsViewModel.prioritiseNightscout) {
        settingsViewModel.prioritiseNightscout.collect { prioritiseNightscout = it }
    }

    var expanded by remember { mutableStateOf(false) }
    val frequencyOptions = listOf(1, 3, 5, 10, 20, 30)
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // Nightscout Settings UI
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Your Nightscout URL") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Nightscout API Key") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Foreground Service (bad for battery life)")
                Switch(
                    checked = useForegroundService,
                    onCheckedChange = { newValue ->
                        settingsViewModel.setUseForegroundService(newValue)
                    }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = notificationsEnabled,
                        onValueChange = { notificationsEnabled = it },
                        role = Role.Switch
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable Notifications", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = prioritiseNightscout,
                        onValueChange = { prioritiseNightscout = it },
                        role = Role.Switch
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Prioritise Nightscout", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = prioritiseNightscout,
                    onCheckedChange = { prioritiseNightscout = it }
                )
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = eventFrequency.toString(),
                    onValueChange = { /* Read-only */ },
                    readOnly = true,
                    label = { Text("Event Frequency (minutes)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    enabled = notificationsEnabled
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    frequencyOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption.toString()) },
                            onClick = {
                                eventFrequency = selectionOption
                                expanded = false
                            },
                            enabled = notificationsEnabled
                        )
                    }
                }
            }

            // Dexcom Connection Section
            // HorizontalDivider(modifier = Modifier.fillMaxWidth().height(1.dp).padding(vertical = 8.dp))
            // Text(text = "Dexcom Connection", style = MaterialTheme.typography.titleMedium)
            // Text(text = dexcomLoginStatus, style = MaterialTheme.typography.bodyMedium)
            Log.d("SettingsScreen", "dexcomAccessToken : ${settingsViewModel.dexcomAccessToken}") // Access value directly for logging

            if (dexcomAccessToken == null) {
                val buttonText = if (isServiceConfigReady) {
                    "Login to Dexcom"
                } else {
                    "Connecting..."
                }
                Button(
                    onClick = {
                        val intent = settingsViewModel.performDexcomLogin()
                        intent?.let {
                            onLoginClick(it) // Use the callback
                        } ?: run {
                            Toast.makeText(context, "Error: Service configuration not ready.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = isServiceConfigReady, // Button is only enabled when config is ready
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text( buttonText )
                }
            } else {
                Button(
                    onClick = {
                        settingsViewModel.performDexcomLogout()
                        Log.d("SettingsScreen", "Local Dexcom logout initiated.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("$dexcomLoginStatus (Logout)")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp) // Adds space between the buttons
            ) {
                Button(
                    onClick = {
                        settingsViewModel.saveNightscoutSettings(
                            baseUrl,
                            apiKey,
                            eventFrequency,
                            notificationsEnabled
                        )
                        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f) // Takes 50% of the available width
                ) {
                    Text("Save")
                }

                Button(
                    onClick = {
                        settingsViewModel.performImmediateCheck()
                        Toast.makeText(context, "Checking for new glucose value...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f), // Takes the other 50% of the available width
                    enabled = notificationsEnabled
                ) {
                    Text("Check Now")
                }
            }


            // Close App Button
            Button(
                onClick = {
                    onCloseApp() // Use the callback
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Close")
            }
        }
    }
}
