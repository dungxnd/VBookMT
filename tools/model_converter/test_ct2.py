import sys
import time
import re
import ctranslate2
import sentencepiece as spm

sys.stdout.reconfigure(encoding='utf-8')

CT2_DIR = "./models/hachimi60_ct2/ct2-int8_float32"

sp_src = spm.SentencePieceProcessor(model_file=f"{CT2_DIR}/source.spm")
sp_tgt = spm.SentencePieceProcessor(model_file=f"{CT2_DIR}/target.spm")
translator = ctranslate2.Translator(CT2_DIR, device="cpu", intra_threads=4)

OPENERS = "“‘«「『【《([{（［｛"
_OPENER_SPACE = re.compile("([" + re.escape(OPENERS) + r"]) (?=[^\W\d_])")

def postprocess(text: str) -> str:
    return _OPENER_SPACE.sub(r"\1", text)

test_sentence = "“醒醒，指挥官！快醒醒！”一个柔柔弱弱的声音在陈舟耳边响起，他感觉有谁在推着自己的肩膀。"

tokens = sp_src.encode(test_sentence, out_type=str)
start = time.perf_counter()
results = translator.translate_batch([tokens], beam_size=1)
elapsed = (time.perf_counter() - start) * 1000

trans = postprocess(sp_tgt.decode(results[0].hypotheses[0]))

print(f"Bản gốc: {test_sentence}")
print(f"Bản dịch: {trans}")
print(f"Thời gian dịch: {elapsed:.2f} ms")
