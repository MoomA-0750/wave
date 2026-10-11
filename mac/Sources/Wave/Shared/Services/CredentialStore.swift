import Foundation
import Security
import WaveCore

/// 認証情報を macOS のキーチェーン（このユーザーのログインキーチェーン。この端末からだけ読める）に保存する。
/// 平文はファイルにもログにも出さない。Windows の DPAPI に当たる。
final class CredentialStore {
    enum LoadState: String { case none, loaded, unreadable }

    private let service = "xyz.mooma0750.wave"
    private let account = "credentials/v1"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func load() -> (LoadState, Credentials?) {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        if status == errSecItemNotFound { return (.none, nil) }
        guard status == errSecSuccess, let data = item as? Data, let text = String(data: data, encoding: .utf8) else { return (.unreadable, nil) }
        guard let credentials = Credentials.deserialize(text) else { return (.unreadable, nil) }
        return (.loaded, credentials)
    }

    func save(_ credentials: Credentials) -> Bool {
        let data = Data(credentials.serialize().utf8)
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        q[kSecAttrLabel as String] = "wave"
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    func clear() {
        SecItemDelete(query as CFDictionary)
    }
}
