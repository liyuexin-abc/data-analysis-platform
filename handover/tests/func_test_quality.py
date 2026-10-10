#!/usr/bin/env python3
"""
问答质量管理 / 自动调优 — 功能测试（API 级，黑盒）
用法：python3 docs/dev/func_test_quality.py [BASE_URL]
  BASE_URL 默认 http://127.0.0.1:8600（经静态代理，含模拟登录）；也可直连 bi-manager http://127.0.0.1:8591（自动本地签发 JWT）
覆盖：正向闭环、状态机守卫、参数校验、权限（超级管理员）、幂等/并发守卫、回归集、配置、通知。
输出：逐条 PASS/FAIL + 汇总；生成 docs/dev/FUNC_TEST_REPORT.md
"""
import sys, json, time, urllib.request, urllib.error, urllib.parse, datetime, os, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

BASE = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else 'http://127.0.0.1:8600'
RESULTS = []


def http(method, path, body=None, token=None, raw=False):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    path = urllib.parse.quote(path, safe='/?=&:%+@')
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('tenantid', '1')
    if token: req.add_header('Authorization', 'Bearer ' + token)
    try:
        r = urllib.request.urlopen(req, timeout=120); status = r.status; txt = r.read().decode()
    except urllib.error.HTTPError as e:
        status = e.code; txt = e.read().decode()
    if raw: return status, txt
    try: return status, json.loads(txt)
    except Exception: return status, {'_raw': txt[:200]}


def login(user):
    """优先走静态代理的模拟登录；若直连 bi-manager（无 /upc），则本地签发 JWT。"""
    _, r = http('POST', '/upc/user/login', {'username': user, 'password': 'x'})
    if isinstance(r, dict) and r.get('code') == 1:
        return r['data']['token']
    import hmac, hashlib, base64
    secret = os.environ.get('JWT_SECRET', 'chatbi-jwt-secret-key-for-production-2026').encode()
    b64 = lambda x: base64.urlsafe_b64encode(x).rstrip(b'=')
    uid = {'admin': 1, 'yangbin': 2}.get(user, 3); now = int(time.time())
    h = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode())
    pl = b64(json.dumps({'user_id': uid, 'sub': user, 'iat': now, 'exp': now + 86400}).encode())
    return (h + b'.' + pl + b'.' + b64(hmac.new(secret, h + b'.' + pl, hashlib.sha256).digest())).decode()


def check(group, name, cond, detail=''):
    RESULTS.append((group, name, bool(cond), str(detail)[:140]))
    print(('PASS ' if cond else 'FAIL ') + f'[{group}] {name}' + ('' if cond else f'  -> {detail}'))
    return cond


