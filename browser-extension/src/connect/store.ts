// 保存（端末の鍵・家の記録・端末の名前と種類・設定）。拡張では IndexedDB に置く（Chrome と Firefox のどちらの拡張の文脈でも使え、
// CryptoKey を書き出せないまま構造化複製で置ける）。試験ではメモリに置く。

export interface KeyValueStore {
  get<T>(key: string): Promise<T | undefined>;
  set(key: string, value: unknown): Promise<void>;
  delete(key: string): Promise<void>;
}

export const StoreKeys = {
  /** 端末の鍵（StoredKeys。CryptoKey のまま）。 */
  keys: "keys",
  /** 家の記録（ClientPersisted の JSON の文字列）。 */
  client: "client",
  /** 端末 ID から表示名・種類。 */
  labels: "labels",
  /** 中継の宛先と端末名（ConnectSettings）。 */
  settings: "settings",
} as const;

export class MemoryStore implements KeyValueStore {
  private readonly map = new Map<string, unknown>();

  async get<T>(key: string): Promise<T | undefined> {
    return this.map.get(key) as T | undefined;
  }

  async set(key: string, value: unknown): Promise<void> {
    this.map.set(key, value);
  }

  async delete(key: string): Promise<void> {
    this.map.delete(key);
  }
}

const DB_NAME = "wave-connect";
const DB_STORE = "kv";

export class IndexedDbStore implements KeyValueStore {
  private db: Promise<IDBDatabase> | null = null;

  private open(): Promise<IDBDatabase> {
    this.db ??= new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(DB_STORE);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => {
        this.db = null;
        reject(request.error ?? new Error("indexeddb"));
      };
    });
    return this.db;
  }

  private async run<T>(mode: IDBTransactionMode, body: (store: IDBObjectStore) => IDBRequest): Promise<T> {
    const db = await this.open();
    return new Promise<T>((resolve, reject) => {
      const tx = db.transaction(DB_STORE, mode);
      const request = body(tx.objectStore(DB_STORE));
      tx.oncomplete = () => resolve(request.result as T);
      tx.onerror = () => reject(tx.error ?? new Error("indexeddb"));
      tx.onabort = () => reject(tx.error ?? new Error("indexeddb"));
    });
  }

  get<T>(key: string): Promise<T | undefined> {
    return this.run<T | undefined>("readonly", (s) => s.get(key));
  }

  async set(key: string, value: unknown): Promise<void> {
    await this.run("readwrite", (s) => s.put(value, key));
  }

  async delete(key: string): Promise<void> {
    await this.run("readwrite", (s) => s.delete(key));
  }
}
