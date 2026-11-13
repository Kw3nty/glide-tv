package app.glidetv.engine.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Клиент ADB внутри Glide TV (libadb-android). Ключ RSA-2048 и самоподписанный сертификат
 * генерируются один раз и лежат в приватной no-backup папке приложения: именно этот ключ
 * пользователь подтверждает в диалоге «Разрешить отладку?» и при сопряжении.
 *
 * Важно: [close] из базового класса уничтожает приватный ключ, поэтому для разрыва
 * соединения используем только [disconnect].
 */
class GlideAdbManager private constructor(
    private val privateKey: PrivateKey,
    private val certificate: Certificate,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
        // Первое подключение ждёт, пока пользователь нажмёт «Разрешить» на экране ТВ.
        setTimeout(90, TimeUnit.SECONDS)
        setThrowOnUnauthorised(false)
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "GlideTV@${Build.MODEL}"

    companion object {
        @Volatile private var instance: GlideAdbManager? = null

        fun get(context: Context): GlideAdbManager =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun keyDir(context: Context): File {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
            return File(base, "adb").apply { mkdirs() }
        }

        private fun create(context: Context): GlideAdbManager {
            val dir = keyDir(context)
            val keyFile = File(dir, "adbkey.pk8")
            val certFile = File(dir, "adbkey.crt")
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                    return GlideAdbManager(key, cert)
                }
            }
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=GlideTV")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(64, SecureRandom()),
                Date(now - TimeUnit.DAYS.toMillis(1)),
                Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
                name,
                kp.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(cert.encoded)
            // Права 600: файл в приватной папке, но ограничиваем явно
            keyFile.setReadable(false, false); keyFile.setReadable(true, true)
            return GlideAdbManager(kp.private, cert)
        }
    }
}
