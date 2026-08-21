import crypto from "node:crypto";
import { XtreamClient } from "./xtream.mjs";

export function providerSessionCacheKey(session) {
  const identity = session?.kind === "xtream"
    ? {
        kind: "xtream",
        serverUrl: String(session.serverUrl || ""),
        username: String(session.username || ""),
        password: String(session.password || ""),
      }
    : { kind: String(session?.kind || ""), url: String(session?.url || "") };
  return `session-refresh:${crypto.createHash("sha256").update(JSON.stringify(identity)).digest("hex")}`;
}

export async function refreshProviderSession(session, clientFactory = (value) => new XtreamClient(value)) {
  if (!session || session.kind !== "xtream") return session;
  const client = clientFactory(session);
  const state = await client.accountStatus();
  return {
    ...session,
    account: state.account,
    serverName: state.account.serverName || session.serverName || "",
  };
}

export function providerSessionResponseStatus(session) {
  return session?.account?.authenticated === false ? 402 : 200;
}
