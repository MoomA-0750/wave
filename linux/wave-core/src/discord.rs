//! Discord のデスクトップ版のローカル IPC（Rich Presence）で「聴いています」を出す（Windows の DiscordIpc・DiscordPresence と同じ）。
//! 枠は [op: i32 LE][長さ: i32 LE][JSON（UTF-8）]。最初に op 0 で {v:1, client_id} を送り、READY が返ったら op 1 で SET_ACTIVITY を送る。

use std::io::{Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, LazyLock, Mutex};
use std::time::{Duration, Instant};

use regex::Regex;
use serde_json::{Value, json};

pub const OP_HANDSHAKE: i32 = 0;
pub const OP_FRAME: i32 = 1;
pub const OP_CLOSE: i32 = 2;
pub const OP_PING: i32 = 3;
pub const OP_PONG: i32 = 4;
/// 1つの枠の中身の上限（壊れた長さで大きな配列を取らないため）。
pub const MAX_PAYLOAD: usize = 64 * 1024;

static SIZED_GOOGLE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"=w\d+-h\d+((?:-[a-z0-9]+)*)$").expect("valid regex"));

/// Discord に出す「再生中の曲」。large_text は画像に重ねて出す文字（アルバム名・ほかの端末で鳴っていればその名前）。
/// playing が false のときは出さない（一時停止中は消す）。
#[derive(Debug, Clone, PartialEq)]
pub struct PresenceTrack {
    pub video_id: String,
    pub title: String,
    pub artists: String,
    pub large_text: Option<String>,
    pub thumbnail_url: Option<String>,
    pub position_seconds: f64,
    pub duration_seconds: f64,
    pub playing: bool,
}

pub fn encode(op: i32, json: &str) -> Vec<u8> {
    let mut frame = Vec::with_capacity(8 + json.len());
    frame.extend_from_slice(&op.to_le_bytes());
    frame.extend_from_slice(&(json.len() as i32).to_le_bytes());
    frame.extend_from_slice(json.as_bytes());
    frame
}

/// 1枠を読む。相手が閉じたら None。壊れた長さなら Err。
pub fn read_frame(stream: &mut impl Read) -> std::io::Result<Option<(i32, String)>> {
    let mut header = [0u8; 8];
    if !read_exact_or_end(stream, &mut header)? {
        return Ok(None);
    }
    let op = i32::from_le_bytes(header[..4].try_into().expect("4 bytes"));
    let length = i32::from_le_bytes(header[4..].try_into().expect("4 bytes"));
    if length < 0 || length as usize > MAX_PAYLOAD {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "discord frame length"));
    }
    let mut body = vec![0; length as usize];
    if !read_exact_or_end(stream, &mut body)? {
        return Ok(None);
    }
    Ok(Some((op, String::from_utf8_lossy(&body).into_owned())))
}

fn read_exact_or_end(stream: &mut impl Read, buffer: &mut [u8]) -> std::io::Result<bool> {
    let mut read = 0;
    while read < buffer.len() {
        match stream.read(&mut buffer[read..])? {
            0 => return Ok(false),
            n => read += n,
        }
    }
    Ok(true)
}

pub fn handshake(client_id: &str) -> String {
    json!({ "v": 1, "client_id": client_id }).to_string()
}

/// SET_ACTIVITY の命令。track が None（か一時停止中）なら、出している状態を消す。
pub fn set_activity(pid: u32, track: Option<&PresenceTrack>, now_unix_ms: i64, nonce: &str) -> String {
    let mut args = json!({ "pid": pid });
    if let Some(track) = track.filter(|t| t.playing) {
        args["activity"] = activity(track, now_unix_ms);
    }
    json!({ "cmd": "SET_ACTIVITY", "args": args, "nonce": nonce }).to_string()
}

