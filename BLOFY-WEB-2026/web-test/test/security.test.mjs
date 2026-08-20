import assert from "node:assert/strict";
import test from "node:test";
import { signResource, verifyResource } from "../lib/security.mjs";

test("signed media token encrypts credentials and verifies the original URL", () => {
  const source = "https://provider.example/live/private-user/private-password/123.m3u8";
  const token = signResource(source, 60);
  assert.equal(verifyResource(token.encoded, token.expires, token.signature), source);
  assert.equal(token.encoded.includes("private-user"), false);
  assert.equal(token.encoded.includes(Buffer.from(source).toString("base64url")), false);
  assert.equal(verifyResource(token.encoded, token.expires, `${token.signature}x`), null);
});
