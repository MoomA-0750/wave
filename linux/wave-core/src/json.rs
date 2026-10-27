//! InnerTube の応答（JSON）をたどる小さな道具。形が変わっても落とさず、無ければ None・空にする。

use serde_json::Value;

pub(crate) trait JsonPath<'a> {
    /// 名前の並びでたどる（配列は数字の名前で添字にする）。途中で無ければ None。
    fn path(self, keys: &[&str]) -> Option<&'a Value>;
    /// 配列の中のオブジェクトだけ（配列でなければ空）。
    fn items(self) -> Vec<&'a Value>;
    fn str_(self) -> Option<&'a str>;
}

impl<'a> JsonPath<'a> for &'a Value {
    fn path(self, keys: &[&str]) -> Option<&'a Value> {
        let mut node = self;
        for key in keys {
            node = match node {
                Value::Object(map) => map.get(*key)?,
                Value::Array(list) => list.get(key.parse::<usize>().ok()?)?,
                _ => return None,
            };
        }
        Some(node)
    }

    fn items(self) -> Vec<&'a Value> {
        match self {
            Value::Array(list) => list.iter().filter(|v| v.is_object()).collect(),
            _ => Vec::new(),
        }
    }

    fn str_(self) -> Option<&'a str> {
        self.as_str()
    }
}

impl<'a> JsonPath<'a> for Option<&'a Value> {
    fn path(self, keys: &[&str]) -> Option<&'a Value> {
        self.and_then(|v| v.path(keys))
    }

    fn items(self) -> Vec<&'a Value> {
        self.map(|v| v.items()).unwrap_or_default()
    }

    fn str_(self) -> Option<&'a str> {
        self.and_then(|v| v.as_str())
    }
}

/// 木の中から、その名前のキーが指すオブジェクトを全部集める（深さ優先・出てくる順）。
pub(crate) fn find_all<'a>(root: &'a Value, key: &str) -> Vec<&'a Value> {
    let mut found = Vec::new();
    collect(root, key, &mut found);
    found
}

fn collect<'a>(node: &'a Value, key: &str, found: &mut Vec<&'a Value>) {
    match node {
        Value::Object(map) => {
            for (name, value) in map {
                if name == key && value.is_object() {
                    found.push(value);
                }
                collect(value, key, found);
            }
        }
        Value::Array(list) => list.iter().for_each(|v| collect(v, key, found)),
        _ => {}
    }
}
