// Android（Kotlin）・Windows（C#）との相互運用。
// Kotlin の CSharpInteropTest が固定のテスト鍵で作った成果物（windows/Wave.Core.Tests/Fixtures/connect/kotlin.json）と、
// C# の InteropTests が同じ鍵で作った成果物（csharp.json）を、JS で検証・開く（Swift の InteropTests と同じ確かめ）。
// JS で作った成果物も、同じ形に書き出して同じ規則で検証する。

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { b64, fromUtf8, unb64, utf8 } from "../src/connect/bytes.ts";
import { addRecord, genesisRecord, recordHash, verifyChain, type VerifiedChain } from "../src/connect/chain.ts";
import { importPkcs8 } from "../src/connect/crypto.ts";
import { Device } from "../src/connect/device.ts";
import { docHash, openDoc, sealDoc } from "../src/connect/doc.ts";
import { decodeSnapshot, encodeSnapshot } from "../src/connect/handoff.ts";
import { serialize, type Json } from "../src/connect/json.ts";
import {
  accountTagOf,
  chainRecordToJson,
  commandToJson,
  cursorToJson,
  joinInfoToJson,
  memberKeysToJson,
  queueToJson,
  readChainRecord,
  readCommand,
  readCursor,
  readJoinInfo,
  readMemberKeys,
  readProfileBody,
  readQueue,
  readSealedDoc,
  sealedDocToJson,
  type ChainRecord,
  type CursorBody,
  type HandoffSnapshot,
  type JoinInfo,
  type LoginGrantCommand,
} from "../src/connect/models.ts";
import { confirmToJson, decodeConfirm, decodeHello, decodeOffer, encodeOffer, helloToJson, Initiator, Responder } from "../src/connect/pairing.ts";
import { decodeRelay, encodeRelay, type RelayMessage } from "../src/connect/relay-messages.ts";

const HANDOFF_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const fixtureDir = fileURLToPath(new URL("../../windows/Wave.Core.Tests/Fixtures/connect/", import.meta.url));

type F = { [key: string]: any };

function load(name: string): F {
  return JSON.parse(readFileSync(fixtureDir + name, "utf8"));
}

const kotlin = load("kotlin.json");
const devices = new Map<string, Device>();

async function device(name: string): Promise<Device> {
  let d = devices.get(name);
  if (!d) {
    const keys = kotlin["keys"][name];
    d = await Device.fromPkcs8(unb64(keys.identity)!, unb64(keys.kx)!);
    devices.set(name, d);
  }
  return d;
}

async function ephemeral(pkcs8: string): Promise<CryptoKeyPair> {
  return importPkcs8(unb64(pkcs8)!, "ecdh");
}

async function verified(records: ChainRecord[]): Promise<VerifiedChain> {
  const result = await verifyChain(records);
  assert.equal(result.kind, "ok");
  return (result as { chain: VerifiedChain }).chain;
}

/** Kotlin の CSharpInteropTest.sampleLoginGrant と同じ値（合成値）。 */
function sampleLoginGrant(from: string, to: string): LoginGrantCommand {
  return {
    type: "login_grant",
    commandId: "grant-0000000000000001",
    from,
    to,
    cookie: "SAPISID=synthetic-sapisid; HSID=synthetic",
    visitorData: "synthetic-visitor",
    dataSyncId: "synthetic-sync||x",
    obtainedAtEpochMs: 1_700_000_000_000,
  };
}

async function checkChain(f: F): Promise<void> {
  const nodes: Json[] = f["chain"];
  const records = nodes.map(readChainRecord);
  const chain = await verified(records);
  assert.equal(chain.householdId, f["householdId"]);
  assert.deepEqual(await Promise.all(records.map(recordHash)), f["chainHashes"]);
  assert.deepEqual(chain.memberIds, [(await device("a")).deviceId, (await device("b")).deviceId, (await device("pc")).deviceId]);
  assert.equal(chain.members.get((await device("pc")).deviceId)?.keys.role, "controller");
  // JSON の形も同じ（読んで書き直すと元と同じ文字列）
  for (const node of nodes) assert.equal(serialize(chainRecordToJson(readChainRecord(node))), serialize(node));
  const tag = f["accountTag"];
  assert.equal(await accountTagOf(tag.householdId, tag.accountId), tag.tag);
}

