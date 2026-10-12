package app.truenasdock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenasdock.data.AuthMethod

@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TrueNAS Dock", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect to your TrueNAS server over HTTPS. Self-signed certificates are verified by fingerprint.",
            style = MaterialTheme.typography.bodyMedium,
        )

        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            label = { Text("Server URL (https://nas.local)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.authMethod == AuthMethod.API_KEY,
                onClick = { viewModel.onAuthMethodChange(AuthMethod.API_KEY) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("API key") },
            )
            SegmentedButton(
                selected = state.authMethod == AuthMethod.PASSWORD,
                onClick = { viewModel.onAuthMethodChange(AuthMethod.PASSWORD) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("Password") },
            )
        }

        OutlinedTextField(
            value = state.secret,
            onValueChange = viewModel::onSecretChange,
            label = { Text(if (state.authMethod == AuthMethod.API_KEY) "API key" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.redirectUrls.isNotEmpty()) {
            Text("Server requested redirect: ${state.redirectUrls.joinToString()}")
        }

        Button(
            onClick = viewModel::connectAndLogin,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            } else {
                Text("Connect")
            }
        }

        if (state.otpUsername != null) {
            Spacer(Modifier.height(4.dp))
            Text("One-time code required for ${state.otpUsername}")
            OutlinedTextField(
                value = state.otpCode,
                onValueChange = viewModel::onOtpCodeChange,
                label = { Text("OTP code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::submitOtp,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Verify code")
            }
        }
    }

    state.untrusted?.let { cert ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUntrusted,
            title = { Text("Trust this certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The server presented a certificate that is not trusted by the system.")
                    Text("Subject: ${cert.subject}")
                    Text("Issuer: ${cert.issuer}")
                    Text("SHA-256: ${cert.sha256Fingerprint}")
                    Text("Only trust this if you recognize the fingerprint (compare it in the TrueNAS UI).")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::trustAndRetry) { Text("Trust once & retry") } },
            dismissButton = { TextButton(onClick = viewModel::dismissUntrusted) { Text("Cancel") } },
        )
    }
}
