//! RFC 9180 HPKE の base モード、`DHKEM(P-256, HKDF-SHA256)`・`HKDF-SHA256`・`AES-128-GCM`（Android 版・Windows 版の Hpke と同じ）。
//! 付録 A.3 のテストベクタで確かめる。書き込みごとの内容鍵を、家の端末それぞれの受け取りの鍵（kx）へ包むのに使う。
//! 受け手の DH は関数で受ける。公開鍵の送る形は非圧縮 SEC1（65バイト）。

use p256::{PublicKey, SecretKey};

use super::crypto::{self, EcPublicKey, hkdf};

const KEM_ID: u64 = 0x0010;
const KDF_ID: u64 = 0x0001;
const AEAD_ID: u64 = 0x0001;
const MODE_BASE: u8 = 0x00;
const N_SECRET: usize = 32;
const N_K: usize = 16;
const N_N: usize = 12;
pub const N_ENC: usize = 65;

const VERSION_LABEL: &[u8] = b"HPKE-v1";

fn kem_suite() -> Vec<u8> {
    [b"KEM".as_slice(), &i2osp(KEM_ID, 2)].concat()
}

fn hpke_suite() -> Vec<u8> {
    [b"HPKE".as_slice(), &i2osp(KEM_ID, 2), &i2osp(KDF_ID, 2), &i2osp(AEAD_ID, 2)].concat()
}

/// 封じた結果。enc は使い捨ての鍵の公開鍵（SEC1）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sealed {
    pub enc: Vec<u8>,
    pub ciphertext: Vec<u8>,
}

/// 受け手の公開鍵へ、1回だけの暗号化（シーケンス番号 0）。
pub fn seal(recipient: &EcPublicKey, info: &[u8], aad: &[u8], plaintext: &[u8]) -> Sealed {
    seal_with(recipient, info, aad, plaintext, &crypto::generate_key())
}

/// 使い捨ての鍵を外から与える（テスト用）。
pub(crate) fn seal_with(recipient: &EcPublicKey, info: &[u8], aad: &[u8], plaintext: &[u8], ephemeral: &SecretKey) -> Sealed {
    let (shared_secret, enc) = encap(recipient, ephemeral);
    let ciphertext = key_schedule(&shared_secret, info).seal(0, aad, plaintext);
    Sealed { enc, ciphertext }
}

/// 開く。認証に失敗したら None。
/// - `recipient_public`: 受け手自身の公開鍵（kem_context に入る）
/// - `dh`: 受け手の秘密鍵で、渡された公開鍵との ECDH の x 座標（32バイト）を返す。できなければ None
pub fn open(
    enc: &[u8],
    recipient_public: &EcPublicKey,
    dh: impl FnOnce(&EcPublicKey) -> Option<Vec<u8>>,
    info: &[u8],
    aad: &[u8],
    ciphertext: &[u8],
) -> Option<Vec<u8>> {
    let shared_secret = decap(enc, recipient_public, dh)?;
    key_schedule(&shared_secret, info).open(0, aad, ciphertext)
}

/// 非圧縮 SEC1（0x04 ‖ X ‖ Y）。
pub fn serialize_public_key(key: &EcPublicKey) -> Vec<u8> {
    [&[0x04u8][..], key.x(), key.y()].concat()
}

/// 非圧縮 SEC1 を読む。長さ・形・曲線上の点を確かめる（曲線上の点かは p256 が確かめる）。
pub fn deserialize_public_key(encoded: &[u8]) -> Option<EcPublicKey> {
    if encoded.len() != N_ENC || encoded[0] != 0x04 {
        return None;
    }
    let key = PublicKey::from_sec1_bytes(encoded).ok()?;
    crypto::decode_public_key(EcPublicKey::from_key(key).spki())
}

/// 付録 A.3 の ikm から秘密鍵のスカラーを導く（DeriveKeyPair、P-256 のビットマスクは 0xff）。テスト用。
#[cfg(test)]
pub(crate) fn derive_secret_scalar(ikm: &[u8]) -> SecretKey {
    let suite = kem_suite();
    let prk = labeled_extract(&suite, &[], "dkp_prk", ikm);
    for counter in 0..=255u8 {
        let bytes = labeled_expand(&suite, &prk, "candidate", &[counter], N_SECRET);
        // 0 と群の位数以上は SecretKey にできない
        if let Ok(key) = SecretKey::from_slice(&bytes) {
            return key;
        }
    }
    panic!("DeriveKeyPair failed");
}

fn encap(recipient: &EcPublicKey, ephemeral: &SecretKey) -> (Vec<u8>, Vec<u8>) {
    let validated = crypto::decode_public_key(recipient.spki()).expect("invalid recipient key");
    let dh = crypto::ecdh(ephemeral, &validated);
    let enc = serialize_public_key(&crypto::public_key_of(ephemeral));
    let kem_context = [enc.as_slice(), &serialize_public_key(&validated)].concat();
    (extract_and_expand(&dh, &kem_context), enc)
}

