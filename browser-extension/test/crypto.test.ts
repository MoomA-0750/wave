// 暗号の部品の試験（RFC 5869 の HKDF、RFC 9180 付録 A.3 の HPKE、署名・鍵の形）。

import assert from "node:assert/strict";
import { test } from "node:test";
import { b64, fromHex, hex, lengthPrefixed, unb64, utf8 } from "../src/connect/bytes.ts";
import {
  aesGcmOpen,
  decodePublicKey,
  derToRaw,
  ecdh,
  generateEphemeral,
  generateSigningKey,
  Hkdf,
  open,
  publicKeyOf,
  rawToDer,
  seal,
  sign,
  spkiOfSec1,
  verify,
} from "../src/connect/crypto.ts";
import { contextOpen, contextSeal, deserializePublicKey, encap, hpkeOpen, hpkeSeal, keySchedule } from "../src/connect/hpke.ts";

test("HKDF は RFC 5869 の Case 1 と同じ", async () => {
  const prk = await Hkdf.extract(fromHex("000102030405060708090a0b0c"), fromHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"));
  assert.equal(hex(prk), "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
  const okm = await Hkdf.expand(prk, fromHex("f0f1f2f3f4f5f6f7f8f9"), 42);
  assert.equal(hex(okm), "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
});

test("HKDF は RFC 5869 の Case 3（salt と info が空）と同じ", async () => {
  const prk = await Hkdf.extract(new Uint8Array(0), fromHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"));
  assert.equal(hex(prk), "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04");
  const okm = await Hkdf.expand(prk, new Uint8Array(0), 42);
  assert.equal(hex(okm), "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
});

test("base64url はパディングなしで、崩れた形を読まない", () => {
  assert.equal(b64(Uint8Array.of(0xfb, 0xff)), "-_8");
  assert.deepEqual(unb64("-_8"), Uint8Array.of(0xfb, 0xff));
  assert.deepEqual(unb64("-_8="), Uint8Array.of(0xfb, 0xff));
  assert.equal(unb64("-_8=="), null);
  assert.equal(unb64("+/8"), null);
  assert.equal(unb64("a"), null);
  assert.equal(unb64("a b"), null);
  for (let n = 0; n < 40; n++) {
    const bytes = crypto.getRandomValues(new Uint8Array(n));
    assert.deepEqual(unb64(b64(bytes)), bytes);
  }
});

test("長さ付きの連結はビッグエンディアン", () => {
  assert.equal(hex(lengthPrefixed(Uint8Array.of(1, 2), new Uint8Array(0))), "000000020102" + "00000000");
});

test("署名は DER で、ラベルが違えば通らない", async () => {
  const pair = await generateSigningKey();
  const pub = await publicKeyOf(pair.publicKey);
  const signature = await sign(pair.privateKey, "label", utf8("message"));
  assert.equal(signature[0], 0x30);
  assert.ok(await verify(pub, "label", utf8("message"), signature));
  assert.ok(!(await verify(pub, "other", utf8("message"), signature)));
  assert.ok(!(await verify(pub, "label", utf8("massage"), signature)));
  // r‖s と DER の詰め替えは往復する（頭の 0 の扱いを含む）
  for (let i = 0; i < 50; i++) {
    const raw = crypto.getRandomValues(new Uint8Array(64));
    if (i % 3 === 0) raw.fill(0, 0, 2);
    if (i % 4 === 0) raw[32] = 0x80;
    assert.deepEqual(derToRaw(rawToDer(raw)), raw);
  }
  assert.equal(derToRaw(Uint8Array.of(0x30, 0x06, 0x02, 0x01, 0x80, 0x02, 0x01, 0x01)), null);
});

test("ECDH は x 座標そのもの（32 バイト）で、両側が同じ", async () => {
  const a = await generateEphemeral();
  const b = await generateEphemeral();
  const ab = await ecdh(a.privateKey, await publicKeyOf(b.publicKey));
  const ba = await ecdh(b.privateKey, await publicKeyOf(a.publicKey));
  assert.equal(ab.length, 32);
  assert.deepEqual(ab, ba);
});

test("公開鍵は正規の P-256 の SPKI だけを受ける", async () => {
  const pair = await generateSigningKey();
  const pub = await publicKeyOf(pair.publicKey);
  assert.ok(await decodePublicKey(pub.spki));
  // 曲線の外の点
  const off = Uint8Array.from(pub.sec1);
  off[64]! ^= 1;
  assert.equal(await decodePublicKey(spkiOfSec1(off)), null);
  // 圧縮点・長さの違い
  assert.equal(await deserializePublicKey(pub.sec1.slice(0, 33)), null);
  assert.equal(await decodePublicKey(pub.spki.slice(0, 90)), null);
  assert.equal(await decodePublicKey(new Uint8Array(91)), null);
});

test("AES-GCM は往復し、改ざんを拒む", async () => {
  const key = crypto.getRandomValues(new Uint8Array(32));
  const { nonce, ciphertext } = await seal(key, utf8("aad"), utf8("hello"));
  assert.deepEqual(await open(key, nonce, utf8("aad"), ciphertext), utf8("hello"));
  assert.equal(await open(key, nonce, utf8("aae"), ciphertext), null);
  ciphertext[0]! ^= 1;
  assert.equal(await aesGcmOpen(key, nonce, utf8("aad"), ciphertext), null);
});

// RFC 9180 付録 A.3（DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM）、base モード
const v = {
  info: "4f6465206f6e2061204772656369616e2055726e",
  pkEm: "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4",
  skEm: "4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb",
  pkRm: "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0",
  skRm: "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2",
  sharedSecret: "c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8",
  key: "868c066ef58aae6dc589b6cfdd18f97e",
  baseNonce: "4e0bc5018beba4bf004cca59",
  exporterSecret: "14ad94af484a7ad3ef40e9f3be99ecc6fa9036df9d4920548424df127ee0d99f",
  pt: "4265617574792069732074727574682c20747275746820626561757479",
  encryptions: [
    [0, "436f756e742d30", "5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434"],
    [1, "436f756e742d31", "fa6f037b47fc21826b610172ca9637e82d6e5801eb31cbd3748271affd4ecb06646e0329cbdf3c3cd655b28e82"],
    [2, "436f756e742d32", "895cabfac50ce6c6eb02ffe6c048bf53b7f7be9a91fc559402cbc5b8dcaeb52b2ccc93e466c28fb55fed7a7fec"],
    [4, "436f756e742d34", "8787491ee8df99bc99a246c4b3216d3d57ab5076e18fa27133f520703bc70ec999dd36ce042e44f0c3169a6a8f"],
    [255, "436f756e742d323535", "2ad71c85bf3f45c6eca301426289854b31448bcf8a8ccb1deef3ebd87f60848aa53c538c30a4dac71d619ee2cd"],
    [256, "436f756e742d323536", "10f179686aa2caec1758c8e554513f16472bd0a11e2a907dde0b212cbe87d74f367f8ffe5e41cd3e9962a6afb2"],
  ] as [number, string, string][],
};

/** 秘密のスカラーと公開鍵の点から ECDH の鍵の組を作る（JWK で読む）。 */
async function keyPairOf(skHex: string, pkHex: string): Promise<CryptoKeyPair> {
  const pk = fromHex(pkHex);
  const jwk = { kty: "EC", crv: "P-256", x: b64(pk.slice(1, 33)), y: b64(pk.slice(33, 65)), d: b64(fromHex(skHex)), ext: true };
  const algorithm = { name: "ECDH", namedCurve: "P-256" };
  const privateKey = await crypto.subtle.importKey("jwk", jwk, algorithm, true, ["deriveBits"]);
  const publicKey = await crypto.subtle.importKey("jwk", { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y, ext: true }, algorithm, true, []);
  return { privateKey, publicKey };
}

test("HPKE の encap・鍵の予定・封じる・開くが RFC 9180 A.3 と同じ", async () => {
  const ephemeral = await keyPairOf(v.skEm, v.pkEm);
  const recipient = await keyPairOf(v.skRm, v.pkRm);
  const recipientPub = (await deserializePublicKey(fromHex(v.pkRm)))!;
  const { sharedSecret, enc } = await encap(recipientPub, ephemeral);
  assert.equal(hex(enc), v.pkEm);
  assert.equal(hex(sharedSecret), v.sharedSecret);
  const context = await keySchedule(sharedSecret, fromHex(v.info));
  assert.equal(hex(context.key), v.key);
  assert.equal(hex(context.baseNonce), v.baseNonce);
  assert.equal(hex(context.exporterSecret), v.exporterSecret);
  for (const [seq, aad, ct] of v.encryptions) {
    assert.equal(hex(await contextSeal(context, seq, fromHex(aad), fromHex(v.pt))), ct);
    assert.equal(hex((await contextOpen(context, seq, fromHex(aad), fromHex(ct)))!), v.pt);
  }
  // 受け手の DH を関数で渡して開く（1回だけの暗号化はシーケンス 0）
  const [, aad0, ct0] = v.encryptions[0]!;
  const opened = await hpkeOpen(fromHex(v.pkEm), recipientPub, (peer) => ecdh(recipient.privateKey, peer), fromHex(v.info), fromHex(aad0), fromHex(ct0));
  assert.equal(hex(opened!), v.pt);
});

test("HPKE は往復し、改ざん・別の宛先を拒む", async () => {
  const recipient = await generateEphemeral();
  const other = await generateEphemeral();
  const pub = await publicKeyOf(recipient.publicKey);
  const sealed = await hpkeSeal(pub, utf8("info"), utf8("aad"), utf8("content-key"));
  const dh = (peer: Parameters<typeof ecdh>[1]) => ecdh(recipient.privateKey, peer);
  assert.deepEqual(await hpkeOpen(sealed.enc, pub, dh, utf8("info"), utf8("aad"), sealed.ciphertext), utf8("content-key"));
  assert.equal(await hpkeOpen(sealed.enc, pub, dh, utf8("info"), utf8("aae"), sealed.ciphertext), null);
  assert.equal(await hpkeOpen(sealed.enc, pub, (peer) => ecdh(other.privateKey, peer), utf8("info"), utf8("aad"), sealed.ciphertext), null);
  const badEnc = Uint8Array.from(sealed.enc);
  badEnc[64]! ^= 1;
  assert.equal(await hpkeOpen(badEnc, pub, dh, utf8("info"), utf8("aad"), sealed.ciphertext), null);
});
