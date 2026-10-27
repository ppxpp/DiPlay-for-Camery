package com.shilapi.xcertplay.btprobe

import android.content.Context
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File

/** Same provider/provisioning pattern as DiPlayBootstrap; never log key or certificate bytes. */
internal object ProbeAuthentication {
    fun load(context: Context, log: (String) -> Unit): LocalMfiAuthenticationClient {
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "probe-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "无法创建认证目录" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "无法安装认证素材" }
            } finally { staging.deleteRecursively() }
        }
        return LocalMfiAuthenticationClient.load(target) { size -> log("MFi challenge 已签名 bytes=$size") }
            .also { log("MFi 本地素材加载及密钥/证书匹配检查通过；仍需 iPhone AA05 确认") }
    }
    fun identity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences("probe-airplay-identity", Context.MODE_PRIVATE)
        val private = prefs.getString("private", null)
        if (private != null) return AirPlayIdentity(Base64.decode(private, Base64.NO_WRAP),
            Base64.decode(checkNotNull(prefs.getString("public", null)), Base64.NO_WRAP),
            checkNotNull(prefs.getString("id", null)))
        return AirPlayIdentity.generate().also {
            check(prefs.edit().putString("private", Base64.encodeToString(it.privateKey, Base64.NO_WRAP))
                .putString("public", Base64.encodeToString(it.publicKey, Base64.NO_WRAP))
                .putString("id", it.pairingId).commit()) { "无法保存接收器身份" }
        }
    }
    fun pairings(context: Context): PairingStore {
        val prefs = context.getSharedPreferences("probe-pairings", Context.MODE_PRIVATE)
        val store = PairingStore { id, key ->
            check(prefs.edit().putString(id, Base64.encodeToString(key, Base64.NO_WRAP)).commit())
        }
        prefs.all.forEach { (id, key) -> (key as? String)?.let { store.save(id, Base64.decode(it, Base64.NO_WRAP)) } }
        return store
    }
}