/// 付録 A.3 の shared_secret の確認（テスト用）。
#[cfg(test)]
pub(crate) fn encap_for_test(recipient: &EcPublicKey, ephemeral: &SecretKey) -> (Vec<u8>, Vec<u8>) {
    encap(recipient, ephemeral)
}

fn decap(enc: &[u8], recipient_public: &EcPublicKey, dh: impl FnOnce(&EcPublicKey) -> Option<Vec<u8>>) -> Option<Vec<u8>> {
    let ephemeral = deserialize_public_key(enc)?;
    let shared = dh(&ephemeral)?;
    if shared.len() != N_SECRET {
        return None;
    }
    let kem_context = [enc, &serialize_public_key(recipient_public)].concat();
    Some(extract_and_expand(&shared, &kem_context))
}

fn extract_and_expand(dh: &[u8], kem_context: &[u8]) -> Vec<u8> {
    let suite = kem_suite();
    let prk = labeled_extract(&suite, &[], "eae_prk", dh);
    labeled_expand(&suite, &prk, "shared_secret", kem_context, N_SECRET)
}

/// 鍵の予定（key schedule）の結果。中身は秘密なので Debug を付けない。
pub(crate) struct Context {
    pub(crate) key: Vec<u8>,
    pub(crate) base_nonce: Vec<u8>,
    #[cfg_attr(not(test), allow(dead_code))]
    pub(crate) exporter_secret: Vec<u8>,
}

impl Context {
    pub(crate) fn seal(&self, sequence: u64, aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
        crypto::aes_gcm_seal(&self.key, &self.nonce_for(sequence), aad, plaintext)
    }

    pub(crate) fn open(&self, sequence: u64, aad: &[u8], ciphertext: &[u8]) -> Option<Vec<u8>> {
        crypto::aes_gcm_open(&self.key, &self.nonce_for(sequence), aad, ciphertext)
    }

    fn nonce_for(&self, sequence: u64) -> Vec<u8> {
        let seq = i2osp(sequence, N_N);
        self.base_nonce.iter().zip(seq).map(|(n, s)| n ^ s).collect()
    }
}

pub(crate) fn key_schedule(shared_secret: &[u8], info: &[u8]) -> Context {
    let suite = hpke_suite();
    let psk_id_hash = labeled_extract(&suite, &[], "psk_id_hash", &[]);
    let info_hash = labeled_extract(&suite, &[], "info_hash", info);
    let key_schedule_context = [&[MODE_BASE][..], &psk_id_hash, &info_hash].concat();
    let secret = labeled_extract(&suite, shared_secret, "secret", &[]);
    Context {
        key: labeled_expand(&suite, &secret, "key", &key_schedule_context, N_K),
        base_nonce: labeled_expand(&suite, &secret, "base_nonce", &key_schedule_context, N_N),
        exporter_secret: labeled_expand(&suite, &secret, "exp", &key_schedule_context, N_SECRET),
    }
}

fn labeled_extract(suite: &[u8], salt: &[u8], label: &str, ikm: &[u8]) -> Vec<u8> {
    hkdf::extract(salt, &[VERSION_LABEL, suite, label.as_bytes(), ikm].concat())
}

fn labeled_expand(suite: &[u8], prk: &[u8], label: &str, info: &[u8], length: usize) -> Vec<u8> {
    let labeled_info = [&i2osp(length as u64, 2)[..], VERSION_LABEL, suite, label.as_bytes(), info].concat();
    hkdf::expand(prk, &labeled_info, length)
}

fn i2osp(value: u64, length: usize) -> Vec<u8> {
    (0..length)
        .map(|i| {
            let shift = length - 1 - i;
            if shift >= 8 { 0 } else { (value >> (8 * shift)) as u8 }
        })
        .collect()
}

