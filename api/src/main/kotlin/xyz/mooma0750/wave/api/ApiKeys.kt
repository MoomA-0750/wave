package xyz.mooma0750.wave.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import xyz.mooma0750.wave.handoff.v2.DeviceSigner
import xyz.mooma0750.wave.handoff.v2.KxOpener
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * wave-api の端末の鍵（身元の署名鍵と受け取りの鍵 kx、どちらも P-256）。サーバーには Keystore が無いので、
 * ソフトウェアで作ってデータのディレクトリに権限 600 で置く（ADR-0015。その機械に入れる人は家の状態を読める）。
 */
class ApiKeys private constructor(
    private val identity: KeyPair,
    private val kx: KeyPair,
) : DeviceSigner,
    KxOpener {
    override val identityPub: ByteArray = WaveCrypto.encodePublicKey(identity.public)
    override val deviceId: String = EnvelopeSealer.deviceId(identityPub)
    override val kxPub: ECPublicKey = kx.public as ECPublicKey
    val identityPrivate: PrivateKey get() = identity.private
    val identityPublic: PublicKey get() = identity.public

    override fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray = WaveCrypto.sign(identity.private, label, message)

    override fun dh(peer: ECPublicKey): ByteArray = WaveCrypto.ecdh(kx.private, requireNotNull(WaveCrypto.decodePublicKey(peer.encoded)) { "invalid peer key" })

    @Serializable
    private data class Stored(
        val identityPrivate: String,
        val identityPublic: String,
        val kxPrivate: String,
        val kxPublic: String,
    )

    companion object {
        private val b64 = Base64.getUrlEncoder().withoutPadding()
        private val unb64 = Base64.getUrlDecoder()
        private val json = Json { ignoreUnknownKeys = true }

        /** 置いてある鍵を読む。無ければ作って置く。読めない鍵は作り直さない（家の記録と合わなくなるので、止めて本人に知らせる）。 */
        fun loadOrCreate(data: DataDir): ApiKeys {
            val text = data.read(DataDir.KEYS)
            if (text != null) {
                val stored = json.decodeFromString(Stored.serializer(), text)
                val factory = KeyFactory.getInstance("EC")
                fun pair(
                    private: String,
                    public: String,
                ) = KeyPair(factory.generatePublic(X509EncodedKeySpec(unb64.decode(public))), factory.generatePrivate(PKCS8EncodedKeySpec(unb64.decode(private))))
                return ApiKeys(pair(stored.identityPrivate, stored.identityPublic), pair(stored.kxPrivate, stored.kxPublic))
            }
            val identity = WaveCrypto.generateEphemeralKeyPair()
            val kx = WaveCrypto.generateEphemeralKeyPair()
            val stored = Stored(b64.encodeToString(identity.private.encoded), b64.encodeToString(identity.public.encoded), b64.encodeToString(kx.private.encoded), b64.encodeToString(kx.public.encoded))
            data.write(DataDir.KEYS, json.encodeToString(Stored.serializer(), stored))
            return ApiKeys(identity, kx)
        }
    }
}