/// 種類は 2（Listening）。始まりと終わりの時刻を付け、Discord が残り時間を数える。
pub fn activity(track: &PresenceTrack, now_unix_ms: i64) -> Value {
    let artists = if track.artists.trim().is_empty() { "wave" } else { &track.artists };
    let mut activity = json!({ "type": 2, "details": text(&track.title), "state": text(artists) });
    let start = start_ms(track, now_unix_ms);
    let mut timestamps = json!({ "start": start });
    if track.duration_seconds > 0.0 {
        timestamps["end"] = json!(start + (track.duration_seconds * 1000.0).round() as i64);
    }
    activity["timestamps"] = timestamps;
    let mut assets = serde_json::Map::new();
    if let Some(image) = image_url(track.thumbnail_url.as_deref()) {
        assets.insert("large_image".into(), json!(image));
    }
    if let Some(large) = track.large_text.as_deref().filter(|t| !t.trim().is_empty()) {
        assets.insert("large_text".into(), json!(text(large)));
    }
    if !assets.is_empty() {
        activity["assets"] = Value::Object(assets);
    }
    if is_video_id(&track.video_id) {
        activity["buttons"] = json!([{ "label": "YouTube Music で開く", "url": format!("https://music.youtube.com/watch?v={}", track.video_id) }]);
    }
    activity
}

/// 曲の始まりの時刻（今 − 位置。秒に丸めて、少しの揺れで送り直さないようにする）。
pub fn start_ms(track: &PresenceTrack, now_unix_ms: i64) -> i64 {
    (now_unix_ms - (track.position_seconds.max(0.0) * 1000.0).round() as i64) / 1000 * 1000
}

/// 大きな画像に使う URL（https の外の画像をそのまま使える。256 文字まで）。googleusercontent の小さな画像は 512 に広げる。
pub fn image_url(thumbnail: Option<&str>) -> Option<String> {
    let thumbnail = thumbnail.filter(|t| !t.is_empty())?;
    let url = if thumbnail.starts_with("//") { format!("https:{thumbnail}") } else { thumbnail.to_string() };
    if !url.starts_with("https://") {
        return None;
    }
    let url = SIZED_GOOGLE.replace(&url, "=w512-h512$1").into_owned();
    (url.chars().count() <= 256).then_some(url)
}

fn is_video_id(id: &str) -> bool {
    (1..=64).contains(&id.len()) && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Discord の文字の欄は 2〜128 文字（1文字なら空白を足す）。
fn text(value: &str) -> String {
    let trimmed = value.trim();
    let clipped: String = if trimmed.chars().count() <= 128 { trimmed.to_string() } else { trimmed.chars().take(127).chain(['…']).collect() };
    match clipped.chars().count() {
        0 => "wave".into(),
        1 => format!("{clipped} "),
        _ => clipped,
    }
}

/// 返事が ERROR なら、その文。
pub fn error_of(json: &str) -> Option<String> {
    let node: Value = serde_json::from_str(json).ok()?;
    (node["evt"] == "ERROR").then(|| node["data"]["message"].as_str().unwrap_or("error").to_string())
}

pub fn is_ready(json: &str) -> bool {
    serde_json::from_str::<Value>(json).is_ok_and(|n| n["evt"] == "READY")
}

/// Discord のソケットを探す（discord-ipc-0〜9。$XDG_RUNTIME_DIR などと、Flatpak・Snap の Discord の置き場）。
pub fn connect() -> Option<UnixStream> {
    let mut dirs: Vec<PathBuf> = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"].iter().filter_map(|k| std::env::var_os(k)).filter(|v| !v.is_empty()).map(PathBuf::from).collect();
    dirs.push(PathBuf::from("/tmp"));
    if let Some(runtime) = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from) {
        dirs.push(runtime.join("app/com.discordapp.Discord"));
        dirs.push(runtime.join("snap.discord"));
    }
    for i in 0..10 {
        for dir in &dirs {
            if let Ok(stream) = UnixStream::connect(dir.join(format!("discord-ipc-{i}"))) {
                return Some(stream);
            }
        }
    }
    None
}

/// いつ SET_ACTIVITY を送るか（Discord は 20 秒に 5 回までなので、送りすぎない）。
/// 曲・再生中か・画像の文字が変わったらすぐ（前に送ってから MIN_GAP_MS は空ける）、シークで始まりの時刻がずれたら SEEK_GAP_MS おきに。
#[derive(Default)]
pub struct PresencePlanner {
    sent: Option<(String, i64, i64)>,
}

impl PresencePlanner {
    pub const MIN_GAP_MS: i64 = 2_000;
    pub const SEEK_GAP_MS: i64 = 15_000;
    const SEEK_TOLERANCE_MS: i64 = 3_000;

