import http.server, urllib.request, os, json, hmac, hashlib, base64, time
JWT_SECRET=b"chatbi-jwt-secret-key-for-production-2026"
SANDBOX_USERS={"admin":"admin123","yangbin":"yangbin123","tester":"tester123"}
def _b64(x): return base64.urlsafe_b64encode(x).rstrip(b"=")
def make_jwt(username, uid):
    h=_b64(json.dumps({"alg":"HS256","typ":"JWT"}).encode()); now=int(time.time())
    p=_b64(json.dumps({"user_id":uid,"sub":username,"iat":now,"exp":now+86400}).encode())
    sig=_b64(hmac.new(JWT_SECRET, h+b"."+p, hashlib.sha256).digest())
    return (h+b"."+p+b"."+sig).decode()
DIST=os.environ.get("FE_DIST","/home/user/fe_dist"); BACK="http://127.0.0.1:8591"; SYSB="http://127.0.0.1:5055"
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self,*a,**k): super().__init__(*a,directory=DIST,**k)
    def _proxy(self, target):
        body=self.rfile.read(int(self.headers.get('Content-Length') or 0)) if self.command in('POST','PUT','DELETE') else None
        req=urllib.request.Request(target+self.path, data=body, method=self.command)
        for k,v in self.headers.items():
            if k.lower() not in('host','content-length'): req.add_header(k,v)
        try: r=urllib.request.urlopen(req, timeout=120); code=r.status; data=r.read(); hdrs=r.headers
        except urllib.error.HTTPError as e: code=e.code; data=e.read(); hdrs=e.headers
        self.send_response(code)
        for k,v in hdrs.items():
            if k.lower() in('content-type',): self.send_header(k,v)
        self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path.startswith('/api/'): return self._proxy(BACK)
        if self.path.startswith('/webapp/'): self.path=self.path[7:]; return self._proxy(SYSB)
        return super().do_GET()
    def _sandbox_login(self):
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length') or 0)) or b'{}')
        u=(body.get('username') or '').strip()
        # 沙箱：密码为 RSA 密文无法在此解密，仅校验用户名存在；生产走 /upc 权限服务
        if u in SANDBOX_USERS:
            uid={"admin":1,"yangbin":2}.get(u,3)
            out={"code":1,"message":"success","data":{"token":make_jwt(u,uid),"user":{"id":uid,"username":u,"name":u,"tenantId":1}}}
        else:
            out={"code":0,"message":"沙箱可用账号：admin / yangbin / tester（任意密码）"}
        data=json.dumps(out,ensure_ascii=False).encode()
        self.send_response(200); self.send_header('Content-Type','application/json;charset=utf-8')
        self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        if self.path.startswith('/upc/user/login'): return self._sandbox_login()
        if self.path.startswith('/webapp/'): self.path=self.path[7:]; return self._proxy(SYSB)
        return self._proxy(BACK)
    do_PUT=do_POST; do_DELETE=do_POST
    def log_message(self,*a): pass
http.server.ThreadingHTTPServer(('0.0.0.0',8600),H).serve_forever()
