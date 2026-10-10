#!/usr/bin/env python3
"""生成 bi-manager 可校验的 JWT（HS256）。用法：python3 gen_token.py [username] [user_id]
密钥须与 application.yml 的 jwt.secret 一致。"""
import sys, json, hmac, hashlib, base64, time
SECRET = b"chatbi-jwt-secret-key-for-production-2026"
def b64(x): return base64.urlsafe_b64encode(x).rstrip(b"=")
u = sys.argv[1] if len(sys.argv) > 1 else "admin"; uid = int(sys.argv[2]) if len(sys.argv) > 2 else 1
h = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode()); now = int(time.time())
p = b64(json.dumps({"user_id": uid, "sub": u, "iat": now, "exp": now + 86400}).encode())
print((h + b"." + p + b"." + b64(hmac.new(SECRET, h + b"." + p, hashlib.sha256).digest())).decode())
