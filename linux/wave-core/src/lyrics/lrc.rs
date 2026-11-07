//! LRC 形式（[mm:ss.xx]本文）の読み取りと、再生位置と行の対応。

use std::sync::LazyLock;

use regex::Regex;

use super::LyricLine;

static TIME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]").unwrap());
static OFFSET: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)^\[offset:\s*([+-]?\d+)\s*]$").unwrap());

/// 時刻の付いた行だけを時刻の順に返す。[offset:+500] は全体を 500ms 早める。
pub fn parse(text: &str) -> Vec<LyricLine> {
    let mut offset: i64 = 0;
    let mut lines = Vec::new();
    for raw in text.split('\n') {
        let line = raw.trim();
        if let Some(m) = OFFSET.captures(line) {
            offset = m[1].parse().unwrap_or(0);
            continue;
        }
        let mut times = Vec::new();
        let mut rest = line;
        while let Some(m) = TIME.captures(rest) {
            // 小数部は桁数で意味が変わる（.9 = 900ms、.90 = 900ms、.905 = 905ms）
            let fraction = m.get(3).map_or(0, |f| format!("{:0<3}", f.as_str()).parse::<i64>().unwrap_or(0));
            times.push(m[1].parse::<i64>().unwrap_or(0) * 60_000 + m[2].parse::<i64>().unwrap_or(0) * 1_000 + fraction);
            rest = &rest[m.get(0).unwrap().end()..];
        }
        let body = rest.trim();
        for t in times {
            lines.push(LyricLine { start_ms: (t - offset).max(0) as u64, text: body.to_string() });
        }
    }
    lines.sort_by_key(|l| l.start_ms);
    lines
}

/// 行の開始より少し早く切り替える（読み始めが歌に遅れないように）。
pub const LEAD_MS: u64 = 150;

/// position_ms で歌っている行の位置。最初の行より前なら None。
pub fn current_index(lines: &[LyricLine], position_ms: u64) -> Option<usize> {
    let at = position_ms + LEAD_MS;
    lines.partition_point(|l| l.start_ms <= at).checked_sub(1)
}

/// position_ms で歌っている行の範囲（最初と最後の位置）。同じ時刻の行（原文に続く読み・訳）はまとめて今の行とする
/// （Mac・Android と同じ）。最初の行より前なら None。
pub fn current_range(lines: &[LyricLine], position_ms: u64) -> Option<(usize, usize)> {
    let last = current_index(lines, position_ms)?;
    let start = lines[last].start_ms;
    Some((lines.partition_point(|l| l.start_ms < start), last))
}

/// 前の行と同じ時刻の行（原文に添える読み・訳）か。
pub fn is_sub_line(lines: &[LyricLine], index: usize) -> bool {
    index > 0 && lines.get(index).is_some_and(|l| l.start_ms == lines[index - 1].start_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lines_are_sorted_and_offset_applied() {
        let lines = parse("[ar:x]\n[offset:+500]\n[00:02.00][00:10.5]サビ\n[00:01.905]はじめ\nno time");
        assert_eq!(lines.iter().map(|l| l.start_ms).collect::<Vec<_>>(), [1405, 1500, 10000]);
        assert_eq!(lines[0].text, "はじめ");
    }

    #[test]
    fn current_index_leads_slightly() {
        let lines = [LyricLine { start_ms: 1000, text: "a".into() }, LyricLine { start_ms: 2000, text: "b".into() }];
        assert_eq!(current_index(&lines, 500), None);
        assert_eq!(current_index(&lines, 900), Some(0));
        assert_eq!(current_index(&lines, 5000), Some(1));
    }

    #[test]
    fn lines_with_the_same_time_are_current_together() {
        let line = |start_ms, text: &str| LyricLine { start_ms, text: text.into() };
        let lines = [line(1000, "原文"), line(1000, "よみ"), line(1000, "訳"), line(2000, "次")];
        assert_eq!(current_range(&lines, 500), None);
        assert_eq!(current_range(&lines, 1500), Some((0, 2)));
        assert_eq!(current_range(&lines, 2500), Some((3, 3)));
        assert_eq!((0..4).map(|i| is_sub_line(&lines, i)).collect::<Vec<_>>(), [false, true, true, false]);
    }
}
