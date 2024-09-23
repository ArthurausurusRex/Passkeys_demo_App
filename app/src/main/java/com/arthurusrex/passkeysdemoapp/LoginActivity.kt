package com.arthurusrex.passkeysdemoapp

//The goal of the application is to display a login page with a simple login button.
//When the user clicks the login button it will trigger an OpenId Connect authorization clode flow

import AuthStateManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arthurusrex.passkeysdemoapp.ui.theme.BoldWhite
import com.arthurusrex.passkeysdemoapp.ui.theme.PasskeysDemoAppTheme
import com.arthurusrex.passkeysdemoapp.ui.theme.WavestonePurple
import com.arthurusrex.passkeysdemoapp.ui.theme.WhiteBorder
import com.transmit.authentication.AuthenticationResult
import com.transmit.authentication.TSAuthCallback
import com.transmit.authentication.TSAuthentication
import com.transmit.authentication.TSWebAuthnAuthenticationError
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

class LoginActivity : ComponentActivity() {
    private lateinit var authService: AuthorizationService
    private lateinit var authRequest: AuthorizationRequest
    private lateinit var serviceConfiguration: AuthorizationServiceConfiguration
    private val authorizationResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val authResponse = AuthorizationResponse.fromIntent(data)
        val authException = AuthorizationException.fromIntent(data)

        if (authResponse != null) {
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
                        AuthStateManager(this@LoginActivity).current = authState
                        if(authState.idToken != null){

                            val intent = Intent(this@LoginActivity, LoggedInActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                    } else {
                        Log.e("LoginActivity", "Token exchange failed: ${response.code}")
                    }
                }
            })
        } else {
            Log.e("LoginActivity", "Authorization failed", authException)
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val configuration = Configuration.getInstance(this)

        val clientId = configuration.clientId
        val redirectUri = configuration.redirectUri
        val scopes = configuration.scope?.split(" ")
        TSAuthentication.initialize(
            this,
            clientId!!,
            "https://api.transmitsecurity.io/"
        )
        val scope = CoroutineScope(Job() +Dispatchers.Main)
        TSAuthentication.authenticateWebAuthn(this,
            callback =  object : TSAuthCallback<AuthenticationResult, TSWebAuthnAuthenticationError> {
                override fun success(result: AuthenticationResult) {
                    val encodedResult = result.result()
                    Log.i("LoginActivity", "WebAuthn authentication success: $encodedResult")
                    scope.launch {
                        completeAuthentication(encodedResult, serviceConfiguration)

                    }

                }

                override fun error(error: TSWebAuthnAuthenticationError) {
                    Log.e("LoginActivity", "WebAuthn authentication error: $error")
                }
            })
        AuthorizationServiceConfiguration.fetchFromIssuer(
            Uri.parse("https://api.transmitsecurity.io/cis/oidc/")
        ) { serviceConfig, ex ->
            if (ex != null) {
                Log.e("LoginActivity", "Failed to retrieve configuration", ex)
                return@fetchFromIssuer
            }
            this.serviceConfiguration = serviceConfig!!
            val authRequestBuilder = AuthorizationRequest.Builder(
                serviceConfig!!,
                clientId!!,
                ResponseTypeValues.CODE,
                redirectUri!!
            )

            scopes?.let {
                authRequestBuilder.setScopes(it)
            }
            authRequestBuilder.setPromptValues("consent")
            authRequest = authRequestBuilder.build()
            authService = AuthorizationService(this)
        }



        setContent {
            PasskeysDemoAppTheme {
                LoginScreen()
            }
        }
    }

    private suspend fun completeAuthentication(encodedResult: String, serviceConfiguration: AuthorizationServiceConfiguration) {
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
                if (!response.isSuccessful) throw IOException("Unexpected code $response and ${response.body?.string()}")

                // Handle the response

                val responseJson = response.body?.string()
                Log.i("Backend Response", responseJson!!)

                // Parse the responseJson to a JSONObject
                val jsonObject = JSONObject(responseJson)
                val authState = AuthStateManager(this@LoginActivity).current
                val configuration = Configuration.getInstance(this@LoginActivity)
                val tokenRequest = TokenRequest.Builder(serviceConfiguration, configuration.clientId!!).setGrantType("custom").build()
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

                authState.update(tokenResponse, null)
                AuthStateManager(this@LoginActivity).current = authState
                if(authState.idToken != null){
                    val intent = Intent(this@LoginActivity, LoggedInActivity::class.java)
                    startActivity(intent)
                    finish()
                }

            }
        }
    }


    @Composable
    fun LoginScreen() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(color = WavestonePurple, modifier = Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Passkeys Demo App", style = BoldWhite)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val intent = authService.getAuthorizationRequestIntent(authRequest)
                            authorizationResultLauncher.launch(intent)
                        },
                        border = WhiteBorder,
                        colors = ButtonDefaults.buttonColors(containerColor = WavestonePurple)
                    ) {
                        Text("Login", color = Color.White)
                    }
                }
            }
        }
    }
}
