package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun iOSAuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    isPassword: Boolean = false,
    tag: String
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color(0xFF8E8E93), fontSize = 15.sp) },
        leadingIcon = { Icon(leadingIcon, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(20.dp)) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 15.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    )
}

@Composable
fun AuthScreen(
    viewModel: MovieViewModel,
    onAuthSuccess: () -> Unit
) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var showAvatarSelector by remember { mutableStateOf(false) }
    var selectedAvatar by remember { mutableStateOf("https://i.ibb.co/yBNK21P/avatar1.jpg") }
    var isSubmitting by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val avatars = listOf(
        "https://i.ibb.co/yBNK21P/avatar1.jpg",
        "https://i.ibb.co/6803Y3B/avatar2.jpg",
        "https://i.ibb.co/1q20KzP/avatar3.jpg",
        "https://i.ibb.co/b3n1Vsh/avatar4.jpg",
        "https://i.ibb.co/HxhbyC8/avatar5.jpg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14)),
        contentAlignment = Alignment.Center
    ) {
        // iOS soft atmospheric radial backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elegant Branded Mana Cinema Header
            AsyncImage(
                model = com.example.R.drawable.ic_movie_pro_logo,
                contentDescription = "Mana Cinema Logo",
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 12.dp)
                    .testTag("auth_logo_img")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = "MANA ",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "CINEMA",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Text(
                text = "Premium Apple-style Streaming",
                color = Color(0xFF8E8E93),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 36.dp)
            )

            // Dynamic Form Mode Title
            Text(
                text = if (isSignUp) "Create Apple Account" else "Sign In with Apple ID",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Unified Grouped Form Container (Classic iOS style)
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    if (isSignUp) {
                        iOSAuthField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            placeholder = "Display Name",
                            leadingIcon = Icons.Default.Person,
                            tag = "display_name_input"
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF2C2C2E)))
                    }

                    iOSAuthField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "Email Address",
                        leadingIcon = Icons.Default.Email,
                        tag = "email_input"
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF2C2C2E)))

                    iOSAuthField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Secret Password",
                        leadingIcon = Icons.Default.Lock,
                        isPassword = true,
                        tag = "password_input"
                    )
                }
            }

            // Avatar Choices for Register
            if (isSignUp) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                AsyncImage(
                                    model = selectedAvatar,
                                    contentDescription = "Selected Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("PROFILE PICTURE", color = Color(0xFF8E8E93), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Choose your virtual avatar", color = Color.LightGray, fontSize = 13.sp)
                            }
                            Button(
                                onClick = { showAvatarSelector = !showAvatarSelector },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Select", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (showAvatarSelector) {
                            Spacer(modifier = Modifier.height(14.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                modifier = Modifier
                                    .height(54.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(avatars) { url ->
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .border(
                                                width = if (selectedAvatar == url) 2.dp else 0.dp,
                                                color = if (selectedAvatar == url) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                shape = CircleShape
                                            )
                                            .clickable { selectedAvatar = url }
                                    ) {
                                        AsyncImage(
                                            model = url,
                                            contentDescription = "Avatar Options",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (isSubmitting) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                // High contrast action Submit Button
                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            Toast.makeText(context, "All entries are required", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSubmitting = true
                        try {
                            val authInstance = FirebaseAuth.getInstance()
                            if (isSignUp) {
                                authInstance.createUserWithEmailAndPassword(email.trim(), password)
                                    .addOnCompleteListener { task ->
                                        isSubmitting = false
                                        if (task.isSuccessful) {
                                            viewModel.updateProfile(displayName.ifBlank { "Viewer" }, "male") {
                                                viewModel.updateAvatar(selectedAvatar) {
                                                    viewModel.handleAuthUpdate()
                                                    onAuthSuccess()
                                                }
                                            }
                                        } else {
                                            Toast.makeText(context, "Sign up failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                            } else {
                                authInstance.signInWithEmailAndPassword(email.trim(), password)
                                    .addOnCompleteListener { task ->
                                        isSubmitting = false
                                        if (task.isSuccessful) {
                                            viewModel.handleAuthUpdate()
                                            onAuthSuccess()
                                        } else {
                                            Toast.makeText(context, "Login failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                            }
                        } catch (e: Exception) {
                            isSubmitting = false
                            Toast.makeText(context, "Auth service currently unavailable. Entering via Guest Mode.", Toast.LENGTH_LONG).show()
                            viewModel.handleGuestLogin()
                            onAuthSuccess()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White), // White high contrast standard action
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("auth_submit_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isSignUp) "Register Securely" else "Continue with Password",
                        color = Color.Black, // Dark contrasting text
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Switch Screen mode action text
            Text(
                text = if (isSignUp) "Already registered? Sign In" else "Don't have an Apple ID? Create one now",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { isSignUp = !isSignUp }
                    .padding(8.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Guest Entry Point
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isSubmitting = true
                        try {
                            FirebaseAuth.getInstance().signInAnonymously()
                                .addOnCompleteListener { task ->
                                    isSubmitting = false
                                    if (task.isSuccessful) {
                                        viewModel.handleAuthUpdate()
                                        onAuthSuccess()
                                    } else {
                                        // Fallback if anonymous is not enabled / offline / error
                                        viewModel.handleGuestLogin()
                                        onAuthSuccess()
                                    }
                                }
                        } catch (e: Exception) {
                            isSubmitting = false
                            viewModel.handleGuestLogin()
                            onAuthSuccess()
                        }
                    }
                    .testTag("guest_login_btn")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Continue as Guest",
                        color = Color.LightGray,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Forward arrow",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