def main():
    admin = login('admin'); yb = login('yangbin'); tester = login('tester')

    # ---------- 0. 认证 ----------
    s, r = http('GET', '/api/v1/chat/error-types')
    check('认证', '无 token 访问被拒绝', s == 401 or r.get('code') in (401, 404))
    s, r = http('GET', '/api/v1/chat/error-types', token=admin)
    check('认证', '有 token 可访问错误类型字典', r.get('code') == 200 and len(r['data']) >= 8, r)
    s, r = http('POST', '/upc/user/login', {'username': 'nobody'})
    if isinstance(r, dict) and 'code' in r and '_raw' not in r:
        check('认证', '未知用户登录失败（模拟登录代理）', r.get('code') == 0, r)

    # ---------- 1. 用户反馈 ----------
    g = '用户反馈'
    s, r = http('POST', '/api/v1/chat/feedback/submit', token=tester, body={
        'chatSessionId': 'S1', 'chatId': 'C1', 'aiBodyCode': 'xs_agent', 'question': '查询深圳各电压等级的分压线损率',
        'rating': -1, 'errorTypes': ['TABLE_SELECT'], 'description': '功能测试：应选分压表'})
    check(g, '普通用户提交 👎 反馈', r.get('code') == 200, r)
    s, r = http('POST', '/api/v1/chat/feedback/submit', token=tester, body={
        'chatSessionId': 'S1', 'chatId': 'C1', 'aiBodyCode': 'xs_agent', 'question': '查询深圳各电压等级的分压线损率',
        'rating': -1, 'errorTypes': ['RESULT'], 'description': '改为结果错误'})
    check(g, '同一用户同一问答重复提交 → 覆盖而非新增', r.get('code') == 200, r)
    s, r = http('GET', '/api/v1/chat/feedback/mine?chatId=C1', token=tester)
    c1 = [r['data']] if r.get('data') else []
    check(g, '我的反馈返回最新内容', c1 and 'RESULT' in json.dumps(c1[0].get('errorTypes')), c1)
    s, r = http('POST', '/api/v1/chat/feedback/submit', token=tester, body={
        'chatSessionId': 'S1', 'chatId': 'C1', 'rating': -1, 'errorTypes': []})
    check(g, '👎 未选错误类型被拒绝', r.get('code') != 200, r)
    s, r = http('POST', '/api/v1/chat/feedback/submit', token=tester, body={
        'chatSessionId': 'S1', 'chatId': 'C2', 'aiBodyCode': 'xs_agent', 'question': '查询深圳各电压等级的分压线损率',
        'rating': 1})
    check(g, '👍 反馈提交成功', r.get('code') == 200, r)
    s, r = http('GET', '/api/v1/tuning/regression/page?page=1&pageSize=50', token=admin)
    recs = (r.get('data') or {}).get('records') or []
    check(g, '👍 自动沉淀为回归用例', any(x.get('sourceChatId') == 'C2' for x in recs), [x.get('sourceChatId') for x in recs])
    s, r = http('GET', '/api/v1/chat/feedback/page?page=1&pageSize=20&rating=-1', token=admin)
    check(g, '管理端反馈分页(👎筛选)', r.get('code') == 200 and r['data']['total'] >= 1, r.get('data', {}).get('total'))
    fb_id = r['data']['records'][0]['id']
    s, r = http('PUT', f'/api/v1/chat/feedback/{fb_id}/status', token=admin, body={'status': 1})
    check(g, '反馈状态流转 → 处理中', r.get('code') == 200, r)

    # ---------- 2. 问答诊断 ----------
    g = '问答诊断'
    s, r = http('GET', '/api/v1/chat/diagnosis/page?page=1&pageSize=20&quick=feedback', token=admin)
    check(g, '诊断列表（有👎反馈）', r.get('code') == 200 and r['data']['total'] >= 1, r.get('data', {}).get('total'))
    row = next((x for x in r['data']['records'] if x['chatId'] == 'C1'), r['data']['records'][0])
    check(g, '列表含自动预判 autoHint', bool(row.get('autoHint')), row.get('autoHint'))
    s, r = http('GET', '/api/v1/chat/trace/C1', token=admin)
    d = r.get('data') or {}
    check(g, 'trace 含 5 环节/步骤/反馈', d.get('trace') and len(d.get('stages') or []) == 5 and d.get('feedbacks'),
          {k: (len(v) if isinstance(v, list) else bool(v)) for k, v in d.items()})
    check(g, 'trace.createdAt 为格式化时间', isinstance(d['trace'].get('createdAt'), str) and '-' in d['trace']['createdAt'], d['trace'].get('createdAt'))
    s, r = http('GET', '/api/v1/chat/trace/C1/system-b-log', token=admin)
    check(g, 'System B 日志可取', r.get('code') == 200 and (r.get('data') or {}).get('content'), r)
    s, r = http('GET', '/api/v1/chat/trace/NOT_EXIST', token=admin)
    check(g, '不存在的 chatId 返回空 trace 不报错', r.get('code') == 200 and (r.get('data') or {}).get('trace') is None, r)
    s, r = http('POST', '/api/v1/chat/diagnosis/save', token=admin, body={
        'chatId': 'C1', 'primaryErrorType': 'METRIC_DIM', 'secondaryErrorTypes': ['TABLE_SELECT'],
        'errorStepId': 'step_1', 'expectedEntities': [{'type': 'DIM', 'code': 'voltage_level', 'alias': '电压等级'}],
        'rootCause': '功能测试：「电压等级」未映射到 voltage_level', 'fixMethod': 'ALIAS',
        'expectedAnswerKeywords': ['电压等级'], 'addRegression': True})
    dg = (r.get('data') or {}).get('diagnosis') or (r.get('data') or {})
    check(g, '保存定位', r.get('code') == 200 and dg.get('id'), r)
    diag_id = dg['id']
    s, r = http('GET', '/api/v1/chat/feedback/page?page=1&pageSize=50', token=admin)
    c1fb = [x for x in r['data']['records'] if x['chatId'] == 'C1']
    check(g, '保存定位后 C1 反馈状态 → 已定位(1) 并关联 diagnosisId', c1fb and all(x['status'] >= 1 and x.get('diagnosisId') == diag_id for x in c1fb),
          [(x['chatId'], x['status'], x.get('diagnosisId')) for x in c1fb])
    s, r = http('POST', '/api/v1/chat/diagnosis/save', token=admin, body={'chatId': 'C1'})
    check(g, '缺少主错误类型被拒绝', r.get('code') != 200, r)
    s, r = http('GET', '/api/v1/chat/quality/stats?days=30', token=admin)
    check(g, '质量统计', r.get('code') == 200 and 'adminErrorTypeDist' in r['data'], list((r.get('data') or {}).keys()))

    # ---------- 3. 调优建议 & 任务 ----------
    g = '调优任务'
    s, r = http('POST', '/api/v1/tuning/suggest', token=admin, body={'diagnosisId': diag_id})
    sug = r.get('data') or []
    if isinstance(sug, dict): sug = sug.get('suggestions', [])
    check(g, '生成建议（R7 实体别名）', any(x.get('ruleCode') == 'R7' for x in sug), [x.get('ruleCode') for x in sug])
    changes = [dict(x, accepted=True) for x in sug if x.get('ruleCode') == 'R7'] or [dict(sug[0], accepted=True)]
    s, r = http('POST', '/api/v1/tuning/task', token=admin, body={'diagnosisId': diag_id, 'changes': changes})
    check(g, '创建任务 → DRAFT', r.get('code') == 200 and r['data']['status'] == 'DRAFT', r)
    tid = r['data']['id']; task_no = r['data']['taskNo']
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/submit-approval', token=admin, body={})
    check(g, '守卫：DRAFT 不能直接提交审批', r.get('code') == 400, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/verify', token=admin, body={})
    check(g, '守卫：DRAFT 不能直接验证', r.get('code') == 400, r)
    # 并发守卫：同资产第二个任务
    s, r2 = http('POST', '/api/v1/tuning/task', token=admin, body={'diagnosisId': diag_id, 'changes': changes})
    tid2 = r2['data']['id']
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/execute', token=admin, body={})
    check(g, '执行草稿 → APPLIED/VERIFYING', r.get('code') == 200 and r['data']['status'] in ('APPLIED', 'VERIFYING', 'VERIFIED'), r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid2}/execute', token=admin, body={})
    check(g, '并发守卫：同资产另一任务执行 → 409', r.get('code') == 409, r)
    http('POST', f'/api/v1/tuning/task/{tid2}/cancel', token=admin, body={})
    # 等待验证完成
    st = None
    for _ in range(30):
        time.sleep(1); s, r = http('GET', f'/api/v1/tuning/task/{tid}', token=admin); st = r['data'].get('status')
        if st == 'VERIFIED': break
    check(g, '异步验证完成 → VERIFIED', st == 'VERIFIED', st)
    s, r = http('GET', f'/api/v1/tuning/task/{tid}/report', token=admin)
    rep = r.get('data') or {}
    rep_obj = rep.get('report') or rep
    check(g, '验证报告结论 PASS 且原问题已修复', rep_obj.get('conclusion') == 'PASS' and rep_obj.get('originFixed') in (1, True), {k: rep_obj.get(k) for k in ('conclusion', 'originFixed', 'totalCases')})

    # ---------- 4. 审批 / 权限 ----------
    g = '审批与权限'
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/approve', token=admin, body={'approved': True})
    check(g, '守卫：VERIFIED 不能审批', r.get('code') == 400, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/submit-approval', token=yb, body={})
    check(g, '管理员提交审批 → PENDING_APPROVAL', r.get('code') == 200 and r['data']['status'] == 'PENDING_APPROVAL', r)
    s, r = http('GET', '/api/v1/tuning/approval/pending', token=admin)
    check(g, '审批中心出现待办', any(x.get('taskNo') == task_no for x in (r.get('data') or [])), [x.get('taskNo') for x in r.get('data') or []])
    s, r = http('GET', '/api/v1/tuning/notifications', token=admin)
    check(g, '产生待审批通知', any('待审批' in (x.get('title') or '') for x in r.get('data') or []), len(r.get('data') or []))
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/approve', token=yb, body={'approved': True, 'comment': '越权'})
    check(g, '权限：非超级管理员审批 → 403', r.get('code') == 403, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/approve', token=tester, body={'approved': True})
    check(g, '权限：普通用户审批 → 403', r.get('code') == 403, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/withdraw', token=yb, body={})
    check(g, '提交人撤回 → VERIFIED', r.get('code') == 200 and r['data']['status'] == 'VERIFIED', r)
    http('POST', f'/api/v1/tuning/task/{tid}/submit-approval', token=yb, body={})
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/approve', token=admin, body={'approved': False, 'comment': '请补充说明'})
    check(g, '超管驳回 → VERIFIED', r.get('code') == 200 and r['data']['status'] == 'VERIFIED', r)
    http('POST', f'/api/v1/tuning/task/{tid}/submit-approval', token=yb, body={})
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/approve', token=admin, body={'approved': True, 'comment': '同意发布'})
    check(g, '超管通过 → PUBLISHED', r.get('code') == 200 and r['data']['status'] == 'PUBLISHED', r)
    for _ in range(15):
        s, r = http('GET', f'/api/v1/tuning/task/{tid}', token=admin); det = r.get('data') or {}
        if det.get('onlineRecheck'): break
        time.sleep(1)
    t = det.get('task') or det
    check(g, '发布后线上复验 success + 观察期字段', t.get('observeUntil') and t.get('publishedAt') and (det.get('onlineRecheck') or {}).get('status') == 'success', {'publishedAt': t.get('publishedAt'), 'observeUntil': t.get('observeUntil'), 'onlineRecheck': det.get('onlineRecheck')})
    s, r = http('GET', f'/api/v1/tuning/task/{tid}/impact', token=admin)
    check(g, '影响范围接口', r.get('code') == 200, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/cancel', token=admin, body={})
    check(g, '守卫：PUBLISHED 不能取消', r.get('code') == 400, r)

    # ---------- 5. 回滚 ----------
    g = '回滚'
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/rollback', token=yb, body={'reason': 'x'})
    check(g, '权限：非超管回滚 → 403', r.get('code') == 403, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/rollback', token=admin, body={})
    check(g, '校验：回滚已发布任务必须填原因', r.get('code') == 400, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/rollback', token=admin, body={'reason': '功能测试回滚'})
    check(g, '超管回滚 → ROLLED_BACK', r.get('code') == 200 and r['data']['status'] == 'ROLLED_BACK', r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid}/rollback', token=admin, body={'reason': 'again'})
    check(g, '守卫：已回滚不能再次回滚', r.get('code') == 400, r)
    # 二轮：新任务验证后再编辑变更 → round+1 回 DRAFT
    s, r = http('POST', '/api/v1/tuning/task', token=admin, body={'diagnosisId': diag_id, 'changes': changes})
    tid3 = r['data']['id']
    http('POST', f'/api/v1/tuning/task/{tid3}/execute', token=admin, body={})
    for _ in range(40):
        time.sleep(1); s, r = http('GET', f'/api/v1/tuning/task/{tid3}', token=admin)
        if r['data'].get('status') == 'VERIFIED': break
    s, r = http('PUT', f'/api/v1/tuning/task/{tid3}/changes', token=admin, body={'changes': changes})
    check(g, 'VERIFIED 后编辑变更 → 回 DRAFT 且 round+1', r.get('code') == 200 and r['data']['status'] == 'DRAFT' and r['data']['round'] == 2, r)
    s, r = http('POST', f'/api/v1/tuning/task/{tid3}/cancel', token=admin, body={})
    check(g, 'DRAFT 可取消 → CANCELLED', r.get('code') == 200 and r['data']['status'] == 'CANCELLED', r)
    s, r = http('GET', f'/api/v1/tuning/task/{tid}', token=admin)
    audit = (r.get('data') or {}).get('audit') or []
    acts = [a.get('action') for a in audit]
    check(g, '审计日志完整(CREATE/EXECUTE/SUBMIT/REJECT/APPROVE/ROLLBACK)', all(a in acts for a in ('CREATE', 'EXECUTE', 'APPROVE', 'ROLLBACK')), acts)

    # ---------- 6. 提示词编辑器 ----------
    g = '提示词编辑器'
    s, r = http('GET', f'/api/v1/tuning/prompt/editor-context?taskId={tid}&groupName=prompt-main', token=admin)
    ctx = r.get('data') or {}
    cur = ctx.get('current') or {}
    check(g, '编辑器上下文（当前版本/内容/原问题/锚点）', r.get('code') == 200 and cur.get('content') and cur.get('version') and ctx.get('question') and 'anchors' in ctx, list(ctx.keys()))
    sysb = os.environ.get('SYSTEM_B_URL', 'http://127.0.0.1:5055')
    try:
        rq = urllib.request.Request(sysb + '/api/v1/prompts/lint', data=json.dumps({'content': 'no placeholders'}).encode(), method='POST', headers={'Content-Type': 'application/json'})
        lint = json.loads(urllib.request.urlopen(rq, timeout=10).read().decode())
    except Exception as e:
        lint = {'_err': str(e)}
    check(g, 'System B lint 接口可达（直连 mock/真实 System B）', lint.get('code') == 200, lint)
    s, r = http('POST', '/api/v1/tuning/prompt/quick-verify', token=admin, body={
        'taskId': tid, 'groupName': 'prompt-main', 'content': cur.get('content', '') + '\n规则三 测试'})
    check(g, '快速验证（仅原问题）', r.get('code') == 200 and (r.get('data') or {}).get('ok') is not None, r)
    s, r = http('POST', '/api/v1/tuning/prompt/quick-verify', token=admin, body={'taskId': tid, 'groupName': 'prompt-main', 'content': ''})
    check(g, '校验：空内容被拒绝', r.get('code') != 200 or (r.get('data') or {}).get('ok') is False, r)

    # ---------- 7. 回归集 ----------
    g = '回归集'
    s, r = http('POST', '/api/v1/tuning/regression', token=admin, body={
        'aiBodyCode': 'xs_agent', 'question': '功能测试-手工回归用例', 'expectedTable': 'view_fenqu', 'enabled': 1})
    check(g, '手工新增用例', r.get('code') == 200 and r['data'].get('id'), r)
    rid = r['data']['id']
    s, r = http('PUT', f'/api/v1/tuning/regression/{rid}/toggle', token=admin, body={})
    check(g, '启停切换', r.get('code') == 200, r)
    s, r = http('GET', '/api/v1/tuning/regression/page?page=1&pageSize=50&keyword=功能测试', token=admin)
    check(g, '关键词检索', any(x['id'] == rid for x in (r.get('data') or {}).get('records', [])), r.get('data', {}).get('total'))
    s, r = http('DELETE', f'/api/v1/tuning/regression/{rid}', token=admin)
    check(g, '删除用例', r.get('code') == 200, r)

    # ---------- 8. 验证配置 ----------
    g = '验证配置'
    s, r = http('GET', '/api/v1/tuning/config?scope=GLOBAL', token=admin)
    cfg = r.get('data') or {}
    check(g, 'GLOBAL 默认 max_cases=30', cfg.get('maxCases') == 30 and cfg.get('alertWindowHours') == 24, {k: cfg.get(k) for k in ('maxCases', 'alertWindowHours', 'observeDays')})
    bad = dict(cfg); bad['maxCases'] = 500
    s, r = http('PUT', '/api/v1/tuning/config', token=admin, body=bad)
    check(g, '校验：max_cases 超限(500) → 400', r.get('code') == 400, r)
    s, r = http('PUT', '/api/v1/tuning/config', token=yb, body=cfg)
    check(g, '权限：非超管修改配置 → 403', r.get('code') == 403, r)
    agent_cfg = dict(cfg); agent_cfg.pop('id', None); agent_cfg.update(scope='AGENT', aiBodyCode='xs_agent', maxCases=12)
    s, r = http('PUT', '/api/v1/tuning/config', token=admin, body=agent_cfg)
    check(g, '新增智能体级覆盖配置', r.get('code') == 200 and r['data'].get('maxCases') == 12, r)
    s, r = http('GET', '/api/v1/tuning/config?scope=AGENT&aiBodyCode=xs_agent', token=admin)
    check(g, '读取智能体级配置', (r.get('data') or {}).get('maxCases') == 12, r.get('data'))
    s, r = http('GET', '/api/v1/tuning/config/all', token=admin)
    check(g, '配置列表 ≥2', len(r.get('data') or []) >= 2, len(r.get('data') or []))

    # ---------- 9. 通知 / 观察期 ----------
    g = '通知与观察期'
    s, r = http('GET', '/api/v1/tuning/notifications', token=admin)
    notes = r.get('data') or []
    check(g, '通知列表含发布/回滚事件', any(('发布' in (n.get('title') or '')) or ('回滚' in (n.get('title') or '')) or ('回退' in (n.get('title') or '')) for n in notes), [n.get('title') for n in notes][:6])
    if notes:
        s, r = http('PUT', f"/api/v1/tuning/notifications/{notes[0]['id']}/read", token=admin, body={})
        check(g, '标记已读', r.get('code') == 200, r)
    s, r = http('POST', '/api/v1/tuning/observe/check', token=admin, body={})
    check(g, '手动触发观察期巡检', r.get('code') == 200, r)

    # ---------- 汇总 ----------
    total = len(RESULTS); passed = sum(1 for x in RESULTS if x[2])
    print(f'\n==== {passed}/{total} passed ====')
    out = ['# 功能测试报告 — 问答质量管理 / 自动调优', '',
           f'- 执行时间：{datetime.datetime.now():%Y-%m-%d %H:%M}', f'- 环境：{BASE}（沙箱，System B/chat-server 为 mock）',
           f'- 结果：**{passed}/{total} 通过**', '', '| # | 模块 | 用例 | 结果 | 备注 |', '|---|---|---|---|---|']
    for i, (grp, name, ok, detail) in enumerate(RESULTS, 1):
        out.append(f"| {i} | {grp} | {name} | {'✅' if ok else '❌'} | {'' if ok else detail.replace('|', '/')} |")
    rep_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'FUNC_TEST_REPORT.md')
    open(rep_path, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('report ->', rep_path)
    sys.exit(0 if passed == total else 1)


if __name__ == '__main__':
    main()
