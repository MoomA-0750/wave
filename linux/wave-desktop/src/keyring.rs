//! ログインの情報を GNOME のキーリング（Secret Service）に置く。ファイルには書かない。

use wave_core::credentials::Credentials;

use crate::runtime;

/// 確かめ用: WAVE_KEYRING_OFF があれば、キーリングを読みも書きもしない（人の機械で、その人のログインに触れずに画面を確かめる）。
fn off() -> bool {
    std::env::var_os("WAVE_KEYRING_OFF").is_some()
}

const ATTRIBUTES: [(&str, &str); 2] = [("application", "xyz.mooma0750.wave"), ("kind", "youtube-music-login")];
const LABEL: &str = "wave (YouTube Music のログイン)";

pub async fn load() -> Option<Credentials> {
    if off() {
        return None;
    }

    runtime::run(async {
        let keyring = oo7::Keyring::new().await.ok()?;
        let _ = keyring.unlock().await;
        let item = keyring.search_items(&ATTRIBUTES).await.ok()?.into_iter().next()?;
        let secret = item.secret().await.ok()?;
        Credentials::deserialize(std::str::from_utf8(&secret).ok()?)
    })
    .await
}

pub async fn save(credentials: &Credentials) -> bool {
    if off() {
        return false;
    }

    let text = credentials.serialize();
    runtime::run(async move {
        let Ok(keyring) = oo7::Keyring::new().await else { return false };
        let _ = keyring.unlock().await;
        keyring.create_item(LABEL, &ATTRIBUTES, oo7::Secret::text(text), true).await.is_ok()
    })
    .await
}

/// ログアウト: キーリングからログインの情報を消す。
pub async fn delete() {
    if off() {
        return;
    }

    runtime::run(async {
        if let Ok(keyring) = oo7::Keyring::new().await {
            let _ = keyring.delete(&ATTRIBUTES).await;
        }
    })
    .await
}

/// Claude の中継のつなぎ先とトークン（ログインとは別の項目）。
const AI_ATTRIBUTES: [(&str, &str); 2] = [("application", "xyz.mooma0750.wave"), ("kind", "claude-bridge")];

pub async fn load_ai() -> Option<String> {
    if off() {
        return None;
    }

    runtime::run(async {
        let keyring = oo7::Keyring::new().await.ok()?;
        let _ = keyring.unlock().await;
        let item = keyring.search_items(&AI_ATTRIBUTES).await.ok()?.into_iter().next()?;
        let secret = item.secret().await.ok()?;
        std::str::from_utf8(&secret).ok().map(String::from)
    })
    .await
}

pub async fn save_ai(text: String) -> bool {
    if off() {
        return false;
    }

    runtime::run(async move {
        let Ok(keyring) = oo7::Keyring::new().await else { return false };
        let _ = keyring.unlock().await;
        keyring.create_item("wave (Claude の中継)", &AI_ATTRIBUTES, oo7::Secret::text(text), true).await.is_ok()
    })
    .await
}

pub async fn delete_ai() {
    if off() {
        return;
    }

    runtime::run(async {
        if let Ok(keyring) = oo7::Keyring::new().await {
            let _ = keyring.delete(&AI_ATTRIBUTES).await;
        }
    })
    .await
}

/// 端末の連携の、この端末の鍵（身元の署名鍵と受け取りの鍵の PKCS#8）。ログアウトしても消さない（端末 ID が変わると家に入り直しになる）。
const CONNECT_ATTRIBUTES: [(&str, &str); 2] = [("application", "xyz.mooma0750.wave"), ("kind", "connect-device-keys")];

pub async fn load_connect_keys() -> Option<String> {
    if off() {
        return None;
    }

    runtime::run(async {
        let keyring = oo7::Keyring::new().await.ok()?;
        let _ = keyring.unlock().await;
        let item = keyring.search_items(&CONNECT_ATTRIBUTES).await.ok()?.into_iter().next()?;
        let secret = item.secret().await.ok()?;
        std::str::from_utf8(&secret).ok().map(String::from)
    })
    .await
}

pub async fn save_connect_keys(text: String) -> bool {
    if off() {
        return false;
    }

    runtime::run(async move {
        let Ok(keyring) = oo7::Keyring::new().await else { return false };
        let _ = keyring.unlock().await;
        keyring.create_item("wave (端末の連携の鍵)", &CONNECT_ATTRIBUTES, oo7::Secret::text(text), true).await.is_ok()
    })
    .await
}
