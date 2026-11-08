//! 画面に出す短い文のうち、GTK 版と QML 版で同じにするもの。

/// 歌詞の出典の表示。出典がもう「提供元: Musixmatch」「Source: …」のような見出し付きの文なら、そのまま出す
/// （「歌詞の提供: 提供元: …」と重ねない）。名前だけなら「歌詞の提供: 名前」。
pub fn lyrics_source(source: &str) -> String {
    let source = source.trim();
    if source.contains(':') || source.contains('：') { source.to_string() } else { format!("歌詞の提供: {source}") }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn does_not_repeat_the_heading() {
        assert_eq!(lyrics_source("LRCLIB"), "歌詞の提供: LRCLIB");
        assert_eq!(lyrics_source("提供元: Musixmatch"), "提供元: Musixmatch");
        assert_eq!(lyrics_source("Source: Musixmatch"), "Source: Musixmatch");
    }
}
