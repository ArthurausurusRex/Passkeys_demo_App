import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import net.openid.appauth.AuthState
import org.json.JSONException

class AuthStateManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("AuthStatePreference", Context.MODE_PRIVATE)

    var current: AuthState
        get() = readState()
        set(value) = writeState(value)

    private fun readState(): AuthState {
        val currentState = prefs.getString(KEY_STATE, null) ?: return AuthState()
        return try {
            AuthState.jsonDeserialize(currentState)
        } catch (exception: JSONException) {
            Log.w("AuthStateManager", "Failed to deserialize stored auth state - discarding")
            AuthState()
        }
    }

    private fun writeState(state: AuthState) {
        prefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply()
    }

    fun replace(state: AuthState) {
        this.current = state
    }
    companion object {
        private const val KEY_STATE = "state"
    }
}