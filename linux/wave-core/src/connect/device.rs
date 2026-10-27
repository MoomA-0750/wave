//! 身元鍵（ECDSA）と受け取りの鍵（ECDH）をソフトウェアで持つ端末。鍵は PKCS#8 で書き出せる（保存するときは呼ぶ側が保護する。例: キーリング）。

use p256::SecretKey;
use p256::pkcs8::{DecodePrivateKey, EncodePrivateKey};

use super::ConnectError;
use super::chain::{self, DeviceIdentity, DeviceSigner};
use super::crypto::{self, EcPublicKey};
use super::doc::KxOpener;
use super::models::{MemberKeys, Role};

pub struct SoftwareDevice {
    identity: SecretKey,
    kx: SecretKey,
    device_id: String,
    identity_pub: Vec<u8>,
    kx_pub: EcPublicKey,
}

/// 秘密鍵は出さない。
impl std::fmt::Debug for SoftwareDevice {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "SoftwareDevice({})", self.device_id)
    }
}

impl SoftwareDevice {
    pub fn new(identity: SecretKey, kx: SecretKey) -> Self {
        let identity_pub = crypto::encode_public_key(&identity);
        let device_id = crypto::device_id(&identity_pub);
        let kx_pub = crypto::public_key_of(&kx);
        Self { identity, kx, device_id, identity_pub, kx_pub }
    }

    /// 新しい鍵で作る。
    pub fn generate() -> Self {
        Self::new(crypto::generate_key(), crypto::generate_key())
    }

    /// PKCS#8（DER）の秘密鍵から作る。P-256 でない鍵・読めない鍵は Err。
    pub fn from_pkcs8(identity_pkcs8: &[u8], kx_pkcs8: &[u8]) -> Result<Self, ConnectError> {
        let identity = SecretKey::from_pkcs8_der(identity_pkcs8).map_err(|_| ConnectError::InvalidArgument("身元の鍵が P-256 の PKCS#8 ではない"))?;
        let kx = SecretKey::from_pkcs8_der(kx_pkcs8).map_err(|_| ConnectError::InvalidArgument("受け取りの鍵が P-256 の PKCS#8 ではない"))?;
        Ok(Self::new(identity, kx))
    }

    /// 身元の秘密鍵の PKCS#8（DER）。保存するときは呼ぶ側が保護する。
    pub fn identity_pkcs8(&self) -> Vec<u8> {
        self.identity.to_pkcs8_der().expect("P-256 の秘密鍵は常に PKCS#8 にできる").as_bytes().to_vec()
    }

    /// 受け取りの秘密鍵の PKCS#8（DER）。保存するときは呼ぶ側が保護する。
    pub fn kx_pkcs8(&self) -> Vec<u8> {
        self.kx.to_pkcs8_der().expect("P-256 の秘密鍵は常に PKCS#8 にできる").as_bytes().to_vec()
    }

    pub fn kx_public(&self) -> &EcPublicKey {
        &self.kx_pub
    }

    /// 家に加わるときの鍵の記述。
    pub fn member_keys(&self, role: Role) -> MemberKeys {
        chain::member_keys(self, &self.kx_pub, role)
    }
}

impl DeviceIdentity for SoftwareDevice {
    fn device_id(&self) -> &str {
        &self.device_id
    }
}

impl DeviceSigner for SoftwareDevice {
    fn identity_pub(&self) -> &[u8] {
        &self.identity_pub
    }

    fn sign(&self, label: &str, message: &[u8]) -> Vec<u8> {
        crypto::sign(&self.identity, label, message)
    }
}

impl KxOpener for SoftwareDevice {
    fn kx_pub(&self) -> &EcPublicKey {
        &self.kx_pub
    }

    fn dh(&self, peer: &EcPublicKey) -> Option<Vec<u8>> {
        Some(crypto::ecdh(&self.kx, peer))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkcs8_round_trips_and_debug_hides_keys() {
        let device = SoftwareDevice::generate();
        let again = SoftwareDevice::from_pkcs8(&device.identity_pkcs8(), &device.kx_pkcs8()).unwrap();
        assert_eq!(device.device_id(), again.device_id());
        assert_eq!(device.kx_pub(), again.kx_pub());
        assert!(SoftwareDevice::from_pkcs8(&[1, 2, 3], &device.kx_pkcs8()).is_err());
        let text = format!("{device:?}");
        assert_eq!(format!("SoftwareDevice({})", device.device_id()), text);
    }
}
