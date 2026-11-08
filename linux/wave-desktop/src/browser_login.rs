//! ふだんのブラウザに残っている YouTube のログインを読む（Linux だけ）。
//! Firefox の系統（Firefox・Zen・LibreWolf・Floorp・Waterfox）は cookies.sqlite をそのまま、
//! Chromium の系統（Chrome・Chromium・Brave・Edge・Vivaldi）は Cookies を、キーリングの「… Safe Storage」の鍵で解いて読む。
//! ブラウザが開いたままでも読めるよう、DB を自分だけが読める一時の置き場へ写してから開き、読んだらすぐ消す。
//! 読むのは music.youtube.com に送られる cookie だけ。押されたときに選ばれた1つだけを読み、ほかへは送らない。

use std::path::{Path, PathBuf};

use aes::cipher::{BlockDecryptMut, KeyIvInit, block_padding::Pkcs7};
use sha2::Digest;

/// ログインが残っているブラウザのプロファイル1つ。
#[derive(Debug, Clone, PartialEq)]
pub struct Profile {
    /// 画面に出す名前（「Zen（Default (release)）」）
    pub label: String,
    kind: Kind,
    db: PathBuf,
}

#[derive(Debug, Clone, PartialEq)]
enum Kind {
    Firefox,
    /// キーリングの鍵の application（chrome・chromium・brave など）
    Chromium { application: &'static str },
}

const FIREFOX_ROOTS: [(&str, &str); 9] = [
    ("Firefox", ".mozilla/firefox"),
    ("Firefox", ".config/mozilla/firefox"),
    ("Firefox", ".var/app/org.mozilla.firefox/.mozilla/firefox"),
    ("Firefox", "snap/firefox/common/.mozilla/firefox"),
    ("Zen", ".zen"),
    ("Zen", ".config/zen"),
    ("LibreWolf", ".librewolf"),
    ("Floorp", ".floorp"),
    ("Waterfox", ".waterfox"),
];

/// (名前, ~/.config からの場所, キーリングの application)
const CHROMIUM_ROOTS: [(&str, &str, &str); 7] = [
    ("Google Chrome", "google-chrome", "chrome"),
    ("Google Chrome Beta", "google-chrome-beta", "chrome"),
    ("Chromium", "chromium", "chromium"),
    ("Brave", "BraveSoftware/Brave-Browser", "brave"),
    ("Microsoft Edge", "microsoft-edge", "microsoft-edge"),
    ("Vivaldi", "vivaldi", "vivaldi"),
    ("Opera", "opera", "opera"),
];

const LOGIN_COOKIES: [&str; 2] = ["SAPISID", "__Secure-3PAPISID"];

/// ログイン（SAPISID 系の cookie）が残っているプロファイルを挙げる。cookie の値はここでは解かない。
pub fn find(home: &Path) -> Vec<Profile> {
    let mut out = Vec::new();
    for (name, root) in FIREFOX_ROOTS {
        let Ok(entries) = std::fs::read_dir(home.join(root)) else { continue };
        let mut dirs: Vec<PathBuf> = entries.flatten().map(|e| e.path()).filter(|p| p.join("cookies.sqlite").is_file()).collect();
        dirs.sort();
        for dir in dirs {
            let db = dir.join("cookies.sqlite");
            if out.iter().any(|p: &Profile| p.db == db) || !with_copy(&db, |c| firefox_has_login(c)).unwrap_or(false) {
                continue;
            }
            // プロファイルの名前は「xxxxxxxx.Default (release)」の後ろ
            let file = dir.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
            let profile = file.split_once('.').map_or(file.as_str(), |(_, rest)| rest).to_string();
            out.push(Profile { label: format!("{name}（{profile}）"), kind: Kind::Firefox, db });
        }
    }
    for (name, root, application) in CHROMIUM_ROOTS {
        let base = home.join(".config").join(root);
        let Ok(entries) = std::fs::read_dir(&base) else { continue };
        let names = chromium_profile_names(&base);
        let mut dirs: Vec<PathBuf> = entries.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect();
        dirs.sort();
        for dir in dirs {
            let Some(db) = [dir.join("Network/Cookies"), dir.join("Cookies")].into_iter().find(|p| p.is_file()) else { continue };
            if !with_copy(&db, |c| chromium_has_login(c)).unwrap_or(false) {
                continue;
            }
            let file = dir.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
            let profile = names.iter().find(|(d, _)| *d == file).map_or(file.clone(), |(_, n)| n.clone());
            out.push(Profile { label: format!("{name}（{profile}）"), kind: Kind::Chromium { application }, db });
        }
    }
    out
}

/// 選んだブラウザのログインを読み、music.youtube.com を1度開いて確かめてから、キーリングに保存する（GTK 版・QML 版の「ブラウザのログインを使う」）。
pub async fn use_profile(profile: &Profile) -> Result<wave_core::credentials::Credentials, String> {
    let cookie = read_cookie(profile).await?;
    let (visitor, data_sync) = page_config(cookie.clone()).await?;
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64);
    let credentials = wave_core::credentials::Credentials::from_login(&cookie, visitor.as_deref(), data_sync.as_deref(), now)
        .ok_or_else(|| "ログインの cookie が見つかりませんでした".to_string())?;
    eprintln!("browser login visitor={} dataSync={}", visitor.is_some(), data_sync.is_some());
    if !crate::keyring::save(&credentials).await {
        return Err("ログイン情報を保存できませんでした".to_string());
    }
    Ok(credentials)
}

