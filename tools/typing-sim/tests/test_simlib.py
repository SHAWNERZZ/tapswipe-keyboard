import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from simlib import finger  # noqa: E402
from simlib.score import Attempt, classify, probe_result, summary  # noqa: E402


def grid_layout():
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    keys = []
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            keys.append(finger.Key(ord(ch), ch, 10 + c * 100 + r * 50, 1500 + r * 150, 100, 150))
    keys.append(finger.Key(32, " ", 300, 1950, 400, 150))
    keys.append(finger.Key(-5, "del", 900, 1800, 150, 150))
    return finger.Layout(keys)


class FingerTest(unittest.TestCase):
    def test_same_seed_same_events(self):
        lay = grid_layout()
        a = finger.to_script(finger.strokes_for(random.Random(7), lay, "hello", "S+T", finger.FingerParams()))
        b = finger.to_script(finger.strokes_for(random.Random(7), lay, "hello", "S+T", finger.FingerParams()))
        self.assertEqual(a, b)

    def test_styles_cover_the_word(self):
        rng = random.Random(1)
        for style in finger.STYLES:
            for word in ["because", "area", "the"]:
                plan = finger.split_word(rng, word, style)
                if plan is None:
                    continue
                self.assertEqual("".join(t for _, t in plan), word, (style, word, plan))

    def test_single_pointer_script_is_down_moves_up(self):
        lay = grid_layout()
        lines = finger.to_script([finger.swipe(random.Random(2), lay, "the", finger.FingerParams(), 0)])
        actions = [int(l.split()[1]) for l in lines]
        self.assertEqual(actions[0], finger.DOWN)
        self.assertEqual(actions[-1], finger.UP)
        self.assertTrue(all(a == finger.MOVE for a in actions[1:-1]))

    def test_overlapping_thumbs_use_pointer_down_and_up(self):
        lay = grid_layout()
        strokes = finger.strokes_for(random.Random(3), lay, "because", "S/S", finger.FingerParams())
        lines = finger.to_script(strokes)
        actions = [int(l.split()[1]) for l in lines]
        self.assertIn(finger.POINTER_DOWN, actions)
        self.assertIn(finger.POINTER_UP, actions)
        self.assertEqual(actions.count(finger.DOWN), 1)
        self.assertEqual(actions.count(finger.UP), 1)
        # While both are down, every line lists two pointers.
        self.assertTrue(any(l.split()[3] == "2" for l in lines))

    def test_swipe_passes_near_each_letter(self):
        lay = grid_layout()
        s = finger.swipe(random.Random(4), lay, "qp", finger.FingerParams(swipe_sigma=0), 0)
        self.assertAlmostEqual(s.points[0][1], lay.key_for("q").cx, delta=1)
        self.assertAlmostEqual(s.points[-1][1], lay.key_for("p").cx, delta=1)

    def test_layout_from_event_applies_padding_and_correction(self):
        ev = {"screenX": 0, "screenY": 1000, "padLeft": 5, "padTop": 10, "verticalCorrection": 4,
              "keys": [{"code": 97, "label": "a", "x": 0, "y": 0, "w": 100, "h": 150}]}
        k = finger.Layout.from_layout_event(ev).key_for("a")
        self.assertEqual((k.x, k.y), (5, 1006))


class ScoreTest(unittest.TestCase):
    def a(self, plan=(("swipe", "hel"), ("tap", "l"), ("tap", "o"))):
        return Attempt("hello", "S+T", list(plan), 1)

    def test_ok(self):
        r = classify(self.a(), [{"type": "finalize", "typedWord": "hello"}, {"type": "commit", "text": "Hello"}])
        self.assertEqual(r.verdict, "OK")

    def test_wrong_commit_when_composing_was_right(self):
        r = classify(self.a(), [{"type": "finalize", "typedWord": "hello"}, {"type": "commit", "text": "hells"}])
        self.assertEqual(r.verdict, "WRONG_COMMIT")

    def test_wrong_decode_and_last_stroke_flip(self):
        ev = [{"type": "decode", "text": "hello", "covered": 2, "candidates": ["hello"], "scores": [1]},
              {"type": "decode", "text": "hellos", "covered": 3, "candidates": ["hellos", "hello"], "scores": [2, 1]},
              {"type": "finalize", "typedWord": "hellos"}, {"type": "commit", "text": "hellos"}]
        r = classify(self.a(), ev)
        self.assertEqual(r.verdict, "WRONG_DECODE")
        self.assertIn("LAST_STROKE_FLIP", r.flags)
        self.assertEqual(r.rank, 1)
        probe_result(r, "the hello")
        self.assertIn("BACKSPACE_RECOVERS", r.flags)

    def test_lost_and_split(self):
        self.assertEqual(classify(self.a(), []).verdict, "LOST")
        r = classify(self.a(), [{"type": "commit", "text": "he"}, {"type": "commit", "text": "llo"}])
        self.assertEqual(r.verdict, "SPLIT")

    def test_summary_penalty(self):
        s1 = Attempt("a", "S", [], 1, verdict="OK")
        s2 = Attempt("b", "S", [], 1, verdict="OK")
        t1 = Attempt("a", "S+T", [], 1, verdict="OK")
        t2 = Attempt("b", "S+T", [], 1, verdict="WRONG_DECODE")
        s = summary([s1, s2, t1, t2])
        self.assertEqual(s["by_style"]["S+T"]["penalty_vs_S"], 0.5)


