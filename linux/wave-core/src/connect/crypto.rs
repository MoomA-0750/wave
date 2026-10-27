//! Connect v2 の暗号の部品（Android 版・Windows 版の WaveCrypto と同じ形）。RustCrypto の ECDH・ECDSA・AES-GCM・HMAC だけを使い、暗号そのものは書かない。
//! 署名は JCA と同じ DER 形式、ECDH の共有秘密は x 座標そのもの（ハッシュしない）。曲線は P-256。

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes128Gcm, Aes256Gcm, Nonce};
use hmac::{Hmac, Mac};
use p256::ecdsa::signature::{Signer, Verifier};
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::pkcs8::{DecodePublicKey, EncodePublicKey};
use p256::{PublicKey, SecretKey};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

/// base64url（パディングなし）。読むときは Java の URL デコーダと同じく、パディングの有無どちらも受ける。
pub mod b64 {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    pub fn encode(data: &[u8]) -> String {
        let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
        for chunk in data.chunks(3) {
            let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
            let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
            let chars = chunk.len() + 1;
            for i in 0..chars {
                out.push(ALPHABET[((n >> (18 - 6 * i)) & 0x3f) as usize] as char);
            }
        }
        out
    }

    /// 読めなければ None（URL 用でない文字・空白・長さの崩れたもの）。
    pub fn decode(text: &str) -> Option<Vec<u8>> {
        let mut body = text;
        let mut padding = 0;
        while body.ends_with('=') && padding < 3 {
            body = &body[..body.len() - 1];
            padding += 1;
        }
        if padding > 0 && (padding > 2 || !text.len().is_multiple_of(4)) {
            return None;
        }
        if body.len() % 4 == 1 {
            return None;
        }
        let mut values = Vec::with_capacity(body.len());
        for c in body.bytes() {
            values.push(match c {
                b'A'..=b'Z' => c - b'A',
                b'a'..=b'z' => c - b'a' + 26,
                b'0'..=b'9' => c - b'0' + 52,
                b'-' => 62,
                b'_' => 63,
                _ => return None,
            });
        }
        // .NET の Convert.FromBase64String・Java の デコーダと同じく、最後の文字の余りのビットは見ない
        let mut out = Vec::with_capacity(values.len() * 3 / 4);
        for chunk in values.chunks(4) {
            let mut n = 0u32;
            for (i, v) in chunk.iter().enumerate() {
                n |= u32::from(*v) << (18 - 6 * i);
            }
            let bytes = [(n >> 16) as u8, (n >> 8) as u8, n as u8];
            out.extend_from_slice(&bytes[..chunk.len() - 1]);
        }
        Some(out)
    }
}

/// 検証済みの P-256 の公開鍵。送る形（X.509 SubjectPublicKeyInfo、正規形）と座標を持つ。
/// 作るのは [`decode_public_key`] と [`super::hpke::deserialize_public_key`] と、自分の秘密鍵から取り出すときだけ（曲線上の点を確かめたもの）。
#[derive(Clone, PartialEq, Eq)]
pub struct EcPublicKey {
    spki: Vec<u8>,
    x: [u8; 32],
    y: [u8; 32],
    key: PublicKey,
}

impl std::fmt::Debug for EcPublicKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "EcPublicKey({})", b64::encode(&self.spki))
    }
}

impl EcPublicKey {
    pub(crate) fn from_key(key: PublicKey) -> Self {
        let spki = key.to_public_key_der().expect("P-256 の公開鍵は常に SPKI にできる").as_bytes().to_vec();
        let point = key.to_encoded_point(false);
        let mut x = [0u8; 32];
        let mut y = [0u8; 32];
        x.copy_from_slice(point.x().expect("非圧縮の点"));
        y.copy_from_slice(point.y().expect("非圧縮の点"));
        Self { spki, x, y, key }
    }

    /// 送る形（SPKI）。
    pub fn spki(&self) -> &[u8] {
        &self.spki
    }

    pub fn x(&self) -> &[u8; 32] {
        &self.x
    }

    pub fn y(&self) -> &[u8; 32] {
        &self.y
    }
}

/// 使い捨ての鍵（ペアリング・HPKE）・受け取りの鍵・身元の署名鍵。どれも P-256 の秘密のスカラー。メモリだけに置く。
pub fn generate_key() -> SecretKey {
    SecretKey::random(&mut OsRng)
}