/// 選ばれたプロファイルから、music.youtube.com に送る cookie を「名前=値; …」で読む。Chromium の系統は鍵をキーリングから取る。
pub async fn read_cookie(profile: &Profile) -> Result<String, String> {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64);
    let cookies = match &profile.kind {
        Kind::Firefox => with_copy(&profile.db, |c| firefox_cookies(c, now)).map_err(|e| format!("読めませんでした（{e}）"))?,
        Kind::Chromium { application } => {
            let rows = with_copy(&profile.db, |c| chromium_rows(c, now)).map_err(|e| format!("読めませんでした（{e}）"))?;
            let passwords = chromium_passwords(application).await;
            decrypt_rows(&rows, &passwords).ok_or("ブラウザの鍵で cookie を解けませんでした")?
        }
    };
    let header = cookie_header(&cookies);
    if wave_core::credentials::Credentials::from_login(&header, None, None, 0).is_none() {
        return Err("ログインの cookie が見つかりませんでした".into());
    }
    Ok(header)
}

/// music.youtube.com の画面を cookie つきで1度読み、ログインしているかと VISITOR_DATA・DATASYNC_ID を取る。
pub async fn page_config(cookie: String) -> Result<(Option<String>, Option<String>), String> {
    crate::runtime::run(async move {
        let client = reqwest::Client::builder().user_agent(wave_core::innertube::USER_AGENT).build().map_err(|e| e.to_string())?;
        let html = client
            .get("https://music.youtube.com/")
            .header("Cookie", cookie)
            .header("Accept-Language", "ja")
            .send()
            .await
            .map_err(|e| e.to_string())?
            .text()
            .await
            .map_err(|e| e.to_string())?;
        if !html.contains("\"LOGGED_IN\":true") {
            return Err("ブラウザのログインが切れていました。ブラウザで music.youtube.com を開き直してから、もう一度押してください".into());
        }
        Ok((config_value(&html, "VISITOR_DATA"), config_value(&html, "DATASYNC_ID")))
    })
    .await
}

fn config_value(html: &str, key: &str) -> Option<String> {
    let start = html.find(&format!("\"{key}\":\""))? + key.len() + 4;
    let value = &html[start..start + html[start..].find('"')?];
    (!value.is_empty() && !value.contains('\\')).then(|| value.to_string())
}

/// 1つの cookie（どのホスト・パスに送られるかつき）。
#[derive(Debug, Clone, PartialEq)]
struct Cookie {
    host: String,
    path: String,
    name: String,
    value: String,
}

/// music.youtube.com の「/」に送られるものだけを、ブラウザと同じく細かいホストを先に並べる。
fn cookie_header(cookies: &[Cookie]) -> String {
    let mut sent: Vec<&Cookie> = cookies
        .iter()
        .filter(|c| matches!(c.host.as_str(), ".youtube.com" | "youtube.com" | ".music.youtube.com" | "music.youtube.com"))
        .filter(|c| c.path == "/" || c.path.is_empty())
        .filter(|c| !c.name.is_empty() && !c.value.is_empty())
        .collect();
    sent.sort_by_key(|c| std::cmp::Reverse(c.host.trim_start_matches('.').len()));
    let mut seen = std::collections::HashSet::new();
    sent.into_iter().filter(|c| seen.insert(c.name.clone())).map(|c| format!("{}={}", c.name, c.value)).collect::<Vec<_>>().join("; ")
}

