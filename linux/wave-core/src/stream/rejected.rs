//! 解き直しても音の取得を拒まれた曲（Windows の RejectedSongs・Android の ResolvedStreamCache.markRejected と同じ）。
//! しばらく（2分）はその曲を解き直さずに飛ばす（/player の連打を防ぐ）。覚えるのはログインの世代ごとで、ログイン・ログアウトで忘れる。

use std::collections::HashMap;

pub const COOLDOWN_MS: i64 = 2 * 60 * 1000;

#[derive(Default)]
pub struct RejectedSongs {
    rejected_at: HashMap<String, i64>,
    generation: u64,
}

impl RejectedSongs {
    pub fn mark_rejected(&mut self, video_id: &str, login_generation: u64, now_ms: i64) {
        self.follow(login_generation);
        self.rejected_at.insert(video_id.to_string(), now_ms);
    }

    pub fn recently_rejected(&mut self, video_id: &str, login_generation: u64, now_ms: i64) -> bool {
        self.follow(login_generation);
        match self.rejected_at.get(video_id) {
            Some(at) if now_ms - at < COOLDOWN_MS => true,
            Some(_) => {
                self.rejected_at.remove(video_id);
                false
            }
            None => false,
        }
    }

    fn follow(&mut self, login_generation: u64) {
        if login_generation != self.generation {
            self.generation = login_generation;
            self.rejected_at.clear();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_rejected_song_is_skipped_for_two_minutes_and_forgotten_on_login() {
        let mut songs = RejectedSongs::default();
        songs.mark_rejected("a", 0, 1_000);
        assert!(songs.recently_rejected("a", 0, 1_000 + COOLDOWN_MS - 1));
        assert!(!songs.recently_rejected("b", 0, 2_000));
        assert!(!songs.recently_rejected("a", 0, 1_000 + COOLDOWN_MS));
        songs.mark_rejected("a", 0, 5_000);
        // ログイン・ログアウトで世代が変わったら忘れる
        assert!(!songs.recently_rejected("a", 1, 5_001));
    }
}
