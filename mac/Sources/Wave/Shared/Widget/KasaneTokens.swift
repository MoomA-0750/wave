// 書き出したもの。直すのは kasane の tokens.css と themes/personal.css（scripts/build.mjs）
// kasane 1.1.0  source 3d5c1044cf587f0b
import SwiftUI

/// kasane の角丸・余白。色・字・動きはシステムに従うので、ここには無い（kasane の docs/native.md）
public enum Kasane {
    /// 角丸（pt）。ピルは Capsule() を使う
    public enum Radius {
        public static let sm: CGFloat = 6
        public static let base: CGFloat = 10
        public static let lg: CGFloat = 16
        public static let min: CGFloat = 2
        public static let control: CGFloat = 10
        public static let field: CGFloat = 10
        public static let card: CGFloat = 26
        public static let row: CGFloat = 26
    }

    /// 余白（pt）
    public enum Space {
        public static let s1: CGFloat = 4
        public static let s2: CGFloat = 8
        public static let s3: CGFloat = 12
        public static let s4: CGFloat = 16
        public static let s5: CGFloat = 20
        public static let s6: CGFloat = 24
        public static let s8: CGFloat = 32
        public static let s10: CGFloat = 40
    }

    /// 角の形。kasane の squircle は .continuous
    public static let cornerStyle: RoundedCornerStyle = .continuous

    /// 同心の角丸: 中の角丸 = max(radius-min, 外 − 余白)。外がピルなら使わない（iOS 26・macOS 26 以降は ConcentricRectangle に任せる）
    public static func innerRadius(outer: CGFloat, padding: CGFloat) -> CGFloat {
        max(Radius.min, outer - padding)
    }
}
