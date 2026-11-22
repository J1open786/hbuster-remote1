package com.hbuster.remote

import android.content.Context
import android.content.SharedPreferences
import android.hardware.ConsumerIrManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------- Envio do sinal infravermelho (protocolo NEC) ----------
class IrSender(context: Context) {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val disponivel: Boolean
        get() = ir?.hasIrEmitter() == true

    fun enviarNec(endereco: Int, comando: Int) {
        val manager = ir ?: return
        if (!manager.hasIrEmitter()) return

        val a = endereco and 0xFF
        val c = comando and 0xFF
        // NEC: endereço, endereço invertido, comando, comando invertido (bit menos significativo primeiro)
        val bits = a or ((a.inv() and 0xFF) shl 8) or (c shl 16) or ((c.inv() and 0xFF) shl 24)

        val padrao = ArrayList<Int>()
        padrao.add(9000)
        padrao.add(4500)
        for (i in 0 until 32) {
            padrao.add(560)
            padrao.add(if (((bits shr i) and 1) == 1) 1690 else 560)
        }
        padrao.add(560)

        manager.transmit(38000, padrao.toIntArray())
    }
}

// ---------- Botões do controle ----------
val BOTOES = listOf(
    "power" to "Liga/Desliga",
    "mute" to "Mudo",
    "source" to "Fonte",
    "vol_up" to "Vol +",
    "vol_down" to "Vol −",
    "menu" to "Menu",
    "ch_up" to "Canal +",
    "ch_down" to "Canal −",
    "ok" to "OK",
    "up" to "▲",
    "down" to "▼",
    "left" to "◀",
    "right" to "▶"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sender = IrSender(this)
        val prefs = getSharedPreferences("hbuster", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                TelaPrincipal(sender, prefs)
            }
        }
    }
}

@Composable
fun TelaPrincipal(sender: IrSender, prefs: SharedPreferences) {
    var enderecoTexto by remember { mutableStateOf(prefs.getString("endereco", "00") ?: "00") }
    var comando by remember { mutableIntStateOf(0) }
    var varrendo by remember { mutableStateOf(false) }
    var modoConfigurar by remember { mutableStateOf(false) }
    var botaoAlvo by remember { mutableStateOf("power") }
    var mensagem by remember { mutableStateOf("") }
    // Muda toda vez que um comando é salvo, para a tela atualizar
    var versao by remember { mutableIntStateOf(0) }

    val endereco = enderecoTexto.toIntOrNull(16) ?: 0

    // Varredura automática: manda um comando a cada 600 ms
    LaunchedEffect(varrendo) {
        if (varrendo) {
            while (varrendo && comando < 255) {
                sender.enviarNec(endereco, comando)
                delay(600)
                if (varrendo) comando++
            }
            varrendo = false
        }
    }

    fun comandoSalvo(chave: String): Int = prefs.getInt("cmd_$chave", -1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Controle H-Buster", style = MaterialTheme.typography.headlineSmall)

        if (!sender.disponivel) {
            Text("Este celular não tem emissor infravermelho (ou o Android não liberou).")
        }

        OutlinedTextField(
            value = enderecoTexto,
            onValueChange = {
                enderecoTexto = it.filter { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' }.take(2)
                prefs.edit().putString("endereco", enderecoTexto).apply()
            },
            label = { Text("Endereço NEC (hex, 00 a FF)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = modoConfigurar, onCheckedChange = { modoConfigurar = it })
            Text(
                if (modoConfigurar) "Modo descobrir códigos (tocar escolhe o botão)"
                else "Modo controle (tocar envia o sinal)",
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Teclado do controle
        val linhas = BOTOES.chunked(3)
        for (linha in linhas) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((chave, rotulo) in linha) {
                    val salvo = comandoSalvo(chave)
                    val selecionado = modoConfigurar && botaoAlvo == chave
                    // 'versao' é lido aqui só para forçar a atualização da tela
                    val texto = if (versao >= 0 && salvo >= 0) "$rotulo\n(%02X)".format(salvo) else rotulo
                    Button(
                        onClick = {
                            if (modoConfigurar) {
                                botaoAlvo = chave
                                mensagem = "Botão escolhido: $rotulo"
                            } else if (salvo >= 0) {
                                sender.enviarNec(endereco, salvo)
                            } else {
                                mensagem = "\"$rotulo\" ainda não tem código. Ligue o modo descobrir."
                            }
                        },
                        colors = if (selecionado) ButtonDefaults.buttonColors()
                        else ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(texto)
                    }
                }
                // Completa a linha para os botões não esticarem
                repeat(3 - linha.size) {
                    Row(modifier = Modifier.weight(1f)) {}
                }
            }
        }

        if (mensagem.isNotEmpty()) {
            Text(mensagem, color = MaterialTheme.colorScheme.primary)
        }

        // Área de descoberta
        if (modoConfigurar) {
            val nomeAlvo = BOTOES.first { it.first == botaoAlvo }.second
            Text("Descobrir código de: $nomeAlvo", style = MaterialTheme.typography.titleMedium)
            Text("Comando atual: %02X (%d de 255)".format(comando, comando))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (comando > 0) comando-- }) { Text("−1") }
                OutlinedButton(onClick = { if (comando < 255) comando++ }) { Text("+1") }
                OutlinedButton(onClick = { sender.enviarNec(endereco, comando) }) { Text("Enviar") }
            }

            Button(
                onClick = {
                    if (!varrendo && comando >= 255) comando = 0
                    varrendo = !varrendo
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (varrendo) "Parar varredura" else "Iniciar varredura automática")
            }

            Button(
                onClick = {
                    varrendo = false
                    prefs.edit().putInt("cmd_$botaoAlvo", comando).apply()
                    versao++
                    mensagem = "Salvo %02X em \"$nomeAlvo\"".format(comando)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Salvar comando atual em \"$nomeAlvo\"")
            }

            Text(
                "Como usar: aponte o celular para a TV, perto do sensor. Escolha o botão acima, " +
                    "inicie a varredura e pare quando a TV reagir. Como você demora um pouco para parar, " +
                    "use −1 e Enviar para achar o comando exato que fez a TV reagir. Depois salve.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
