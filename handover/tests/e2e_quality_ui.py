import asyncio, json, sys
from playwright.async_api import async_playwright
import sys
BASE=sys.argv[1] if len(sys.argv)>1 else 'http://localhost:8600'
SHOT='/home/user/webapp/docs/prototype/screenshots/impl/'
async def main():
    async with async_playwright() as p:
        b=await p.chromium.launch(); ctx=await b.new_context(viewport={'width':1500,'height':900})
        page=await ctx.new_page(); errs=[]
        page.on('pageerror', lambda e: errs.append(str(e)))
        page.on('console', lambda m: errs.append(m.text) if m.type=='error' and 'favicon' not in m.text else None)
        # 真实登录页登录（沙箱 /upc/user/login 由静态代理模拟）
        await page.goto(BASE+'/#/login'); await page.wait_for_timeout(2500)
        await page.fill('input[type="text"]', 'admin'); await page.fill('input[type="password"]', 'admin123'); await page.keyboard.press('Enter')
        await page.wait_for_timeout(4000)
        await page.click('text=管理端'); await page.wait_for_timeout(1500)
        await page.click('.el-menu-item:has-text("问答质量管理")'); await page.wait_for_timeout(3000)
        await page.screenshot(path=SHOT+'01_feedback_list.png')
        rows=await page.locator('.quality-manager .el-table__body tr').count(); print('feedback rows', rows)
        # diagnosis tab
        await page.click('.qm-tabs >> text=问答诊断'); await page.wait_for_timeout(1500)
        await page.screenshot(path=SHOT+'02_diagnosis_list.png')
        btn=page.locator('.quality-manager .el-table__fixed-right .el-table__body tr').first.locator('.el-button').first
        if not await btn.count(): btn=page.locator('.quality-manager .el-table__body tr').first.locator('.el-button').last
        print('row action:', await btn.inner_text()); await btn.click()
        await page.wait_for_timeout(2500)
        await page.screenshot(path=SHOT+'03_diagnosis_detail.png')
        print('stages', await page.locator('.qm-stage-item').count(), 'form radios', await page.locator('.dg-radio .el-radio').count())
        await page.click('text=System B 日志'); await page.wait_for_timeout(1200)
        print('log dialog', await page.locator('.dg-log').inner_text() != '')
        await page.keyboard.press('Escape'); await page.wait_for_timeout(500)
        # save & suggestions
        await page.click('text=保存并生成调优建议'); await page.wait_for_timeout(2500)
        await page.screenshot(path=SHOT+'04_suggestions.png')
        sug=await page.locator('.el-dialog:visible .el-table__body tr').count(); print('suggestions', sug)
        if not await page.locator('.el-dialog:visible .el-table__body .el-checkbox.is-checked').count():
            await page.locator('.el-dialog:visible .el-table__body .el-checkbox').first.click(); await page.wait_for_timeout(300)
        await page.click('.el-dialog:visible >> text=创建调优任务'); await page.wait_for_timeout(3000)
        await page.screenshot(path=SHOT+'05_task_detail_draft.png')
        print('task title', await page.locator('.td-title').inner_text())
        # execute & verify
        await page.click('text=执行并验证'); await page.wait_for_timeout(800)
        await page.click('.el-message-box__btns >> text=确定'); await page.wait_for_timeout(8000)
        await page.screenshot(path=SHOT+'06_task_verified.png')
        status=await page.locator('.td-head .el-tag').first.inner_text(); print('status after verify', status)
        # submit approval
        await page.click('text=提交超级管理员审批'); await page.wait_for_timeout(800)
        await page.locator('.el-dialog__wrapper:visible .el-dialog__footer .el-button--primary').click(); await page.wait_for_timeout(2500)
        print('status after submit', await page.locator('.td-head .el-tag').first.inner_text())
        await page.screenshot(path=SHOT+'07_task_pending_approval.png')
        # super admin approve inline
        await page.fill('.td-actions input[placeholder="审批意见"]', '同意发布')
        await page.click('.td-actions >> text=通过并发布'); await page.wait_for_timeout(4000)
        print('status after approve', await page.locator('.td-head .el-tag').first.inner_text())
        await page.screenshot(path=SHOT+'08_task_published.png')
        await page.click('.td-head >> text=关闭'); await page.wait_for_timeout(800)
        # tuning list, approval, regression, stats, settings tabs
        for tab,name in [('调优任务','09_tuning_list'),('审批中心','10_approval_center'),('回归集','11_regression'),('质量统计','12_stats'),('设置','13_settings')]:
            await page.click(f'.qm-tabs >> text={tab}'); await page.wait_for_timeout(1500); await page.screenshot(path=SHOT+name+'.png')
        # prompt editor: create a task with prompt change via API-less path: open tuning list first task w/ PROMPT? skip; open editor from a new draft
        print('errors:', [e[:160] for e in errs][:8] or 'none')
        await b.close()
asyncio.run(main())
