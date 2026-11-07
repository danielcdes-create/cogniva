import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import legacy_classification_preservation as legacy
import inventory_questions
from question_contracts import content_revision

class LegacyAutomaticContractTest(unittest.TestCase):
    def fixture(self):
        before={'_id':'00000000000000000000bb01','question':'Synthetic question','answers':['A','B','C','D','E'],'correct_answers':['B'],'unknown':None}
        member={'question_id':before['_id'],'question_revision':legacy.objective_revision(before),'blocked':False}
        after=copy.deepcopy(before)
        after.update(canonical_subject_id='MAT',canonical_topic_id='MAT_01_001',legacy_auto_classification={
            'schema_version':1,'policy_version':'legacy-618-auto/1','cohort_id':'legacy-618/2026-11-02','status':'AUTO_APPLIED','operation_id':'legacy-auto:'+before['_id'],
            'content_revision':content_revision(legacy.content(before)),'catalog_version':1,
            'links':[dict(subject_id='MAT',topic_id='MAT_01_001',role='PRIMARY',coverage='DIRECT',rationale='Synthetic taxonomy rationale.',confidence=0.95)],
            'provenance':dict(actor={'type':'AI','id':'grok-4.7'},method='AI_AUTOMATIC_LEGACY',model='grok-4.7',prompt_id='classify-question',prompt_version='2',prompt_sha256='a'*64,source_suggestion_id='20000000-0000-4000-8000-000000000001',recorded_at='2026-11-02T12:00:00Z',rationale='Finite policy application, not human review.')})
        return before,after,member

    def test_frozen_cohort_has_exactly_618_distinct_members_and_six_blocked(self):
        c=legacy.cohort();self.assertEqual(len(c),618);self.assertEqual(sum(m['blocked'] for m in c.values()),6)
        self.assertTrue(all(len(m['question_revision'])==64 for m in c.values() if not m['blocked']))
        self.assertTrue(all(m['question_revision'] is None for m in c.values() if m['blocked']))

    def test_automatic_extension_is_distinct_from_human_confirmation(self):
        before,after,member=self.fixture()
        with patch.object(legacy,'cohort',return_value={before['_id']:member}):
            self.assertTrue(legacy.validate_auto(after))
            previous=copy.deepcopy(after)
            previous['legacy_auto_classification']['provenance']['actor']['id']='grok-4.3'
            previous['legacy_auto_classification']['provenance']['model']='grok-4.3'
            self.assertTrue(legacy.validate_auto(previous))
            self.assertEqual(legacy.compare([before],[after])['changed_questions'],1)
            self.assertEqual(inventory_questions.classify_document(after,inventory_questions.load_catalog()),'legacy_auto_applied')
            self.assertNotIn('classification',after)
            invalid=copy.deepcopy(after);invalid['legacy_auto_classification']['provenance']['actor']['type']='HUMAN'
            with self.assertRaises(ValueError):legacy.validate_auto(invalid)

    def test_changed_content_unknown_cohort_and_ambiguous_answers_are_rejected(self):
        before,after,member=self.fixture()
        with self.assertRaises(ValueError):legacy.validate_auto(after)
        with patch.object(legacy,'cohort',return_value={before['_id']:member}):
            for field,value in [('question','Edited body'),('answers',['E','D','C','B','A']),('correct_answers',['A','B'])]:
                invalid=copy.deepcopy(after);invalid[field]=value
                with self.assertRaises(ValueError):legacy.validate_auto(invalid)

    def test_preservation_rejects_deleted_null_extra_fields_and_original_answer_changes(self):
        before,after,member=self.fixture()
        with patch.object(legacy,'cohort',return_value={before['_id']:member}):
            for change in ['unknown','question','answers']:
                invalid=copy.deepcopy(after);invalid.pop(change)
                with self.assertRaises(ValueError):legacy.compare([before],[invalid])
            with self.assertRaises(ValueError):legacy.compare([before,before],[after])
            with self.assertRaises(ValueError):legacy.compare([after],[before])

    def test_low_confidence_unknown_topic_and_stale_catalog_remain_unapplied(self):
        before,after,member=self.fixture()
        with patch.object(legacy,'cohort',return_value={before['_id']:member}):
            for field,value in [('confidence',0.89),('topic_id','UNKNOWN'),('coverage','CONTEXTUAL')]:
                invalid=copy.deepcopy(after);invalid['legacy_auto_classification']['links'][0][field]=value
                with self.assertRaises(ValueError):legacy.validate_auto(invalid)
            invalid=copy.deepcopy(after);invalid['legacy_auto_classification']['catalog_version']=2
            with self.assertRaises(ValueError):legacy.validate_auto(invalid)

if __name__=='__main__':unittest.main()
