import SwiftUI
import WaveCore

/// 中央の枠（ページ）と、下の知らせ。
struct ContentPane: View {
    var body: some View {
        let nav = AppServices.nav!
        ZStack(alignment: .bottom) {
            page(nav.current.route)
                .id(nav.current.id)
                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            ToastView(toast: AppServices.toast)
        }
        .animation(.easeOut(duration: 0.18), value: nav.current.id)
        .animation(.easeOut(duration: 0.2), value: AppServices.toast.text)
    }

    @ViewBuilder
    private func page(_ route: Route) -> some View {
        switch route {
        case .home: HomePageView()
        case .search(let query): SearchPageView(initialQuery: query)
        case .collection(let request): CollectionPageView(request: request)
        case .artist(let browseId): ArtistPageView(browseId: browseId)
        case .song(let song): SongPageView(song: song)
        case .mood(let mood): MoodPageView(mood: mood)
        case .history: HistoryPageView()
        case .settings: SettingsPageView()
        case .devices: DevicesPageView()
        case .lyrics: LyricsPageView()
        }
    }
}