    /// 送ったことを忘れる（つなぎ直したとき。次は必ず送る）。
    pub fn reset(&mut self) {
        if let Some(sent) = &mut self.sent {
            sent.0 = "\u{0}reset".into();
        }
    }

    pub fn key_of(track: Option<&PresenceTrack>) -> String {
        match track.filter(|t| t.playing) {
            Some(t) => format!("{}\n{}\n{}\n{:?}\n{:?}\n{}", t.video_id, t.title, t.artists, t.large_text, t.thumbnail_url, t.duration_seconds as i64),
            None => String::new(),
        }
    }

    /// 今送るべきか（now_ms は単調に増える時計、now_unix_ms は Unix 時刻）。
    pub fn should_send(&self, track: Option<&PresenceTrack>, now_ms: i64, now_unix_ms: i64) -> bool {
        let Some((key, start, at)) = &self.sent else { return true };
        if *key != Self::key_of(track) {
            return now_ms - at >= Self::MIN_GAP_MS;
        }
        let Some(track) = track.filter(|t| t.playing) else { return false };
        (start_ms(track, now_unix_ms) - start).abs() > Self::SEEK_TOLERANCE_MS && now_ms - at >= Self::SEEK_GAP_MS
    }

    pub fn sent(&mut self, track: Option<&PresenceTrack>, now_ms: i64, now_unix_ms: i64) {
        let start = track.filter(|t| t.playing).map_or(0, |t| start_ms(t, now_unix_ms));
        self.sent = Some((Self::key_of(track), start, now_ms));
    }
}

/// 今の状態（設定の画面に出す）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PresenceStatus {
    Off,
    NotFound,
    Refused,
    Connected,
}

#[derive(Default)]
struct Wanted {
    client_id: Option<String>,
    track: Option<PresenceTrack>,
    changed: bool,
    stop: bool,
}

/// Discord の Rich Presence を出し続ける。update で今の曲を渡すと、裏の糸がつなぎ・送り・つなぎ直しをする。
/// Discord が後から起動しても 15 秒おきにつなぎ直しを試す。client_id が None なら消してから切る。
pub struct DiscordPresence {
    wanted: Arc<(Mutex<Wanted>, Condvar)>,
    status: Arc<Mutex<PresenceStatus>>,
}

impl DiscordPresence {
    pub const RETRY: Duration = Duration::from_secs(15);

    pub fn start() -> Self {
        let wanted = Arc::new((Mutex::new(Wanted::default()), Condvar::new()));
        let status = Arc::new(Mutex::new(PresenceStatus::Off));
        let (w, s) = (wanted.clone(), status.clone());
        std::thread::Builder::new().name("discord".into()).spawn(move || Worker::new(w, s).run()).expect("spawn discord thread");
        Self { wanted, status }
    }

    pub fn status(&self) -> PresenceStatus {
        *self.status.lock().unwrap()
    }

    /// 出したい曲（client_id が None なら Discord から離れる）。
    pub fn update(&self, client_id: Option<&str>, track: Option<PresenceTrack>) {
        let client_id = client_id.map(str::trim).filter(|c| !c.is_empty()).map(String::from);
        let (lock, wake) = &*self.wanted;
        let mut wanted = lock.lock().unwrap();
        let changed = wanted.client_id != client_id || PresencePlanner::key_of(wanted.track.as_ref()) != PresencePlanner::key_of(track.as_ref());
        wanted.client_id = client_id;
        wanted.track = track;
        if changed {
            wanted.changed = true;
            wake.notify_one();
        }
    }
}

impl Drop for DiscordPresence {
    fn drop(&mut self) {
        let (lock, wake) = &*self.wanted;
        lock.lock().unwrap().stop = true;
        wake.notify_one();
    }
}

struct Worker {
    wanted: Arc<(Mutex<Wanted>, Condvar)>,
    status: Arc<Mutex<PresenceStatus>>,
    stream: Option<UnixStream>,
    connected_client: Option<String>,
    closed_by_peer: Arc<AtomicBool>,
    planner: PresencePlanner,
    next_attempt: Option<Instant>,
    clock: Instant,
    nonce: AtomicU64,
}

fn now_unix_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

impl Worker {
    fn new(wanted: Arc<(Mutex<Wanted>, Condvar)>, status: Arc<Mutex<PresenceStatus>>) -> Self {
        Self {
            wanted,
            status,
            stream: None,
            connected_client: None,
            closed_by_peer: Arc::default(),
            planner: PresencePlanner::default(),
            next_attempt: None,
            clock: Instant::now(),
            nonce: AtomicU64::new(0),
        }
    }

