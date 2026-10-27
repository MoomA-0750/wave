//! Claude に聞く（Android・Mac・Windows と同じ）。家の PC などで動く中継 ai-bridge 経由で Claude を使う。
//! 中継は状態もアプリのことも持たないので、毎回システムプロンプト・道具の定義（ai/agent.json）と、それまでのやりとり・今の曲・歌詞を
//! 入れた頼みの文を送る。道具の呼び出しはアプリの側で行う。YouTube の認証情報は中継へ送らない（別のクライアントを使う）。

pub mod bridge;
pub mod spec;
pub mod text;
