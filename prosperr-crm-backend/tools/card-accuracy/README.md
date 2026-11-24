# Visiting-card scan accuracy test

Measures how well `/api/ai/card` (Sarvam Vision) reads visiting cards, field by field.

1. `python3 make_cards.py` then `python3 make_hard.py` draw 14 varied fictional cards (dark, bilingual Hindi/Tamil, tilted photo, low
   resolution, blurred, steep angle, cluttered desk with a second card, small grey text, logos, many contacts, all caps) with headless Chrome into `set/`, plus the
   correct answers in `set/truth.json`. Put `card1.png` (the clean sample) next to the script first, or drop it from truth.json.
   `python3 make_qr.py <path to the frontend folder>` adds 4 more (it uses the frontend's `qrcode` package): a blurred card
   with a vCard QR, a card with a LinkedIn QR, a tiny heavily compressed phone photo, and faint condensed print.
2. Start the backend with `SARVAM_API_KEY` set, sign in, and run
   `python3 score.py http://localhost:8081 <access token>`.

It prints per-field accuracy, cards read perfectly and time per card. To test real cards, put the photos in
`set/` and add their correct values to `truth.json` (same field names).

Result on 24 Nov 2026 (15 cards): 105/106 fields correct (99.1%), 14/15 cards perfect, about 3 s per card.
Names, titles, emails, phones, websites and cities: 100%. The one miss was a logo written as "orbitpay".

## What runs around the AI read (`CardRules`, `CardScanService`)

1. **QR first.** ZXing looks for QR codes in the photo (several sizes, two binarizers). A vCard or MECARD QR is exact
   data; when it has the name, company and an email or phone, the AI read is skipped. A LinkedIn or website QR fills that field.
2. **Small photos are enlarged** to about 1600px (bicubic) and lightly sharpened before the read.
3. **Second read for unsure fields.** When the name, company, email or phone comes back below 0.7 confidence, the card is
   read again; the more confident read wins. Two reads agreeing does not clear the flag, because the same misread can repeat.
4. **Repairs:** letters read as digits in phone numbers (O→0, I→1, S→5, B→8); "(at)", spaces and commas in emails;
   one-letter typos of free-mail domains (gmai1.com → gmail.com).
5. **Cross-checks between fields that must agree:** email domain vs website (a 1-2 letter difference means one was misread;
   the one closer to the company name wins); the person's name vs the email (classic OCR swaps such as rn→m are corrected,
   any other near-miss is flagged); Indian mobile numbers must start 6-9; emails from another card in the photo are dropped.
6. **Suggestions, never silent changes:** a company name 85%+ similar to an account already in the CRM, and a job title
   88%+ similar to a known designation, are offered as one-tap "Use it".
7. Everything above is listed to the BDR under "checks on what was read", and fields under 0.8 confidence are highlighted.

Result on 24 Nov 2026 with 19 cards (the 15 above plus the 4 from `make_qr.py`), same cards, before and after:

| | Before | After |
|---|---|---|
| Fields correct | 132/135 (97.8%) | 133/135 (98.5%) |
| Cards fully correct | 16/19 | 17/19 |
| LinkedIn | 1/2 | 2/2 (read from the QR) |

Still wrong: "orbitpay" (logo lettering) and one digit on the tiny compressed photo (8 read as 5 twice, ~6px-high print);
that phone stays highlighted at 0.29 confidence so the BDR checks it. The blurred vCard QR was too blurred to decode
(about 3.5px per QR square under 2.4px blur); the AI read got that card fully right anyway.
These are synthetic cards; real photos of real cards will score differently, so test a batch of real ones the same way.
