import Foundation

/// 連携の形式の JSON にできるもの。
/// kotlinx.serialization の設定（ignoreUnknownKeys・encodeDefaults・null を "x":null と出す）に合わせる。
/// 項目は Kotlin の宣言の順に並べる（kotlinx.serialization と同じ順で出す）。
/// Kotlin で既定値の無い、null にならない項目は必須（欠けていれば読めない）。null になりうる項目は、欠けていても null として読む。
public protocol ConnectCodable {
    func toJSON() -> JSON

    /// 読めなければ投げる（`ConnectJSON.decode` が nil にする）。
    static func fromJSON(_ json: JSON) throws -> Self
}

/// 読めない JSON（値を含めない）。
struct ConnectDecodeError: Error {
    let field: String
}

/// Windows の ConnectJson（System.Text.Json に kotlinx と同じ規則を足したもの）に当たる。
public enum ConnectJSON {
    public static func encode<T: ConnectCodable>(_ value: T) -> String { serialize(value.toJSON()) }

    public static func encodeToUTF8<T: ConnectCodable>(_ value: T) -> Data { Data(encode(value).utf8) }

    /// 読めなければ nil（例外を出さない）。
    public static func decode<T: ConnectCodable>(_ type: T.Type, from text: String) -> T? {
        guard let json = try? JSON.parse(text) else { return nil }
        return decode(type, json: json)
    }

    public static func decode<T: ConnectCodable>(_ type: T.Type, from utf8: Data) -> T? {
        guard let json = try? JSON.parse(utf8) else { return nil }
        return decode(type, json: json)
    }

    public static func decode<T: ConnectCodable>(_ type: T.Type, json: JSON) -> T? { try? T.fromJSON(json) }

    /// 詰めた文字列。kotlinx.serialization と同じく、JSON に必要な文字（`"`・`\`・U+0000〜U+001F）だけを逃がす。
    /// 日本語・絵文字はそのまま出す（同じ値から Kotlin と同じ文字列ができる）。
    public static func serialize(_ json: JSON) -> String {
        var out = ""
        write(json, to: &out)
        return out
    }