    fn set_status(&self, status: PresenceStatus) {
        *self.status.lock().unwrap() = status;
    }

    fn run(mut self) {
        loop {
            let (client_id, track) = {
                let (lock, wake) = &*self.wanted;
                let mut wanted = lock.lock().unwrap();
                if !wanted.changed && !wanted.stop {
                    wanted = wake.wait_timeout(wanted, Duration::from_secs(1)).unwrap().0;
                }
                if wanted.stop {
                    break;
                }
                wanted.changed = false;
                (wanted.client_id.clone(), wanted.track.clone())
            };
            if self.step(client_id, track).is_err() {
                eprintln!("discord: write failed");
                self.disconnect();
            }
        }
        self.clear_and_disconnect();
    }

    fn step(&mut self, client_id: Option<String>, track: Option<PresenceTrack>) -> std::io::Result<()> {
        if self.closed_by_peer.swap(false, Ordering::SeqCst) {
            // Discord が閉じた（終了した・ID が違った）: 少し待ってからつなぎ直す
            self.disconnect();
            self.next_attempt = Some(Instant::now() + DiscordPresence::RETRY);
        }
        if self.stream.is_some() && (client_id.is_none() || client_id != self.connected_client) {
            self.clear_and_disconnect();
            self.next_attempt = None;
        }
        let Some(client_id) = client_id else {
            self.set_status(PresenceStatus::Off);
            return Ok(());
        };
        if self.stream.is_none() {
            // 何も出していない（一時停止中・曲が無い）ときは、つなぎに行かない
            if !track.as_ref().is_some_and(|t| t.playing) || self.next_attempt.is_some_and(|at| Instant::now() < at) {
                return Ok(());
            }
            self.next_attempt = Some(Instant::now() + DiscordPresence::RETRY);
            if !self.open(&client_id) {
                return Ok(());
            }
        }
        let (now, unix) = (self.clock.elapsed().as_millis() as i64, now_unix_ms());
        if !self.planner.should_send(track.as_ref(), now, unix) {
            return Ok(());
        }
        let nonce = self.next_nonce();
        let frame = encode(OP_FRAME, &set_activity(std::process::id(), track.as_ref(), unix, &nonce));
        self.stream.as_mut().expect("connected above").write_all(&frame)?;
        self.planner.sent(track.as_ref(), now, unix);
        Ok(())
    }

    fn open(&mut self, client_id: &str) -> bool {
        let Some(mut stream) = connect() else {
            self.set_status(PresenceStatus::NotFound);
            return false;
        };
        let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
        let reply = stream.write_all(&encode(OP_HANDSHAKE, &handshake(client_id))).and_then(|_| read_frame(&mut stream));
        match reply {
            Ok(Some((OP_FRAME, json))) if is_ready(&json) => {}
            Ok(_) => {
                eprintln!("discord: handshake refused");
                self.set_status(PresenceStatus::Refused);
                return false;
            }
            Err(_) => {
                self.set_status(PresenceStatus::NotFound);
                return false;
            }
        }
        let _ = stream.set_read_timeout(None);
        // 返事を読み捨てる（読まないと詰まる）。PING には PONG を返し、閉じられたらつなぎ直す
        if let Ok(mut reader) = stream.try_clone() {
            let (closed, wanted) = (self.closed_by_peer.clone(), self.wanted.clone());
            std::thread::spawn(move || {
                loop {
                    match read_frame(&mut reader) {
                        Ok(Some((OP_PING, json))) => {
                            if reader.write_all(&encode(OP_PONG, &json)).is_err() {
                                break;
                            }
                        }
                        Ok(Some((OP_CLOSE, _))) | Ok(None) | Err(_) => break,
                        Ok(Some((_, json))) => {
                            if let Some(error) = error_of(&json) {
                                eprintln!("discord: {error}");
                            }
                        }
                    }
                }
                closed.store(true, Ordering::SeqCst);
                let (lock, wake) = &*wanted;
                lock.lock().unwrap().changed = true;
                wake.notify_one();
            });
        }
        self.stream = Some(stream);
        self.connected_client = Some(client_id.to_string());
        self.planner.reset();
        self.closed_by_peer.store(false, Ordering::SeqCst);
        self.set_status(PresenceStatus::Connected);
        eprintln!("discord: connected");
        true
    }

