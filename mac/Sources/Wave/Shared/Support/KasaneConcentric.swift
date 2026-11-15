import SwiftUI

// kasane の同心の角丸（kasane の docs/native.md の N1）。外の角丸と余白から、中の面の角を決める。
// macOS 26・iOS 26 以降は ConcentricRectangle に任せ（入れ物の containerShape から決まる）、
// 外の角から遠い角は Kasane.innerRadius の値にする。それより前は Kasane.innerRadius だけ。
extension View {
    /// 外の入れ物。中の kasaneInnerBackground が角を合わせる形を置く
    func kasaneContainer(radius: CGFloat) -> some View {
        containerShape(.rect(cornerRadius: radius, style: Kasane.cornerStyle))
    }

    /// 外の角丸 outer・余白 padding の入れ物の中に置く面
    @ViewBuilder
    func kasaneInnerBackground<S: ShapeStyle>(_ style: S, outer: CGFloat, padding: CGFloat) -> some View {
        let r = Kasane.innerRadius(outer: outer, padding: padding)
        if #available(macOS 26.0, iOS 26.0, *) {
            background(style, in: ConcentricRectangle(corners: .concentric(minimum: .fixed(r))))
        } else {
            background(style, in: RoundedRectangle(cornerRadius: r, style: Kasane.cornerStyle))
        }
    }
}
