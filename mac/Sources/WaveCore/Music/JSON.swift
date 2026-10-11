import Foundation

/// InnerTube の応答・送る中身の JSON。オブジェクトのキーは届いた順を保つ（FindAll の「出てくる順」に要る）。
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object(JSONObject)

    // MARK: 読む道具（Windows の JsonPath と同じ。途中が無い・型が違えば nil や空を返す）

    public subscript(key: String) -> JSON? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    /// keys の順にたどる。
    public func path(_ keys: String...) -> JSON? { path(keys) }

    public func path(_ keys: [String]) -> JSON? {
        var current: JSON? = self
        for key in keys {
            guard case .object(let o)? = current, let next = o[key] else { return nil }
            current = next
        }
        return current
    }

    public var obj: JSONObject? {
        if case .object(let o) = self { return o }
        return nil
    }

    public var arr: [JSON]? {
        if case .array(let a) = self { return a }
        return nil
    }

    /// 配列の中のオブジェクトだけ。
    public var items: [JSON] {
        guard case .array(let a) = self else { return [] }
        return a.filter { if case .object = $0 { return true } else { return false } }
    }

    public var firstItem: JSON? { items.first }

    public var str: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    public var bool: Bool? {
        if case .bool(let b) = self { return b }
        return nil
    }

    public var double: Double? {
        switch self {
        case .number(let d): return d
        case .string(let s): return Double(s)
        default: return nil
        }
    }

    /// 数か、数の文字列を整数として読む。
    public var long: Int64? {
        switch self {
        case .number(let d): return d.isFinite ? Int64(d) : nil
        case .string(let s): return Int64(s)
        default: return nil
        }
    }

    public var int: Int? { long.map { Int($0) } }

    public var isNull: Bool { if case .null = self { return true } else { return false } }

    /// key という名前のオブジェクトを、木のどこにあってもすべて集める（深さ優先、出てくる順）。
    public func findAll(_ key: String) -> [JSON] {
        var found: [JSON] = []
        func walk(_ node: JSON) {
            switch node {
            case .object(let o):
                for (name, value) in o.pairs {
                    if name == key, case .object = value { found.append(value) }
                    walk(value)
                }
            case .array(let a):
                for item in a { walk(item) }
            default:
                break
            }
        }
        walk(self)
        return found
    }

    // MARK: 作る

    public static func obj(_ pairs: KeyValuePairs<String, JSON>) -> JSON {
        .object(JSONObject(pairs.map { ($0.key, $0.value) }))
    }

    public static func parse(_ data: Data) throws -> JSON {
        var parser = JSONParser(bytes: [UInt8](data))
        return try parser.parseDocument()
    }

    public static func parse(_ text: String) throws -> JSON {
        try parse(Data(text.utf8))
    }

    /// 詰めた文字列（prettyPrint なし）。
    public func serialized() -> String {
        var out = ""
        write(to: &out)
        return out
    }

    public func data() -> Data { Data(serialized().utf8) }

    private func write(to out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let d):
            if d.rounded() == d, abs(d) < 9.0e15 { out += String(Int64(d)) } else { out += String(d) }
        case .string(let s): JSON.writeString(s, to: &out)
        case .array(let a):
            out += "["
            for (i, item) in a.enumerated() {
                if i > 0 { out += "," }
                item.write(to: &out)
            }
            out += "]"
        case .object(let o):
            out += "{"
            for (i, (k, v)) in o.pairs.enumerated() {
                if i > 0 { out += "," }
                JSON.writeString(k, to: &out)
                out += ":"
                v.write(to: &out)
            }
            out += "}"
        }
    }

    private static func writeString(_ s: String, to out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByFloatLiteral, ExpressibleByNilLiteral
{
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSON)...) { self = .object(JSONObject(elements)) }
    public init(nilLiteral: ()) { self = .null }
}