async function checkDocs(f: F): Promise<void> {
  const chain = await verified((f["chain"] as Json[]).map(readChainRecord));
  const names = ["a", "b", "pc"];
  const docs: F[] = f["docs"];
  assert.deepEqual(
    docs.map((d) => d["name"]),
    ["queue", "cursor", "command", "profile", "library", "login_grant"],
  );
  for (const entry of docs) {
    const doc = readSealedDoc(entry["doc"]);
    assert.equal(await docHash(doc), entry["hash"]);
    assert.equal(serialize(sealedDocToJson(doc)), serialize(entry["doc"]));
    const plaintext: string = entry["plaintext"];
    // 宛先を絞った doc（ログインを渡す命令）は openers の端末だけが開ける
    const openers: string[] = entry["openers"] ?? names;
    for (const name of names) {
      const opened = await openDoc(doc, chain, await device(name));
      if (!openers.includes(name)) {
        assert.notEqual(opened.kind, "ok", `${entry["name"]} を宛先でない ${name} が開けた`);
        continue;
      }
      assert.equal(opened.kind, "ok", `${entry["name"]} を ${name} が開けない`);
      if (opened.kind === "ok") assert.equal(fromUtf8(opened.plaintext), plaintext);
    }
    switch (entry["name"]) {
      case "queue": {
        const queue = readQueue(JSON.parse(plaintext));
        assert.equal(serialize(queueToJson(queue)), plaintext);
        assert.equal(decodeSnapshot(queue.snapshot, HANDOFF_ID).kind, "ok");
        break;
      }
      case "cursor":
        assert.equal(serialize(cursorToJson(readCursor(JSON.parse(plaintext)))), plaintext);
        break;
      case "command": {
        const command = readCommand(JSON.parse(plaintext));
        assert.equal(command.type, "yield");
        assert.equal(serialize(commandToJson(command)), plaintext);
        break;
      }
      case "profile":
        assert.equal(readProfileBody(JSON.parse(plaintext)).name, "Windows PC");
        break;
      case "login_grant": {
        const grant = readCommand(JSON.parse(plaintext));
        assert.deepEqual(grant, sampleLoginGrant((await device("a")).deviceId, (await device("b")).deviceId));
        assert.deepEqual(
          doc.recipients.map((r) => r.deviceId),
          [(await device("b")).deviceId],
        );
        assert.equal(serialize(commandToJson(grant)), plaintext);
        break;
      }
      case "library":
        // ライブラリ（ADR-0013）はこの拡張では扱わない。開けて、平文が同じことだけ確かめる（上で済み）
        break;
      default:
        assert.fail("知らない doc");
    }
  }
}

function checkRelay(f: F): void {
  const texts: string[] = f["relay"];
  assert.ok(texts.length > 30);
  for (const text of texts) {
    const message = decodeRelay(text);
    assert.ok(message, text);
    assert.equal(encodeRelay(message!), text);
  }
}

function checkHandoff(f: F): void {
  const text: string = f["handoff"]["text"];
  const decoded = decodeSnapshot(text, HANDOFF_ID);
  assert.equal(decoded.kind, "ok");
  if (decoded.kind === "ok") assert.equal(encodeSnapshot(decoded.snapshot), text);
}

