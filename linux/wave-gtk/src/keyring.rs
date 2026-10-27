//! ログインの情報を GNOME のキーリング（Secret Service）に置く。ファイルには書かない。

use wave_core::credentials::Credentials;

use crate::runtime;

const ATTRIBUTES: [(&str, &str); 2] = [("application", "xyz.mooma0750.wave"), ("kind", "youtube-music-login")];
const LABEL: &str = "wave (YouTube Music のログイン)";

pub async fn load() -> Option<Credentials> {
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
    runtime::run(async {
        if let Ok(keyring) = oo7::Keyring::new().await {
            let _ = keyring.delete(&ATTRIBUTES).await;
        }
    })
    .await
}
