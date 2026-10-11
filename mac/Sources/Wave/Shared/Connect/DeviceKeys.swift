import Foundation
import Security
import WaveCore

/// 端末の連携（Connect v2）の、この端末の鍵（身元の署名鍵と受け取りの鍵 kx、どちらも P-256）。
/// macOS のキーチェーン（このユーザーのログインキーチェーン。この Mac からだけ、ロックを外している間だけ読める）に PKCS#8 で置く。
/// Windows は CNG の保管庫に書き出しできない鍵として作るが、Mac ではコアの SoftwareDevice（ソフトウェアの鍵）をキーチェーンで保護する。
/// 鍵の値はログにも画面にも出さない。
enum DeviceKeys {
    private static let identityAccount = "connect-identity/v2"
    private static let kxAccount = "connect-kx/v2"

    enum LoadState: String { case loaded, created, unreadable }

    /// キーチェーンの鍵を開く。無ければ作る。開けない（壊れた・片方だけ残った）ときは作り直さない。
    static func loadOrCreate() -> (state: LoadState, device: SoftwareDevice?) {
        let identity = ConnectKeychain.read(identityAccount)
        let kx = ConnectKeychain.read(kxAccount)
        switch (identity, kx) {
        case (.found(let identityPkcs8), .found(let kxPkcs8)):
            do {
                return (.loaded, try SoftwareDevice.fromPkcs8(identity: identityPkcs8, kx: kxPkcs8))
            } catch {
                AppPaths.log("connect-v2 keys failed type=\(String(describing: type(of: error)))")
                return (.unreadable, nil)
            }
        case (.missing, .missing):
            let device = SoftwareDevice.generate()
            // 片方だけ書けたまま残すと、次の起動で「片方だけ残った」になる。書けなければ両方消して、今回は使わない
            guard ConnectKeychain.write(identityAccount, device.exportIdentityPkcs8()), ConnectKeychain.write(kxAccount, device.exportKxPkcs8()) else {
                ConnectKeychain.delete(identityAccount)
                ConnectKeychain.delete(kxAccount)
                AppPaths.log("connect-v2 keys failed type=save")
                return (.unreadable, nil)
            }
            return (.created, device)
        default:
            // 片方だけ残っているのは、作る途中で落ちたか壊れた。勝手に作り直すと端末 ID が変わるので、本人に任せる
            // （キーチェーンを読めない＝ロック中などもここ）
            return (.unreadable, nil)
        }
    }

    /// キーチェーンの鍵を消す（連携の保存を作り直すとき）。
    static func delete() {
        ConnectKeychain.delete(identityAccount)
        ConnectKeychain.delete(kxAccount)
    }
}

/// 端末の連携で使うキーチェーンの項目（generic password、service は `xyz.mooma0750.wave`、account は用途ごとに別）。
/// Windows の DPAPI で包んだファイルに当たる。CredentialStore と同じ書き方。
enum ConnectKeychain {
    private static let service = "xyz.mooma0750.wave"

    /// 家の記録（Windows の connect-v2.bin）。
    static let stateAccount = "connect-v2/state"

    enum Read {
        case missing
        case found(Data)

        /// 読めない（ロック中・壊れた・許可が無い）。
        case failed
    }

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    static func read(_ account: String) -> Read {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, let data = item as? Data else { return .failed }
        return .found(data)
    }

    /// 置き換える（あれば中身だけ書き換え、無ければ足す。途中で落ちても前の値か新しい値のどちらかが残る）。
    static func write(_ account: String, _ data: Data) -> Bool {
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let updated = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)
        if updated == errSecSuccess { return true }
        if updated != errSecItemNotFound { return false }
        var q = query(account)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        q[kSecAttrLabel as String] = "wave"
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    static func delete(_ account: String) {
        SecItemDelete(query(account) as CFDictionary)
    }
}