async function checkPairing(f: F, responderName: string): Promise<void> {
  const p = f["pairing"];
  const offer = (await decodeOffer(p.offer))!;
  assert.ok(offer);
  const hello = decodeHello(p.hello)!;
  const confirm = decodeConfirm(p.confirm)!;
  const code: string = p.code;

  // JS の Responder（相手の A と同じ使い捨ての鍵・token）が相手の Hello を受ける
  const responder = await Responder.create({
    identity: await device("a"),
    deviceName: responderName,
    accountId: p.accountId,
    nowMs: () => 0,
    confirmExtension: p.confirmExtension,
    ephemeral: await ephemeral(p.responderEphemeral),
    token: unb64(offer.token)!,
  });
  assert.deepEqual(responder.offer, offer);
  const onHello = await responder.onHello(hello);
  assert.ok(onHello.ok, JSON.stringify(onHello));
  assert.equal((responder.onCodeEntered(code === "000000" ? "111111" : "000000") as { failure: string }).failure, "wrong_code");
  const onA = responder.onCodeEntered(code);
  assert.ok(onA.ok);
  if (!onA.ok) return;
  const pairOnA = onA.value;
  assert.equal(b64(pairOnA.sendKey), p.aSendKey);
  if (p.aReceiveKey) assert.equal(b64(pairOnA.receiveKey), p.aReceiveKey);
  if (p.accountKey) assert.equal(b64(pairOnA.accountKey), p.accountKey);
  assert.equal(pairOnA.peerExtension, p.helloExtension);
  assert.equal(pairOnA.peerDeviceName, "Windows test");
  assert.equal(pairOnA.peerAccountless, false);
  assert.equal(readMemberKeys(JSON.parse(pairOnA.peerExtension!)).deviceId, (await device("b")).deviceId);

  // ログインをまだ持たない B（wave-api、ADR-0015）の Hello: A は印で照合して受け、ログインなしの相手として印を付ける
  const accountless = p.accountless;
  if (accountless) {
    const offer2 = (await decodeOffer(accountless.offer))!;
    const responder2 = await Responder.create({
      identity: await device("a"),
      deviceName: responderName,
      accountId: p.accountId,
      nowMs: () => 0,
      confirmExtension: p.confirmExtension,
      ephemeral: await ephemeral(accountless.responderEphemeral),
      token: unb64(offer2.token)!,
    });
    assert.deepEqual(responder2.offer, offer2);
    assert.ok((await responder2.onHello(decodeHello(accountless.hello)!)).ok);
    const pair = responder2.onCodeEntered(accountless.code);
    assert.ok(pair.ok && pair.value.peerAccountless);
    if (pair.ok) assert.deepEqual(pair.value.peerPublicKey, (await device("b")).identityPub);
    // ログインの照合を受けない A は断る
    const strict = await Responder.create({
      identity: await device("a"),
      deviceName: responderName,
      accountId: p.accountId,
      nowMs: () => 0,
      confirmExtension: p.confirmExtension,
      ephemeral: await ephemeral(accountless.responderEphemeral),
      token: unb64(offer2.token)!,
      acceptAccountless: false,
    });
    const refused = await strict.onHello(decodeHello(accountless.hello)!);
    assert.ok(!refused.ok && refused.failure === "account_mismatch");
  }

  // JS の Initiator（相手の B と同じ使い捨ての鍵）が相手の Confirm を受け、同じ6桁を出す
  const initiator = await Initiator.create({
    offer,
    identity: await device("b"),
    deviceName: p.initiatorName,
    accountId: p.accountId,
    helloExtension: p.helloExtension,
    ephemeral: await ephemeral(p.initiatorEphemeral),
  });
  const confirmed = await initiator.onConfirm(confirm);
  assert.ok(confirmed.ok);
  if (!confirmed.ok) return;
  assert.equal(confirmed.value.code, code);
  assert.deepEqual(confirmed.value.pair.receiveKey, pairOnA.sendKey);
  assert.deepEqual(confirmed.value.pair.sendKey, pairOnA.receiveKey);
  assert.equal(confirmed.value.pair.peerDeviceName, responderName);
  const joinInfo = readJoinInfo(JSON.parse(confirmed.value.pair.peerExtension!));
  assert.equal(joinInfo.householdId, f["householdId"]);
  assert.equal(serialize(joinInfoToJson(joinInfo)), confirmed.value.pair.peerExtension);
}

async function checkArtifacts(f: F, responderName: string): Promise<void> {
  await checkChain(f);
  await checkDocs(f);
  checkRelay(f);
  checkHandoff(f);
  await checkPairing(f, responderName);
}

test("端末 ID と鍵の記述が Kotlin と同じ", async () => {
  const a = await device("a");
  assert.equal(a.deviceId, kotlin["deviceIds"]["a"]);
  assert.equal((await device("pc")).deviceId, kotlin["deviceIds"]["pc"]);
  const keys = readMemberKeys(kotlin["memberKeys"]["a"]);
  assert.equal(b64(a.identityPub), keys.identityPub);
  assert.equal(b64(a.kxPub.sec1), keys.kxPub);
});