if __name__ == "__main__":
    unittest.main()


class ReplayTest(unittest.TestCase):
    def test_frame_maps_keyboard_corners(self):
        from simlib.replay import Frame
        phone = Frame.of({"padLeft": 0, "padTop": 0, "verticalCorrection": 0, "kbW": 1000, "kbH": 500,
                          "screenX": 0, "screenY": 1500})
        emu = Frame.of({"padLeft": 0, "padTop": 0, "verticalCorrection": 0, "kbW": 2000, "kbH": 600,
                        "screenX": 10, "screenY": 1000})
        self.assertEqual(emu.place(*phone.normalize(1000, 500)), (2010, 1600))
        self.assertEqual(emu.place(*phone.normalize(500, 250)), (1010, 1300))

    def test_script_expands_history_and_caps_pauses(self):
        from simlib.replay import Frame, script
        lay = {"type": "layout", "viewW": 100, "padLeft": 0, "padTop": 0, "verticalCorrection": 0,
               "kbW": 100, "kbH": 100, "screenX": 0, "screenY": 0}
        ev = [lay,
              {"type": "touch", "action": "ACTION_DOWN", "index": 0, "et": 1000, "ids": [0], "x": [1], "y": [1]},
              {"type": "touch", "action": "ACTION_MOVE", "index": 0, "et": 1020, "ids": [0], "x": [3], "y": [3],
               "ht": [1010], "hx": [2], "hy": [2]},
              {"type": "touch", "action": "ACTION_UP", "index": 0, "et": 1030, "ids": [0], "x": [3], "y": [3]},
              {"type": "touch", "action": "ACTION_DOWN", "index": 0, "et": 9000, "ids": [0], "x": [5], "y": [5]}]
        lines = script(ev, Frame.of(lay))
        self.assertEqual([int(l.split()[0]) for l in lines], [0, 10, 20, 30, 1530])
        self.assertEqual([int(l.split()[1]) for l in lines], [0, 2, 2, 1, 0])

    def test_align_counts_one_mistake_once(self):
        from simlib.score import align
        a = align(["see", "me", "now"], ["seem", "now"])
        self.assertEqual(sum(k == "MATCH" for k, _, _ in a), 1)
        self.assertEqual(a[-1], ("MATCH", "now", "now"))


class LandedTest(unittest.TestCase):
    def test_landed_words(self):
        from simlib.runner import landed_words
        self.assertEqual(landed_words("the ", "the cat "), ["cat"])
        self.assertEqual(landed_words("", "The "), ["The"])
        # The repro: "something" then a new word "g" glued to it.
        self.assertEqual(landed_words("a ", "a somethingg "), ["somethingg"])
        self.assertEqual(landed_words("a ", "a are a "), ["are", "a"])

    def test_no_commit_event_and_reset_flags(self):
        a = Attempt("because", "S+T", [("swipe", "becaus"), ("tap", "e")], 1)
        r = classify(a, [{"type": "session_reset", "reason": "editor is no longer composing", "strokes": 2}],
                     landed=["because"])
        self.assertEqual(r.verdict, "OK")
        self.assertIn("NO_COMMIT_EVENT", r.flags)
        self.assertIn("SESSION_RESET", r.flags)


class CalibrateTest(unittest.TestCase):
    def test_style_names(self):
        from simlib.calibrate import _style_name
        self.assertEqual(_style_name(["S"]), "S")
        self.assertEqual(_style_name(["T", "T", "T"]), "T")
        self.assertEqual(_style_name(["S", "T", "T"]), "S+T")
        self.assertEqual(_style_name(["T", "S"]), "T+S")
        self.assertEqual(_style_name(["S", "S"]), "S+S")
        self.assertEqual(_style_name(["S", "T", "S"]), "mix:STS")

    def test_measure_tap_offset_in_key_units(self):
        from simlib.calibrate import measure
        lay = {"type": "layout", "viewW": 100, "padLeft": 0, "padTop": 0, "verticalCorrection": 0,
               "kbW": 1000, "kbH": 500, "screenX": 0, "screenY": 0,
               "keys": [{"code": 97, "x": 0, "y": 0, "w": 100, "h": 100}]}
        ev = [lay,
              {"type": "touch", "action": "ACTION_DOWN", "index": 0, "et": 0, "ids": [0], "x": [60], "y": [50]},
              {"type": "touch", "action": "ACTION_UP", "index": 0, "et": 70, "ids": [0], "x": [60], "y": [50]},
              {"type": "stroke", "t": 71, "kind": "tap", "code": 97, "strokes": 1},
              {"type": "finalize", "t": 300}]
        m = measure(ev)
        self.assertAlmostEqual(m["taps"]["dx_keys"]["median"], 0.1)
        self.assertEqual(m["taps"]["hold_ms"]["median"], 70)
        self.assertEqual(m["word_styles"], {"T": 1})