/// 公開鍵の送る形（X.509 SubjectPublicKeyInfo）。
pub fn encode_public_key(key: &SecretKey) -> Vec<u8> {
    public_key_of(key).spki
}

/// 鍵の組の公開鍵を、検証済みの形で取り出す。
pub fn public_key_of(key: &SecretKey) -> EcPublicKey {
    EcPublicKey::from_key(key.public_key())
}

/// 受け取った公開鍵を読む。P-256 で、正規形で、曲線上の点のときだけ（不正な曲線の点を使った攻撃を防ぐ）。
/// 曲線上の点か・無限遠点でないかは p256 が読むときに確かめる。
pub fn decode_public_key(encoded: &[u8]) -> Option<EcPublicKey> {
    if encoded.len() > 200 {
        return None;
    }
    // 末尾のごみ・ほかの曲線・曲線の外の点はここで落ちる
    let key = PublicKey::from_public_key_der(encoded).ok()?;
    let decoded = EcPublicKey::from_key(key);
    // 送る形を正規化したものだけ（圧縮点など、読み直すと別のバイト列になる形を受けると、端末と中継で端末 ID が食い違う）
    if decoded.spki != encoded {
        return None;
    }
    Some(decoded)
}

/// 端末 ID・指紋: 署名公開鍵の送る形の SHA-256。
pub fn fingerprint(encoded_public_key: &[u8]) -> Vec<u8> {
    sha256(&[encoded_public_key])
}

/// 端末 ID（指紋の base64url）。
pub fn device_id(encoded_public_key: &[u8]) -> String {
    b64::encode(&fingerprint(encoded_public_key))
}

pub fn sha256(parts: &[&[u8]]) -> Vec<u8> {
    let mut hash = Sha256::new();
    for part in parts {
        hash.update(part);
    }
    hash.finalize().to_vec()
}

/// ECDH の共有秘密（x 座標そのもの、32バイト）。
pub fn ecdh(private_key: &SecretKey, public_key: &EcPublicKey) -> Vec<u8> {
    let shared = p256::ecdh::diffie_hellman(private_key.to_nonzero_scalar(), public_key.key.as_affine());
    shared.raw_secret_bytes().to_vec()
}

/// 用途ラベルを前置して署名する（DER 形式、SHA-256）。
pub fn sign(private_key: &SecretKey, label: &str, message: &[u8]) -> Vec<u8> {
    let signing = SigningKey::from(private_key);
    let signature: Signature = signing.sign(&labeled(label, message));
    signature.to_der().as_bytes().to_vec()
}

/// 署名を確かめる。DER でない・合わないものは false（JCA と同じく、S の大きい署名も受ける）。
pub fn verify(public_key: &EcPublicKey, label: &str, message: &[u8], signature: &[u8]) -> bool {
    let Ok(signature) = Signature::from_der(signature) else {
        return false;
    };
    VerifyingKey::from(&public_key.key).verify(&labeled(label, message), &signature).is_ok()
}

pub fn hmac_sha256(key: &[u8], parts: &[&[u8]]) -> Vec<u8> {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC はどの長さの鍵も受ける");
    for part in parts {
        mac.update(part);
    }
    mac.finalize().into_bytes().to_vec()
}

/// AES-256-GCM で封じる。nonce は毎回 96 ビットの乱数。暗号文は JCA と同じく末尾に 16 バイトのタグを付ける。
/// 戻りは (nonce, 暗号文)。key が 32 バイトでなければ呼ぶ側の誤り（panic）。
pub fn seal(key: &[u8], aad: &[u8], plaintext: &[u8]) -> (Vec<u8>, Vec<u8>) {
    assert_eq!(key.len(), 32, "key は 32 バイト");
    let nonce = random_bytes(12);
    let ciphertext = aes_gcm_seal(key, &nonce, aad, plaintext);
    (nonce, ciphertext)
}

/// 認証に失敗したら None。
pub fn open(key: &[u8], nonce: &[u8], aad: &[u8], ciphertext: &[u8]) -> Option<Vec<u8>> {
    if key.len() != 32 || nonce.len() != 12 {
        return None;
    }
    aes_gcm_open(key, nonce, aad, ciphertext)
}

