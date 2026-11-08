//! freedesktop の通知（org.freedesktop.Notifications）を D-Bus で直に送る。押されたら on_click を呼ぶ。
//! GApplication を持たない QML 版が、新作の知らせに使う（Omarchy の mako も GNOME の通知もこれを受ける）。

use std::cell::{Cell, RefCell};
use std::collections::HashMap;

use gio::prelude::*;
use glib::Variant;

thread_local! {
    static CLICKS: RefCell<HashMap<u32, Box<dyn Fn()>>> = RefCell::new(HashMap::new());
    static SUBSCRIBED: Cell<bool> = const { Cell::new(false) };
}

const NAME: &str = "org.freedesktop.Notifications";
const PATH: &str = "/org/freedesktop/Notifications";

/// 通知を出す。通知の口が無ければ何もしない。
pub fn notify(app_id: &str, summary: &str, body: &str, on_click: impl Fn() + 'static) {
    let Ok(connection) = gio::bus_get_sync(gio::BusType::Session, gio::Cancellable::NONE) else { return };
    subscribe(&connection);
    let hints = glib::VariantDict::new(None);
    hints.insert_value("desktop-entry", &app_id.to_variant());
    let actions: Vec<&str> = vec!["default", "開く"];
    let parameters = Variant::tuple_from_iter([
        "wave".to_variant(),
        0u32.to_variant(),
        app_id.to_variant(),
        summary.to_variant(),
        body.to_variant(),
        actions.to_variant(),
        hints.end(),
        (-1i32).to_variant(),
    ]);
    let on_click = Box::new(on_click);
    connection.call(Some(NAME), PATH, NAME, "Notify", Some(&parameters), Some(glib::VariantTy::new("(u)").unwrap()), gio::DBusCallFlags::NONE, 5000, gio::Cancellable::NONE, move |result| {
        match result.ok().and_then(|v| v.child_value(0).get::<u32>()) {
            Some(id) => CLICKS.with(|c| {
                c.borrow_mut().insert(id, on_click);
            }),
            None => eprintln!("notify failed"),
        }
    });
}

#[allow(deprecated)] // 受け続けるので、外す口（subscribe_to_signal の守り）は要らない
fn subscribe(connection: &gio::DBusConnection) {
    if SUBSCRIBED.with(|s| s.replace(true)) {
        return;
    }
    connection.signal_subscribe(Some(NAME), Some(NAME), Some("ActionInvoked"), Some(PATH), None, gio::DBusSignalFlags::NONE, |_, _, _, _, _, parameters| {
        let Some((id, _action)) = parameters.get::<(u32, String)>() else { return };
        if let Some(click) = CLICKS.with(|c| c.borrow_mut().remove(&id)) {
            click();
        }
    });
    connection.signal_subscribe(Some(NAME), Some(NAME), Some("NotificationClosed"), Some(PATH), None, gio::DBusSignalFlags::NONE, |_, _, _, _, _, parameters| {
        if let Some((id, _reason)) = parameters.get::<(u32, u32)>() {
            CLICKS.with(|c| c.borrow_mut().remove(&id));
        }
    });
}
