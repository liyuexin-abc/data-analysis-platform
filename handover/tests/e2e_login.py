import asyncio, sys
from playwright.async_api import async_playwright
BASE=sys.argv[1] if len(sys.argv)>1 else 'http://localhost:8600'
async def main():
    async with async_playwright() as p:
        b=await p.chromium.launch(); ctx=await b.new_context(viewport={'width':1500,'height':900}); page=await ctx.new_page(); errs=[]
        page.on('pageerror', lambda e: errs.append(str(e)))
        await page.goto(BASE+'/#/login'); await page.wait_for_timeout(3000)
        print('url', page.url)
        await page.screenshot(path='/home/user/webapp/docs/prototype/screenshots/impl/18_login.png')
        inputs=page.locator('input'); print('inputs', await inputs.count())
        await page.fill('input[placeholder*="账号"], input[placeholder*="用户名"], input[type="text"]', 'admin')
        await page.fill('input[type="password"]', 'admin123')
        await page.keyboard.press('Enter'); await page.wait_for_timeout(4000)
        print('after login url', page.url, 'btns', await page.locator('.top-nav__switch-item').all_inner_texts())
        print('cookie has token', 'Admin-Token' in await page.evaluate('document.cookie'), 'user', await page.evaluate("localStorage.getItem('Admin-User')"))
        await page.click('text=管理端'); await page.wait_for_timeout(1000)
        await page.click('.el-menu-item:has-text("问答质量管理")'); await page.wait_for_timeout(2500)
        print('quality rows', await page.locator('.quality-manager .el-table__body tr').count())
        await page.screenshot(path='/home/user/webapp/docs/prototype/screenshots/impl/19_after_login_quality.png')
        print('errors', errs[:5] or 'none')
        await b.close()
asyncio.run(main())