public extension Optional where Wrapped == JSON {
    func path(_ keys: String...) -> JSON? { self?.path(keys) }
    subscript(key: String) -> JSON? { self?[key] }
    var str: String? { self?.str }
    var long: Int64? { self?.long }
    var int: Int? { self?.int }
    var double: Double? { self?.double }
    var bool: Bool? { self?.bool }
    var items: [JSON] { self?.items ?? [] }
    var firstItem: JSON? { self?.firstItem }
    var obj: JSONObject? { self?.obj }
    var arr: [JSON]? { self?.arr }
    func findAll(_ key: String) -> [JSON] { self?.findAll(key) ?? [] }
}

public extension JSON {
    init(_ s: String?) { self = s.map { .string($0) } ?? .null }
    init(_ n: Int) { self = .number(Double(n)) }
    init(_ n: Int64) { self = .number(Double(n)) }
    init(_ d: Double) { self = .number(d) }
    init(_ b: Bool) { self = .bool(b) }
}

/// 届いた順を保つオブジェクト。
public struct JSONObject: Equatable, Sendable {
    public private(set) var pairs: [(String, JSON)]
    private var index: [String: Int]

    public init(_ pairs: [(String, JSON)] = []) {
        self.pairs = []
        self.index = [:]
        for (k, v) in pairs { self[k] = v }
    }

    public subscript(key: String) -> JSON? {
        get { index[key].map { pairs[$0].1 } }
        set {
            if let i = index[key] {
                if let newValue { pairs[i].1 = newValue } else {
                    pairs.remove(at: i)
                    index = [:]
                    for (n, (k, _)) in pairs.enumerated() { index[k] = n }
                }
            } else if let newValue {
                index[key] = pairs.count
                pairs.append((key, newValue))
            }
        }
    }

    public var keys: [String] { pairs.map(\.0) }
    public var count: Int { pairs.count }

    public static func == (a: JSONObject, b: JSONObject) -> Bool {
        a.pairs.count == b.pairs.count && a.pairs.allSatisfy { b[$0.0] == $0.1 }
    }
}

public struct JSONError: Error, CustomStringConvertible {
    public let offset: Int
    public var description: String { "JSON parse error at \(offset)" }
}

/// 小さな JSON の読み手（キーの順を保つため Foundation の JSONSerialization を使わない）。
struct JSONParser {
    let bytes: [UInt8]
    var i = 0

    init(bytes: [UInt8]) { self.bytes = bytes }

    mutating func parseDocument() throws -> JSON {
        // BOM を飛ばす
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF { i = 3 }
        let value = try parseValue(depth: 0)
        skipSpace()
        guard i == bytes.count else { throw JSONError(offset: i) }
        return value
    }

    mutating func skipSpace() {
        while i < bytes.count, bytes[i] == 0x20 || bytes[i] == 0x0A || bytes[i] == 0x0D || bytes[i] == 0x09 { i += 1 }
    }

