package xyz.mooma0750.wave.handoff.v2

import xyz.mooma0750.wave.handoff.crypto.EnvelopeSealer
import xyz.mooma0750.wave.handoff.crypto.WaveCrypto
import java.security.interfaces.ECPublicKey

/** テスト用の端末（身元鍵と kx 鍵をソフトウェアで持つ）。 */
class TestDevice(
    val role: Role = Role.Player,
) : DeviceSigner, KxOpener {
    private val identity = WaveCrypto.generateEphemeralKeyPair()
    private val kx = WaveCrypto.generateEphemeralKeyPair()

    override val identityPub: ByteArray = WaveCrypto.encodePublicKey(identity.public)
    override val deviceId: String = EnvelopeSealer.deviceId(identityPub)
    override val kxPub: ECPublicKey = kx.public as ECPublicKey

    override fun sign(
        label: String,
        message: ByteArray,
    ): ByteArray = WaveCrypto.sign(identity.private, label, message)

    override fun dh(peer: ECPublicKey): ByteArray = WaveCrypto.ecdh(kx.private, peer)

    val keys: MemberKeys by lazy { MemberChain.memberKeys(this, kxPub, role) }
}

fun List<ChainRecord>.verified(): VerifiedChain = (MemberChain.verify(this) as ChainVerification.Ok).chain