    /// 切る前に消す（Discord はプロセスが切れたら消すが、すぐ消えるように）。
    fn clear_and_disconnect(&mut self) {
        let nonce = self.next_nonce();
        if let Some(stream) = &mut self.stream {
            let _ = stream.write_all(&encode(OP_FRAME, &set_activity(std::process::id(), None, now_unix_ms(), &nonce)));
        }
        self.disconnect();
    }

    fn disconnect(&mut self) {
        if let Some(stream) = self.stream.take() {
            let _ = stream.shutdown(std::net::Shutdown::Both);
        }
        self.connected_client = None;
        self.planner = PresencePlanner::default();
        if *self.status.lock().unwrap() == PresenceStatus::Connected {
            self.set_status(PresenceStatus::NotFound);
        }
    }

    fn next_nonce(&self) -> String {
        (self.nonce.fetch_add(1, Ordering::SeqCst) + 1).to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(position: f64, playing: bool) -> PresenceTrack {
        PresenceTrack {
            video_id: "dQw4w9WgXcQ".into(),
            title: "曲".into(),
            artists: "".into(),
            large_text: Some("アルバム".into()),
            thumbnail_url: Some("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj".into()),
            position_seconds: position,
            duration_seconds: 200.0,
            playing,
        }
    }

    #[test]
    fn frames_round_trip() {
        let frame = encode(OP_FRAME, r#"{"evt":"READY"}"#);
        assert_eq!(&frame[..8], &[1, 0, 0, 0, 15, 0, 0, 0]);
        let (op, json) = read_frame(&mut frame.as_slice()).unwrap().unwrap();
        assert_eq!(op, OP_FRAME);
        assert!(is_ready(&json));
        assert!(read_frame(&mut [1u8, 0, 0, 0].as_slice()).unwrap().is_none());
        assert!(read_frame(&mut [1u8, 0, 0, 0, 0xff, 0xff, 0xff, 0x7f].as_slice()).is_err());
        assert_eq!(error_of(r#"{"evt":"ERROR","data":{"message":"bad"}}"#).as_deref(), Some("bad"));
    }

    #[test]
    fn activity_lists_the_song_and_hides_when_paused() {
        let a = activity(&track(30.4, true), 1_000_000_500);
        assert_eq!(a["type"], 2);
        assert_eq!(a["details"], "曲 ");
        assert_eq!(a["state"], "wave");
        assert_eq!(a["timestamps"]["start"], 999_970_000);
        assert_eq!(a["timestamps"]["end"], 999_970_000 + 200_000);
        assert_eq!(a["assets"]["large_image"], "https://lh3.googleusercontent.com/abc=w512-h512-l90-rj");
        assert_eq!(a["buttons"][0]["url"], "https://music.youtube.com/watch?v=dQw4w9WgXcQ");
        let paused: Value = serde_json::from_str(&set_activity(7, Some(&track(0.0, false)), 0, "1")).unwrap();
        assert!(paused["args"].get("activity").is_none());
        assert_eq!(image_url(Some("http://x.example/a.jpg")), None);
    }

    #[test]
    fn planner_limits_how_often_it_sends() {
        let mut planner = PresencePlanner::default();
        let t = track(10.0, true);
        assert!(planner.should_send(Some(&t), 0, 100_000));
        planner.sent(Some(&t), 0, 100_000);
        // 同じ曲が進んでいるだけなら送らない
        assert!(!planner.should_send(Some(&track(20.0, true)), 10_000, 110_000));
        // 曲が変わっても、前に送ってから 2 秒は空ける
        let mut other = t.clone();
        other.video_id = "aaaaaaaaaaa".into();
        assert!(!planner.should_send(Some(&other), 1_000, 101_000));
        assert!(planner.should_send(Some(&other), 2_000, 102_000));
        // シークは 15 秒おき
        assert!(!planner.should_send(Some(&track(90.0, true)), 5_000, 105_000));
        assert!(planner.should_send(Some(&track(90.0, true)), 15_000, 115_000));
        // 止めたら（消すのを）送る
        assert!(planner.should_send(Some(&track(10.0, false)), 15_000, 115_000));
    }
}
