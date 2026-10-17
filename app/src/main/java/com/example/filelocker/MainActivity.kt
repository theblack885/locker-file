@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.filelocker

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshot / recent-apps preview block
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun queryName(ctx: android.content.Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return "file_${System.currentTimeMillis()}"
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { File(ctx.filesDir, "vault").apply { mkdirs() } }

    var files by remember { mutableStateOf(vault.listFiles()?.sortedBy { it.name } ?: emptyList()) }
    fun refresh() { files = vault.listFiles()?.sortedBy { it.name } ?: emptyList() }

    var lockUri by remember { mutableStateOf<Uri?>(null) }
    var unlockTarget by remember { mutableStateOf<File?>(null) }   // dialog ke liye
    var saveTarget by remember { mutableStateOf<File?>(null) }     // save ke liye
    var secret by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { lockUri = it }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = saveTarget
        if (uri != null && f != null) {
            scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    f.inputStream().use { i ->
                        ctx.contentResolver.openOutputStream(uri)!!.use { o ->
                            CryptoUtil.decrypt(i, o, secret)
                        }
                    }
                }
                if (r.isFailure) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                msg = if (r.isSuccess) "Unlock ho gayi ✅" else "Galat PIN/Pattern ❌"
                secret = ""
                saveTarget = null
            }
        } else {
            secret = ""
            saveTarget = null
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("🔐 File Locker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Lock a file")
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(msg)
        }
        Spacer(Modifier.height(12.dp))
        Text("Locked files (${files.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.name }) { f ->
                val name = remember(f) { CryptoUtil.originalName(f) }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(name)
                        Row {
                            TextButton(onClick = { unlockTarget = f }) { Text("Unlock") }
                            TextButton(onClick = { f.delete(); refresh() }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    // LOCK dialog
    if (lockUri != null) {
        SecretDialog(
            title = "Lock ke liye PIN / Pattern set karo",
            onCancel = { lockUri = null },
            onDone = { s ->
                val u = lockUri!!
                lockUri = null
                scope.launch(Dispatchers.IO) {
                    val r = runCatching {
                        val name = queryName(ctx, u)
                        val out = File(vault, "${System.currentTimeMillis()}.locked")
                        try {
                            ctx.contentResolver.openInputStream(u)!!.use { i ->
                                out.outputStream().use { o -> CryptoUtil.encrypt(i, o, s, name) }
                            }
                        } catch (e: Exception) { out.delete(); throw e }
                    }
                    if (r.isSuccess) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, u) }
                    msg = if (r.isSuccess) "File lock ho gayi ✅" else "Lock fail hui ❌"
                    refresh()
                }
            }
        )
    }

    // UNLOCK dialog
    unlockTarget?.let { f ->
        SecretDialog(
            title = "PIN / Pattern dalo",
            onCancel = { unlockTarget = null },
            onDone = { s ->
                secret = s
                saveTarget = f
                unlockTarget = null
                saver.launch(CryptoUtil.originalName(f))
            }
        )
    }
}

@Composable
fun SecretDialog(title: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var usePattern by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf(listOf<Int>()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                Row {
                    FilterChip(selected = !usePattern, onClick = { usePattern = false }, label = { Text("PIN") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = usePattern, onClick = { usePattern = true }, label = { Text("Pattern") })
                }
                Spacer(Modifier.height(12.dp))
                if (!usePattern) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit) },
                        label = { Text("PIN (min 4 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                } else {
                    Text("Kam se kam 4 dots order me dabao")
                    for (r in 0..2) {
                        Row {
                            for (c in 0..2) {
                                val n = r * 3 + c
                                val idx = pat.indexOf(n)
                                OutlinedButton(
                                    onClick = { if (n !in pat) pat = pat + n },
                                    modifier = Modifier.padding(4.dp).size(56.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text(if (idx >= 0) "${idx + 1}" else "•") }
                            }
                        }
                    }
                    TextButton(onClick = { pat = emptyList() }) { Text("Reset pattern") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!usePattern && pin.length >= 4) onDone("P:$pin")
                else if (usePattern && pat.size >= 4) onDone("G:" + pat.joinToString("-"))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