    private static func write(_ json: JSON, to out: inout String) {
        switch json {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let d):
            if d.rounded() == d, abs(d) < 9.0e18, let n = Int64(exactly: d) { out += String(n) } else { out += String(d) }
        case .string(let s): writeString(s, to: &out)
        case .array(let a):
            out += "["
            for (i, item) in a.enumerated() {
                if i > 0 { out += "," }
                write(item, to: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (i, (k, v)) in o.pairs.enumerated() {
                if i > 0 { out += "," }
                writeString(k, to: &out)
                out += ":"
                write(v, to: &out)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, to out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x09: out += "\\t"
            case 0x0A: out += "\\n"
            case 0x0C: out += "\\f"
            case 0x0D: out += "\\r"
            case 0..<0x20: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }
}

/// JSON のオブジェクトから項目を読む（型が違う・必須が欠けている・null にならない項目が null なら投げる）。
struct FieldReader {
    let object: JSONObject

    init(_ json: JSON) throws {
        guard case .object(let o) = json else { throw ConnectDecodeError(field: "(root)") }
        object = o
    }

    /// 必須の値（欠けている・null なら投げる）。
    private func required(_ key: String) throws -> JSON {
        guard let value = object[key], !value.isNull else { throw ConnectDecodeError(field: key) }
        return value
    }

    /// null 可の値（欠けていれば null）。
    private func optional(_ key: String) -> JSON? {
        guard let value = object[key], !value.isNull else { return nil }
        return value
    }

    func string(_ key: String) throws -> String {
        guard case .string(let s) = try required(key) else { throw ConnectDecodeError(field: key) }
        return s
    }

    func optString(_ key: String) throws -> String? {
        guard let value = optional(key) else { return nil }
        guard case .string(let s) = value else { throw ConnectDecodeError(field: key) }
        return s
    }

    func bool(_ key: String) throws -> Bool {
        guard case .bool(let b) = try required(key) else { throw ConnectDecodeError(field: key) }
        return b
    }

    func bool(_ key: String, default value: Bool) throws -> Bool {
        object[key] == nil ? value : try bool(key)
    }

    func long(_ key: String) throws -> Int64 { try FieldReader.integer(try required(key), key) }

    func long(_ key: String, default value: Int64) throws -> Int64 {
        object[key] == nil ? value : try long(key)
    }

    func optLong(_ key: String) throws -> Int64? {
        guard let value = optional(key) else { return nil }
        return try FieldReader.integer(value, key)
    }

    func int(_ key: String) throws -> Int { try FieldReader.int32(try long(key), key) }

    func int(_ key: String, default value: Int) throws -> Int {
        object[key] == nil ? value : try int(key)
    }

    func optInt(_ key: String) throws -> Int? {
        guard let n = try optLong(key) else { return nil }
        return try FieldReader.int32(n, key)
    }

    func value<T: ConnectCodable>(_ key: String, _ type: T.Type) throws -> T { try T.fromJSON(try required(key)) }

    func optValue<T: ConnectCodable>(_ key: String, _ type: T.Type) throws -> T? {
        guard let value = optional(key) else { return nil }
        return try T.fromJSON(value)
    }

    func array<T: ConnectCodable>(_ key: String, _ type: T.Type) throws -> [T] {
        guard case .array(let items) = try required(key) else { throw ConnectDecodeError(field: key) }
        return try items.map { try T.fromJSON($0) }
    }

    func array<T: ConnectCodable>(_ key: String, _ type: T.Type, default value: [T]) throws -> [T] {
        object[key] == nil ? value : try array(key, type)
    }

    func strings(_ key: String) throws -> [String] {
        guard case .array(let items) = try required(key) else { throw ConnectDecodeError(field: key) }
        return try items.map {
            guard case .string(let s) = $0 else { throw ConnectDecodeError(field: key) }
            return s
        }
    }

    func strings(_ key: String, default value: [String]) throws -> [String] {
        object[key] == nil ? value : try strings(key)
    }

    func enumValue<E: RawRepresentable>(_ key: String, _ type: E.Type) throws -> E where E.RawValue == String {
        guard let e = E(rawValue: try string(key)) else { throw ConnectDecodeError(field: key) }
        return e
    }

    /// 任意の JSON（不透明な値）。
    func raw(_ key: String) throws -> JSON {
        guard let value = object[key] else { throw ConnectDecodeError(field: key) }
        return value
    }

    func longMap(_ key: String, default value: [(String, Int64)]) throws -> [(String, Int64)] {
        guard object[key] != nil else { return value }
        guard case .object(let o) = try required(key) else { throw ConnectDecodeError(field: key) }
        return try o.pairs.map { ($0.0, try FieldReader.integer($0.1, key)) }
    }

    /// 整数（小数・文字列は受けない）。
    static func integer(_ json: JSON, _ key: String) throws -> Int64 {
        guard case .number(let d) = json, d.rounded() == d, let n = Int64(exactly: d) else { throw ConnectDecodeError(field: key) }
        return n
    }

    static func int32(_ n: Int64, _ key: String) throws -> Int {
        guard n >= Int64(Int32.min), n <= Int64(Int32.max) else { throw ConnectDecodeError(field: key) }
        return Int(n)
    }
}

extension JSON {
    init(_ n: Int64?) { self = n.map { .number(Double($0)) } ?? .null }
    init(_ n: Int?) { self = n.map { .number(Double($0)) } ?? .null }
    init<T: ConnectCodable>(_ value: T?) { self = value?.toJSON() ?? .null }
    init(strings: [String]) { self = .array(strings.map { .string($0) }) }
    init<T: ConnectCodable>(items: [T]) { self = .array(items.map { $0.toJSON() }) }
}
