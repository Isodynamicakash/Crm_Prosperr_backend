"""Generate a varied test set of visiting cards (fictional people) as PNGs, with the correct answers in truth.json."""
import json, os, subprocess, sys

D = os.path.dirname(os.path.abspath(__file__))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SANS = "Helvetica, Arial, sans-serif"

def wrap(inner, bg="#e9e6df", w=1050, h=600, extra=""):
    return f"""<html><head><meta charset="utf-8"><link href="https://fonts.googleapis.com/css2?family=Noto+Sans+Devanagari&family=Noto+Sans+Tamil&display=swap" rel="stylesheet"></head>
<body style="margin:0;background:{bg};display:flex;align-items:center;justify-content:center;width:{w}px;height:{h}px;overflow:hidden">{inner}</body></html>"""

CARDS = [
  dict(id="02-dark-two-col", truth=dict(fullName="Rohit Malhotra", designation="Head of Payroll", company="Quantix Analytics",
       emails=["rohit.malhotra@quantix.co.in"], phones=["+919810022334"], website="quantix.co.in", city="Gurugram"),
       html=f"""<div style="width:900px;height:500px;background:#14181f;color:#e8ecf2;border-radius:14px;display:flex;font-family:{SANS};box-shadow:0 10px 30px #0006">
       <div style="width:40%;background:#1f6feb;display:flex;align-items:center;justify-content:center;font:800 40px {SANS};color:#fff;border-radius:14px 0 0 14px">QUANTIX<br>ANALYTICS</div>
       <div style="padding:60px 50px;font-size:20px;line-height:1.8"><div style="font:600 34px {SANS}">Rohit Malhotra</div><div style="color:#9fb0c6">Head of Payroll</div>
       <div style="margin-top:40px">M: +91 98100 22334<br>rohit.malhotra@quantix.co.in<br>quantix.co.in<br>DLF Cyber City, Gurugram</div></div></div>"""),
  dict(id="03-hindi-english", truth=dict(fullName="Sunita Verma", designation="Vice President - Human Resources", company="Bharat Agro Industries",
       emails=["sunita.verma@bharatagro.com"], phones=["+919926011223"], website="www.bharatagro.com", city="Indore"),
       html=f"""<div style="width:900px;height:500px;background:#fffaf0;border:2px solid #b45309;border-radius:10px;padding:46px 56px;box-sizing:border-box;font-family:{SANS}">
       <div style="font:700 30px {SANS};color:#b45309">Bharat Agro Industries <span style="font-family:'Noto Sans Devanagari'">भारत एग्रो</span></div>
       <div style="margin-top:50px;font-size:34px">Sunita Verma <span style="font-family:'Noto Sans Devanagari';font-size:26px;color:#555">सुनीता वर्मा</span></div>
       <div style="font-size:20px;color:#333">Vice President - Human Resources</div>
       <div style="margin-top:46px;font-size:19px;line-height:1.7">Mobile: 99260 11223 &nbsp; Email: sunita.verma@bharatagro.com<br>www.bharatagro.com &nbsp;|&nbsp; Vijay Nagar, Indore, MP</div></div>"""),
  dict(id="04-vertical", truth=dict(fullName="Karthik Raman", designation="Finance Controller", company="Lumen Health",
       emails=["karthik.r@lumenhealth.in"], phones=["+917338012345"], website="lumenhealth.in", city="Chennai"),
       html=f"""<div style="width:420px;height:560px;background:#fff;border-radius:12px;box-shadow:0 8px 24px #0003;text-align:center;padding:50px 30px;box-sizing:border-box;font-family:{SANS}">
       <div style="width:90px;height:90px;border-radius:50%;background:#10b981;margin:0 auto;color:#fff;font:700 40px/90px {SANS}">LH</div>
       <div style="font:700 24px {SANS};margin-top:18px;color:#065f46">Lumen Health</div>
       <div style="font-size:28px;margin-top:40px">Karthik Raman</div><div style="color:#555;font-size:17px">Finance Controller</div>
       <div style="margin-top:40px;font-size:16px;line-height:1.8">+91 73380 12345<br>karthik.r@lumenhealth.in<br>lumenhealth.in · Chennai</div></div>"""),
  dict(id="05-many-contacts", truth=dict(fullName="Priya Nair", designation="Senior Manager, Compensation & Benefits", company="Coastal Logistics",
       emails=["priya.nair@coastallogistics.com", "hr@coastallogistics.com"], phones=["+919946055667", "+914842345678"], website="coastallogistics.com", city="Kochi"),
       html=f"""<div style="width:900px;height:500px;background:#fff;border-left:16px solid #0e7490;border-radius:8px;padding:44px 54px;box-sizing:border-box;font-family:{SANS};box-shadow:0 8px 24px #0003">
       <div style="font:700 28px {SANS};color:#0e7490">COASTAL LOGISTICS</div>
       <div style="margin-top:44px;font-size:32px">Priya Nair</div><div style="font-size:19px;color:#444">Senior Manager, Compensation &amp; Benefits</div>
       <div style="margin-top:40px;font-size:18px;line-height:1.75">Mob: +91-99460-55667 &nbsp; Tel: 0484-2345678<br>E: priya.nair@coastallogistics.com &nbsp; HR desk: hr@coastallogistics.com<br>coastallogistics.com · Marine Drive, Kochi</div></div>"""),
  dict(id="06-low-contrast-small", truth=dict(fullName="Arjun Mehta", designation="CHRO", company="Tensor Retail",
       emails=["arjun@tensorretail.com"], phones=["+919820098765"], website="tensorretail.com", city="Mumbai"),
       html=f"""<div style="width:900px;height:500px;background:#f4f4f4;border-radius:8px;padding:60px;box-sizing:border-box;font-family:{SANS};color:#9a9a9a">
       <div style="font:300 22px {SANS};letter-spacing:6px">TENSOR RETAIL</div>
       <div style="margin-top:110px;font-size:22px;color:#7d7d7d">Arjun Mehta · CHRO</div>
       <div style="margin-top:20px;font-size:14px;line-height:1.8">98200 98765 · arjun@tensorretail.com · tensorretail.com · Lower Parel, Mumbai</div></div>"""),
  dict(id="07-tilted-photo", truth=dict(fullName="Neha Kapoor", designation="AVP Finance", company="Orbit Pay",
       emails=["neha.kapoor@orbitpay.in"], phones=["+918800112233"], website="orbitpay.in", city="Noida"),
       html=f"""<div style="width:900px;height:500px;background:#fff;border-radius:10px;padding:50px 60px;box-sizing:border-box;font-family:{SANS};transform:rotate(-7deg) skewX(-3deg);box-shadow:20px 30px 40px #0006">
       <div style="font:800 32px {SANS};color:#7c3aed">orbit<span style="color:#111">pay</span></div>
       <div style="margin-top:70px;font-size:34px">Neha Kapoor</div><div style="font-size:20px;color:#444">AVP Finance</div>
       <div style="margin-top:44px;font-size:18px;line-height:1.7">+91 88001 12233 | neha.kapoor@orbitpay.in<br>orbitpay.in | Sector 62, Noida</div></div>""", bg="#6b5b4b"),
  dict(id="08-title-first-logo", truth=dict(fullName="Vikram Singh Rathore", designation="Director - People & Culture", company="Kestrel Fintech",
       emails=["vikram.rathore@kestrelfin.com"], phones=["+917023344556"], website="kestrelfin.com", city="Jaipur"),
       html=f"""<div style="width:900px;height:500px;background:linear-gradient(135deg,#fef3c7,#fde68a);border-radius:12px;padding:50px 60px;box-sizing:border-box;font-family:Georgia,serif;box-shadow:0 8px 24px #0003">
       <div style="font:italic 700 44px Georgia;color:#92400e">Kestrel</div><div style="font:14px {SANS};letter-spacing:4px;color:#92400e">FINTECH</div>
       <div style="margin-top:44px;font:14px {SANS};letter-spacing:2px;color:#78350f">DIRECTOR - PEOPLE &amp; CULTURE</div>
       <div style="font-size:34px;margin-top:4px">Vikram Singh Rathore</div>
       <div style="margin-top:40px;font:18px {SANS};line-height:1.7">☎ 70233 44556 &nbsp; ✉ vikram.rathore@kestrelfin.com<br>kestrelfin.com &nbsp; C-Scheme, Jaipur</div></div>"""),
  dict(id="09-linkedin", truth=dict(fullName="Ritu Agarwal", designation="VP - Finance & Accounts", company="Brightline Retail",
       emails=["ritu.agarwal@brightline.co"], phones=["+919831234567"], website="brightline.co", city="Kolkata", linkedin="linkedin.com/in/ritu-agarwal-fa"),
       html=f"""<div style="width:900px;height:500px;background:#fff;border-radius:10px;padding:50px 60px;box-sizing:border-box;font-family:{SANS};box-shadow:0 8px 24px #0003;border-top:10px solid #dc2626">
       <div style="font:700 30px {SANS}">Brightline Retail</div>
       <div style="margin-top:60px;font-size:34px">Ritu Agarwal</div><div style="font-size:20px;color:#444">VP - Finance &amp; Accounts</div>
       <div style="margin-top:40px;font-size:18px;line-height:1.7">+91 98312 34567 · ritu.agarwal@brightline.co<br>linkedin.com/in/ritu-agarwal-fa · brightline.co · Salt Lake, Kolkata</div></div>"""),
  dict(id="10-tamil-english", truth=dict(fullName="Lakshmi Sundaram", designation="Payroll Manager", company="Vel Textiles",
       emails=["lakshmi.s@veltextiles.com"], phones=["+919443012345"], website="veltextiles.com", city="Coimbatore"),
       html=f"""<div style="width:900px;height:500px;background:#fff7ed;border-radius:10px;padding:46px 56px;box-sizing:border-box;font-family:{SANS};box-shadow:0 8px 24px #0003">
       <div style="font:700 30px {SANS};color:#c2410c">Vel Textiles <span style="font-family:'Noto Sans Tamil';font-size:24px">வேல் டெக்ஸ்டைல்ஸ்</span></div>
       <div style="margin-top:56px;font-size:32px">Lakshmi Sundaram <span style="font-family:'Noto Sans Tamil';font-size:22px;color:#555">லட்சுமி சுந்தரம்</span></div>
       <div style="font-size:19px;color:#444">Payroll Manager</div>
       <div style="margin-top:44px;font-size:18px;line-height:1.7">94430 12345 · lakshmi.s@veltextiles.com · veltextiles.com · Peelamedu, Coimbatore</div></div>"""),
  dict(id="11-low-res", truth=dict(fullName="Sameer Joshi", designation="CFO", company="Harbor Analytics",
       emails=["sameer.joshi@harboranalytics.io"], phones=["+919850011122"], website="harboranalytics.io", city="Pune"),
       html=f"""<div style="width:420px;height:240px;background:#fff;border-radius:6px;padding:22px 26px;box-sizing:border-box;font-family:{SANS};box-shadow:0 4px 12px #0003">
       <div style="font:700 15px {SANS};color:#1d4ed8">HARBOR ANALYTICS</div>
       <div style="margin-top:26px;font-size:16px">Sameer Joshi</div><div style="font-size:11px;color:#444">CFO</div>
       <div style="margin-top:20px;font-size:10px;line-height:1.6">+91 98500 11122 · sameer.joshi@harboranalytics.io<br>harboranalytics.io · Baner, Pune</div></div>""", w=480, h=280),
  dict(id="12-all-caps", truth=dict(fullName="Deepa Iyer", designation="General Manager - HR", company="Sterling Foods",
       emails=["deepa.iyer@sterlingfoods.in"], phones=["+919740055443"], website="sterlingfoods.in", city="Bengaluru"),
       html=f"""<div style="width:900px;height:500px;background:#111;color:#f5d68a;border-radius:10px;padding:56px 64px;box-sizing:border-box;font-family:{SANS};text-transform:uppercase;letter-spacing:2px">
       <div style="font:700 28px {SANS}">STERLING FOODS PVT. LTD.</div>
       <div style="margin-top:70px;font-size:32px;color:#fff">DEEPA IYER</div><div style="font-size:18px">GENERAL MANAGER - HR</div>
       <div style="margin-top:46px;font-size:17px;line-height:1.7;text-transform:none;letter-spacing:0">+91 97400 55443 · deepa.iyer@sterlingfoods.in<br>sterlingfoods.in · Whitefield, Bengaluru</div></div>"""),
]
truth = {"01-clean": dict(fullName="Ananya Krishnan", designation="Chief Financial Officer", company="Northwind Labs",
         emails=["ananya.k@northwindlabs.in"], phones=["+919845012345", "+918041235566"], website="www.northwindlabs.in", city="Bengaluru")}
os.makedirs(os.path.join(D, "set"), exist_ok=True)
subprocess.run(["cp", os.path.join(D, "card1.png"), os.path.join(D, "set", "01-clean.png")])
for c in CARDS:
    w, h = c.get("w", 1050), c.get("h", 600)
    path = os.path.join(D, "set", c["id"] + ".html")
    open(path, "w").write(wrap(c["html"], c.get("bg", "#e9e6df"), w, h))
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--window-size={w},{h}", "--virtual-time-budget=3000",
                    f"--screenshot={os.path.join(D, 'set', c['id'] + '.png')}", "file://" + path], capture_output=True)
    truth[c["id"]] = c["truth"]
json.dump(truth, open(os.path.join(D, "set", "truth.json"), "w"), indent=1, ensure_ascii=False)
print(len(truth), "cards")
