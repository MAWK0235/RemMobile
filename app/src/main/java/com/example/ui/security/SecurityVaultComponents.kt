package com.example.ui.security

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.ConnectionProfileEntity
import com.example.security.BiometricCapabilityStatus
import com.example.security.PinVerificationResult
import com.example.security.SecurityAuditCheckItem
import com.example.security.SecuritySelfTestResult
import com.example.security.SecurityVaultPolicy

/**
 * Represents a sensitive action gated by Biometric or Master Vault PIN authentication.
 */
sealed class PendingSecurityChallenge {
    data class ConnectToSystem(val profile: ConnectionProfileEntity) : PendingSecurityChallenge()
    data class EditProtectedProfile(val profile: ConnectionProfileEntity) : PendingSecurityChallenge()
}

// ============================================================================
// 1. Full-Screen App Lock Overlay (Biometric + Cryptographic PIN Pad)
// ============================================================================

@Composable
fun VaultAppLockOverlay(
    policy: SecurityVaultPolicy,
    biometricStatus: BiometricCapabilityStatus,
    onVerifyPin: (String) -> PinVerificationResult,
    onTriggerBiometric: (Activity, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var enteredPin by remember { mutableStateOf("") }
    var feedbackMessage by remember {
        mutableStateOf("Vault locked • Authenticate with Biometrics or your 4–8 digit Vault PIN")
    }
    var isError by remember { mutableStateOf(false) }

    // Auto-trigger biometric prompt on lock screen appearance when enabled
    LaunchedEffect(Unit) {
        if (policy.biometricEnabled && biometricStatus.isAvailable && activity != null) {
            onTriggerBiometric(activity) { errMsg ->
                feedbackMessage = errMsg
                isError = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF07111C),
                        Color(0xFF0C1D2E),
                        Color(0xFF07111C)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("vault_app_lock_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF102235))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "RemMobile Encrypted Vault",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Text(
                    text = "RemMobile Security Vault",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Surface(
                    color = Color(0xFF153450),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.EnhancedEncryption,
                            contentDescription = null,
                            tint = Color(0xFF57E389),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "AES-256-GCM • PBKDF2-SHA256 Protected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF57E389),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = feedbackMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) Color(0xFFFF7B7B) else Color(0xFFB8C7D9),
                    textAlign = TextAlign.Center
                )

                // Masked PIN Dots Display
                PinDotsIndicator(
                    pinLength = enteredPin.length,
                    isError = isError
                )

                // Interactive Cryptographic Keypad
                PinNumericKeypad(
                    onDigitClick = { digit ->
                        if (enteredPin.length < 8) {
                            enteredPin += digit
                            isError = false
                        }
                    },
                    onBackspaceClick = {
                        if (enteredPin.isNotEmpty()) {
                            enteredPin = enteredPin.dropLast(1)
                            isError = false
                        }
                    },
                    onSubmitClick = {
                        when (val res = onVerifyPin(enteredPin)) {
                            is PinVerificationResult.Success -> {
                                enteredPin = ""
                                isError = false
                            }
                            is PinVerificationResult.InvalidPin -> {
                                enteredPin = ""
                                isError = true
                                feedbackMessage = "Incorrect PIN (${res.attemptsRemainingBeforeLockout} attempts left before cooldown)"
                            }
                            is PinVerificationResult.LockedOut -> {
                                enteredPin = ""
                                isError = true
                                feedbackMessage = "Too many failed attempts. Locked for ${res.remainingSeconds}s."
                            }
                        }
                    },
                    submitEnabled = enteredPin.length >= 4
                )

                if (policy.biometricEnabled && activity != null) {
                    OutlinedButton(
                        onClick = {
                            onTriggerBiometric(activity) { errMsg ->
                                feedbackMessage = errMsg
                                isError = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_screen_biometric_button")
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unlock with Biometrics")
                    }
                }
            }
        }
    }
}

// ============================================================================
// 2. Per-System Connection & Credential Biometric / PIN Gate Dialog
// ============================================================================

@Composable
fun SystemAuthGateDialog(
    challenge: PendingSecurityChallenge,
    policy: SecurityVaultPolicy,
    biometricStatus: BiometricCapabilityStatus,
    onAuthenticated: () -> Unit,
    onVerifyPin: (String) -> PinVerificationResult,
    onSetupQuickPinAndProceed: (String) -> Boolean,
    onTriggerBiometric: (Activity, (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val targetProfile = when (challenge) {
        is PendingSecurityChallenge.ConnectToSystem -> challenge.profile
        is PendingSecurityChallenge.EditProtectedProfile -> challenge.profile
    }
    val actionTitle = when (challenge) {
        is PendingSecurityChallenge.ConnectToSystem -> "Authenticate System Connection"
        is PendingSecurityChallenge.EditProtectedProfile -> "Authenticate Credential Access"
    }
    val actionSubtitle = "${targetProfile.protocol} • ${targetProfile.name} (${targetProfile.server}:${targetProfile.port})"

    var enteredPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var statusText by remember {
        mutableStateOf(
            if (policy.isPinConfigured) {
                "Verify Biometrics or enter your Master Vault PIN to decrypt credentials and continue."
            } else {
                "Use Biometrics or create a 4–8 digit Master Vault PIN to protect system connections."
            }
        )
    }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(challenge) {
        if (policy.biometricEnabled && biometricStatus.isAvailable && activity != null) {
            onTriggerBiometric(activity) { msg ->
                statusText = msg
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 430.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    RoundedCornerShape(18.dp)
                )
                .testTag("system_auth_gate_dialog"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Column {
                            Text(
                                text = actionTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = actionSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel authentication")
                    }
                }

                HorizontalDivider()

                // Target System Security Badge
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF57E389),
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = if (targetProfile.password.isNotEmpty()) {
                                    "Saved Password Encrypted (AES-256-GCM)"
                                } else {
                                    "Biometric / PIN Protected System Endpoint"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "User: ${targetProfile.username.ifBlank { "Prompt on connect" }} • Security: ${targetProfile.securityMode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Biometric Primary Action Button
                if (activity != null) {
                    Button(
                        onClick = {
                            onTriggerBiometric(activity) { errMsg ->
                                statusText = errMsg
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("system_auth_biometric_button")
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Authenticate with Biometrics")
                    }
                }

                if (policy.isPinConfigured) {
                    PinDotsIndicator(pinLength = enteredPin.length, isError = isError)

                    PinNumericKeypad(
                        onDigitClick = { digit ->
                            if (enteredPin.length < 8) {
                                enteredPin += digit
                                isError = false
                            }
                        },
                        onBackspaceClick = {
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                                isError = false
                            }
                        },
                        onSubmitClick = {
                            when (val res = onVerifyPin(enteredPin)) {
                                is PinVerificationResult.Success -> {
                                    enteredPin = ""
                                    onAuthenticated()
                                }
                                is PinVerificationResult.InvalidPin -> {
                                    enteredPin = ""
                                    isError = true
                                    statusText = "Invalid PIN (${res.attemptsRemainingBeforeLockout} attempts remaining)"
                                }
                                is PinVerificationResult.LockedOut -> {
                                    enteredPin = ""
                                    isError = true
                                    statusText = "PIN locked out for ${res.remainingSeconds}s due to failed attempts."
                                }
                            }
                        },
                        submitEnabled = enteredPin.length >= 4
                    )
                } else {
                    // First-time quick PIN setup right inside the system gate dialog
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = { enteredPin = it.filter { ch -> ch.isDigit() }.take(8) },
                        label = { Text("Create 4–8 Digit Vault PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_setup_pin_input")
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { ch -> ch.isDigit() }.take(8) },
                        label = { Text("Confirm Vault PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_setup_pin_confirm_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (enteredPin.length < 4) {
                                    isError = true
                                    statusText = "PIN must be 4 to 8 digits."
                                } else if (enteredPin != confirmPin) {
                                    isError = true
                                    statusText = "PIN confirmation does not match."
                                } else if (onSetupQuickPinAndProceed(enteredPin)) {
                                    onAuthenticated()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_setup_pin_save_button")
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save PIN & Continue")
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 3. Master Vault PIN Setup / Change Dialog
// ============================================================================

@Composable
fun SetupOrChangePinDialog(
    isPinAlreadyConfigured: Boolean,
    onVerifyExistingPin: (String) -> PinVerificationResult,
    onSaveNewPin: (String) -> Boolean,
    onRemovePin: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_or_change_pin_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Password, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = if (isPinAlreadyConfigured) "Change or Remove Vault PIN" else "Configure Master Vault PIN",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Your Vault PIN is hashed using PBKDF2-HMAC-SHA256 (210,000 iterations + 128-bit random salt) and gates access to the app and encrypted system passwords.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isPinAlreadyConfigured) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("Current Vault PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("current_vault_pin_input")
                    )
                }

                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("New 4–8 Digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_vault_pin_input")
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("Confirm New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_vault_pin_input")
                )

                errorMessage?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPinAlreadyConfigured) {
                        OutlinedButton(
                            onClick = {
                                when (val res = onVerifyExistingPin(currentPin)) {
                                    is PinVerificationResult.Success -> {
                                        onRemovePin()
                                        onDismiss()
                                    }
                                    is PinVerificationResult.InvalidPin -> {
                                        errorMessage = "Current PIN is incorrect (${res.attemptsRemainingBeforeLockout} tries left)."
                                    }
                                    is PinVerificationResult.LockedOut -> {
                                        errorMessage = "Locked out for ${res.remainingSeconds}s."
                                    }
                                }
                            },
                            modifier = Modifier.testTag("remove_vault_pin_button")
                        ) {
                            Text("Remove PIN", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (isPinAlreadyConfigured) {
                                    when (val res = onVerifyExistingPin(currentPin)) {
                                        is PinVerificationResult.InvalidPin -> {
                                            errorMessage = "Current PIN is incorrect (${res.attemptsRemainingBeforeLockout} tries left)."
                                            return@Button
                                        }
                                        is PinVerificationResult.LockedOut -> {
                                            errorMessage = "Locked out for ${res.remainingSeconds}s."
                                            return@Button
                                        }
                                        is PinVerificationResult.Success -> {}
                                    }
                                }
                                if (newPin.length !in 4..8) {
                                    errorMessage = "New PIN must be between 4 and 8 digits."
                                    return@Button
                                }
                                if (newPin != confirmPin) {
                                    errorMessage = "New PIN and confirmation do not match."
                                    return@Button
                                }
                                if (onSaveNewPin(newPin)) {
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.testTag("save_vault_pin_button")
                        ) {
                            Text("Save PIN")
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 4. Security & Biometric Vault Control Center Tab
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityAuditAndVaultSection(
    policy: SecurityVaultPolicy,
    biometricStatus: BiometricCapabilityStatus,
    auditChecklist: List<SecurityAuditCheckItem>,
    lastSelfTestResult: SecuritySelfTestResult?,
    onOpenPinSetupDialog: () -> Unit,
    onLockAppNow: () -> Unit,
    onRunSecuritySelfTest: () -> Unit,
    onUpdatePolicy: (
        biometricEnabled: Boolean?,
        requireAuthOnAppLaunch: Boolean?,
        requireAuthBeforeSystemConnect: Boolean?,
        autoLockTimeoutSeconds: Int?,
        flagSecureEnabled: Boolean?,
        strictTlsTofuPinning: Boolean?
    ) -> Unit
) {
    val passedCount = auditChecklist.count { it.passed }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("security_audit_vault_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Triple-Check Security Audit & Live Cryptographic Self-Test Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp)
                    ),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color(0xFF57E389),
                                modifier = Modifier.size(28.dp)
                            )
                            Column {
                                Text(
                                    text = "Triple-Check Security & Encryption Audit",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$passedCount / ${auditChecklist.size} Defense-in-Depth Controls Active",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF57E389)
                                )
                            }
                        }

                        Button(
                            onClick = onRunSecuritySelfTest,
                            modifier = Modifier.testTag("run_crypto_self_test_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Self-Test")
                        }
                    }

                    if (lastSelfTestResult != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (lastSelfTestResult.passedAll) Color(0xFF57E389).copy(alpha = 0.5f) else MaterialTheme.colorScheme.error,
                                    RoundedCornerShape(10.dp)
                                )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = lastSelfTestResult.summary,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (lastSelfTestResult.passedAll) Color(0xFF57E389) else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                                lastSelfTestResult.details.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                    auditChecklist.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (item.passed) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (item.passed) Color(0xFF57E389) else Color(0xFFF5C211),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = item.severity,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = item.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Biometric & Master Vault PIN Configuration Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(
                                text = "Biometrics & Master Vault PIN Authentication",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = biometricStatus.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (biometricStatus.isAvailable) Color(0xFF57E389) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenPinSetupDialog,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("configure_vault_pin_button")
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (policy.isPinConfigured) "Change / Manage PIN" else "Set Master Vault PIN")
                        }

                        if (policy.isPinConfigured || biometricStatus.isAvailable) {
                            OutlinedButton(
                                onClick = onLockAppNow,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lock_vault_now_button")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Lock App Now")
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                    SecurityPolicySwitchRow(
                        title = "Enable Android Biometrics (Fingerprint / Face Unlock)",
                        subtitle = "Allow hardware BiometricPrompt to unlock the app and authorize system connections",
                        checked = policy.biometricEnabled,
                        onCheckedChange = { onUpdatePolicy(it, null, null, null, null, null) },
                        testTag = "switch_biometric_enabled"
                    )

                    SecurityPolicySwitchRow(
                        title = "Require Biometric / PIN on App Launch & Resume",
                        subtitle = "Lock RemMobile on cold start and after background inactivity timeout",
                        checked = policy.requireAuthOnAppLaunch,
                        onCheckedChange = { enabled ->
                            if (enabled && !policy.isPinConfigured && !biometricStatus.isAvailable) {
                                onOpenPinSetupDialog()
                            } else {
                                onUpdatePolicy(null, enabled, null, null, null, null)
                            }
                        },
                        testTag = "switch_require_auth_launch"
                    )

                    SecurityPolicySwitchRow(
                        title = "Require Biometric / PIN Before Connecting to Systems",
                        subtitle = "Challenge with Biometrics or Vault PIN before decrypting passwords or launching remote sessions",
                        checked = policy.requireAuthBeforeSystemConnect,
                        onCheckedChange = { onUpdatePolicy(null, null, it, null, null, null) },
                        testTag = "switch_require_auth_connect"
                    )

                    Text(
                        text = "Background Auto-Lock Timeout",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0 to "Immediate",
                            60 to "1 min",
                            300 to "5 min",
                            900 to "15 min"
                        ).forEach { (sec, label) ->
                            FilterChip(
                                selected = policy.autoLockTimeoutSeconds == sec,
                                onClick = { onUpdatePolicy(null, null, null, sec, null, null) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Runtime Display Shield & TLS Pinning Hardening Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column {
                            Text(
                                text = "Screen Shield & Transport Security Hardening",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Protect active remote desktops against screen capture & MITM certificate spoofing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    SecurityPolicySwitchRow(
                        title = "Screenshot & App-Switcher Shield (FLAG_SECURE)",
                        subtitle = "Prevent screenshots, screen recording, and Android Recents task switcher from capturing remote desktops or passwords",
                        checked = policy.flagSecureEnabled,
                        onCheckedChange = { onUpdatePolicy(null, null, null, null, it, null) },
                        testTag = "switch_flag_secure"
                    )

                    SecurityPolicySwitchRow(
                        title = "Trust-On-First-Use (TOFU) TLS Certificate Pinning",
                        subtitle = "Automatically pin X.509 SHA-256 certificate fingerprints on first RDP/TLS connection and alert on changes",
                        checked = policy.strictTlsTofuPinning,
                        onCheckedChange = { onUpdatePolicy(null, null, null, null, null, it) },
                        testTag = "switch_tls_tofu_pinning"
                    )
                }
            }
        }
    }
}

@Composable
private fun SecurityPolicySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun PinDotsIndicator(
    pinLength: Int,
    isError: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        val totalDots = maxOf(4, pinLength.coerceAtMost(8))
        for (i in 0 until totalDots) {
            val filled = i < pinLength
            val dotColor = when {
                isError -> Color(0xFFFF6B6B)
                filled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            }
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (filled) dotColor else Color.Transparent)
                    .border(1.5.dp, dotColor, CircleShape)
            )
        }
    }
}

@Composable
private fun PinNumericKeypad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onSubmitClick: () -> Unit,
    submitEnabled: Boolean
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowDigits.forEach { digit ->
                    KeypadButton(
                        label = digit,
                        onClick = { onDigitClick(digit) },
                        testTag = "pin_key_$digit"
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier
                    .size(width = 76.dp, height = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBackspaceClick)
                    .testTag("pin_key_backspace")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            KeypadButton(
                label = "0",
                onClick = { onDigitClick("0") },
                testTag = "pin_key_0"
            )

            Button(
                onClick = onSubmitClick,
                enabled = submitEnabled,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(width = 76.dp, height = 48.dp)
                    .testTag("pin_key_unlock")
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier
            .size(width = 76.dp, height = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
