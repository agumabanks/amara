import unittest
from collect_five_hours import summarize
class EvaluationTest(unittest.TestCase):
    def test_missing_outcome_is_not_a_success_and_retries_do_not_duplicate(self):
        rows=[{'event':'offered','key':'a','at':1000,'fields':{'kind':'WA_REPLY_INBOUND','observed_at':1000}},
              {'event':'accepted','key':'a','at':1000},
              {'event':'offered','key':'b','at':1000,'fields':{'kind':'WA_REPLY_INBOUND','observed_at':1000}},
              {'event':'accepted','key':'b','at':1000},
              {'event':'outcome','key':'b','at':2000,'fields':{'status':'FAILED'}},
              {'event':'outcome','key':'b','at':4000,'fields':{'status':'DONE'}}]
        r=summarize(rows,10000,5000)
        self.assertEqual(r['accepted_inbound'],2)
        self.assertEqual(r['inbound_not_verified'],1)
        self.assertEqual(r['verified_reply_p95_seconds'],3)
        self.assertFalse(r['evaluation_complete'])
    def test_external_attempt_is_not_a_verified_post(self):
        r=summarize([{'event':'external_effect','key':'x','fields':{'status':'ACTING','capability':'tiktok'}}],10,20)
        self.assertEqual(r['unresolved_external_effects'],1)
        self.assertTrue(r['evaluation_complete'])
if __name__=='__main__':unittest.main()
