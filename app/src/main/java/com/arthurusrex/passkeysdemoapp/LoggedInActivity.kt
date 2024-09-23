package com.arthurusrex.passkeysdemoapp

import AuthStateManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arthurusrex.passkeysdemoapp.ui.theme.PasskeysDemoAppTheme
import com.arthurusrex.passkeysdemoapp.ui.theme.WavestonePurple
import com.transmit.authentication.RegistrationResult
import com.transmit.authentication.TSAuthCallback
import com.transmit.authentication.TSAuthentication
import com.transmit.authentication.TSWebAuthnRegistrationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.IdToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject

class LoggedInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LoggedInActivity", "onCreate")
        val configuration = Configuration.getInstance(this)
        TSAuthentication.initialize(
            this,
            configuration.clientId!!,
            "https://api.transmitsecurity.io/"
        )
        val authState = AuthStateManager(this).current
        if (authState.accessToken != null) {
            Log.i("Current Access token", authState.accessToken!!)
        }

        val idToken = authState.idToken
        if (idToken != null) {
            val parsedIdToken = authState.parsedIdToken

            if (parsedIdToken != null) {
                setContent {
                    PasskeysDemoAppTheme {
                        LoggedInScreen(parsedIdToken)
                    }
                }
            }
        }
    }

    @Composable
    fun LoggedInScreen(parsedIdToken: IdToken) {
        val context = LocalContext.current
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        val accessToken = AuthStateManager(context).current.accessToken!!
        Surface(modifier = Modifier.fillMaxSize(), color = WavestonePurple) {
            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,) {
                    Text("Logged In", color = Color.White)

                    val isPasskeySupported = TSAuthentication.isWebAuthnSupported()
                    if (isPasskeySupported) {
                        Text("WebAuthn is supported !!", color = Color.White)
                        Button(onClick = {
                            TSAuthentication.registerWebAuthn(
                                context,
                                "menthe94+testpasskeys@gmail.com",
                                "Demo Passkeys",
                                object :
                                    TSAuthCallback<RegistrationResult, TSWebAuthnRegistrationError> {
                                    override fun success(registrationResult: RegistrationResult) {
                                        val encodedResult = registrationResult.result()
                                        Log.i("WebAuthn Registration", "Registration result ${encodedResult}")
                                        scope.launch {
                                            sendEncodedResultToBackend(encodedResult, accessToken!!)
                                        }
                                    }

                                    override fun error(error: TSWebAuthnRegistrationError) {
                                        Log.e(
                                            "WebAuthn Registration Error",
                                            "Error from API ${error}"
                                        )
                                        //handle error
                                    }
                                }
                            )
                        })
                        {
                            Text("Register a Passkey")
                        }
                    } else {
                        Text("WebAuthn is not supported", color = Color.White)
                    }

                    val scrollState = rememberScrollState()
                    val authStateManager = AuthStateManager(context)
                    val jsonAdditionalClaims = JSONObject(parsedIdToken.additionalClaims)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(16.dp)

                    ) {
                        Row(Modifier.horizontalScroll(scrollState)) {
                            Text(
                                jsonAdditionalClaims.toString(4),
                                color = Color.Black,
                                modifier = Modifier.padding(16.dp)
                            )
                        }

                    }
                    Button(onClick = {
                        authStateManager.replace(AuthState())
                        val intent = Intent(context, LoginActivity::class.java)
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        context.startActivity(intent)
                    }, modifier = Modifier.padding(16.dp)) {
                        Text("Log Out")
                    }
                }
            }
        }
    }

    private suspend fun sendEncodedResultToBackend(encodedResult: String, accessToken: String) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val json = JSONObject()
            json.put("webauthn_encoded_result", encodedResult)
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://passkeys.arthurusrex.fr/api/webauthn/register") // Replace with your backend server URL
                .header("Authorization", "Bearer $accessToken")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response and ${response.body?.string()}")

                // Handle the response
                val responseJson = response.body?.string()
                Log.i("Backend Response", responseJson!!)
            }
        }
    }

}