/// DB を一時の置き場（$XDG_RUNTIME_DIR、無ければキャッシュ。自分だけが読める）へ写して開く。終わったら消す。
fn with_copy<T>(db: &Path, read: impl FnOnce(&rusqlite::Connection) -> rusqlite::Result<T>) -> Result<T, String> {
    let base = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
    let mut random = [0u8; 8];
    getrandom::fill(&mut random).map_err(|e| e.to_string())?;
    let dir = base.join(format!("wave-browser-login-{}", hex::encode(random)));
    std::fs::create_dir(&dir).map_err(|e| e.to_string())?;
    let result = (|| {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).map_err(|e| e.to_string())?;
        let copy = dir.join("cookies.sqlite");
        std::fs::copy(db, &copy).map_err(|e| e.to_string())?;
        for suffix in ["-wal", "-journal"] {
            let side = PathBuf::from(format!("{}{suffix}", db.display()));
            if side.is_file() {
                std::fs::copy(&side, format!("{}{suffix}", copy.display())).map_err(|e| e.to_string())?;
            }
        }
        let connection = rusqlite::Connection::open(&copy).map_err(|e| e.to_string())?;
        read(&connection).map_err(|e| e.to_string())
    })();
    let _ = std::fs::remove_dir_all(&dir);
    result
}

fn firefox_has_login(c: &rusqlite::Connection) -> rusqlite::Result<bool> {
    c.query_row(
        "SELECT COUNT(*) FROM moz_cookies WHERE host IN ('.youtube.com','youtube.com') AND name IN (?1, ?2) AND originAttributes = ''",
        LOGIN_COOKIES,
        |r| r.get::<_, i64>(0),
    )
    .map(|n| n > 0)
}

fn firefox_cookies(c: &rusqlite::Connection, now_secs: i64) -> rusqlite::Result<Vec<Cookie>> {
    // 既定の入れ物（originAttributes が空）のものだけ。コンテナー・プライベートの窓のものは混ぜない
    let mut statement = c.prepare("SELECT host, path, name, value, expiry FROM moz_cookies WHERE host LIKE '%youtube.com' AND originAttributes = ''")?;
    let rows = statement.query_map([], |r| {
        Ok((Cookie { host: r.get(0)?, path: r.get(1)?, name: r.get(2)?, value: r.get(3)? }, r.get::<_, i64>(4)?))
    })?;
    // expiry は秒（新しい Firefox はミリ秒のこともある）
    Ok(rows.flatten().filter(|(_, e)| *e == 0 || (if *e > 100_000_000_000 { *e / 1000 } else { *e }) > now_secs).map(|(c, _)| c).collect())
}

/// Chromium のプロファイルのフォルダ名 → 表示名（Local State の profile.info_cache）。
fn chromium_profile_names(base: &Path) -> Vec<(String, String)> {
    let Ok(text) = std::fs::read_to_string(base.join("Local State")) else { return Vec::new() };
    let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) else { return Vec::new() };
    json.pointer("/profile/info_cache")
        .and_then(|v| v.as_object())
        .map(|m| m.iter().filter_map(|(dir, v)| Some((dir.clone(), v.get("name")?.as_str()?.to_string()))).collect())
        .unwrap_or_default()
}

fn chromium_has_login(c: &rusqlite::Connection) -> rusqlite::Result<bool> {
    c.query_row(
        "SELECT COUNT(*) FROM cookies WHERE host_key IN ('.youtube.com','youtube.com') AND name IN (?1, ?2)",
        LOGIN_COOKIES,
        |r| r.get::<_, i64>(0),
    )
    .map(|n| n > 0)
}

/// 解く前の行（値は暗号のまま）と、DB の版（24 から値の先頭にホストの SHA-256 が付く）。
#[derive(Debug, Clone)]
struct ChromiumRows {
    version: i64,
    rows: Vec<(Cookie, Vec<u8>)>,
}

/// Chromium の expires_utc は 1601-01-01 からのマイクロ秒
const WINDOWS_EPOCH_OFFSET_SECS: i64 = 11_644_473_600;

fn chromium_rows(c: &rusqlite::Connection, now_secs: i64) -> rusqlite::Result<ChromiumRows> {
    let version = c.query_row("SELECT value FROM meta WHERE key = 'version'", [], |r| r.get::<_, String>(0)).ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut statement = c.prepare("SELECT host_key, path, name, value, encrypted_value, expires_utc FROM cookies WHERE host_key LIKE '%youtube.com'")?;
    let rows = statement.query_map([], |r| {
        Ok((
            Cookie { host: r.get(0)?, path: r.get(1)?, name: r.get(2)?, value: r.get(3)? },
            r.get::<_, Vec<u8>>(4)?,
            r.get::<_, i64>(5)?,
        ))
    })?;
    let rows = rows
        .flatten()
        .filter(|(_, _, expires)| *expires == 0 || *expires / 1_000_000 - WINDOWS_EPOCH_OFFSET_SECS > now_secs)
        .map(|(c, e, _)| (c, e))
        .collect();
    Ok(ChromiumRows { version, rows })
}

