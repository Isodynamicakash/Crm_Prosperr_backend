"""Four more cards: two with QR codes (vCard, LinkedIn), a heavily compressed tiny phone photo, and faint condensed print."""
import json, os, subprocess, sys
D = os.path.dirname(os.path.abspath(__file__)); S = os.path.join(D, "set"); FE = sys.argv[1]
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SANS = "Helvetica, Arial, sans-serif"
def qr(text, w=220):
    return subprocess.run(["node", "-e", f"require('qrcode').toDataURL(process.argv[1],{{width:{w},margin:1}}).then(u=>process.stdout.write(u))", text],
                          cwd=FE, capture_output=True, text=True, check=True).stdout
VC = "BEGIN:VCARD\nVERSION:3.0\nN:Ramnath;Sharmila\nFN:Sharmila Ramnath\nORG:Vertex Logistics\nTITLE:Senior Manager - Payroll\nTEL;TYPE=CELL:+91 97311 20456\nEMAIL;TYPE=WORK:sharmila.ramnath@vertexlogistics.in\nURL:https://vertexlogistics.in\nADR;TYPE=WORK:;;Whitefield;Bengaluru;KA;560066;India\nEND:VCARD"
CARDS = [
 dict(id="16-vcard-qr-blurred", page_style="filter:blur(2.4px)", body=f"""
   <div style="font:700 30px {SANS};color:#7c3aed">VERTEX LOGISTICS</div>
   <div style="display:flex;justify-content:space-between;align-items:flex-end;margin-top:40px">
     <div><div style="font-size:34px">Sharmila Ramnath</div><div style="font-size:20px;color:#444">Senior Manager - Payroll</div>
       <div style="margin-top:30px;font-size:17px;line-height:1.7">+91 97311 20456<br>sharmila.ramnath@vertexlogistics.in<br>vertexlogistics.in · Whitefield, Bengaluru</div></div>
     <img src="{qr(VC, 230)}" style="width:230px;height:230px"></div>""",
   truth=dict(fullName="Sharmila Ramnath", designation="Senior Manager - Payroll", company="Vertex Logistics", emails=["sharmila.ramnath@vertexlogistics.in"],
              phones=["+919731120456"], website="vertexlogistics.in", city="Bengaluru")),
 dict(id="17-linkedin-qr", page_style="", body=f"""
   <div style="font:700 28px {SANS};color:#0369a1">Coral Bay Hotels</div>
   <div style="display:flex;justify-content:space-between;align-items:flex-end;margin-top:50px">
     <div><div style="font-size:34px">Anirban Mukherjee</div><div style="font-size:20px;color:#444">General Manager - Finance</div>
       <div style="margin-top:30px;font-size:18px;line-height:1.7">+91 98300 76543 · anirban.m@coralbayhotels.com<br>coralbayhotels.com · Salt Lake, Kolkata</div></div>
     <div style="text-align:center;font-size:13px;color:#555"><img src="{qr('https://www.linkedin.com/in/anirban-mukherjee-cbh', 170)}" style="width:170px;height:170px"><br>Connect on LinkedIn</div></div>""",
   truth=dict(fullName="Anirban Mukherjee", designation="General Manager - Finance", company="Coral Bay Hotels", emails=["anirban.m@coralbayhotels.com"],
              phones=["+919830076543"], website="coralbayhotels.com", city="Kolkata", linkedin="linkedin.com/in/anirban-mukherjee-cbh")),
 dict(id="18-compressed-phone-photo", page_style="transform:rotate(-3deg) scale(.95)", shrink=True, body=f"""
   <div style="font:700 28px {SANS};color:#15803d">Greenfield Agritech</div><div style="margin-top:60px;font-size:32px">Deepika Raghunathan</div>
   <div style="font-size:19px;color:#444">AVP - Human Resources</div>
   <div style="margin-top:36px;font-size:17px;line-height:1.7">+91 94440 31872 · deepika.raghunathan@greenfieldagri.com<br>greenfieldagri.com · Guindy, Chennai</div>""",
   truth=dict(fullName="Deepika Raghunathan", designation="AVP - Human Resources", company="Greenfield Agritech", emails=["deepika.raghunathan@greenfieldagri.com"],
              phones=["+919444031872"], website="greenfieldagri.com", city="Chennai")),
 dict(id="19-faint-condensed", page_style="", font="'Arial Narrow', 'Helvetica Neue', sans-serif", color="#9ca3af", body="""
   <div style="font-weight:700;font-size:26px;color:#a8a29e;letter-spacing:.5px">Summit Ceramics</div><div style="margin-top:70px;font-size:26px;font-stretch:condensed">Vikram Ranganathan</div>
   <div style="font-size:15px">Deputy Manager - Accounts</div>
   <div style="margin-top:40px;font-size:13px;line-height:1.8">+91 98250 11893 · vikram.ranganathan@summitceramics.co.in<br>summitceramics.co.in · Morbi, Gujarat</div>""",
   truth=dict(fullName="Vikram Ranganathan", designation="Deputy Manager - Accounts", company="Summit Ceramics", emails=["vikram.ranganathan@summitceramics.co.in"],
              phones=["+919825011893"], website="summitceramics.co.in", city="Morbi")),
]
truth = json.load(open(os.path.join(S, "truth.json")))
for c in CARDS:
    font = c.get("font", SANS)
    html = f"""<html><head><meta charset="utf-8"></head><body style="margin:0;background:#cbbfa9;width:1100px;height:700px;overflow:hidden;display:flex;align-items:center;justify-content:center">
    <div style="{c['page_style']}"><div style="width:900px;height:500px;background:#fffdf8;border-radius:10px;padding:46px 60px;box-sizing:border-box;font-family:{font};color:{c.get('color','#111')};box-shadow:0 12px 30px #0007">{c['body']}</div></div></body></html>"""
    p = os.path.join(S, c["id"] + ".html"); png = os.path.join(S, c["id"] + ".png"); open(p, "w").write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--window-size=1100,700", f"--screenshot={png}", "file://" + p], capture_output=True)
    if c.get("shrink"):   # what a cheap phone + WhatsApp forward does: tiny and heavily compressed, then back to PNG for upload
        jpg = png[:-4] + ".jpg"
        subprocess.run(["sips", "-Z", "460", "-s", "format", "jpeg", "-s", "formatOptions", "18", png, "--out", jpg], capture_output=True)
        subprocess.run(["sips", "-s", "format", "png", jpg, "--out", png], capture_output=True); os.remove(jpg)
    truth[c["id"]] = c["truth"]
json.dump(truth, open(os.path.join(S, "truth.json"), "w"), indent=1, ensure_ascii=False)
print(len(truth), "cards")