test("Kotlin の鎖を検証する", async () => {
  await checkChain(kotlin);
});

test("Kotlin の doc を開く", async () => {
  await checkDocs(kotlin);
  const cursorEntry = (kotlin["docs"] as F[]).find((d) => d["name"] === "cursor")!;
  const cursor = readCursor(JSON.parse(cursorEntry["plaintext"]));
  assert.equal(cursor.repeat, "one");
  assert.equal(cursor.sessionStartVersion, null);
});

test("中継のメッセージが同じ文字列に往復する", () => {
  checkRelay(kotlin);
  assert.deepEqual(decodeRelay('{"op":"state_get"}'), { op: "state_get" });
  // 判別子が先頭でなくても、知らない項目があっても読む。知らない op は null
  assert.deepEqual(decodeRelay('{"fromSeq":3,"x":1,"op":"chain_get"}'), { op: "chain_get", fromSeq: 3 });
  assert.equal(decodeRelay('{"op":"nope"}'), null);
  assert.equal(decodeRelay('{"op":"chain_ok","headSeq":1}'), null);
  const active = decodeRelay('{"op":"active_changed","claimOrder":2}') as Extract<RelayMessage, { op: "active_changed" }>;
  assert.equal(active.activeDeviceId, null);
});

test("キューのスナップショットが Kotlin と同じ", () => {
  checkHandoff(kotlin);
  const decoded = decodeSnapshot(kotlin["handoff"]["text"], HANDOFF_ID);
  assert.equal(decoded.kind, "ok");
  if (decoded.kind !== "ok") return;
  assert.equal(decoded.snapshot.items[0]!.title, '夜に駆ける "quoted" 🎵');
  assert.deepEqual(decoded.snapshot.source, { type: "radio", seedVideoId: "abcdefghijA" });
  assert.equal(decoded.snapshot.items[1]!.origin, "user");
});

test("Kotlin と両側でペアになれる（ログインなしの加入の見本を含む）", async () => {
  await checkPairing(kotlin, kotlin["pairing"]["responderName"]);
  // JS の Initiator の送る鍵も Kotlin の B と同じ
  const p = kotlin["pairing"];
  const initiator = await Initiator.create({
    offer: (await decodeOffer(p.offer))!,
    identity: await device("b"),
    deviceName: p.initiatorName,
    accountId: p.accountId,
    helloExtension: p.helloExtension,
    ephemeral: await ephemeral(p.initiatorEphemeral),
  });
  const confirmed = await initiator.onConfirm(decodeConfirm(p.confirm)!);
  assert.ok(confirmed.ok);
  if (confirmed.ok) {
    assert.equal(b64(confirmed.value.pair.sendKey), p.bSendKey);
    assert.equal(confirmed.value.pair.peerDeviceName, "Pixel 3");
  }
});

test("ログインを渡す命令は宛先の端末にだけ包まれ、この拡張では値を読まない形として扱える", async () => {
  const kotlinText =
    '{"type":"login_grant","commandId":"c1","from":"A","to":"P","cookie":"SAPISID=synthetic","visitorData":null,"dataSyncId":"sync","obtainedAtEpochMs":5}';
  const grant = readCommand(JSON.parse(kotlinText));
  assert.equal(grant.type, "login_grant");
  assert.equal(serialize(commandToJson(grant)), kotlinText);
  const chain = await verified((kotlin["chain"] as Json[]).map(readChainRecord));
  const a = await device("a");
  const b = await device("b");
  const pc = await device("pc");
  const doc = await sealDoc({
    signer: a,
    chain,
    kind: "command",
    version: 1,
    prevHash: null,
    issuedAtEpochMs: 1,
    plaintext: utf8(kotlinText),
    only: new Set([pc.deviceId]),
  });
  assert.deepEqual(
    doc.recipients.map((r) => r.deviceId),
    [pc.deviceId],
  );
  const opened = await openDoc(doc, chain, pc);
  assert.equal(opened.kind, "ok");
  assert.notEqual((await openDoc(doc, chain, b)).kind, "ok");
  await assert.rejects(
    sealDoc({ signer: a, chain, kind: "command", version: 1, prevHash: null, issuedAtEpochMs: 1, plaintext: new Uint8Array(0), only: new Set(["nobody"]) }),
  );
});