    mutating func parseValue(depth: Int) throws -> JSON {
        guard depth < 512 else { throw JSONError(offset: i) }
        skipSpace()
        guard i < bytes.count else { throw JSONError(offset: i) }
        switch bytes[i] {
        case UInt8(ascii: "{"):
            i += 1
            var pairs: [(String, JSON)] = []
            skipSpace()
            if i < bytes.count, bytes[i] == UInt8(ascii: "}") { i += 1; return .object(JSONObject(pairs)) }
            while true {
                skipSpace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: "\"") else { throw JSONError(offset: i) }
                let key = try parseString()
                skipSpace()
                guard i < bytes.count, bytes[i] == UInt8(ascii: ":") else { throw JSONError(offset: i) }
                i += 1
                pairs.append((key, try parseValue(depth: depth + 1)))
                skipSpace()
                guard i < bytes.count else { throw JSONError(offset: i) }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                if bytes[i] == UInt8(ascii: "}") { i += 1; break }
                throw JSONError(offset: i)
            }
            return .object(JSONObject(pairs))
        case UInt8(ascii: "["):
            i += 1
            var items: [JSON] = []
            skipSpace()
            if i < bytes.count, bytes[i] == UInt8(ascii: "]") { i += 1; return .array(items) }
            while true {
                items.append(try parseValue(depth: depth + 1))
                skipSpace()
                guard i < bytes.count else { throw JSONError(offset: i) }
                if bytes[i] == UInt8(ascii: ",") { i += 1; continue }
                if bytes[i] == UInt8(ascii: "]") { i += 1; break }
                throw JSONError(offset: i)
            }
            return .array(items)
        case UInt8(ascii: "\""):
            return .string(try parseString())
        case UInt8(ascii: "t"):
            try expect("true"); return .bool(true)
        case UInt8(ascii: "f"):
            try expect("false"); return .bool(false)
        case UInt8(ascii: "n"):
            try expect("null"); return .null
        default:
            return try parseNumber()
        }
    }

    mutating func expect(_ word: String) throws {
        for b in word.utf8 {
            guard i < bytes.count, bytes[i] == b else { throw JSONError(offset: i) }
            i += 1
        }
    }

    mutating func parseNumber() throws -> JSON {
        let start = i
        while i < bytes.count {
            let b = bytes[i]
            if (b >= 0x30 && b <= 0x39) || b == UInt8(ascii: "-") || b == UInt8(ascii: "+") || b == UInt8(ascii: ".")
                || b == UInt8(ascii: "e") || b == UInt8(ascii: "E")
            {
                i += 1
            } else { break }
        }
        guard i > start, let text = String(bytes: bytes[start..<i], encoding: .ascii), let d = Double(text) else {
            throw JSONError(offset: start)
        }
        return .number(d)
    }

    mutating func parseString() throws -> String {
        i += 1  // "
        var buffer: [UInt8] = []
        var runStart = i
        while i < bytes.count {
            let b = bytes[i]
            if b == UInt8(ascii: "\"") {
                buffer.append(contentsOf: bytes[runStart..<i])
                i += 1
                return String(decoding: buffer, as: UTF8.self)
            }
            if b == UInt8(ascii: "\\") {
                buffer.append(contentsOf: bytes[runStart..<i])
                i += 1
                guard i < bytes.count else { throw JSONError(offset: i) }
                let e = bytes[i]
                i += 1
                switch e {
                case UInt8(ascii: "\""): buffer.append(0x22)
                case UInt8(ascii: "\\"): buffer.append(0x5C)
                case UInt8(ascii: "/"): buffer.append(0x2F)
                case UInt8(ascii: "b"): buffer.append(0x08)
                case UInt8(ascii: "f"): buffer.append(0x0C)
                case UInt8(ascii: "n"): buffer.append(0x0A)
                case UInt8(ascii: "r"): buffer.append(0x0D)
                case UInt8(ascii: "t"): buffer.append(0x09)
                case UInt8(ascii: "u"):
                    var code = try hex4()
                    if code >= 0xD800 && code < 0xDC00, i + 1 < bytes.count, bytes[i] == UInt8(ascii: "\\"),
                        bytes[i + 1] == UInt8(ascii: "u")
                    {
                        i += 2
                        let low = try hex4()
                        if low >= 0xDC00 && low < 0xE000 {
                            code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                        } else {
                            buffer.append(contentsOf: Array(String(Character(Unicode.Scalar(0xFFFD)!)).utf8))
                            code = low
                        }
                    }
                    let scalar = Unicode.Scalar(code) ?? Unicode.Scalar(0xFFFD)!
                    buffer.append(contentsOf: Array(String(Character(scalar)).utf8))
                default:
                    throw JSONError(offset: i)
                }
                runStart = i
                continue
            }
            i += 1
        }
        throw JSONError(offset: i)
    }

    mutating func hex4() throws -> UInt32 {
        guard i + 4 <= bytes.count else { throw JSONError(offset: i) }
        var v: UInt32 = 0
        for _ in 0..<4 {
            let b = bytes[i]
            v <<= 4
            switch b {
            case 0x30...0x39: v |= UInt32(b - 0x30)
            case 0x41...0x46: v |= UInt32(b - 0x41 + 10)
            case 0x61...0x66: v |= UInt32(b - 0x61 + 10)
            default: throw JSONError(offset: i)
            }
            i += 1
        }
        return v
    }
}
