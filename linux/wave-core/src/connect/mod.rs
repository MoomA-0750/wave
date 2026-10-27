//! Connect v2（家の端末の間で再生を引き継ぐ・操作する）の暗号とデータの層。Android 版（Kotlin、handoff-protocol）が正本で、
//! Windows 版（C#）・この版はそれとバイト単位で同じものを作り・読む。中継とのやり取り（接続・状態の流れ）はここに入れない。
//!
//! - crypto: base64url・P-256 の鍵・署名・ECDH・AES-GCM・HMAC・HKDF
//! - hpke: RFC 9180 の base モード（内容鍵を端末ごとに包む）
//! - device: ソフトウェアで鍵を持つ端末（PKCS#8 で読み書き）
//! - chain: 端末一覧の署名付きの鎖（作る・genesis から検証する）
//! - doc: SealedDoc（封じる・開く）
//! - pairing: QR と6桁でのペアリング
//! - snapshot: 引き取りのキューのスナップショットと、その検証
//! - models: JSON の形（記録・doc・カーソル・キュー・命令・ライブラリ）
//! - library_sync: ライブラリを中継の端末の間で合わせる
//! - arbiter: 同時に鳴らしたときに止まるかの規則

pub mod arbiter;
pub mod client;
pub mod chain;
pub mod crypto;
pub mod device;
pub mod doc;
pub mod hpke;
pub mod library_sync;
pub mod models;
pub mod pairing;
pub mod relay;
pub mod snapshot;
pub mod socket;

#[cfg(test)]
mod interop_tests;

pub use chain::{ChainMember, ChainVerification, DeviceIdentity, DeviceSigner, Head, VerifiedChain};
pub use crypto::{EcPublicKey, b64};
pub use device::SoftwareDevice;
pub use doc::{DocOpen, KxOpener};
pub use models::*;
pub use snapshot::{HandoffDecodeResult, HandoffItem, HandoffOrigin, HandoffRef, HandoffRepeat, HandoffSnapshot, HandoffSource};

/// 呼ぶ側の誤り（C# の ArgumentException に当たるもの）。中身に秘密を入れない。
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ConnectError {
    #[error("{0}")]
    InvalidArgument(&'static str),
}

#[cfg(test)]
pub(crate) mod test_support {
    use super::chain::{self, ChainVerification, DeviceIdentity, DeviceSigner, VerifiedChain};
    use super::crypto::EcPublicKey;
    use super::device::SoftwareDevice;
    use super::doc::KxOpener;
    use super::models::{ChainRecord, MemberKeys, Role};

    pub(crate) fn verified(records: &[ChainRecord]) -> VerifiedChain {
        match chain::verify(records, None) {
            ChainVerification::Ok(chain) => chain,
            other => panic!("検証できない: {other:?}"),
        }
    }

    /// テスト用の端末（役割を持つ）。
    pub(crate) struct TestDevice {
        inner: SoftwareDevice,
        pub(crate) keys: MemberKeys,
    }

    impl TestDevice {
        pub(crate) fn new(role: Role) -> Self {
            let inner = SoftwareDevice::generate();
            let keys = inner.member_keys(role);
            Self { inner, keys }
        }
    }

    impl DeviceIdentity for TestDevice {
        fn device_id(&self) -> &str {
            self.inner.device_id()
        }
    }

    impl DeviceSigner for TestDevice {
        fn identity_pub(&self) -> &[u8] {
            self.inner.identity_pub()
        }

        fn sign(&self, label: &str, message: &[u8]) -> Vec<u8> {
            self.inner.sign(label, message)
        }
    }

    impl KxOpener for TestDevice {
        fn kx_pub(&self) -> &EcPublicKey {
            self.inner.kx_pub()
        }

        fn dh(&self, peer: &EcPublicKey) -> Option<Vec<u8>> {
            self.inner.dh(peer)
        }
    }
}