test("C# の成果物（Kotlin のテスト鍵で作ったもの）を同じ規則で検証して開く", async () => {
  await checkArtifacts(load("csharp.json"), "Pixel 3");
});

/** Kotlin の CSharpInteropTest の sampleSnapshot と同じもの。 */
function sampleSnapshot(): HandoffSnapshot {
  return {
    schemaVersion: 1,
    handoffId: HANDOFF_ID,
    items: [
      {
        itemId: "00000000-0000-4000-8000-000000000001",
        videoId: "abcdefghijA",
        title: '夜に駆ける "quoted" 🎵',
        artists: [{ name: "YOASOBI", browseId: "UCsynthetic000000000000000" }],
        album: { name: "THE BOOK", browseId: null },
        durationSeconds: 261,
        thumbnailUrl: "https://lh3.googleusercontent.com/x=w120-h120",
        origin: "source",
        isLive: false,
      },
      {
        itemId: "00000000-0000-4000-8000-000000000002",
        videoId: "abcdefghijB",
        title: "second",
        artists: [],
        album: null,
        durationSeconds: null,
        thumbnailUrl: null,
        origin: "user",
        isLive: false,
      },
    ],
    currentItemId: "00000000-0000-4000-8000-000000000001",
    positionMs: 42_000,
    playing: true,
    repeat: "all",
    source: { type: "radio", seedVideoId: "abcdefghijA" },
    continuation: null,
    radioPlaylistId: "RDsynthetic",
    exhausted: false,
    listenedMs: 30_000,
    historySent: true,
    capturedAtEpochMs: 1_700_000_000_000,
  };
}

