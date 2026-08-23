from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
server_path = ROOT / "BLOFY-WEB-2026/web-test/server.mjs"

server = server_path.read_text()
server = server.replace('const APP_VERSION = "2026.08.23.6";', 'const APP_VERSION = "2026.08.23.8";')
server = server.replace('const pageSize = boundedInteger(query.get("page_size"), 60, 30, 500);', 'const pageSize = boundedInteger(query.get("page_size"), 60, 30, 2000);')

old_limiter = '''  const nativeRequest = url.pathname.startsWith("/api/native-");
  if (limited(req, nativeRequest ? 1200 : 120, 60_000, nativeRequest ? "native" : "api")) {
    return json(res, 429, { error: "طلبات كثيرة، حاول بعد دقيقة." }, securityHeaders());
  }'''
new_limiter = '''  const nativeRequest = url.pathname.startsWith("/api/native-");
  const syncRequest = req.method === "GET" && (url.pathname === "/api/catalog" || url.pathname === "/api/categories");
  const requestLimit = nativeRequest ? 1200 : syncRequest ? 1800 : 120;
  const rateNamespace = nativeRequest ? "native" : syncRequest ? "sync" : "api";
  if (limited(req, requestLimit, 60_000, rateNamespace)) {
    return json(res, 429, { error: "طلبات كثيرة، حاول بعد دقيقة." }, securityHeaders());
  }'''

if old_limiter in server:
    server = server.replace(old_limiter, new_limiter)
elif 'const syncRequest = req.method === "GET"' not in server:
    raise SystemExit("server limiter block not found")

if '30, 2000' not in server:
    raise SystemExit("catalog page-size patch was not applied")
if 'const syncRequest = req.method === "GET"' not in server:
    raise SystemExit("sync limiter patch was not applied")

server_path.write_text(server)
print("BLOFY server sync fix applied")
