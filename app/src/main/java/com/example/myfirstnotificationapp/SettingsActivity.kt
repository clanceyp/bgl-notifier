// SettingsActivity.kt
package com.example.myfirstnotificationapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.myfirstnotificationapp.ui.theme.MyFirstNotificationAppTheme
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import androidx.compose.material3.Switch // Import the Switch composable
import androidx.compose.foundation.selection.toggleable // For making the row clickable
import androidx.compose.ui.semantics.Role // For accessibility

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyFirstNotificationAppTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val dataStoreManager = remember { DataStoreManager(context) }

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var eventFrequency by remember { mutableStateOf(10) }
    var notificationsEnabled by remember { mutableStateOf(true) } // NEW STATE VARIABLE

    var expanded by remember { mutableStateOf(false) }

    val frequencyOptions = listOf(5, 10, 20, 30)

// Collect flows from DataStore
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.baseUrlFlow.collect { baseUrl = it }
    }
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.apiKeyFlow.collect { apiKey = it }
    }
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.eventFrequencyFlow.collect { eventFrequency = it }
    }
// NEW: Collect notificationsEnabled state
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.notificationsEnabledFlow.collect { notificationsEnabled = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (Domain Only)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth()
            )

            // NEW: Notifications Enable/Disable Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = notificationsEnabled,
                        onValueChange = { notificationsEnabled = it },
                        role = Role.Switch
                    )
                    .padding(vertical = 8.dp), // Add some padding for touch target
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable Notifications", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
            }

            // Frequency Dropdown - now conditionally enabled
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
                    enabled = notificationsEnabled // DISABLED WHEN NOTIFICATIONS ARE OFF
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
                            enabled = notificationsEnabled // DISABLED WHEN NOTIFICATIONS ARE OFF
                        )
                    }
                }
            }

            // Save Button
            Button(
                onClick = {
                    (context as? SettingsActivity)?.lifecycleScope?.launch {
                        dataStoreManager.saveBaseUrl(baseUrl)
                        dataStoreManager.saveApiKey(apiKey)
                        dataStoreManager.saveEventFrequency(eventFrequency)
                        dataStoreManager.saveNotificationsEnabled(notificationsEnabled) // NEW SAVE
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            // Close App Button
            Button(
                onClick = {
                    (context as? ComponentActivity)?.finishAffinity()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Close App")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MyFirstNotificationAppTheme {
        SettingsScreen()
    }
}