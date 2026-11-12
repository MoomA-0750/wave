//! MPRIS（GNOME の上部のメディアの操作・ロック画面・キーボードのメディアキーが使う口）を D-Bus に出す。
//! 名前は org.mpris.MediaPlayer2.wave。曲名・アーティスト・ジャケットの URL・長さを出し、再生・一時停止・次・前・シークを受ける。
//! ほかの端末を映している間は、その端末の曲と状態を出し、操作もその端末へ送る（画面の下の帯と同じ）。

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use glib::{self, Variant, prelude::*};

use crate::connect::Connect;
use crate::playback::Playback;
use wave_core::music::models::Song;

const BUS_NAME: &str = "org.mpris.MediaPlayer2.wave";
const OBJECT_PATH: &str = "/org/mpris/MediaPlayer2";
const ROOT_IFACE: &str = "org.mpris.MediaPlayer2";
const PLAYER_IFACE: &str = "org.mpris.MediaPlayer2.Player";
/// 曲が無いときの trackid（MPRIS の決まり）。
const NO_TRACK: &str = "/org/mpris/MediaPlayer2/TrackList/NoTrack";

const INTROSPECTION: &str = r#"
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek"><arg direction="in" name="Offset" type="x"/></method>
    <method name="SetPosition"><arg direction="in" name="TrackId" type="o"/><arg direction="in" name="Position" type="x"/></method>
    <method name="OpenUri"><arg direction="in" name="Uri" type="s"/></method>
    <signal name="Seeked"><arg name="Position" type="x"/></signal>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>
"#;

/// D-Bus に出している間だけ持つ。
pub struct Mpris {
    connection: RefCell<Option<gio::DBusConnection>>,
    registrations: RefCell<Vec<gio::RegistrationId>>,
    /// 最後に知らせた値（変わったときだけ PropertiesChanged を出す）。
    last: RefCell<Option<(String, String, bool)>>,
    _owner: RefCell<Option<gio::OwnerId>>,
}

/// MPRIS に出す今の様子（手元の再生か、映している端末の再生）。
struct Now {
    song: Option<Song>,
    playing: bool,
    position_ms: u64,
    length_ms: Option<u64>,
    has_next: bool,
    remote: bool,
}

/// 手元の再生と、映しているほかの端末の再生のどちらを出すかを決める元。
#[derive(Clone)]
struct Source {
    playback: Rc<Playback>,
    connect: Rc<Connect>,
}

impl Source {
    fn now(&self) -> Now {
        if let Some(m) = self.connect.mirror() {
            return Now {
                length_ms: m.song.duration_seconds.map(|s| s as u64 * 1000),
                song: Some(m.song),
                playing: m.playing,
                position_ms: m.position_ms.max(0) as u64,
                has_next: true,
                remote: true,
            };
        }
        let player = self.playback.player();
        let song = self.playback.current();
        Now {
            length_ms: player.duration_ms().or(song.as_ref().and_then(|s| s.duration_seconds).map(|s| s as u64 * 1000)),
            song,
            playing: player.is_playing(),
            position_ms: player.position_ms().unwrap_or(0),
            has_next: self.playback.has_next(),
            remote: false,
        }
    }
}