/** JS で作る成果物（Swift の swiftArtifacts・C# の WriteCSharpFixtures と同じもの）。 */
async function jsArtifacts(): Promise<F> {
  const a = await device("a");
  const b = await device("b");
  const pc = await device("pc");
  const genesis = await genesisRecord(a, await a.memberKeys("player"));
  const addB = await addRecord(a, await verified([genesis]), await b.memberKeys("player"));
  const addPc = await addRecord(b, await verified([genesis, addB]), await pc.memberKeys("controller"));
  const records = [genesis, addB, addPc];
  const chain = await verified(records);
  const tag = await accountTagOf(chain.householdId, "account-1");

  const snapshotText = encodeSnapshot(sampleSnapshot());
  const queueBody = { accountTag: tag, snapshot: snapshotText };
  const queueDoc = await sealDoc({
    signer: a,
    chain,
    kind: "queue",
    version: 1,
    prevHash: null,
    issuedAtEpochMs: 1_700_000_000_001,
    plaintext: utf8(serialize(queueToJson(queueBody))),
  });
  const cursorBody: CursorBody = {
    queueRef: await docHash(queueDoc),
    currentItemId: "00000000-0000-4000-8000-000000000001",
    positionMs: 42_000,
    playing: true,
    repeat: "one",
    sessionBase: 3,
    sessionStartVersion: 4,
    deviceSeq: 7,
    listenedMs: 30_000,
    historySent: false,
    accountTag: tag,
    startedAtEpochMs: 1_700_000_000_002,
    volumePercent: 40,
    shuffle: false,
  };
  const cursorDoc = await sealDoc({
    signer: a,
    chain,
    kind: "cursor",
    version: 4,
    prevHash: "prev-hash",
    issuedAtEpochMs: 1_700_000_000_003,
    plaintext: utf8(serialize(cursorToJson(cursorBody))),
  });
  const command = { type: "yield" as const, claimId: "claim-0000000000000001", from: b.deviceId, to: a.deviceId, sessionBase: 4, accountTag: tag };
  const commandDoc = await sealDoc({
    signer: b,
    chain,
    kind: "command",
    version: 1,
    prevHash: null,
    issuedAtEpochMs: 1_700_000_000_004,
    plaintext: utf8(serialize(commandToJson(command))),
  });
  const profileText = serialize({ name: "Windows PC" });
  const profileDoc = await sealDoc({ signer: pc, chain, kind: "profile", version: 1, prevHash: null, issuedAtEpochMs: 1_700_000_000_005, plaintext: utf8(profileText) });
  // ライブラリは Kotlin の見本の平文をそのまま封じる（この拡張はライブラリの中身を扱わない）
  const libraryText: string = (kotlin["docs"] as F[]).find((d) => d["name"] === "library")!["plaintext"];
  const libraryDoc = await sealDoc({ signer: pc, chain, kind: "library", version: 1, prevHash: null, issuedAtEpochMs: 1_700_000_000_006, plaintext: utf8(libraryText) });
  const grant = sampleLoginGrant(a.deviceId, b.deviceId);
  const grantDoc = await sealDoc({
    signer: a,
    chain,
    kind: "command",
    version: 1,
    prevHash: null,
    issuedAtEpochMs: 1_700_000_000_007,
    plaintext: utf8(serialize(commandToJson(grant))),
    only: new Set([b.deviceId]),
  });

  // ペアリング（どちらも JS）。使い捨ての鍵は書き出せる形で作り、確かめる側が同じ鍵で相手の側を再現する
  const joinInfo: JoinInfo = { householdId: chain.householdId, headSeq: chain.headSeq, headHash: chain.headHash, storeId: "store-1", cursorFloor: 4, queueFloor: 1 };
  const joinInfoJson = serialize(joinInfoToJson(joinInfo));
  const memberKeysJson = serialize(memberKeysToJson(await b.memberKeys("player")));
  const algorithm = { name: "ECDH", namedCurve: "P-256" };
  const eA = (await crypto.subtle.generateKey(algorithm, true, ["deriveBits"])) as CryptoKeyPair;
  const eB = (await crypto.subtle.generateKey(algorithm, true, ["deriveBits"])) as CryptoKeyPair;
  const responder = await Responder.create({
    identity: a,
    deviceName: "Pixel 3",
    accountId: "account-1",
    nowMs: () => 0,
    confirmExtension: joinInfoJson,
    ephemeral: eA,
  });
  const offerText = encodeOffer(responder.offer);
  const initiator = await Initiator.create({
    offer: (await decodeOffer(offerText))!,
    identity: b,
    deviceName: "Windows ‮test",
    accountId: "account-1",
    helloExtension: memberKeysJson,
    ephemeral: eB,
  });
  const hello = await initiator.hello();
  const confirm = await responder.onHello(hello);
  assert.ok(confirm.ok);
  if (!confirm.ok) throw new Error("confirm");
  const confirmed = await initiator.onConfirm(confirm.value);
  assert.ok(confirmed.ok);
  if (!confirmed.ok) throw new Error("confirmed");
  const pairOnA = responder.onCodeEntered(confirmed.value.code);
  assert.ok(pairOnA.ok);
  if (!pairOnA.ok) throw new Error("pair");

  const stored = { kind: "cursor" as const, version: 4, doc: cursorDoc, storeId: "store-1", receivedAtServerMs: 1, serverNowMs: 2, activeDeviceId: a.deviceId };
  const tokenHash = b64(responder.tokenHash);
  const libraryState = { version: 1, doc: libraryDoc, storeId: "store-1" };
  const relay: RelayMessage[] = [
    { op: "challenge", host: "relay.example", nonce: "bm9uY2U" },
    { op: "auth", publicKey: b64(a.identityPub), signature: "c2ln" },
    { op: "welcome", deviceId: a.deviceId, storeId: "store-1", householdId: null, headSeq: null, headHash: null, activeDeviceId: null, online: [], states: [], claimOrders: [] },
    {
      op: "welcome",
      deviceId: a.deviceId,
      storeId: "store-1",
      householdId: chain.householdId,
      headSeq: chain.headSeq,
      headHash: chain.headHash,
      activeDeviceId: a.deviceId,
      online: [a.deviceId],
      states: [stored],
      claimOrders: [[a.deviceId, 3]],
    },
    { op: "household_create", genesis },
    { op: "chain_get", fromSeq: 0 },
    { op: "chain", records },
    { op: "chain_append", record: addPc },
    { op: "chain_ok", headSeq: 2, headHash: chain.headHash },
    { op: "state_get" },
    { op: "state_put", requestId: "req-1", kind: "queue", expectVersion: 0, doc: queueDoc },
    { op: "state_ok", requestId: "req-1", kind: "queue", version: 1 },
    { op: "state_conflict", requestId: "req-2", kind: "cursor", latest: null },
    { op: "state_conflict", requestId: "req-2", kind: "cursor", latest: stored },
    { op: "state", state: stored },
    { op: "claim", claimId: "claim-0000000000000001", yieldDoc: commandDoc },
    { op: "transfer", claimId: "claim-0000000000000001", basedOnVersion: 4, yieldDoc: commandDoc },
    { op: "yield", claimId: "claim-0000000000000001", yieldDoc: commandDoc },
    { op: "yielded", claimId: "claim-0000000000000001", expectVersion: 4, doc: cursorDoc },
    { op: "release", claimId: "claim-0000000000000001" },
    { op: "active_changed", activeDeviceId: null, claimId: null, claimOrder: 5, pendingOwner: null },
    { op: "active_changed", activeDeviceId: a.deviceId, claimId: "c", claimOrder: 6, pendingOwner: b.deviceId },
    { op: "pending_expired", claimId: "c" },
    { op: "presence", online: [a.deviceId, b.deviceId] },
    { op: "pairing_open", tokenHash },
    { op: "pair_hello_in", token: responder.offer.token, hello: helloToJson(hello) },
    { op: "pair_hello", tokenHash, hello: helloToJson(hello) },
    { op: "pair_confirm", tokenHash, confirm: confirmToJson(confirm.value) },
    { op: "pair_confirmed", confirm: confirmToJson(confirm.value) },
    { op: "error", code: "not_active", requestId: null },
    { op: "error", code: "stale_head", requestId: "req-1" },
    { op: "library_get" },
    { op: "library_put", requestId: "req-3", expectVersion: 0, doc: libraryDoc },
    { op: "library_ok", requestId: "req-3", version: 1 },
    { op: "library_conflict", requestId: "req-4", latest: null },
    { op: "library_conflict", requestId: "req-4", latest: libraryState },
    { op: "library", state: null },
    { op: "library", state: libraryState },
  ];
  const exportPkcs8 = async (k: CryptoKeyPair) => b64(new Uint8Array(await crypto.subtle.exportKey("pkcs8", k.privateKey)));
  const docEntry = async (name: string, doc: typeof queueDoc, plaintext: string, openers?: string[]) => ({
    name,
    doc: sealedDocToJson(doc),
    plaintext,
    hash: await docHash(doc),
    ...(openers ? { openers } : {}),
  });
  return {
    chain: records.map(chainRecordToJson),
    chainHashes: await Promise.all(records.map(recordHash)),
    householdId: chain.householdId,
    memberKeysJson,
    accountTag: { householdId: chain.householdId, accountId: "account-1", tag },
    docs: [
      await docEntry("queue", queueDoc, serialize(queueToJson(queueBody))),
      await docEntry("cursor", cursorDoc, serialize(cursorToJson(cursorBody))),
      await docEntry("command", commandDoc, serialize(commandToJson(command))),
      await docEntry("profile", profileDoc, profileText),
      await docEntry("library", libraryDoc, libraryText),
      await docEntry("login_grant", grantDoc, serialize(commandToJson(grant)), ["b"]),
    ],
    handoff: { handoffId: HANDOFF_ID, text: snapshotText },
    relay: relay.map(encodeRelay),
    pairing: {
      offer: offerText,
      responderEphemeral: await exportPkcs8(eA),
      initiatorEphemeral: await exportPkcs8(eB),
      accountId: "account-1",
      initiatorName: "Windows ‮test",
      hello: helloToJson(hello),
      confirm: confirmToJson(confirm.value),
      code: confirmed.value.code,
      helloExtension: memberKeysJson,
      confirmExtension: joinInfoJson,
      aSendKey: b64(pairOnA.value.sendKey),
    },
  };
}

test("JS で作った成果物が、Kotlin・C# の成果物と同じ規則の検証を通る", async () => {
  const artifacts = await jsArtifacts();
  // 文字列を通して読み直す（書き出したものを他の端末が読むのと同じ）
  await checkArtifacts(JSON.parse(serialize(artifacts)), "Pixel 3");
});
