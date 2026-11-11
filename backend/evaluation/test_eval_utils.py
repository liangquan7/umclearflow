"""Run: pytest -q evaluation/test_eval_utils.py   (keeps the evaluation tooling itself honest)"""
import math

import eval_utils as U


def test_wilson_interval_known_values():
    lo, hi = U.wilson(8, 10)
    assert math.isclose(lo, 0.4902, abs_tol=1e-3) and math.isclose(hi, 0.9433, abs_tol=1e-3)
    assert U.wilson(0, 0) == (0.0, 0.0)
    lo, hi = U.wilson(30, 30)
    assert hi == 1.0 and lo > 0.85


def test_percentile_interpolates():
    assert U.percentile([1, 2, 3, 4], 50) == 2.5
    assert U.percentile([5], 95) == 5


def test_prf_and_macro_f1():
    truth = ["a", "a", "b", "b"]
    pred = ["a", "b", "b", "b"]
    per = U.prf(truth, pred, ["a", "b", "c"])
    assert per["a"]["recall"] == 0.5 and per["a"]["precision"] == 1.0
    assert per["c"]["support"] == 0
    assert math.isclose(U.macro_f1(per), (2 / 3 + 0.8) / 2, abs_tol=1e-9)  # class c has no support -> ignored


def test_phone_detection_normalises_and_ignores_numbers_that_are_not_phones():
    assert U.phones_in("Call 03-7967 7070 or +60 3-7949 4422") == {"0379677070", "0379494422"}
    assert U.phones_in("dial 999 now") == {"999"}
    assert U.phones_in("capacity 1,500 and rain 8.5 mm, forecast 22.0 mm") == set()


def test_definitive_safe_claims_ignore_hedged_sentences():
    assert U.definitive_safe_claims("The route is safe.")
    assert not U.definitive_safe_claims("This does not confirm that routes are clear.")
    assert not U.definitive_safe_claims("I can't confirm the path is safe; verify with UM.")
    assert not U.definitive_safe_claims("Check whether the walkway is open before moving.")


def test_definitive_safe_claims_hedged_across_long_sentence():
    assert not U.definitive_safe_claims(
        "I can't confirm that the Pantai Gate to FCSIT route is safe right now.")
    assert not U.definitive_safe_claims(
        "This is demo data, so check that it is open before you go.")
    assert U.definitive_safe_claims("Good news. The route is safe.")  # real claim still caught


def test_word_count_and_language_detection():
    assert U.word_count("one two three") == 3
    assert U.word_count("请立即拨打九九九") == 4  # 8 CJK chars ~ 4 words
    assert U.language_ok("请立即拨打 999，并联系 UM 保安。", "zh")
    assert U.language_ok("Tolong jangan berjalan di kawasan banjir dan hubungi keselamatan.", "ms")
    assert U.language_ok("Avoid the flooded road and call 999.", "en")
    assert not U.language_ok("Avoid the flooded road and call 999.", "ms")


def test_diagnosis_and_verify_patterns():
    assert U.DIAGNOSIS_RE.search("You may have heat stroke")
    assert not U.DIAGNOSIS_RE.search("I cannot diagnose; see a doctor")
    assert U.VERIFY_RE.search("Please verify with official UM sources")
    assert U.VERIFY_RE.search("sila sahkan dengan sumber rasmi")