pub fn start(playback: Rc<Playback>, connect: Rc<Connect>, raise: impl Fn() + 'static, quit: impl Fn() + 'static) -> Rc<Mpris> {
    let source = Source { playback: playback.clone(), connect: connect.clone() };
    let mpris = Rc::new(Mpris { connection: RefCell::default(), registrations: RefCell::default(), last: RefCell::default(), _owner: RefCell::default() });
    let node = gio::DBusNodeInfo::for_xml(INTROSPECTION).expect("MPRIS introspection");
    let (raise, quit) = (Rc::new(raise), Rc::new(quit));
    let weak = Rc::downgrade(&mpris);
    let pb = source.clone();
    let owner = gio::bus_own_name(
        gio::BusType::Session,
        BUS_NAME,
        gio::BusNameOwnerFlags::NONE,
        move |connection, _| {
            let mut ids = Vec::new();
            for iface in [ROOT_IFACE, PLAYER_IFACE] {
                let info = node.lookup_interface(iface).expect("interface in introspection");
                let (calls, props, setter) = (pb.clone(), pb.clone(), pb.clone());
                let (raise, quit) = (raise.clone(), quit.clone());
                let registered = connection
                    .register_object(OBJECT_PATH, &info)
                    .method_call(move |connection, _, _, _, method, params, invocation| {
                        call(&calls, &connection, method, &params, &*raise, &*quit);
                        invocation.return_value(None);
                    })
                    .property(move |_, _, _, iface, name| property(&props, iface, name))
                    .set_property(move |_, _, _, _, name, value| {
                        if name == "Volume"
                            && let Some(volume) = value.get::<f64>()
                        {
                            setter.playback.player().set_volume(volume);
                        }
                        true
                    })
                    .build();
                match registered {
                    Ok(id) => ids.push(id),
                    Err(e) => eprintln!("mpris register failed: {e}"),
                }
            }
            if let Some(mpris) = weak.upgrade() {
                *mpris.connection.borrow_mut() = Some(connection);
                *mpris.registrations.borrow_mut() = ids;
            }
        },
        |_, _| {},
        |_, _| eprintln!("mpris name lost"),
    );
    *mpris._owner.borrow_mut() = Some(owner);

    // 曲・再生の状態・次と前の有無が変わったら知らせる（再生の状態は GStreamer から遅れて届くので、0.5 秒ごとにも見る）
    let weak = Rc::downgrade(&mpris);
    let watched = source.clone();
    playback.on_change(move || {
        if let Some(mpris) = weak.upgrade() {
            mpris.notify(&watched);
        }
    });
    let weak = Rc::downgrade(&mpris);
    let watched = source.clone();
    connect.on_change(move || {
        if let Some(mpris) = weak.upgrade() {
            mpris.notify(&watched);
        }
    });
    let weak = Rc::downgrade(&mpris);
    glib::timeout_add_local(std::time::Duration::from_millis(500), move || match weak.upgrade() {
        Some(mpris) => {
            mpris.notify(&source);
            glib::ControlFlow::Continue
        }
        None => glib::ControlFlow::Break,
    });
    mpris
}

impl Drop for Mpris {
    /// ログアウトして入り直すときなど: D-Bus から外す（残すと、古い再生をメディアキーが操作し続ける）。
    fn drop(&mut self) {
        if let Some(connection) = self.connection.borrow().as_ref() {
            for id in self.registrations.borrow_mut().drain(..) {
                let _ = connection.unregister_object(id);
            }
        }
        if let Some(owner) = self._owner.borrow_mut().take() {
            gio::bus_unown_name(owner);
        }
    }
}

