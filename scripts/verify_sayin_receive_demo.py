"""Verify the standalone reception prototype. Requires Python Playwright + Chromium."""
from pathlib import Path
import hashlib
import json
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
DEMO = ROOT / "docs/demos"
HTML = DEMO / "sayin-receive.html"
checks = []
matrix = []
errors = []


def check(name, passed):
    assert passed, name
    checks.append(name)


def open_network_details(page):
    # Reuse the existing help entry; no permanent extra network control in daily use.
    trigger = page.locator("#network,#help,#connection-help").first
    trigger.click()
    disclosure = page.locator("#dialog .network-details summary")
    if disclosure.count():
        disclosure.click()
    return trigger


with sync_playwright() as playwright:
    browser = playwright.chromium.launch()
    context = browser.new_context(
        viewport={"width": 1280, "height": 860},
        permissions=["clipboard-read", "clipboard-write"],
    )
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(HTML.as_uri())
    check("exactly three connection scenarios", page.locator("[data-stage]").count() == 3)
    check("first use is default", page.evaluate("ui.stage==='first'"))
    check("no history or fake record on first use", not page.locator("#recent-section").is_visible())
    check("old tutorial is removed", page.locator(".guide-steps,.step-visual,#connection-guide").count() == 0)
    check("QR has explicit demo label", page.evaluate("document.querySelector('.qr-image').alt===t('qrAlt')"))
    check("QR native size matches rendered size", page.locator(".qr-image").evaluate("i=>i.naturalWidth===205 && i.clientWidth===205"))
    invitation_height = page.locator(".invitation").bounding_box()["height"]
    check("inactive code is excluded from keyboard access", page.locator("#code-panel").evaluate("p=>p.inert"))
    page.evaluate("window.pairingTrackBefore=document.querySelector('.pairing-track')")
    page.locator("#show-code").click()
    page.wait_for_function("Math.abs(new DOMMatrix(getComputedStyle(document.querySelector('.pairing-track')).transform).m41+document.querySelector('.pairing-track').clientWidth)<0.5")
    check("pairing code slides one viewport left", page.evaluate("document.querySelector('.pairing-track').dataset.method==='code'"))
    check("slide preserves DOM for transition", page.evaluate("window.pairingTrackBefore===document.querySelector('.pairing-track')"))
    check("switching does not change invitation height", abs(page.locator(".invitation").bounding_box()["height"] - invitation_height) < 1)
    check("inactive QR is excluded from assistive navigation", page.locator("#qr-panel").get_attribute("aria-hidden") == "true")
    check("active code is accessible", page.locator("#code-panel").evaluate("p=>!p.inert && p.getAttribute('aria-hidden')==='false'"))
    check("switch preserves keyboard focus", page.locator("#show-code").evaluate("b=>b===document.activeElement"))
    page.keyboard.press("Enter")
    page.wait_for_function("Math.abs(new DOMMatrix(getComputedStyle(document.querySelector('.pairing-track')).transform).m41)<0.5")
    check("keyboard returns to QR with reverse slide", page.evaluate("!ui.code && document.querySelector('.pairing-track').dataset.method==='qr'"))
    page.emulate_media(reduced_motion="reduce")
    page.locator("#show-code").click()
    check("reduced motion switches without animation", page.locator(".pairing-track").evaluate("p=>getComputedStyle(p).transitionDuration==='0s'"))
    page.emulate_media(reduced_motion="no-preference")
    page.locator("#simulate").click()
    check("pairing waits for explicit approval", page.evaluate("ui.stage==='first' && ui.phase==='approval'"))
    check("approval shows real model name", page.locator(".device-name").inner_text() == "Redmi K40")
    page.locator("#reject").click()
    check("reject leaves device unpaired", page.evaluate("ui.stage==='first' && ui.phase==='invite'"))
    page.locator("#simulate").click()
    page.locator("#approve").click()
    check("approval connects and enters standby", page.evaluate("ui.stage==='connected' && !ui.paused && !ui.issue"))
    check("approval creates no sample text", page.locator(".empty").count() == 1)
    page.locator("#toggle-receiving").click()
    check("connected receiving can pause", page.evaluate("ui.paused"))
    page.locator("#toggle-receiving").click()
    check("connected receiving can resume", page.evaluate("!ui.paused"))
    page.locator("[data-stage=first]").click()
    page.locator("#condition").select_option("expired")
    check("expired invite hides QR", page.locator(".qr-image").count() == 0)
    page.locator("#refresh-invite").click()
    check("refresh returns to invitation", page.evaluate("ui.phase==='invite'"))
    page.locator("[data-stage=reconnect]").click()
    check("reconnect has no QR or tutorial", page.locator(".qr-image,.pair-steps").count() == 0)
    page.locator("#toggle-receiving").click()
    check("standby can pause while offline", page.evaluate("ui.paused"))
    page.locator("#connection-help").click()
    check("connection help opens", page.locator("#dialog").evaluate("d=>d.open"))
    page.keyboard.press("Escape")
    page.wait_for_function("document.activeElement.id==='connection-help'")
    check("help restores focus", page.locator("#connection-help").evaluate("b=>b===document.activeElement"))
    page.locator("#simulate").click()
    check("reconnection preserves explicit pause", page.evaluate("ui.stage==='connected' && ui.paused"))
    for issue in ["uncertain", "storage"]:
        page.locator("[data-stage=reconnect]").click()
        page.locator("#condition").select_option(issue)
        check(f"{issue} disables standby toggle", page.locator("#toggle-receiving").count() == 0)
        page.locator("#simulate").click()
        check(f"reconnection preserves {issue}", page.evaluate("ui.issue") == issue)
    for stage in ["first", "reconnect"]:
        page.evaluate("s=>setStage(s)", stage)
        page.locator("#network-preview").select_option("public")
        check(f"{stage} public network requires no extra decision", page.locator("input[type=checkbox]").count() == 0 and page.locator("#retry-network").count() == 0)
        if stage == "first":
            check("public network shows invitation automatically", page.locator(".qr-image").count() == 1)
        else:
            check("paired phone on public network needs no new invite", page.locator(".qr-image").count() == 0 and page.locator("#connection-help").count() == 1)
    network_trigger = open_network_details(page)
    check("network details disclose category without authorization", "Public" in page.locator("#dialog-body").inner_text() or "公用网络" in page.locator("#dialog-body").inner_text())
    check("network detail has no adapter or consent selector", page.locator("#dialog input,#dialog select").count() == 0)
    page.keyboard.press("Escape")
    check("network detail restores focus", network_trigger.evaluate("b=>b===document.activeElement"))
    for blocker in ["paused", "uncertain", "held", "storage"]:
        page.evaluate("b=>{setStage('connected');setCondition(b)}", blocker)
        original_state = page.evaluate("JSON.stringify([ui.paused,ui.issue,recordState,ui.empty])")
        page.locator("#network-preview").select_option("offline")
        check(f"network loss preserves {blocker}", page.evaluate("JSON.stringify([ui.paused,ui.issue,recordState,ui.empty])") == original_state)
        check(f"network loss hides invite and input toggle for {blocker}", page.locator(".qr-image,#toggle-receiving").count() == 0)
        page.locator("#retry-network").click()
        check(f"checking offline network does not fake recovery for {blocker}", page.evaluate("networkBlocked()"))
        page.locator("#simulate").click()
        check(f"network returns without repairing {blocker}", page.evaluate("ui.stage==='reconnect' && !networkBlocked() && JSON.stringify([ui.paused,ui.issue,recordState,ui.empty])") == original_state)
        check(f"network return never re-pairs {blocker}", page.locator(".qr-image,.pair-steps").count() == 0)
        page.locator("#simulate").click()
        check(f"authenticated reconnect preserves {blocker}", page.evaluate("ui.stage==='connected' && JSON.stringify([ui.paused,ui.issue,recordState,ui.empty])") == original_state)
    page.evaluate("setStage('first');setNetworkMode('offline')")
    check("first use without network hides QR", page.locator(".qr-image").count() == 0)
    page.locator("#simulate").click()
    check("first network return shows invite without approval ceremony", page.locator(".qr-image").count() == 1 and page.evaluate("ui.stage==='first' && network.mode==='public'"))
    page.evaluate("setStage('connected');setCondition('paused');setNetworkMode('changed')")
    check("network switch waits for reconnect and preserves pause", page.evaluate("ui.stage==='reconnect' && ui.paused && network.mode==='changed'"))
    page.locator("#simulate").click()
    check("network switch reconnects without new pairing", page.evaluate("ui.stage==='connected' && ui.paused") and page.locator(".qr-image").count() == 0)
    page.locator("[data-stage=connected]").click()
    page.locator("#condition").select_option("uncertain")
    page.locator("#uncertain").click()
    check("review requires explicit local confirmation", page.locator("#confirm-review").is_disabled())
    page.keyboard.press("Escape")
    check("cancel review does not resume", page.evaluate("ui.issue==='uncertain'"))
    page.locator("#uncertain").click()
    page.locator("#review-check").check()
    page.locator("#confirm-review").click()
    check("review resumes only new text", page.evaluate("!ui.issue && !ui.paused && recordState==='uncertain'"))
    page.locator("#condition").select_option("held")
    page.locator("#held").click()
    page.locator("#simulate-expiry").click()
    check("expiry preserves text and original reason", page.evaluate("recordState==='expired' && ui.paused && !ui.issue"))
    check("expired record has no execution target", page.evaluate("document.querySelector('.destination').textContent===t('noTarget')"))
    page.locator("#condition").select_option("ready")
    original = page.locator("#original-text").inner_text()
    page.locator("#language").click()
    check("language preserves original text", page.locator("#original-text").inner_text() == original)
    check("language preserves connection", page.evaluate("ui.stage==='connected' && !ui.paused"))
    page.locator("#copy").click()
    check("copy writes original to clipboard", page.evaluate("navigator.clipboard.readText()") == original)
    page.locator("#record-details").click()
    check("record details expand", page.locator("#record-panel").is_visible())
    page.locator("#record-details").click()
    check("record details collapse", not page.locator("#record-panel").is_visible())
    page.locator("[data-nav=settings]").click()
    page.locator("#appearance-choice").select_option("system")
    check("system theme supported", page.evaluate("themeChoice==='system'"))
    page.locator("#language-choice").select_option("system")
    check("system language supported", page.evaluate("languageChoice==='system'"))
    page.keyboard.press("Escape")
    page.wait_for_function("document.activeElement.dataset.nav==='settings'")
    check("settings restores toolbar focus", page.locator("[data-nav=settings]").evaluate("b=>b===document.activeElement"))
    for width, height in [(1280, 860), (800, 640), (480, 760)]:
        page.set_viewport_size({"width": width, "height": height})
        for language in ["zh", "en"]:
            for theme in ["light", "dark"]:
                for stage, conditions in {
                    "first": ["invite", "approval", "expired"],
                    "connected": ["ready", "paused", "held", "uncertain", "network", "storage"],
                    "reconnect": ["ready", "paused", "uncertain", "network", "storage"],
                }.items():
                    for condition in conditions:
                        page.evaluate("([l,t,s,c])=>{ui.lang=l;ui.theme=t;setStage(s);setCondition(c)}", [language, theme, stage, condition])
                        result = page.evaluate("""()=>({
                          overflow:document.documentElement.scrollWidth>innerWidth || document.querySelector('.workspace').scrollWidth>document.querySelector('.workspace').clientWidth,
                          missingText:document.body.innerText.includes('undefined'),
                          missingIcon:[...document.querySelectorAll('use')].some(e=>!document.querySelector(e.getAttribute('href'))),
                          brokenImage:[...document.images].some(i=>!i.complete||!i.naturalWidth),
                          namelessButton:[...document.querySelectorAll('button')].filter(b=>b.getClientRects().length).some(b=>!b.textContent.trim()&&!b.getAttribute('aria-label'))
                        })""")
                        assert not any(result.values()), (width, language, theme, stage, condition, result)
                        matrix.append({"width": width, "height": height, "language": language, "theme": theme, "stage": stage, "condition": condition, "passed": True})
                        if stage == "first" and condition == "invite":
                            before = page.locator(".invitation").bounding_box()["height"]
                            page.evaluate("ui.code=true;syncPairingMethod()")
                            page.wait_for_function("Math.abs(new DOMMatrix(getComputedStyle(document.querySelector('.pairing-track')).transform).m41+document.querySelector('.pairing-track').clientWidth)<0.5")
                            assert abs(page.locator(".invitation").bounding_box()["height"] - before) < 1
                            assert page.evaluate("document.querySelector('.workspace').scrollWidth<=document.querySelector('.workspace').clientWidth")
                            matrix.append({"width": width, "height": height, "language": language, "theme": theme, "stage": stage, "condition": "pairing-code", "passed": True})
    check("180 state/layout combinations pass", len(matrix) == 180)
    network_matrix = []
    for width, height in [(1280, 860), (800, 640), (480, 760)]:
        page.set_viewport_size({"width": width, "height": height})
        for language in ["zh", "en"]:
            for theme in ["light", "dark"]:
                for stage in ["first", "connected", "reconnect"]:
                    for mode in ["private", "public", "changed", "offline"]:
                        page.evaluate("([l,t,s,n])=>{ui.lang=l;ui.theme=t;setStage(s);setNetworkMode(n)}", [language, theme, stage, mode])
                        assert page.evaluate("document.querySelector('.workspace').scrollWidth<=document.querySelector('.workspace').clientWidth && document.documentElement.scrollWidth<=innerWidth && !document.body.innerText.includes('undefined')")
                        if mode == "offline":
                            assert page.locator(".qr-image,#toggle-receiving").count() == 0
                        open_network_details(page)
                        assert page.locator("#dialog").evaluate("d=>d.open && d.scrollWidth<=d.clientWidth && d.getBoundingClientRect().right<=innerWidth")
                        assert not page.locator("#dialog input,#dialog select").count()
                        page.keyboard.press("Escape")
                        network_matrix.append({"width": width, "height": height, "language": language, "theme": theme, "stage": stage, "network": mode, "passed": True})
    check("144 automatic-network layouts and detail dialogs pass", len(network_matrix) == 144)
    page.set_viewport_size({"width": 800, "height": 640})
    for stage in ["first", "connected", "reconnect"]:
        page.evaluate("s=>{ui.lang='en';setStage(s);document.documentElement.style.fontSize='20px'}", stage)
        check(f"{stage} large text fits", page.evaluate("document.querySelector('.workspace').scrollWidth<=document.querySelector('.workspace').clientWidth"))
    page.evaluate("document.documentElement.style.fontSize=''")
    page.set_viewport_size({"width": 480, "height": 760})
    page.evaluate("ui.lang='en';setStage('reconnect');setNetworkMode('offline');document.documentElement.style.fontSize='20px'")
    page.locator("#network").click()
    check("large text network details fit narrow window", page.locator("#dialog").evaluate("d=>d.scrollWidth<=d.clientWidth"))
    page.keyboard.press("Escape")
    page.evaluate("document.documentElement.style.fontSize=''")
    page.set_viewport_size({"width": 1280, "height": 860})
    for stage in ["first", "connected", "reconnect"]:
        page.evaluate("s=>{ui.lang='zh';ui.theme='light';setStage(s);document.activeElement.blur()}", stage)
        page.mouse.move(0, 0)
        page.screenshot(path=str(DEMO / f"sayin-receive-{stage}-preview.png"))
    page.evaluate("setStage('first');ui.code=true;syncPairingMethod()")
    page.wait_for_function("Math.abs(new DOMMatrix(getComputedStyle(document.querySelector('.pairing-track')).transform).m41+document.querySelector('.pairing-track').clientWidth)<0.5")
    page.screenshot(path=str(DEMO / "sayin-receive-code-preview.png"))
    page.evaluate("setStage('first');setCondition('approval')")
    page.screenshot(path=str(DEMO / "sayin-receive-approval-preview.png"))
    page.evaluate("setStage('first');setNetworkMode('public')")
    page.screenshot(path=str(DEMO / "sayin-receive-public-preview.png"))
    page.evaluate("setStage('connected');setCondition('uncertain');setNetworkMode('offline')")
    page.screenshot(path=str(DEMO / "sayin-receive-offline-preview.png"))
    page.locator("#network").click()
    page.screenshot(path=str(DEMO / "sayin-receive-network-details-preview.png"))
    page.keyboard.press("Escape")
    page.evaluate("ui.lang='en';ui.theme='dark';setStage('connected');setCondition('uncertain')")
    page.screenshot(path=str(DEMO / "sayin-receive-dark-preview.png"))
    page.set_viewport_size({"width": 480, "height": 760})
    page.evaluate("setStage('first')")
    page.locator(".connection-panel").screenshot(path=str(DEMO / "sayin-receive-narrow-preview.png"))
    check("no browser runtime errors", not errors)
    page.evaluate("setStage('connected')")
    page.locator("#history-link").click()
    check("history opens supplied demo", page.url.endswith("sayin-history-card.html"))
    check("supplied history still loads", page.locator("#records").count() == 1)
    browser.close()

report = {"artifact": "docs/demos/sayin-receive.html", "sha256": hashlib.sha256(HTML.read_bytes()).hexdigest(), "command": "python scripts/verify_sayin_receive_demo.py", "browser": "Playwright Chromium headless", "checks": checks, "matrix": matrix, "network_matrix": network_matrix, "runtime_errors": errors, "scope": "Standalone prototype only. Proposed automatic public-network UX; native session-authorization policy is unchanged. No real pairing, transport or native input tested."}
(DEMO / "sayin-receive.verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"checks_passed": len(checks), "layout_combinations_passed": len(matrix) + len(network_matrix), "runtime_errors": errors}))
