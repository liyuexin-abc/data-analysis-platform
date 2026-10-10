import asyncio
from playwright.async_api import async_playwright
TOKEN=open('/home/user/token').read().strip(); BASE='http://localhost:8600'
SHOT='/home/user/webapp/docs/prototype/screenshots/impl/'
async def main():
    async with async_playwright() as p:
        b=await p.chromium.launch(); ctx=await b.new_context(viewport={'width':1500,'height':900})
        await ctx.add_cookies([{'name':'Admin-Token','value':TOKEN,'url':BASE}])
        await ctx.add_init_script("localStorage.setItem('Admin-User', JSON.stringify({username:'admin',name:'admin'}))")
        page=await ctx.new_page(); errs=[]
        page.on('pageerror', lambda e: errs.append(str(e)))
        page.on('console', lambda m: errs.append(m.text) if m.type=='error' and 'favicon' not in m.text else None)
        await page.goto(BASE+'/#/'); await page.wait_for_timeout(3500)
        # ---- A. 应用端：注入一轮已完成问答，验证 👍👎 反馈条
        ok=await page.evaluate("""() => {
          const find=(vm)=>{ if(vm.$options.name==='queryPage') return vm; for(const c of vm.$children){const r=find(c); if(r) return r;} return null };
          const qp=find(document.querySelector('#app').__vue__); if(!qp) return 'no queryPage';
          qp.sessionId='S1'; qp.currentAgent=Object.assign({}, qp.currentAgent||{}, {code:'xs_agent', name:'线损智能体'});
          qp.turns.push({ id:'t-e2e', chatId:'C2', status:'done', user:{text:'查询深圳各电压等级的分压线损率'}, steps:[{ id:'s1', title:'step_1', status:'done', answer:'深圳分区线损率为 3.2%' , expand:false, data:[], charts:[], tables:[], sql:'', columns:[] }] });
          return 'ok';
        }""")
        print('inject', ok); await page.wait_for_timeout(1500)
        print('fb-bar count', await page.locator('.fb-bar').count())
        await page.screenshot(path=SHOT+'00a_feedback_bar.png')
        await page.locator('.fb-bar .fb-btn--down').first.click(); await page.wait_for_timeout(1000)
        chips=await page.locator('.fb-dialog:visible .fb-chip').count(); print('chips', chips)
        if not await page.locator('.fb-dialog:visible .fb-chip.el-tag--dark').count(): await page.locator('.fb-dialog:visible .fb-chip').nth(1).click()
        await page.locator('.fb-dialog:visible textarea').fill('E2E：应选分压表，电压等级未映射')
        await page.screenshot(path=SHOT+'00b_feedback_dialog.png')
        await page.locator('.fb-dialog:visible >> text=提交反馈').click(); await page.wait_for_timeout(2000)
        print('hint', await page.locator('.fb-bar .fb-hint').count())
        # ---- B. 管理端：手动追加 PROMPT 变更，打开提示词编辑器
        await page.click('text=管理端'); await page.wait_for_timeout(1200)
        await page.click('.el-menu-item:has-text("问答质量管理")'); await page.wait_for_timeout(2500)
        await page.click('.qm-tabs >> text=问答诊断'); await page.wait_for_timeout(1500)
        await page.click('.quality-manager .el-radio-group >> text=全部会话'); await page.wait_for_timeout(1500)
        # 按 chatId=C2 的行定位（主表取 index，操作列在 fixed-right）
        main_rows=page.locator('.quality-manager .el-table__body-wrapper .el-table__body tr')
        n=await main_rows.count(); idx=-1
        for i in range(n):
            t=await main_rows.nth(i).inner_text()
            if 'C2' in t or ('admin' in t and i==0 and n==1): idx=i
        # 行内看不到 chatId 时退化：取第一行（C2 为最新，排序 desc）
        if idx<0: idx=0
        print('diag rows', n, 'pick', idx)
        await page.locator('.quality-manager .el-table__fixed-right .el-table__body tr').nth(idx).locator('.el-button').first.click()
        await page.wait_for_timeout(2500)
        await page.locator('.dg-radio .el-radio:has-text("拆分错误")').click()
        await page.fill('textarea[placeholder*="月累计指标"]', 'E2E：拆解提示词未处理「分压」语义')
        await page.click('text=保存并生成调优建议'); await page.wait_for_timeout(2500)
        print('sugg', await page.locator('.el-dialog:visible .el-table__body tr').count())
        btn=page.locator('.el-dialog:visible .el-dialog__footer .el-button--primary').last
        await page.locator('.el-dialog:visible .el-table__body .el-checkbox').first.click(); await page.wait_for_timeout(300)
        await page.screenshot(path=SHOT+'04b_suggestions_prompt_rule.png')
        print('dialog primary:', await btn.inner_text(), 'checked', await page.locator('.el-dialog:visible .el-table__body .el-checkbox.is-checked').count()); await btn.click(); await page.wait_for_timeout(3000)
        print('task', await page.locator('.td-title').inner_text())
        # 追加手动变更 -> 切换资产为拆解提示词 -> 打开编辑器
        await page.click('text=打开提示词编辑器'); await page.wait_for_timeout(3000)
        await page.screenshot(path=SHOT+'14_prompt_editor.png')
        print('editor versions', await page.locator('.pe-head .el-select').count(), 'textarea len', len(await page.locator('.pe-ta').input_value()))
        # 修改内容 → diff → lint → 快速验证 → 保存草稿
        ta=page.locator('.pe-ta'); await ta.focus(); await page.keyboard.press('Control+End'); await page.keyboard.type('\n规则三 「分压」须映射到 voltage_level 维度')
        await page.wait_for_timeout(1500)
        print('diff hunks', await page.locator('.pe-diff .dl-add').count(), 'lint', await page.locator('.pe-ref >> text=校验').count())
        await page.click('text=快速验证（仅原问题）'); await page.wait_for_timeout(3000)
        print('quick result', await page.locator('.pe-ref >> text=快速验证结果').count())
        await page.fill('input[placeholder="版本说明（必填）"]', 'E2E 草稿')
        await page.screenshot(path=SHOT+'15_prompt_editor_diff.png')
        await page.click('text=保存为草稿版本'); await page.wait_for_timeout(3000)
        print('draft tag', await page.locator('.el-tag:has-text("草稿 v")').count())
        await page.click('text=保存变更'); await page.wait_for_timeout(2000)
        await page.screenshot(path=SHOT+'16_task_with_prompt_change.png')
        print('errors:', [e[:160] for e in errs][:8] or 'none')
        await b.close()
