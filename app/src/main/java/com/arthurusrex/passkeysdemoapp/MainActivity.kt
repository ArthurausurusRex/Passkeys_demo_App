package com.arthurusrex.passkeysdemoapp

import AuthStateManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import com.arthurusrex.passkeysdemoapp.ui.theme.BoldWhite
import com.arthurusrex.passkeysdemoapp.ui.theme.WavestonePurple
import com.transmit.authentication.AuthenticationResult
import com.transmit.authentication.RegistrationResult
import com.transmit.authentication.TSAuthCallback
import com.transmit.authentication.TSAuthentication
import com.transmit.authentication.TSWebAuthnAuthenticationError
import com.transmit.authentication.TSWebAuthnRegistrationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : ComponentActivity() {

    private lateinit var serviceConfiguration: AuthorizationServiceConfiguration
    private lateinit var authService: AuthorizationService
    private var currentAuthState = mutableStateOf(AuthState())

    private val authorizationResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.i("MainActivity", "Authorization result: $result")
            val data = result.data ?: return@registerForActivityResult
            val authResponse = AuthorizationResponse.fromIntent(data)
            val authException = AuthorizationException.fromIntent(data)
            if (authResponse != null) {
                val tokenRequest = authResponse.createTokenExchangeRequest()
                Log.i(
                    "LoginActivity tokenRequest auth code",
                    tokenRequest.authorizationCode.toString()
                )
                Log.i(
                    "LoginActivity tokenRequest code verifier",
                    tokenRequest.codeVerifier.toString()
                )

                // Create OkHttpClient
                val client = OkHttpClient()

                // Create request body
                val requestBody = FormBody.Builder()
                    .add("code", tokenRequest.authorizationCode.toString())
                    .add("redirect_uri", tokenRequest.redirectUri.toString())
                    .add("code_verifier", tokenRequest.codeVerifier.toString())
                    .add("scope", tokenRequest.scope.toString())
                    .build()

                // Create request
                val request = Request.Builder()
                    .url("https://passkeys.arthurusrex.fr/api/oidc/tokens")
                    .post(requestBody)
                    .build()

                // Send request
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("MainActivity", "Token exchange failed", e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            Log.i("LoginActivity", "Token exchange response: $body")

                            // Parse the response body to a JSON object
                            val jsonObject = JSONObject(body)

                            // Create a TokenResponse.Builder
                            val tokenResponseBuilder = TokenResponse.Builder(tokenRequest)

                            // Check if jsonObject has the required parameters and add them to the builder
                            if (jsonObject.has("access_token")) {
                                tokenResponseBuilder.setAccessToken(jsonObject.getString("access_token"))
                            }
                            if (jsonObject.has("expires_at")) {
                                tokenResponseBuilder.setAccessTokenExpirationTime(
                                    jsonObject.getLong(
                                        "expires_at"
                                    )
                                )
                            }
                            if (jsonObject.has("refresh_token")) {
                                tokenResponseBuilder.setRefreshToken(jsonObject.getString("refresh_token"))
                            }
                            if (jsonObject.has("id_token")) {
                                tokenResponseBuilder.setIdToken(jsonObject.getString("id_token"))
                            }

                            // Build the TokenResponse
                            val tokenResponse = tokenResponseBuilder.build()

                            // Create an AuthState
                            val authState = AuthState(authResponse, authException)
                            authState.update(tokenResponse, null)
                            // Save the AuthState
                            AuthStateManager(this@MainActivity).current = authState
                            currentAuthState.value = authState

                           // registerPasskey()
                            registerPasskeyWithoutSdk()
                        } else {
                            Log.e("MainActivity", "Token exchange failed: ${response.code}")
                        }
                    }
                })
            } else {
                Log.e("LoginActivity", "Authorization failed", authException)
            }

        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val configuration = Configuration.getInstance(this)

        super.onCreate(savedInstanceState)
        currentAuthState.value = AuthStateManager(this).current
        AuthorizationServiceConfiguration.fetchFromIssuer(
            Uri.parse("https://api.transmitsecurity.io/cis/oidc/")
        ) { serviceConfig, ex ->
            if (ex != null) {
                Log.e("LoginActivity", "Failed to retrieve configuration", ex)
                return@fetchFromIssuer
            }
            this.serviceConfiguration = serviceConfig!!
            authService = AuthorizationService(this)
        }
        TSAuthentication.initialize(
            this,
            configuration.clientId!!,
            "https://api.transmitsecurity.io/"
        )
        val authState = AuthStateManager(this).current
        setContent {
            MainScreen(currentAuthState)
        }

    }

    private fun registerPasskeyWithoutSdk(accessToken: String? = currentAuthState.value.accessToken) {
        val configuration = Configuration.getInstance(this)
        Log.i(
            "MainActivity",
            "Registering passkey without SDK with accessToken $accessToken and parsedIdToken ${currentAuthState.value.idToken}"
        )
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        //Send a POST request to https://api.transmitsecurity.io/cis/v1/auth/webauthn/register/start
        // with type application/json and the following body:
        //{
        //    "client_id": "string",
        //    "username": "string",
        //    "display_name": "string",
        //    "timeout": 0,
        //    "limit_single_credential_to_device": false
        //}
        // The response will be used to create a PublicKeyCredentialCreationOptions object

        // Create OkHttpClient
        val client = OkHttpClient()

        // Create request body
        val json = JSONObject()
        json.put("client_id", configuration.clientId!!)
        json.put("username", "menthe94+testpasskeys@gmail.com")
        json.put("display_name", "your_display_name")
        json.put("timeout", 30)
        json.put("limit_single_credential_to_device", false)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toString().toRequestBody(mediaType)

        // Create request
        val request = Request.Builder()
            .url("https://api.transmitsecurity.io/cis/v1/auth/webauthn/register/start")
            .post(requestBody)
            .build()

        // Send request
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MainActivity", "Registration start failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.i("MainActivity", "Registration start response: $body")

                    // Parse the response body to a JSON object
                    val jsonObject = JSONObject(body)
                    val publicKeyCredentialCreationOptionsJSON = jsonObject.getJSONObject("credential_creation_options")
                    val authenticatorSelection = publicKeyCredentialCreationOptionsJSON.getJSONObject("authenticatorSelection")
                    authenticatorSelection.put("requireResidentKey", false)
                    authenticatorSelection.put("residentKey", "required")

                    publicKeyCredentialCreationOptionsJSON.put("challenge", "ai6ZG-bYXFHw_KhR5Gej4u0tipvLYBnK0rzsLBbielNgaluxEslxwIFbmwyeG1406xhbv__IsSEh9Y6J6cmxxGxJeKAZVzoV2PFXL3C-uVTukr8Wh6zL5aYs7GPBqxez-aI3bdoJyl4ykpF4KJ4DfSRbns39xuCrvtgLxcqL1f0")
                    publicKeyCredentialCreationOptionsJSON.put("authenticatorSelection", authenticatorSelection)
                    Log.i("MainActivity", "publicKeyCredentialCreationOptionsJSON: $publicKeyCredentialCreationOptionsJSON")
                    val createPublicKeyCredentialRequest  = CreatePublicKeyCredentialRequest(
                        requestJson = publicKeyCredentialCreationOptionsJSON.toString()
                    )
                    scope.launch {
                        try {
                            val credentialManager = CredentialManager.create(this@MainActivity)
                            val result =credentialManager.createCredential(
                                context = this@MainActivity,
                                request = createPublicKeyCredentialRequest
                            )
                            handlePasskeyRegistrationResult(result, accessToken!!)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to register passkey without SDK", e)
                        }
                    }

                } else {
                    Log.e("MainActivity", "Registration start failed: ${response.body?.string()}")
                }
            }
        })
    }

    private suspend fun handlePasskeyRegistrationResult(
        result: CreateCredentialResponse,
        accessToken: String
    ) {
        val encodedResult = result.registrationResponseJson
        Log.i("MainActivity", "Registration result ${encodedResult}")
    //    sendRegistrationRequestToBackend(, accessToken)
    }


    private fun registerPasskey(accessToken: String? = currentAuthState.value.accessToken) {
        Log.i(
            "MainActivity",
            "Registering passkey with accessToken $accessToken and parsedIdToken ${currentAuthState.value.idToken}"
        )
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        TSAuthentication.registerWebAuthn(
            this@MainActivity,
            currentAuthState.value.parsedIdToken!!.additionalClaims["email"] as String,
            "Demo Passkeys",
            object :
                TSAuthCallback<RegistrationResult, TSWebAuthnRegistrationError> {
                override fun success(registrationResult: RegistrationResult) {
                    val encodedResult = registrationResult.result()
                    Log.i("WebAuthn Registration", "Registration result ${encodedResult}")
                    scope.launch {
                        try {
                            sendRegistrationRequestToBackend(encodedResult, accessToken!!)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Passkey registered successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                "Failed to register passkey with error : ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e(
                                "MainActivity",
                                "Failed to send registration request to backend",
                                e
                            )
                            throw (e)
                        }
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
        // Add the logic to start the passkey registration process
    }

    private suspend fun sendRegistrationRequestToBackend(
        encodedResult: String,
        accessToken: String
    ) {
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
                Log.i("Backend Response for a succesful registration", responseJson!!)
            }
        }
    }

    @Composable
    fun MainScreen(authState: MutableState<AuthState>) {
        val context = LocalContext.current
        var currentAuthState = remember { authState }
        Surface(color = WavestonePurple, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Passkeys Demo App", style = BoldWhite, fontSize = 48.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Spacer(modifier = Modifier.height(16.dp))
                LoginButton(currentAuthState, context)

                Spacer(modifier = Modifier.height(16.dp))
                if (currentAuthState.value.idToken != null) {
                    TokenCard(
                        title = "Id token content:",
                        token = JSONObject(authState.value.parsedIdToken!!.additionalClaims).toString(
                            4
                        )
                    )
                    TokenCard("Id Token:", authState.value.idToken!!)

                }
                if (currentAuthState.value.accessToken != null) {
                    TokenCard("Access Token:", authState.value.accessToken!!)
                }
                if (currentAuthState.value.refreshToken != null) {
                    TokenCard("Refresh Token", authState.value.refreshToken!!)
                }
                LogoutButton(currentAuthState, context)

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }


    @Composable
    fun LoginButton(currentAuthState: MutableState<AuthState>, context: Context) {
        val context = LocalContext.current
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        // val isLoggedIn = authState.idToken != null
        var showDialog by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }
        val configuration = Configuration.getInstance(this)

        Button(onClick = {

            TSAuthentication.authenticateWebAuthn(context,
                callback = object :
                    TSAuthCallback<AuthenticationResult, TSWebAuthnAuthenticationError> {
                    override fun success(result: AuthenticationResult) {
                        val encodedResult = result.result()
                        Log.i(
                            "WebAuthn Authentication",
                            "Authentication result ${encodedResult}"
                        )
                        scope.launch {
                            try {
                                completeWebAuthnAuthentication(
                                    encodedResult,
                                    serviceConfiguration,
                                    currentAuthState,
                                    context
                                )
                                Toast.makeText(
                                    context,
                                    "WebAuthn Authentication successful",
                                    Toast.LENGTH_SHORT
                                ).show()

                            } catch (e: Exception) {
                                Log.e(
                                    "MainActivity",
                                    "Failed to complete WebAuthn Authentication with error : ${e.message}",
                                    e
                                )
                                Toast.makeText(
                                    context,
                                    "Failed to complete WebAuthn Authentication with error : ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                        }
                    }

                    override fun error(error: TSWebAuthnAuthenticationError) {
                        Log.e("WebAuthn Authentication Error", "Error from API ${error}")
                        errorMessage = error.eM
                        showDialog = true
                    }
                }
            )
        }) {
            Text("Login with Passkeys", fontSize = 24.sp, color = Color.White)
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("WebAuthn Authentication Error") },
                text = {
                    Column {
                        Text("There was an error during the WebAuthn Authentication process: $errorMessage")
                        Text("You can register a passkey to your account by clicking the following button.")
                        if (currentAuthState.value.idToken == null) {
                            Text("You are not logged in, when you click on the button you will first be prompted to login using another method.")
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (currentAuthState.value.idToken == null) {
                                showDialog = false
                                val authRequest = AuthorizationRequest.Builder(
                                    serviceConfiguration,
                                    configuration.clientId!!,
                                    ResponseTypeValues.CODE,
                                    configuration.redirectUri!!
                                )
                                    .setScope("openid email profile")
                                    .build()

                                val authIntent =
                                    authService.getAuthorizationRequestIntent(authRequest)
                                authorizationResultLauncher.launch(authIntent)
                            } else {
                                //registerPasskey(currentAuthState.value.accessToken!!)
                                registerPasskeyWithoutSdk(currentAuthState.value.accessToken!!)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WavestonePurple)
                    ) {
                        Text(if (currentAuthState.value.idToken != null) "Register a Passkey" else "Login then Register a Passkey")
                    }
                }
            )
        }
    }


    private suspend fun completeWebAuthnAuthentication(
        encodedResult: String,
        serviceConfiguration: AuthorizationServiceConfiguration,
        currentAuthState: MutableState<AuthState>,
        context: Context
    ) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val json = JSONObject()
            json.put("webauthn_encoded_result", encodedResult)
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://passkeys.arthurusrex.fr/api/webauthn/login") // Replace with your backend server URL
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response and ${response.body?.string()}")

                // Handle the response

                val responseJson = response.body?.string()
                Log.i("Backend Response", responseJson!!)

                // Parse the responseJson to a JSONObject
                val jsonObject = JSONObject(responseJson)
                val authState = AuthStateManager(this@MainActivity).current
                val configuration = Configuration.getInstance(this@MainActivity)
                val tokenRequest =
                    TokenRequest.Builder(serviceConfiguration, configuration.clientId!!)
                        .setGrantType("custom").build()
                val tokenResponseBuilder = TokenResponse.Builder(tokenRequest)
                // Parse the response body to a JSON object

                // Check if jsonObject has the required parameters and add them to the builder
                if (jsonObject.has("access_token")) {
                    Log.d("LoginActivity", "access_token: ${jsonObject.getString("access_token")} ")
                    tokenResponseBuilder.setAccessToken(jsonObject.getString("access_token"))
                }
                if (jsonObject.has("expires_at")) {
                    tokenResponseBuilder.setAccessTokenExpirationTime(jsonObject.getLong("expires_at"))
                }
                if (jsonObject.has("refresh_token")) {
                    tokenResponseBuilder.setRefreshToken(jsonObject.getString("refresh_token"))
                }
                if (jsonObject.has("id_token")) {
                    tokenResponseBuilder.setIdToken(jsonObject.getString("id_token"))
                }

                // Build the TokenResponse
                val tokenResponse = tokenResponseBuilder.build()
                withContext(Dispatchers.Main) {
                    authState.update(tokenResponse, null)
                    AuthStateManager(context).replace(authState)
                    currentAuthState.value = authState
                }
            }
        }
    }


    @Composable
    fun TokenCard(title: String, token: String) {
        val scrollState = rememberScrollState()
        val clipboardManager =
            LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipboardData = remember { mutableStateOf("") }
        val isExpanded = remember { mutableStateOf(false) }
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title, color = Color.White
                )
                Button(onClick = { isExpanded.value = !isExpanded.value },colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White), content =
                    {
                    Text(if (isExpanded.value) "Hide" else "Show", color = Color.White)
                })
            }
            if (isExpanded.value) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .padding(4.dp)
                        .fillMaxSize()
                        .clickable(
                            onClick = {
                                clipboardData.value = token
                                clipboardManager.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "token",
                                        token
                                    )
                                )
                                Toast.makeText(
                                    this@MainActivity,
                                    "Token copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                ) {
                    Row(Modifier.horizontalScroll(scrollState)) {
                        Text(
                            token,
                            color = Color.Black,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun LogoutButton(currentAuthState: MutableState<AuthState>, context: Context) {
        Button(onClick = {
            // Clear the AuthState and update the UI
            currentAuthState.value = AuthState()
            AuthStateManager(context).replace(AuthState())
        }) {
            Text("Logout", fontSize = 24.sp, color = Color.White)
        }
    }

    private fun exchangeCodeForTokens(authResponse: AuthorizationResponse, authException: AuthorizationException?) {
        val tokenRequest = authResponse.createTokenExchangeRequest()
        Log.i("LoginActivity tokenRequest auth code", tokenRequest.authorizationCode.toString())
        Log.i("LoginActivity tokenRequest code verifier", tokenRequest.codeVerifier.toString())

        // Create OkHttpClient
        val client = OkHttpClient()

        // Create request body
        val requestBody = FormBody.Builder()
            .add("code", tokenRequest.authorizationCode.toString())
            .add("redirect_uri", tokenRequest.redirectUri.toString())
            .add("code_verifier", tokenRequest.codeVerifier.toString())
            .add("scope", tokenRequest.scope.toString())
            .build()

        // Create request
        val request = Request.Builder()
            .url("https://passkeys.arthurusrex.fr/api/oidc/tokens")
            .post(requestBody)
            .build()

        // Send request
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LoginActivity", "Token exchange failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                if(response.isSuccessful) {
                    val body = response.body?.string()
                    Log.i("LoginActivity", "Token exchange response: $body")

                    // Parse the response body to a JSON object
                    val jsonObject = JSONObject(body)

                    // Create a TokenResponse.Builder
                    val tokenResponseBuilder = TokenResponse.Builder(tokenRequest)

                    // Check if jsonObject has the required parameters and add them to the builder
                    if (jsonObject.has("access_token")) {
                        tokenResponseBuilder.setAccessToken(jsonObject.getString("access_token"))
                    }
                    if (jsonObject.has("expires_at")) {
                        tokenResponseBuilder.setAccessTokenExpirationTime(jsonObject.getLong("expires_at"))
                    }
                    if (jsonObject.has("refresh_token")) {
                        tokenResponseBuilder.setRefreshToken(jsonObject.getString("refresh_token"))
                    }
                    if (jsonObject.has("id_token")) {
                        tokenResponseBuilder.setIdToken(jsonObject.getString("id_token"))
                    }

                    // Build the TokenResponse
                    val tokenResponse = tokenResponseBuilder.build()

                    // Create an AuthState
                    val authState = AuthState(authResponse, authException)
                    authState.update(tokenResponse, null)

                    // Save the AuthState
                    AuthStateManager(this@MainActivity).current = authState

                } else {
                    Log.e("MainActivity", "Token exchange failed: ${response.code}")
                }
            }
        })
    }
}