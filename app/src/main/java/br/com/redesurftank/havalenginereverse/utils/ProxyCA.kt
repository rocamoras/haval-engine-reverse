package br.com.redesurftank.havalenginereverse.utils

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Gerencia o CA auto-assinado e o certificado leaf para interceptação HTTPS.
 * O CA precisa ser instalado como cert de sistema no headunit para que o widget
 * confie no nosso servidor proxy.
 */
object ProxyCA {

    private const val KS_FILE    = "proxy-ca.p12"
    private const val KS_PASS    = "havalproxy"
    private const val CA_ALIAS   = "ca"
    private const val LEAF_ALIAS = "leaf"
    const val TARGET_DOMAIN      = "ap-hu-gateway.gwmcloud.com"
    const val PEM_FILE           = "proxy-ca.pem"

    private var caPrivKey:   PrivateKey?      = null
    private var caCert:      X509Certificate? = null
    private var leafPrivKey: PrivateKey?      = null
    private var leafCert:    X509Certificate? = null

    fun isInitialized() = caCert != null

    /** Carrega keystore existente ou gera novo par CA + leaf. */
    fun initialize(context: Context) {
        val ksFile = File(context.filesDir, KS_FILE)
        if (ksFile.exists()) {
            runCatching {
                val ks = KeyStore.getInstance("PKCS12")
                ksFile.inputStream().use { ks.load(it, KS_PASS.toCharArray()) }
                caPrivKey   = ks.getKey(CA_ALIAS,   KS_PASS.toCharArray()) as PrivateKey
                caCert      = ks.getCertificate(CA_ALIAS)   as X509Certificate
                leafPrivKey = ks.getKey(LEAF_ALIAS, KS_PASS.toCharArray()) as PrivateKey
                leafCert    = ks.getCertificate(LEAF_ALIAS) as X509Certificate
                exportPem(context)
                return
            }
        }
        generate(context)
    }

    /** Gera novo CA + leaf, persiste em PKCS12 e exporta PEM. */
    fun generate(context: Context) {
        val kpg  = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }
        val now  = Date()
        val end  = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)

        // CA auto-assinado
        val caKP   = kpg.generateKeyPair()
        val caName = X500Name("CN=HavalProxy CA,O=HavalReverse,C=BR")
        val caSig  = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(caKP.private)
        val caCertificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(caName, BigInteger.ONE, now, end, caName, caKP.public)
                .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
                .build(caSig)
        )

        // Leaf assinado pelo CA
        val leafKP   = kpg.generateKeyPair()
        val leafName = X500Name("CN=$TARGET_DOMAIN")
        val leafSig  = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(caKP.private)
        val san      = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, TARGET_DOMAIN)))
        val leafCertificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(2), now, end, leafName, leafKP.public)
                .addExtension(Extension.subjectAlternativeName, false, san)
                .addExtension(Extension.basicConstraints, false, BasicConstraints(false))
                .build(leafSig)
        )

        caPrivKey   = caKP.private;    caCert    = caCertificate
        leafPrivKey = leafKP.private;  leafCert  = leafCertificate

        // Persiste PKCS12
        val ks = KeyStore.getInstance("PKCS12").also { it.load(null) }
        ks.setKeyEntry(CA_ALIAS,   caKP.private,   KS_PASS.toCharArray(), arrayOf(caCertificate))
        ks.setKeyEntry(LEAF_ALIAS, leafKP.private, KS_PASS.toCharArray(), arrayOf(leafCertificate))
        File(context.filesDir, KS_FILE).outputStream().use { ks.store(it, KS_PASS.toCharArray()) }

        exportPem(context)
    }

    /** Exporta o PEM do CA para filesDir para que o shell possa instalá-lo. */
    private fun exportPem(context: Context) {
        val encoded = Base64.encodeToString(caCert!!.encoded, Base64.DEFAULT)
        val pem = "-----BEGIN CERTIFICATE-----\n$encoded-----END CERTIFICATE-----\n"
        File(context.filesDir, PEM_FILE).writeText(pem)
    }

    /** Retorna o PEM do CA (para exibição/debug). */
    fun getCaPem(context: Context): String =
        File(context.filesDir, PEM_FILE).takeIf { it.exists() }?.readText() ?: ""

    /**
     * Retorna um SSLContext configurado com o certificado leaf —
     * usado pelo SSLServerSocket do proxy.
     */
    fun serverSslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12").also { it.load(null) }
        ks.setKeyEntry(LEAF_ALIAS, leafPrivKey!!, KS_PASS.toCharArray(), arrayOf(leafCert!!))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .also { it.init(ks, KS_PASS.toCharArray()) }
        return SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, null, null) }
    }
}