/// RFC 9180 付録 A.3（DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM）の base モードのテストベクタ。
#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        hex::decode(s).unwrap()
    }

    const INFO: &str = "4f6465206f6e2061204772656369616e2055726e";
    const IKM_E: &str = "4270e54ffd08d79d5928020af4686d8f6b7d35dbe470265f1f5aa22816ce860e";
    const PK_EM: &str = "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4";
    const SK_EM: &str = "4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb";
    const IKM_R: &str = "668b37171f1072f3cf12ea8a236a45df23fc13b82af3609ad1e354f6ef817550";
    const PK_RM: &str = "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0";
    const SK_RM: &str = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2";
    const SHARED_SECRET: &str = "c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8";
    const KEY: &str = "868c066ef58aae6dc589b6cfdd18f97e";
    const BASE_NONCE: &str = "4e0bc5018beba4bf004cca59";
    const EXPORTER_SECRET: &str = "14ad94af484a7ad3ef40e9f3be99ecc6fa9036df9d4920548424df127ee0d99f";
    const PT: &str = "4265617574792069732074727574682c20747275746820626561757479";

    const ENCRYPTIONS: [(u64, &str, &str); 6] = [
        (0, "436f756e742d30", "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"),
        (1, "436f756e742d31", "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06646e0329cbdf3c3cd655b28e82"),
        (2, "436f756e742d32", "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b2ccc93e466c28fb55fed7a7fec"),
        (4, "436f756e742d34", "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec999dd36ce042e44f0c3169a6a8f"),
        (255, "436f756e742d323535", "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848aa53c538c30a4dac71d619ee2cd"),
        (256, "436f756e742d323536", "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f367f8ffe5e41cd3e9962a6afb2"),
    ];

    #[test]
    fn derive_key_pair_matches_vectors() {
        assert_eq!(hex(SK_EM), derive_secret_scalar(&hex(IKM_E)).to_bytes().to_vec());
        assert_eq!(hex(SK_RM), derive_secret_scalar(&hex(IKM_R)).to_bytes().to_vec());
    }

    #[test]
    fn encap_and_key_schedule_match_vectors() {
        let pk_e = deserialize_public_key(&hex(PK_EM)).unwrap();
        let pk_r = deserialize_public_key(&hex(PK_RM)).unwrap();
        assert_eq!(hex(PK_EM), serialize_public_key(&pk_e));
        let ephemeral = SecretKey::from_slice(&hex(SK_EM)).unwrap();
        assert_eq!(pk_e, crypto::public_key_of(&ephemeral));
        let (shared, enc) = encap_for_test(&pk_r, &ephemeral);
        assert_eq!(hex(PK_EM), enc);
        assert_eq!(hex(SHARED_SECRET), shared);
        let context = key_schedule(&shared, &hex(INFO));
        assert_eq!(hex(KEY), context.key);
        assert_eq!(hex(BASE_NONCE), context.base_nonce);
        assert_eq!(hex(EXPORTER_SECRET), context.exporter_secret);
        for (seq, aad, ct) in ENCRYPTIONS {
            assert_eq!(hex(ct), context.seal(seq, &hex(aad), &hex(PT)));
            assert_eq!(Some(hex(PT)), context.open(seq, &hex(aad), &hex(ct)));
        }
    }

    #[test]
    fn open_with_recipient_dh_round_trips_and_rejects_tampering() {
        let pk_r = deserialize_public_key(&hex(PK_RM)).unwrap();
        let recipient = SecretKey::from_slice(&hex(SK_RM)).unwrap();
        let dh = |peer: &EcPublicKey| Some(crypto::ecdh(&recipient, peer));
        let info = hex(INFO);
        let pt = hex(PT);
        let sealed = seal(&pk_r, &info, &[1], &pt);
        assert_eq!(Some(pt.clone()), open(&sealed.enc, &pk_r, dh, &info, &[1], &sealed.ciphertext));
        assert_eq!(None, open(&sealed.enc, &pk_r, dh, &info, &[2], &sealed.ciphertext));
        assert_eq!(None, open(&sealed.enc, &pk_r, dh, &[0], &[1], &sealed.ciphertext));
        let mut flipped = sealed.ciphertext.clone();
        flipped[0] ^= 1;
        assert_eq!(None, open(&sealed.enc, &pk_r, dh, &info, &[1], &flipped));
        let other = crypto::generate_key();
        let other_pub = crypto::public_key_of(&other);
        assert_eq!(None, open(&serialize_public_key(&other_pub), &pk_r, dh, &info, &[1], &sealed.ciphertext));
        assert_eq!(None, open(&sealed.enc, &other_pub, |p| Some(crypto::ecdh(&other, p)), &info, &[1], &sealed.ciphertext));
        // 受け手の DH ができなかった
        assert_eq!(None, open(&sealed.enc, &pk_r, |_| None, &info, &[1], &sealed.ciphertext));
    }

    #[test]
    fn rejects_malformed_encoded_points() {
        let pk_em = hex(PK_EM);
        assert!(deserialize_public_key(&pk_em[..64]).is_none());
        assert!(deserialize_public_key(&[&[0x02u8][..], &pk_em[1..33]].concat()).is_none());
        assert!(deserialize_public_key(&[0x00]).is_none());
        assert!(deserialize_public_key(&[&[0x04u8][..], &[0u8; 64]].concat()).is_none());
        let mut off_curve = pk_em.clone();
        off_curve[64] ^= 1;
        assert!(deserialize_public_key(&off_curve).is_none());
        let mut prefixed = pk_em.clone();
        prefixed[0] = 0x03;
        assert!(deserialize_public_key(&prefixed).is_none());
    }
}