/// キーリングの「… Safe Storage」の鍵。見つからなければ、キーリングを使わない設定のときの空の鍵だけを試す。
async fn chromium_passwords(application: &'static str) -> Vec<Vec<u8>> {
    let mut out = crate::runtime::run(async move {
        let Ok(keyring) = oo7::Keyring::new().await else { return Vec::new() };
        let _ = keyring.unlock().await;
        let Ok(items) = keyring.search_items(&[("application", application)]).await else { return Vec::new() };
        let mut out = Vec::new();
        for item in items {
            if let Ok(secret) = item.secret().await {
                out.push(secret.to_vec());
            }
        }
        out
    })
    .await;
    out.push(Vec::new());
    out
}

/// 値を解く。v10 は決まった鍵（peanuts）、v11 はキーリングの鍵。どの鍵でもログインの cookie が解けなければ None。
fn decrypt_rows(rows: &ChromiumRows, passwords: &[Vec<u8>]) -> Option<Vec<Cookie>> {
    let v10 = chromium_key(b"peanuts");
    for password in passwords {
        let v11 = chromium_key(password);
        let mut out = Vec::new();
        let mut login_ok = false;
        for (cookie, encrypted) in &rows.rows {
            let value = if encrypted.is_empty() {
                Some(cookie.value.clone())
            } else {
                let key = match encrypted.get(..3) {
                    Some(b"v10") => &v10,
                    Some(b"v11") => &v11,
                    _ => continue,
                };
                decrypt_value(key, &encrypted[3..], &cookie.host, rows.version)
            };
            let Some(value) = value else { continue };
            if LOGIN_COOKIES.contains(&cookie.name.as_str()) {
                login_ok = true;
            }
            out.push(Cookie { value, ..cookie.clone() });
        }
        if login_ok {
            return Some(out);
        }
    }
    None
}

fn chromium_key(password: &[u8]) -> [u8; 16] {
    let mut key = [0u8; 16];
    pbkdf2::pbkdf2_hmac::<sha1::Sha1>(password, b"saltysalt", 1, &mut key);
    key
}

