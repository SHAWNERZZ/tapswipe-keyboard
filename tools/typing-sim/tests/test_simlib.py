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
