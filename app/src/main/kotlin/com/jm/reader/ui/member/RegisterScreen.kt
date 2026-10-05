package com.jm.reader.ui.member

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jm.reader.data.repo.RepoResult
import com.jm.reader.ui.LocalAppStrings
import com.jm.reader.ui.LocalRepository
import com.jm.reader.ui.components.AppTopBar
import com.jm.reader.ui.nav.Routes
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(navController: NavHostController) {
    val repo = LocalRepository.current
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("male") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    // Mirrors the API's own rule (it answers "電子郵件不是有效的電子郵件地址!" for bad input).
    val emailPattern = remember { Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$") }

    Scaffold(topBar = { AppTopBar(s.register, onBack = { navController.popBackStack() }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(s.username) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(s.email) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(s.password) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = passwordConfirm,
                onValueChange = { passwordConfirm = it },
                label = { Text(s.confirmPassword) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = gender == "male", onClick = { gender = "male" }, label = { Text(s.male) })
                FilterChip(selected = gender == "female", onClick = { gender = "female" }, label = { Text(s.female) })
                FilterChip(selected = gender == "other", onClick = { gender = "other" }, label = { Text(s.other) })
            }

            message?.let {
                Text(
                    it,
                    color = if (success) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                    else androidx.compose.ui.graphics.Color(0xFFE53935),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (success) {
                Button(
                    onClick = {
                        // Back to login with the new account name already filled in.
                        navController.navigate(Routes.login(username.trim())) {
                            popUpTo(Routes.MAIN)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) { Text("${s.login} →") }
            } else {
                Button(
                    onClick = {
                        val name = username.trim()
                        val mail = email.trim()
                        // The server enforces these too; checking here keeps the errors understandable.
                        val problem = when {
                            name.isBlank() || mail.isBlank() || password.isBlank() -> s.fillComplete
                            !emailPattern.matches(mail) -> s.invalidEmail
                            password.length < 8 -> s.passwordTooShort
                            password != passwordConfirm -> s.passwordMismatch
                            else -> null
                        }
                        if (problem != null) {
                            success = false
                            message = problem
                        } else {
                            loading = true
                            message = null
                            scope.launch {
                                when (val r = repo.register(name, password, passwordConfirm, mail, gender)) {
                                    is RepoResult.Ok -> {
                                        success = true
                                        message = s.registerSuccess
                                        loading = false
                                    }
                                    is RepoResult.Err -> {
                                        success = false
                                        message = r.message
                                        loading = false
                                    }
                                }
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(s.register)
                }
            }
        }
    }
}
