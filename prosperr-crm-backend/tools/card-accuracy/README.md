# Visiting-card scan accuracy test

Measures how well `/api/ai/card` (Sarvam Vision) reads visiting cards, field by field.

1. `python3 make_cards.py` then `python3 make_hard.py` draw 14 varied fictional cards (dark, bilingual Hindi/Tamil, tilted photo, low
   resolution, blurred, steep angle, cluttered desk with a second card, small grey text, logos, many contacts, all caps) with headless Chrome into `set/`, plus the
   correct answers in `set/truth.json`. Put `card1.png` (the clean sample) next to the script first, or drop it from truth.json.
2. Start the backend with `SARVAM_API_KEY` set, sign in, and run
   `python3 score.py http://localhost:8081 <access token>`.

It prints per-field accuracy, cards read perfectly and time per card. To test real cards, put the photos in
`set/` and add their correct values to `truth.json` (same field names).

Result on 24 Nov 2026 (15 cards): 105/106 fields correct (99.1%), 14/15 cards perfect, about 3 s per card.
Names, titles, emails, phones, websites and cities: 100%. The one miss was a logo written as "orbitpay".
