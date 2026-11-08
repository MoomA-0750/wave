//! 曲に重ねる MV。流すのは wave-desktop の VideoPlayer で、GTK 版は GStreamer の gtk4paintablesink で描き、
//! その絵（Paintable）を画面の gtk::Picture に渡す。

use std::ops::Deref;

use gst::prelude::*;
use wave_core::stream::resolver::StreamResolver;

pub struct VideoPlayer {
    inner: wave_desktop::video::VideoPlayer,
    paintable: gtk::gdk::Paintable,
}

impl VideoPlayer {
    /// gtk4paintablesink が無ければ None（映像は出さない）。
    pub fn new(resolver: StreamResolver) -> Option<Self> {
        gst::init().ok()?;
        let sink = gst::ElementFactory::make("gtk4paintablesink").build().ok()?;
        let paintable = sink.property::<gtk::gdk::Paintable>("paintable");
        let inner = wave_desktop::video::VideoPlayer::with_sink(resolver, &sink)?;
        Some(Self { inner, paintable })
    }

    pub fn paintable(&self) -> &gtk::gdk::Paintable {
        &self.paintable
    }
}

impl Deref for VideoPlayer {
    type Target = wave_desktop::video::VideoPlayer;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}