/// 鍵の長さで AES-128-GCM（HPKE）か AES-256-GCM を選ぶ。
pub(crate) fn aes_gcm_seal(key: &[u8], nonce: &[u8], aad: &[u8], plaintext: &[u8]) -> Vec<u8> {
    let nonce = Nonce::from_slice(nonce);
    let payload = Payload { msg: plaintext, aad };
    let sealed = match key.len() {
        16 => Aes128Gcm::new_from_slice(key).expect("16 バイト").encrypt(nonce, payload),
        32 => Aes256Gcm::new_from_slice(key).expect("32 バイト").encrypt(nonce, payload),
        _ => panic!("AES-GCM の鍵は 16 か 32 バイト"),
    };
    sealed.expect("AES-GCM の暗号化は長さの上限の内では失敗しない")
}

pub(crate) fn aes_gcm_open(key: &[u8], nonce: &[u8], aad: &[u8], ciphertext: &[u8]) -> Option<Vec<u8>> {
    if ciphertext.len() < 16 || nonce.len() != 12 {
        return None;
    }
    let nonce = Nonce::from_slice(nonce);
    let payload = Payload { msg: ciphertext, aad };
    match key.len() {
        16 => Aes128Gcm::new_from_slice(key).ok()?.decrypt(nonce, payload).ok(),
        32 => Aes256Gcm::new_from_slice(key).ok()?.decrypt(nonce, payload).ok(),
        _ => None,
    }
}

pub fn random_bytes(size: usize) -> Vec<u8> {
    let mut bytes = vec![0u8; size];
    OsRng.fill_bytes(&mut bytes);
    bytes
}

/// 定数時間の比較（長さが違えば false）。
pub fn constant_time_equals(a: &[u8], b: &[u8]) -> bool {
    a.ct_eq(b).into()
}

/// 長さ付きで連結する（ビッグエンディアンの4バイト長 ‖ 中身 を並べる）。
pub fn length_prefixed(parts: &[&[u8]]) -> Vec<u8> {
    let mut output = Vec::with_capacity(parts.iter().map(|p| 4 + p.len()).sum());
    for part in parts {
        output.extend_from_slice(&(part.len() as u32).to_be_bytes());
        output.extend_from_slice(part);
    }
    output
}

fn labeled(label: &str, message: &[u8]) -> Vec<u8> {
    length_prefixed(&[label.as_bytes(), message])
}

/// HKDF（RFC 5869）、HMAC-SHA256。HPKE のラベル付きの extract・expand が見えるよう、手で書く。
pub mod hkdf {
    use super::hmac_sha256;

    const HASH_LEN: usize = 32;

    pub fn extract(salt: &[u8], ikm: &[u8]) -> Vec<u8> {
        if salt.is_empty() {
            hmac_sha256(&[0u8; HASH_LEN], &[ikm])
        } else {
            hmac_sha256(salt, &[ikm])
        }
    }

    /// length は 1〜255×32（外れたら呼ぶ側の誤り）。
    pub fn expand(prk: &[u8], info: &[u8], length: usize) -> Vec<u8> {
        assert!((1..=255 * HASH_LEN).contains(&length), "HKDF の長さが範囲の外");
        let mut output = Vec::with_capacity(length + HASH_LEN);
        let mut previous: Vec<u8> = Vec::new();
        let mut counter = 1u8;
        while output.len() < length {
            previous = hmac_sha256(prk, &[&previous, info, &[counter]]);
            output.extend_from_slice(&previous);
            counter = counter.wrapping_add(1);
        }
        output.truncate(length);
        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        hex::decode(s.replace(' ', "")).unwrap()
    }

