// この端末の鍵（身元の署名鍵と受け取りの鍵 kx、どちらも P-256）。秘密鍵は書き出せない CryptoKey のまま持つ（Swift の SoftwareDevice に当たる）。

import type { Bytes } from "./bytes.ts";
import { memberKeysOf, type DeviceSigner, type KxOpener } from "./chain.ts";
import { deviceId, ecdh, generateKxKey, generateSigningKey, importPkcs8, publicKeyOf, sign, type EcPublicKey } from "./crypto.ts";
import type { MemberKeys, Role } from "./models.ts";

/** IndexedDB に置く形（CryptoKey は構造化複製でそのまま置ける）。 */
export interface StoredKeys {
  identity: CryptoKeyPair;
  kx: CryptoKeyPair;
}

export class Device implements DeviceSigner, KxOpener {
  readonly keys: StoredKeys;
  readonly deviceId: string;
  readonly identityPub: Bytes;
  readonly kxPub: EcPublicKey;

  private constructor(keys: StoredKeys, deviceId: string, identityPub: Bytes, kxPub: EcPublicKey) {
    this.keys = keys;
    this.deviceId = deviceId;
    this.identityPub = identityPub;
    this.kxPub = kxPub;
  }

  static async fromKeys(keys: StoredKeys): Promise<Device> {
    const identity = await publicKeyOf(keys.identity.publicKey);
    const kx = await publicKeyOf(keys.kx.publicKey);
    return new Device(keys, await deviceId(identity.spki), identity.spki, kx);
  }

  /** 新しい鍵で作る（秘密鍵は書き出せない）。 */
  static async generate(): Promise<Device> {
    return Device.fromKeys({ identity: await generateSigningKey(), kx: await generateKxKey() });
  }

  /** PKCS#8 の秘密鍵から作る（試験で、他の端末の固定のテスト鍵を使う）。 */
  static async fromPkcs8(identity: Uint8Array, kx: Uint8Array): Promise<Device> {
    return Device.fromKeys({ identity: await importPkcs8(identity, "sign"), kx: await importPkcs8(kx, "ecdh") });
  }

  sign(label: string, message: Uint8Array): Promise<Bytes> {
    return sign(this.keys.identity.privateKey, label, message);
  }

  dh(peer: EcPublicKey): Promise<Bytes> {
    return ecdh(this.keys.kx.privateKey, peer);
  }

  /** 家に加わるときの鍵の記述。 */
  memberKeys(role: Role): Promise<MemberKeys> {
    return memberKeysOf(this, this.kxPub, role);
  }
}
