"""Three harder, photo-like cards: blurred, steep angle with perspective, and cluttered desk with a second card."""
import json, os, subprocess
D = os.path.dirname(os.path.abspath(__file__)); S = os.path.join(D, "set")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SANS = "Helvetica, Arial, sans-serif"
def card(name, title, company, lines, color):
    return f"""<div style="width:900px;height:500px;background:#fff;border-radius:10px;padding:50px 60px;box-sizing:border-box;font-family:{SANS};box-shadow:0 12px 30px #0007">
    <div style="font:700 30px {SANS};color:{color}">{company}</div><div style="margin-top:64px;font-size:34px">{name}</div>
    <div style="font-size:20px;color:#444">{title}</div><div style="margin-top:40px;font-size:18px;line-height:1.7">{lines}</div></div>"""
HARD = [
 dict(id="13-blurred", style="filter:blur(1.6px)", bg="#cfc8bb",
      c=card("Manish Gupta", "Head - Compensation & Benefits", "Polaris Infra", "+91 99100 45678 · manish.gupta@polarisinfra.com<br>polarisinfra.com · Okhla, Delhi", "#b91c1c"),
      truth=dict(fullName="Manish Gupta", designation="Head - Compensation & Benefits", company="Polaris Infra", emails=["manish.gupta@polarisinfra.com"], phones=["+919910045678"], website="polarisinfra.com", city="Delhi")),
 dict(id="14-steep-angle", style="transform:perspective(900px) rotateX(28deg) rotateZ(-10deg) scale(.9)", bg="#5b4636",
      c=card("Farah Siddiqui", "Finance Director", "Meridian Healthcare", "+91 90040 87654 · farah.s@meridianhc.in<br>meridianhc.in · Andheri East, Mumbai", "#0f766e"),
      truth=dict(fullName="Farah Siddiqui", designation="Finance Director", company="Meridian Healthcare", emails=["farah.s@meridianhc.in"], phones=["+919004087654"], website="meridianhc.in", city="Mumbai")),
 dict(id="15-cluttered-desk", style="transform:rotate(4deg)", bg="repeating-linear-gradient(90deg,#7a5230 0 18px,#6b4627 18px 36px)",
      extra=f"""<div style="position:absolute;right:-260px;top:-60px;width:520px;height:300px;background:#fde68a;transform:rotate(-14deg);padding:30px;font:22px {SANS};box-shadow:0 8px 20px #0006">OTHER CARD<br>Someone Else<br>other@another.com</div>
      <div style="position:absolute;left:-90px;bottom:-70px;width:180px;height:180px;border-radius:50%;background:#e5e7eb;box-shadow:0 8px 20px #0006"></div>""",
      c=card("Gaurav Bansal", "VP - HR Operations", "Trident Motors", "+91 98150 33221 · gaurav.bansal@tridentmotors.com<br>tridentmotors.com · Ludhiana", "#1d4ed8"),
      truth=dict(fullName="Gaurav Bansal", designation="VP - HR Operations", company="Trident Motors", emails=["gaurav.bansal@tridentmotors.com"], phones=["+919815033221"], website="tridentmotors.com", city="Ludhiana")),
]
truth = json.load(open(os.path.join(S, "truth.json")))
for h in HARD:
    html = f"""<html><head><meta charset="utf-8"></head><body style="margin:0;background:{h['bg']};width:1100px;height:700px;overflow:hidden;display:flex;align-items:center;justify-content:center">
    <div style="position:relative;{h['style']}">{h['c']}{h.get('extra','')}</div></body></html>"""
    p = os.path.join(S, h["id"] + ".html"); open(p, "w").write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--window-size=1100,700", f"--screenshot={os.path.join(S, h['id'] + '.png')}", "file://" + p], capture_output=True)
    truth[h["id"]] = h["truth"]
json.dump(truth, open(os.path.join(S, "truth.json"), "w"), indent=1, ensure_ascii=False)
print(len(truth), "cards in truth.json")
