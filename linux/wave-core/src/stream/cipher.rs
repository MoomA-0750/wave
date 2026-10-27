//! faraday の表（プレイヤーごとの署名の式と n のクラス名）を使い、YouTube のプレイヤー JS を QuickJS の中で動かして
//! signatureCipher と n を解く（Windows の CipherSolver・Android の innertubex の ZemerCipherSolver と同じやり方）。
//! QuickJS にはネットワーク・ファイル・アプリの値へ触れる手段を渡さない（標準の組み込みだけ）。

use std::sync::LazyLock;

use regex::Regex;
use rquickjs::context::EvalOptions;
use rquickjs::{Context, Function, Runtime};

const PLAYER_IIFE_TRAILER: &str = "})(_yt_player);";
const N_PROBE_INPUT: &str = "KdrqFlzJXl9EcCwlmEy";
const MAX_INPUT_LENGTH: usize = 64 * 1024;

/// faraday の表の1行（プレイヤーのハッシュごと）。
#[derive(Debug, Clone, PartialEq)]
pub struct PlayerCipherConfig {
    pub sig_expression: String,
    pub n_class: String,
    pub signature_timestamp: u32,
}

pub struct CipherSolver {
    // runtime は context より長く生きる必要がある
    context: Context,
    _runtime: Runtime,
}

static VALID_N_RESULT: LazyLock<Regex> = LazyLock::new(|| Regex::new("^[A-Za-z0-9_-]+$").unwrap());

impl CipherSolver {
    /// プレイヤー JS を読み込み、n の変換が働くことを確かめてから返す。働かなければ None。
    pub fn create(player_code: &str, config: &PlayerCipherConfig) -> Option<Self> {
        let runtime = Runtime::new().ok()?;
        runtime.set_memory_limit(512 * 1024 * 1024);
        runtime.set_max_stack_size(8 * 1024 * 1024);
        let context = Context::full(&runtime).ok()?;
        let script = build_modified_player_script(player_code, config);
        let loaded = context.with(|ctx| {
            // プレイヤーは非 strict の JS（先頭の `var window=this;` が strict では undefined になる）。rquickjs の既定は strict なので切る
            let sloppy = || {
                let mut options = EvalOptions::default();
                options.strict = false;
                options
            };
            ctx.eval_with_options::<(), _>(GLOBALS_PREAMBLE, sloppy())?;
            ctx.eval_with_options::<(), _>("globalThis._yt_player = globalThis._yt_player || {};", sloppy())?;
            ctx.eval_with_options::<(), _>(script, sloppy())
        });
        if loaded.is_err() {
            return None;
        }
        let solver = Self { context, _runtime: runtime };
        let probe = solver.call("_nTransformFunc", N_PROBE_INPUT)?;
        (probe != N_PROBE_INPUT && probe.len() >= 5 && VALID_N_RESULT.is_match(&probe)).then_some(solver)
    }

    pub fn solve_signature(&self, input: &str) -> Option<String> {
        self.call("_cipherSigFunc", input).filter(|s| !s.trim().is_empty())
    }

    pub fn solve_n(&self, input: &str) -> Option<String> {
        self.call("_nTransformFunc", input).filter(|s| !s.trim().is_empty() && s != input)
    }

    fn call(&self, function: &str, input: &str) -> Option<String> {
        if input.len() > MAX_INPUT_LENGTH {
            return None;
        }
        self.context.with(|ctx| {
            let f: Function = ctx.globals().get(function).ok()?;
            f.call::<_, Option<String>>((input,)).ok().flatten()
        })
    }
}

pub(crate) fn build_modified_player_script(player_code: &str, config: &PlayerCipherConfig) -> String {
    let sig_expression = config.sig_expression.replace("INPUT", "sig");
    let n_expression = n_expression(&config.n_class).replace("INPUT", "n");
    let exports = format!(
        "; window._cipherSigFunc=function(sig){{try{{return {sig_expression};}}catch(e){{return null;}}}};\
         window._nTransformFunc=function(n){{try{{return {n_expression};}}catch(e){{return n;}}}}; "
    );
    match player_code.rfind(PLAYER_IIFE_TRAILER) {
        Some(at) => format!("{}{exports} {}", &player_code[..at], &player_code[at..]),
        None => format!("{player_code}\n{exports}"),
    }
}

fn n_expression(n_class: &str) -> String {
    format!(
        "(function(n){{try{{var u=new g.{n_class}('https://x.googlevideo.com/videoplayback?n='+n,true);\
         var t=u.get('n');return(t&&t!==n)?t:n;}}catch(e){{return n;}}}})(INPUT)"
    )
}

/// プレイヤー JS がブラウザの外で動くための最小の置き換え（innertubex の setupYoutubeGlobals と同じ）。
const GLOBALS_PREAMBLE: &str = r#"
if (typeof globalThis.XMLHttpRequest === "undefined") { globalThis.XMLHttpRequest = { prototype: {} }; }
globalThis.location = { hash: "", host: "www.youtube.com", hostname: "www.youtube.com", href: "https://www.youtube.com/watch?v=yt-dlp-wins",
    origin: "https://www.youtube.com", password: "", pathname: "/watch", port: "", protocol: "https:", search: "?v=yt-dlp-wins", username: "" };
if (typeof globalThis.document === "undefined") { globalThis.document = Object.create(null); }
if (typeof globalThis.navigator === "undefined") { globalThis.navigator = Object.create(null); }
if (typeof globalThis.self === "undefined") { globalThis.self = globalThis; }
if (typeof globalThis.window === "undefined") { globalThis.window = globalThis; }
if (typeof globalThis.Intl === "undefined") {
    const NumberFormat = function(locale, options) { this.options = options || {}; };
    NumberFormat.supportedLocalesOf = function(locales) { return Array.isArray(locales) ? locales : [locales]; };
    NumberFormat.prototype.format = function(value) { let f = String(value); const m = this.options.minimumIntegerDigits || 0; while (f.length < m) f = "0" + f; return f; };
    const DateTimeFormat = function() {};
    DateTimeFormat.prototype.resolvedOptions = function() { return { timeZone: "UTC" }; };
    DateTimeFormat.prototype.format = function(value) { return String(value); };
    globalThis.Intl = { NumberFormat, DateTimeFormat };
}
"#;

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> PlayerCipherConfig {
        PlayerCipherConfig { sig_expression: "Xy(1,2,INPUT)".into(), n_class: "Nk".into(), signature_timestamp: 20000 }
    }

    #[test]
    fn exports_go_inside_the_player_iife() {
        let script = build_modified_player_script("var _yt_player={};(function(g){var a=1;})(_yt_player);", &config());
        let at = script.find("_cipherSigFunc").unwrap();
        assert!(at < script.rfind(PLAYER_IIFE_TRAILER).unwrap());
        assert!(script.contains("return Xy(1,2,sig);"));
    }

    #[test]
    fn solves_with_a_tiny_fake_player() {
        // 本物のプレイヤーの代わり: 署名は逆順、n は前に "x" を付けるだけ。本物と同じく非 strict の `this` を使う
        let player = r#"var _yt_player={};(function(g){var window=this;
            g.Nk=function(u){this.v=u.split('n=')[1];};g.Nk.prototype.get=function(){return 'xx'+this.v;};
            window.Xy=function(a,b,s){return s.split('').reverse().join('');};
        })(_yt_player);"#;
        let solver = CipherSolver::create(player, &config()).expect("solver");
        assert_eq!(solver.solve_signature("abc").as_deref(), Some("cba"));
        assert_eq!(solver.solve_n("Qq123").as_deref(), Some("xxQq123"));
    }
}