    #[test]
    fn hkdf_matches_rfc5869_case1() {
        let ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        let salt = hex("000102030405060708090a0b0c");
        let info = hex("f0f1f2f3f4f5f6f7f8f9");
        let prk = hkdf::extract(&salt, &ikm);
        assert_eq!(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk);
        assert_eq!(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), hkdf::expand(&prk, &info, 42));
    }

    #[test]
    fn base64url_is_unpadded_and_strict() {
        assert_eq!("-_8", b64::encode(&[0xfb, 0xff]));
        assert_eq!(Some(vec![0xfb, 0xff]), b64::decode("-_8"));
        assert_eq!(Some(vec![0xfb, 0xff]), b64::decode("-_8="));
        assert_eq!(None, b64::decode("+/8"));
        assert_eq!(None, b64::decode("a b"));
        assert_eq!(None, b64::decode("A"));
        assert_eq!(Some(vec![]), b64::decode(""));
        // パディングの崩れたもの
        assert_eq!(None, b64::decode("-_8=="));
        assert_eq!(None, b64::decode("AA="));
        assert_eq!(None, b64::decode("===="));
        assert_eq!(Some(vec![0]), b64::decode("AA=="));
        // 長さごとに往復する
        for n in 0..10 {
            let data: Vec<u8> = (0..n).map(|i| (i * 37 + 200) as u8).collect();
            assert_eq!(Some(data.clone()), b64::decode(&b64::encode(&data)));
        }
    }

    #[test]
    fn signatures_are_der_and_labeled() {
        let key = generate_key();
        let public = public_key_of(&key);
        let signature = sign(&key, "label", &[1, 2, 3]);
        // DER の SEQUENCE（JCA の SHA256withECDSA と同じ形）
        assert_eq!(0x30, signature[0]);
        assert!(verify(&public, "label", &[1, 2, 3], &signature));
        assert!(!verify(&public, "other", &[1, 2, 3], &signature));
        assert!(!verify(&public, "label", &[1, 2, 4], &signature));
        assert!(!verify(&public, "label", &[1, 2, 3], &signature[..signature.len() - 1]));
    }

    #[test]
    fn ecdh_is_the_raw_x_coordinate() {
        let a = generate_key();
        let b = generate_key();
        let ab = ecdh(&a, &public_key_of(&b));
        assert_eq!(32, ab.len());
        assert_eq!(ab, ecdh(&b, &public_key_of(&a)));
        // 共有点の x 座標そのもの（a の秘密 × b の公開点）
        let point = (p256::ProjectivePoint::from(*public_key_of(&b).key.as_affine()) * *a.to_nonzero_scalar()).to_affine();
        assert_eq!(point.to_encoded_point(false).x().unwrap().to_vec(), ab);
    }

    #[test]
    fn public_keys_must_be_canonical_p256() {
        let key = generate_key();
        let spki = encode_public_key(&key);
        assert_eq!(91, spki.len());
        assert!(decode_public_key(&spki).is_some());
        // P-384 の SPKI（形は正しい DER、曲線の OID だけが違う）は受けない
        let mut p384 = hex("3076301006072a8648ce3d020106052b81040022036200");
        p384.push(0x04);
        p384.extend_from_slice(&[0x11; 96]);
        assert!(decode_public_key(&p384).is_none());
        // 末尾にごみ
        let mut trailing = spki.clone();
        trailing.push(0);
        assert!(decode_public_key(&trailing).is_none());
        // 曲線外の点
        let mut bad = spki.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert!(decode_public_key(&bad).is_none());
        assert!(decode_public_key(&[0u8; 201]).is_none());
        // 圧縮点の SPKI（読み直すと別のバイト列になる）
        let compressed = public_key_of(&key).key.to_encoded_point(true);
        let mut compressed_spki = hex("3039301306072a8648ce3d020106082a8648ce3d030107032200");
        compressed_spki.extend_from_slice(compressed.as_bytes());
        assert!(PublicKey::from_public_key_der(&compressed_spki).is_ok());
        assert!(decode_public_key(&compressed_spki).is_none());
    }

    #[test]
    fn aes_gcm_round_trips_and_rejects_tampering() {
        let key = random_bytes(32);
        let (nonce, ciphertext) = seal(&key, &[9], &[1, 2, 3]);
        assert_eq!(3 + 16, ciphertext.len());
        assert_eq!(Some(vec![1, 2, 3]), open(&key, &nonce, &[9], &ciphertext));
        assert_eq!(None, open(&key, &nonce, &[8], &ciphertext));
        assert_eq!(None, open(&key, &nonce[..11], &[9], &ciphertext));
    }

    #[test]
    fn length_prefixed_is_big_endian() {
        assert_eq!(hex("00000002 0102 00000000"), length_prefixed(&[&[1, 2], &[]]));
    }
}