async def part_c():
    async with async_playwright() as p:
        b=await p.chromium.launch(); ctx=await b.new_context(viewport={'width':1500,'height':900})
        await ctx.add_cookies([{'name':'Admin-Token','value':TOKEN,'url':BASE}])
        await ctx.add_init_script("localStorage.setItem('Admin-User', JSON.stringify({username:'admin',name:'admin'}))")
        page=await ctx.new_page(); errs=[]
        page.on('pageerror', lambda e: errs.append(str(e)))
        page.on('console', lambda m: errs.append(m.text) if m.type=='error' and 'favicon' not in m.text else None)
        await page.goto(BASE+'/#/'); await page.wait_for_timeout(3000)
        await page.click('text=管理端'); await page.wait_for_timeout(1000)
        await page.click('.el-menu-item:has-text("问答质量管理")'); await page.wait_for_timeout(2500)
        await page.click('.qm-tabs >> text=调优任务'); await page.wait_for_timeout(1500)
        # 打开草稿任务 TN-002
        rows=page.locator('.quality-manager .el-table__body-wrapper .el-table__body tr'); n=await rows.count(); idx=0
        for i in range(n):
            if '002' in await rows.nth(i).inner_text(): idx=i
        fixed=page.locator('.quality-manager .el-table__fixed-right .el-table__body tr')
        target = fixed.nth(idx) if await fixed.count() else rows.nth(idx)
        await target.locator('.el-button').first.click(); await page.wait_for_timeout(2500)
        print('task', await page.locator('.td-title').inner_text())
        # 手动变更资产下拉可用
        await page.click('text=追加手动变更'); await page.wait_for_timeout(500)
        print('asset select', await page.locator('.st-asset-sel').count())
        await page.locator('.st-asset-sel').last.click(); await page.wait_for_timeout(400)
        opts=await page.locator('.el-select-dropdown:visible .el-select-dropdown__item').all_inner_texts(); print('asset options', len(opts), opts[:4])
        await page.keyboard.press('Escape'); await page.wait_for_timeout(300)
        # 删除刚追加的空行（最后一行的删除按钮）
        await page.locator('.td-root .el-table__body tr').last.locator('.el-icon-delete').click(); await page.wait_for_timeout(300)
        # 执行并验证
        await page.click('text=执行并验证'); await page.wait_for_timeout(800)
        await page.click('.el-message-box__btns >> text=确定'); await page.wait_for_timeout(8000)
        st=await page.locator('.td-head .el-tag').first.inner_text(); print('status after verify', st)
        await page.screenshot(path=SHOT+'17_prompt_task_verified.png')
        print('verify rows', await page.locator('.td-root >> text=验证报告').count(), 'applied', await page.locator('.el-tag:has-text("APPLIED")').count())
        print('errors:', [e[:160] for e in errs][:8] or 'none')
        await b.close()
import sys
asyncio.run(part_c() if len(sys.argv)>1 and sys.argv[1]=='c' else main())
