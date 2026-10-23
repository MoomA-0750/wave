// 連携の形式の JSON（Swift の ConnectJSON・FieldReader と同じ規則）。
// kotlinx.serialization の設定（ignoreUnknownKeys・encodeDefaults・null を "x":null と出す）に合わせる。
// 書くときは Kotlin の宣言の順にキーを並べたオブジェクトを JSON.stringify する（JSON.stringify が逃がす文字は kotlinx と同じ）。

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type JsonObject = { [key: string]: Json };

/** 読めない JSON（値を含めない）。 */
export class DecodeError extends Error {
  readonly field: string;

  constructor(field: string) {
    super(`decode: ${field}`);
    this.field = field;
  }
}

export function serialize(value: unknown): string {
  return JSON.stringify(value);
}

/** 読めなければ null（例外を出さない）。 */
export function parse(text: string): Json | null {
  try {
    return JSON.parse(text) as Json;
  } catch {
    return null;
  }
}

/** 読めなければ null。 */
export function decodeWith<T>(text: string | Json, read: (json: Json) => T): T | null {
  const json = typeof text === "string" ? parse(text) : text;
  if (json === null) return null;
  try {
    return read(json);
  } catch {
    return null;
  }
}

export function isObject(json: unknown): json is JsonObject {
  return typeof json === "object" && json !== null && !Array.isArray(json);
}

/** JSON のオブジェクトから項目を読む（型が違う・必須が欠けている・null にならない項目が null なら投げる）。 */
export class Reader {
  readonly o: JsonObject;

  constructor(json: Json | undefined) {
    if (!isObject(json)) throw new DecodeError("(root)");
    this.o = json;
  }

  has(key: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.o, key);
  }

  private required(key: string): Json {
    const v = this.has(key) ? this.o[key] : undefined;
    if (v === undefined || v === null) throw new DecodeError(key);
    return v;
  }

  private optional(key: string): Json | undefined {
    const v = this.has(key) ? this.o[key] : undefined;
    return v === null ? undefined : v;
  }

  string(key: string): string {
    const v = this.required(key);
    if (typeof v !== "string") throw new DecodeError(key);
    return v;
  }

  optString(key: string): string | null {
    const v = this.optional(key);
    if (v === undefined) return null;
    if (typeof v !== "string") throw new DecodeError(key);
    return v;
  }

  bool(key: string, fallback?: boolean): boolean {
    if (fallback !== undefined && !this.has(key)) return fallback;
    const v = this.required(key);
    if (typeof v !== "boolean") throw new DecodeError(key);
    return v;
  }

  optBool(key: string): boolean | null {
    const v = this.optional(key);
    if (v === undefined) return null;
    if (typeof v !== "boolean") throw new DecodeError(key);
    return v;
  }

  /** 整数（小数・文字列は受けない）。 */
  long(key: string, fallback?: number): number {
    if (fallback !== undefined && !this.has(key)) return fallback;
    return integer(this.required(key), key);
  }

  optLong(key: string): number | null {
    const v = this.optional(key);
    return v === undefined ? null : integer(v, key);
  }

  int(key: string, fallback?: number): number {
    if (fallback !== undefined && !this.has(key)) return fallback;
    return int32(this.long(key), key);
  }

  optInt(key: string): number | null {
    const n = this.optLong(key);
    return n === null ? null : int32(n, key);
  }

  value<T>(key: string, read: (json: Json) => T): T {
    return read(this.required(key));
  }

  optValue<T>(key: string, read: (json: Json) => T): T | null {
    const v = this.optional(key);
    return v === undefined ? null : read(v);
  }

  array<T>(key: string, read: (json: Json) => T, fallback?: T[]): T[] {
    if (fallback !== undefined && !this.has(key)) return fallback;
    const v = this.required(key);
    if (!Array.isArray(v)) throw new DecodeError(key);
    return v.map(read);
  }

  strings(key: string, fallback?: string[]): string[] {
    return this.array(
      key,
      (item) => {
        if (typeof item !== "string") throw new DecodeError(key);
        return item;
      },
      fallback,
    );
  }

  enumValue<E extends string>(key: string, values: readonly E[]): E {
    const v = this.string(key);
    if (!(values as readonly string[]).includes(v)) throw new DecodeError(key);
    return v as E;
  }

  optEnumValue<E extends string>(key: string, values: readonly E[]): E | null {
    const v = this.optString(key);
    if (v === null) return null;
    if (!(values as readonly string[]).includes(v)) throw new DecodeError(key);
    return v as E;
  }

  /** 任意の JSON（不透明な値）。null も値として受ける。 */
  raw(key: string): Json {
    if (!this.has(key)) throw new DecodeError(key);
    return this.o[key]!;
  }

  /** 文字列から整数への対応（届いた順を保つ）。 */
  longMap(key: string, fallback: [string, number][]): [string, number][] {
    if (!this.has(key)) return fallback;
    const v = this.required(key);
    if (!isObject(v)) throw new DecodeError(key);
    return Object.entries(v).map(([k, n]) => [k, integer(n, key)]);
  }
}

export function integer(json: Json, key: string): number {
  if (typeof json !== "number" || !Number.isInteger(json)) throw new DecodeError(key);
  return json;
}

export function int32(n: number, key: string): number {
  if (n < -2147483648 || n > 2147483647) throw new DecodeError(key);
  return n;
}
