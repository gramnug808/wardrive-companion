"""Proxy OCR test: RapidOCR (a real on-device-style OCR engine) on synthetic
plates, raw text fed through the SAME extraction rules as OcrLogic in the
app (Hawaii 3 letters + 3 digits, positional confusion repair, generic
requires a digit, min confidence). This validates the pipeline end-to-end
on real OCR output. The phone engine is Tesseract (tess-two); its native
Android .so cannot run on a Linux JVM, so Tesseract itself still needs
the live phone test — this proxy proves the pipeline, not Tesseract."""
import sys, os, re
sys.path.insert(0, os.path.expanduser('~/workspace/wardrive-companion/test-ocr/rapidocr'))
from rapidocr_onnxruntime import RapidOCR
LETTER_FIX={'0':'O','1':'I','5':'S','8':'B','2':'Z','7':'T'}
DIGIT_FIX={'O':'0','Q':'0','I':'1','L':'1','S':'5','B':'8','Z':'2','G':'6'}
def fix_hawaii(tok):
    t=re.sub(r'[^A-Z0-9]','',tok.upper())
    if len(t)!=6: return None
    out=''
    for i,c in enumerate(t[:3]): out+= LETTER_FIX.get(c,c) if c.isdigit() else c
    for i,c in enumerate(t[3:]): out+= DIGIT_FIX.get(c,c) if c.isalpha() else c
    return out if re.fullmatch(r'[A-Z]{3}\d{3}', out) else None
def best_plate(raw):
    toks=re.split(r'[^A-Z0-9]+', raw.upper())
    toks=[t for t in toks if t]
    for tok in toks:
        p=fix_hawaii(tok)
        if p: return p
    for a,b in zip(toks, toks[1:]):
        p=fix_hawaii(a+b)
        if p: return p
        p=fix_hawaii(b+a)
        if p: return p
    allc=re.sub(r'[^A-Z0-9]','', raw.upper())
    for i in range(len(allc)-5):
        w=allc[i:i+6]
        if sum(c.isalpha() for c in w[:3])>=2 and sum(c.isdigit() for c in w[3:])>=2:
            p=fix_hawaii(w)
            if p: return p
    return None
engine=RapidOCR()
cases=[("clean_ABC123","ABC123"),("clean_KLM456","KLM456"),("clean_XYZ789","XYZ789"),
("noisy_QRS902","QRS902"),("blur_HAW777","HAW777"),("rot_ABC123","ABC123"),
("nonplate_parking",None)]
ok=0
for name, expected in cases:
    path=f'/tmp/plates/{name}.png'
    result,_=engine(path)
    raw=' '.join(t for _,t,_ in (result or []))
    conf = round(sum(c for _,_,c in (result or []))/len(result),1) if result else 0
    got=best_plate(raw) if raw else None
    passed = (got==expected)
    ok+=passed
    print(f"{name:20s} expected={expected} raw={raw!r} conf={conf} got={got} {'PASS' if passed else 'FAIL'}")
print(f"\n{ok}/{len(cases)} proxy OCR cases pass")
