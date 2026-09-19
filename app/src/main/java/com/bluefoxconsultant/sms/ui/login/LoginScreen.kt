package com.bluefoxconsultant.sms.ui.login

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.socle.EcranDeconnecte
import com.bluefoxconsultant.sms.ui.asString

/**
 * L'instance est connue, la session ne l'est pas encore.
 *
 * ⚠️ Même gabarit que les quatre applications sœurs : le logo Symbifox
 * officiel, le mot « Symbifox », le nom de l'application, puis l'adresse de
 * l'instance à laquelle on s'apprête à se connecter.
 */
@Composable
fun LoginScreen(
    instanceUrl: String,
    pendingAuthUri: String?,
    onAuthConsumed: () -> Unit,
    vm: AuthViewModel = viewModel(),
) {
    val context = LocalContext.current

    // Complete the web-login flow when the deep link comes back.
    LaunchedEffect(pendingAuthUri) {
        if (pendingAuthUri != null) {
            vm.handleRedirect(context, pendingAuthUri)
            onAuthConsumed()
        }
    }

    EcranDeconnecte(
        application = stringResource(R.string.app_name),
        promesse = instanceUrl.removePrefix("https://"),
    ) {
        Button(
            onClick = { vm.startLogin(context) },
            enabled = !vm.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.loading) {
                CircularProgressIndicator(
                    // ⚠️ Pas `Color.White` en dur : ce qui se lit sur l'accent
                    // se calcule, et le socle l'a déjà calculé dans `onPrimary`.
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(stringResource(R.string.login_sign_in))
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.changeServer() }, enabled = !vm.loading) {
            Text(stringResource(R.string.login_change_server))
        }

        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it.asString(),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