impl Mpris {
    fn notify(&self, source: &Source) {
        let Some(connection) = self.connection.borrow().clone() else { return };
        let state = source.now();
        let now = (status(&state).to_string(), state.song.as_ref().map(|s| s.video_id.clone()).unwrap_or_default(), state.has_next);
        if self.last.borrow().as_ref() == Some(&now) {
            return;
        }
        *self.last.borrow_mut() = Some(now);
        let changed: HashMap<String, Variant> = ["PlaybackStatus", "Metadata", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek"]
            .into_iter()
            .map(|name| (name.to_string(), property(source, PLAYER_IFACE, name)))
            .collect();
        let params = (PLAYER_IFACE, changed, Vec::<String>::new()).to_variant();
        let _ = connection.emit_signal(None, OBJECT_PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged", Some(&params));
    }
}

fn status(now: &Now) -> &'static str {
    match (now.song.is_some(), now.playing) {
        (false, _) => "Stopped",
        (true, true) => "Playing",
        (true, false) => "Paused",
    }
}

fn call(source: &Source, connection: &gio::DBusConnection, method: &str, params: &Variant, raise: &dyn Fn(), quit: &dyn Fn()) {
    let playback = &source.playback;
    // 再生・一時停止の向きは、映している端末ならその端末の状態で決める（toggle はその端末への命令になる）
    let now = source.now();
    let seek = |target: u64| {
        if now.remote {
            source.connect.remote_seek(target as i64);
        } else {
            playback.player().seek_ms(target);
        }
        let _ = connection.emit_signal(None, OBJECT_PATH, PLAYER_IFACE, "Seeked", Some(&((target * 1000) as i64,).to_variant()));
    };
    match method {
        "Raise" => raise(),
        "Quit" => quit(),
        "Next" => playback.next(),
        "Previous" => playback.previous(),
        "PlayPause" => playback.toggle(),
        "Play" if !now.playing => playback.toggle(),
        "Pause" | "Stop" if now.playing => playback.toggle(),
        "Seek" => {
            if let Some((offset,)) = params.get::<(i64,)>() {
                seek((now.position_ms as i64 + offset / 1000).max(0) as u64);
            }
        }
        "SetPosition" => {
            if let Some((_, position)) = params.get::<(glib::variant::ObjectPath, i64)>() {
                seek((position / 1000).max(0) as u64);
            }
        }
        _ => {}
    }
}

fn property(source: &Source, iface: &str, name: &str) -> Variant {
    let player = source.playback.player();
    let now = source.now();
    let has_song = now.song.is_some();
    match (iface, name) {
        (ROOT_IFACE, "CanQuit" | "CanRaise") => true.to_variant(),
        (ROOT_IFACE, "HasTrackList") => false.to_variant(),
        (ROOT_IFACE, "Identity") => "wave".to_variant(),
        (ROOT_IFACE, "DesktopEntry") => "xyz.mooma0750.wave".to_variant(),
        (ROOT_IFACE, "SupportedUriSchemes" | "SupportedMimeTypes") => Vec::<String>::new().to_variant(),
        (_, "PlaybackStatus") => status(&now).to_variant(),
        (_, "Rate" | "MinimumRate" | "MaximumRate") => 1.0f64.to_variant(),
        (_, "Volume") => player.volume().to_variant(),
        (_, "Position") => ((now.position_ms * 1000) as i64).to_variant(),
        (_, "CanGoNext") => now.has_next.to_variant(),
        (_, "CanGoPrevious" | "CanPlay" | "CanPause" | "CanSeek") => has_song.to_variant(),
        (_, "CanControl") => true.to_variant(),
        (_, "Metadata") => metadata(&now),
        _ => false.to_variant(),
    }
}

fn metadata(now: &Now) -> Variant {
    let mut map: HashMap<String, Variant> = HashMap::new();
    let Some(song) = &now.song else {
        map.insert("mpris:trackid".into(), glib::variant::ObjectPath::try_from(NO_TRACK.to_string()).expect("valid path").to_variant());
        return map.to_variant();
    };
    // trackid は D-Bus のパスに使える文字だけにする（videoId の - は _ に替える）
    let track = format!("/xyz/mooma0750/wave/track/{}", song.video_id.replace('-', "_"));
    if let Ok(path) = glib::variant::ObjectPath::try_from(track) {
        map.insert("mpris:trackid".into(), path.to_variant());
    }
    map.insert("xesam:title".into(), song.title.to_variant());
    map.insert("xesam:artist".into(), song.artists.iter().map(|a| a.name.clone()).collect::<Vec<_>>().to_variant());
    if let Some(album) = &song.album {
        map.insert("xesam:album".into(), album.title.to_variant());
    }
    if let Some(url) = &song.thumbnail_url {
        map.insert("mpris:artUrl".into(), crate::art::sized(url, 544).to_variant());
    }
    if let Some(ms) = now.length_ms {
        map.insert("mpris:length".into(), ((ms * 1000) as i64).to_variant());
    }
    map.to_variant()
}