fn decrypt_value(key: &[u8; 16], data: &[u8], host: &str, version: i64) -> Option<String> {
    let iv = [b' '; 16];
    let plain = cbc::Decryptor::<aes::Aes128>::new(key.into(), &iv.into()).decrypt_padded_vec_mut::<Pkcs7>(data).ok()?;
    // 版 24 から、値の前にホストの SHA-256（32 バイト）が付く。合わなければ鍵が違う
    let plain = if version >= 24 {
        let (hash, rest) = plain.split_at_checked(32)?;
        if hash != sha2::Sha256::digest(host.as_bytes()).as_slice() {
            return None;
        }
        rest.to_vec()
    } else {
        plain
    };
    String::from_utf8(plain).ok().filter(|v| !v.chars().any(char::is_control))
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes::cipher::BlockEncryptMut;

    fn encrypt(password: &[u8], host: &str, value: &str, version: i64) -> Vec<u8> {
        let key = chromium_key(password);
        let mut plain = Vec::new();
        if version >= 24 {
            plain.extend_from_slice(&sha2::Sha256::digest(host.as_bytes()));
        }
        plain.extend_from_slice(value.as_bytes());
        let mut out = b"v11".to_vec();
        out.extend(cbc::Encryptor::<aes::Aes128>::new(&key.into(), &[b' '; 16].into()).encrypt_padded_vec_mut::<Pkcs7>(&plain));
        out
    }

    fn cookie(host: &str, name: &str, value: &str) -> Cookie {
        Cookie { host: host.into(), path: "/".into(), name: name.into(), value: value.into() }
    }

    #[test]
    fn chromium_values_open_with_the_keyring_password_and_check_the_host_hash() {
        let rows = ChromiumRows {
            version: 24,
            rows: vec![
                (cookie(".youtube.com", "SAPISID", ""), encrypt(b"secret", ".youtube.com", "abc/def", 24)),
                (cookie(".youtube.com", "PREF", ""), encrypt(b"secret", ".youtube.com", "hl=ja", 24)),
            ],
        };
        // 違う鍵では解けず、次の鍵で解ける
        let out = decrypt_rows(&rows, &[b"wrong".to_vec(), b"secret".to_vec()]).unwrap();
        assert_eq!(cookie_header(&out), "SAPISID=abc/def; PREF=hl=ja");
        assert!(decrypt_rows(&rows, &[b"wrong".to_vec()]).is_none());
        // ホストの SHA-256 が合わなければ（別のホストの値を写しても）解いたことにしない
        let moved = ChromiumRows { version: 24, rows: vec![(cookie(".youtube.com", "SAPISID", ""), encrypt(b"secret", ".google.com", "x", 24))] };
        assert!(decrypt_rows(&moved, &[b"secret".to_vec()]).is_none());
        // 古い版はハッシュが付かない
        let old = ChromiumRows { version: 23, rows: vec![(cookie(".youtube.com", "SAPISID", ""), encrypt(b"", ".youtube.com", "old", 23))] };
        assert_eq!(cookie_header(&decrypt_rows(&old, &[Vec::new()]).unwrap()), "SAPISID=old");
    }

    #[test]
    fn only_cookies_sent_to_music_youtube_com_are_used() {
        let cookies = vec![
            cookie(".youtube.com", "SAPISID", "a"),
            cookie("www.youtube.com", "WWW", "x"),
            cookie(".google.com", "SID", "g"),
            Cookie { path: "/watch".into(), ..cookie(".youtube.com", "PATH", "p") },
            cookie(".youtube.com", "SID", "s"),
            cookie("music.youtube.com", "SID", "music"),
        ];
        // 同じ名前は細かいホストのものを先に1つだけ
        assert_eq!(cookie_header(&cookies), "SID=music; SAPISID=a");
    }

    #[test]
    fn firefox_profiles_with_a_youtube_login_are_found_and_read() {
        let home = std::env::temp_dir().join(format!("wave-browser-login-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&home);
        let make = |dir: &str, rows: &[(&str, &str, &str, &str)]| {
            let path = home.join(".config/zen").join(dir);
            std::fs::create_dir_all(&path).unwrap();
            let c = rusqlite::Connection::open(path.join("cookies.sqlite")).unwrap();
            c.execute_batch("CREATE TABLE moz_cookies (host TEXT, path TEXT, name TEXT, value TEXT, expiry INTEGER, originAttributes TEXT)").unwrap();
            for (host, name, value, origin) in rows {
                c.execute("INSERT INTO moz_cookies VALUES (?1, '/', ?2, ?3, 0, ?4)", [host, name, value, origin]).unwrap();
            }
        };
        make("aaaa.Default (release)", &[(".youtube.com", "SAPISID", "ff", ""), (".youtube.com", "SID", "sid", ""), (".youtube.com", "SID", "container", "^userContextId=1")]);
        make("bbbb.Other", &[(".youtube.com", "SAPISID", "only-in-container", "^userContextId=2")]);
        let found = find(&home);
        assert_eq!(found.iter().map(|p| p.label.as_str()).collect::<Vec<_>>(), ["Zen（Default (release)）"]);
        let header = crate::runtime::block_on(read_cookie(&found[0])).unwrap();
        assert_eq!(header, "SAPISID=ff; SID=sid");
        std::fs::remove_dir_all(&home).unwrap();
    }

    #[test]
    fn page_config_values_are_read_from_the_html() {
        let html = r#"ytcfg.set({"VISITOR_DATA":"Cgt4eHg%3D","DATASYNC_ID":"123||456","LOGGED_IN":true})"#;
        assert_eq!(config_value(html, "VISITOR_DATA").as_deref(), Some("Cgt4eHg%3D"));
        assert_eq!(config_value(html, "DATASYNC_ID").as_deref(), Some("123||456"));
        assert_eq!(config_value(html, "MISSING"), None);
    }
}

#[cfg(test)]
mod real {
    /// 実際のホームのブラウザを読む（値は出さない）。`cargo test -p wave-desktop real_browsers -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn real_browsers() {
        let profiles = super::find(&glib::home_dir());
        for p in &profiles {
            let result = crate::runtime::block_on(async {
                let cookie = super::read_cookie(p).await?;
                let names: Vec<String> = cookie.split("; ").filter_map(|kv| kv.split_once('=').map(|(k, _)| k.to_string())).collect();
                let config = super::page_config(cookie).await?;
                Ok::<_, String>((names.len(), config.0.is_some(), config.1.is_some()))
            });
            println!("{} => {:?}", p.label, result);
        }
    }
}
