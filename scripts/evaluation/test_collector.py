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
        self.assertEqual(r['inbound_without_completed_outcome'],1)
        self.assertIsNone(r['inbound_not_verified'])
        self.assertEqual(r['completed_inbound_p95_seconds'],3)
        self.assertIsNone(r['verified_reply_p95_seconds'])
        self.assertEqual(r['verified_reply_effects'],0)
        self.assertFalse(r['evaluation_complete'])
    def test_external_attempt_is_not_a_verified_post(self):
        r=summarize([{'event':'external_effect','key':'x','fields':{'status':'ACTING','capability':'tiktok'}}],10,20)
        self.assertEqual(r['unresolved_external_effects'],1)
        self.assertTrue(r['evaluation_complete'])
    def test_notifications_and_replayed_receipts_do_not_inflate_customer_replies(self):
        rows=[{'event':'external_effect','key':key,'fields':{'status':'VERIFIED','capability':cap}}
              for key,cap in [('a','reply_whatsapp'),('a','reply_whatsapp'),('b','notify_owner_whatsapp')]]
        result=summarize(rows,10,20)
        self.assertEqual(result['verified_reply_effects'],1)
        self.assertIsNone(result['verified_reply_outcomes'])
    def test_gaps_and_build_changes_remain_visible(self):
        rows=[{'at':1000,'build_segment':'v6'}, {'at':601000,'build_segment':'v7'}]
        result=summarize(rows,900000,700000)
        self.assertEqual(result['largest_phone_event_gap_seconds'],600)
        self.assertEqual(result['build_segments'],{'v6':1,'v7':1})
        self.assertFalse(result['evaluation_complete'])
if __name__=='__main__':unittest.main()
