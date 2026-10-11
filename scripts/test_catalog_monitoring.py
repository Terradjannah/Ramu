import copy
import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import check_huggingface as monitor
import propose_catalog_update as proposal
from classify_catalog_update import model_risk
from validate_catalog import ROOT
from validate_catalog_evidence import CONTRACT, IDENTITY, validate_evidence


class CatalogMonitoringTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / 'catalog', self.root / 'catalog')
        self.output = self.root / 'candidate'
        self.registry_path = self.root / 'catalog/registry/models.json'
        self.registry = json.loads(self.registry_path.read_text())
        for other in self.registry['models'][1:]:
            other.update(enabled=False, trackUpdates=False)
        row = self.registry['models'][0]
        row.update(enabled=True, trackUpdates=True)
        row['updatePolicy'].update(metadataAutoPublish=True, unchangedArtifactAutoPublish=True)
        monitor.write_json(self.registry_path, self.registry)
        self.path = self.root / 'catalog/production/generations/gemma-3.json'
        self.generation = json.loads(self.path.read_text(encoding='utf-8'))
        self.model = self.generation['models'][0]
        self.model.update(status='available', name='Fixture', description='Fixture description')
        self.generation['status'] = 'available'
        ref = 'catalog/evidence/gemma-3n-e2b-cpu.json'
        self.variant = {
            'variantId': 'gemma-3n-e2b-cpu', 'artifactVersion': 1, 'format': 'litertlm',
            'runtime': {'minVersion': '0.16.1', 'maxVersion': '0.16.1'},
            'testedBackends': ['cpu'], 'minApi': 29, 'abis': ['arm64-v8a'],
            'minRamBytes': 100, 'recommendedRamBytes': 200, 'minStorageBytes': 300,
            'hardware': {'socIds': [], 'driverIds': []},
            'repoId': row['allowedArtifacts'][0]['repoId'], 'revision': 'a' * 40,
            'path': row['allowedArtifacts'][0]['path'], 'sizeBytes': 300, 'sha256': 'b' * 64,
            'evidence': {'artifactSha256Verified': True, 'artifactSource': 'local fixture',
                         'license': 'gemma', 'gated': True, 'runtimeTest': ref, 'backendTests': {'cpu': ref}}
        }
        self.model['variants'] = [self.variant]
        self.record = {
            'schemaVersion': 1,
            'artifact': dict({k: self.variant[k] for k in IDENTITY}, verifiedRevision='a' * 40,
                             bytesVerified=True, formatVerified=True),
            'contract': {k: self.variant[k] for k in CONTRACT}, 'license': 'gemma', 'gated': True, 'accessMode': 'manual',
            'tests': [{'device': 'fixture-device', 'apkSha256': 'c' * 64, 'log': 'fixture://inference',
                       'testedAt': '2026-10-09', 'load': 'pass', 'inference': 'pass', 'runtime': '0.16.1',
                       'backend': 'cpu', 'abi': 'arm64-v8a', 'api': 29}]
        }
        self.evidence = self.root / ref
        monitor.write_json(self.evidence, self.record)
        self.write_catalog()
        self.old = {'id': self.variant['repoId'], 'sha': 'a' * 40, 'gated': 'manual', 'private': False,
                    'cardData': {'license': 'gemma', 'model_name': 'Fixture', 'description': 'Fixture description'},
                    'siblings': [{'rfilename': self.variant['path'], 'size': 300,
                                  'lfs': {'sha256': 'b' * 64, 'size': 300}}]}
        self.new = copy.deepcopy(self.old)

    def write_catalog(self):
        monitor.write_json(self.path, self.generation)
        index_path = self.root / 'catalog/production/index.json'
        index = json.loads(index_path.read_text())
        data = self.path.read_bytes()
        index['generations'][0].update(sizeBytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        monitor.write_json(index_path, index)

    def fetch(self, repo, revision=None):
        self.assertEqual(self.variant['repoId'], repo)
        return copy.deepcopy(self.old if revision == 'a' * 40 and self.new['sha'] != 'a' * 40 else self.new)

    def prepare(self):
        return proposal.prepare(self.root, self.output, self.fetch)

    def test_disabled_or_untracked_never_contacts_hf(self):
        for field in ('enabled', 'trackUpdates'):
            registry = copy.deepcopy(self.registry)
            registry['models'][0][field] = False
            monitor.write_json(self.registry_path, registry)
            fetch = Mock(side_effect=AssertionError('network must not be used'))
            self.assertEqual([], monitor.observe(self.registry_path, fetch)['observations'])
            fetch.assert_not_called()

    def test_monitor_is_read_only_and_only_curated_paths(self):
        before = {p: p.read_bytes() for p in (self.root / 'catalog').rglob('*.json')}
        self.new['siblings'].append({'rfilename': 'untrusted.py', 'size': 5})
        result = monitor.observe(self.registry_path, self.fetch)
        self.assertEqual(1, len(result['observations']))
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        self.assertFalse(any(self.root.rglob('*.sig')))

    def test_unlisted_model_variant_is_reported_for_review_only(self):
        self.new['siblings'].append({'rfilename': 'new-gpu.litertlm', 'size': 500,
                                     'lfs': {'sha256': 'd' * 64, 'size': 500}})
        observed = monitor.observe(self.registry_path, self.fetch)
        self.assertEqual('new-gpu.litertlm', observed['unlistedArtifacts'][0]['path'])
        result = self.prepare()
        self.assertEqual('review', result['risk'])
        self.assertFalse(result['autoPublish'])
        self.assertEqual([self.variant], result['candidate']['gemma-3']['models'][0]['variants'])

    def test_missing_hash_size_path_and_revision_fail_closed(self):
        cases = []
        for key in ('sha256', 'size'):
            value = copy.deepcopy(self.old)
            del value['siblings'][0]['lfs'][key]
            cases.append(value)
        cases.extend([dict(self.old, sha='main'), dict(self.old, siblings=[]), dict(self.old, gated=None)])
        for value in cases:
            with self.subTest(value=value), self.assertRaises(ValueError):
                monitor.artifact_info(value, self.variant['repoId'], self.variant['path'])

    def test_fetch_failure_does_not_write_candidate(self):
        with self.assertRaises(OSError):
            proposal.prepare(self.root, self.output, Mock(side_effect=OSError('401 or 429')))
        self.assertFalse(self.output.exists())

    def test_four_risk_classes(self):
        cases = [('metadata', lambda: self.new['cardData'].update(model_name='Updated fixture'), True),
                 ('same-artifact', lambda: self.new.update(sha='d' * 40), True),
                 ('artifact-changed', lambda: self.new['siblings'][0]['lfs'].update(sha256='e' * 64), False),
                 ('review', lambda: self.new['cardData'].update(license='different-license'), False)]
        for risk, mutate, auto in cases:
            with self.subTest(risk=risk):
                self.new = copy.deepcopy(self.old)
                # Access/license comparisons use distinct old and new pinned commits.
                if risk in ('artifact-changed', 'review'):
                    self.new['sha'] = 'd' * 40
                mutate()
                if self.output.exists():
                    shutil.rmtree(self.output)
                result = self.prepare()
                self.assertEqual(risk, result['risk'])
                self.assertEqual(auto, result['autoPublish'])
                self.assertEqual(auto, (self.output / 'production').exists())
                self.assertTrue((self.output / 'staging/report.json').exists())

    def test_same_content_keeps_local_identity_and_evidence(self):
        self.new['sha'] = 'd' * 40
        self.prepare()
        candidate = json.loads((self.output / 'production/generations/gemma-3.json').read_text())['models'][0]['variants'][0]
        self.assertEqual(dict(self.variant, revision='d' * 40), candidate)

    def test_new_variant_never_added_automatically(self):
        self.model.update(status='unavailable', variants=[])
        self.generation['status'] = 'unavailable'
        self.write_catalog()
        result = self.prepare()
        self.assertEqual('review', result['risk'])
        self.assertFalse(result['autoPublish'])
        self.assertEqual([], result['candidate']['gemma-3']['models'][0]['variants'])
        self.assertEqual({'revision': 'c03b6f60b8da6c5400b6838a2cf26420f80c0a01', 'sizeBytes': 3655827456,
                          'sha256': '2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6'},
                         result['changes'][0]['before'])
        self.assertEqual({'revision': 'a' * 40, 'sizeBytes': 300, 'sha256': 'b' * 64},
                         result['changes'][0]['after'])

    def test_missing_failed_or_stale_evidence_blocks_promotion(self):
        for mutate in (lambda r: r['artifact'].update(sha256='f' * 64),
                       lambda r: r['artifact'].update(bytesVerified=False),
                       lambda r: r['tests'][0].update(inference='fail'),
                       lambda r: r.update(tests=[]),
                       lambda r: r['contract'].update(testedBackends=['npu'])):
            record = copy.deepcopy(self.record)
            mutate(record)
            monitor.write_json(self.evidence, record)
            with self.assertRaises(ValueError):
                validate_evidence(self.root / 'catalog/production', self.root / 'catalog/evidence')
        self.evidence.unlink()
        with self.assertRaises(ValueError):
            self.prepare()

    def test_mixed_change_respects_both_policy_switches(self):
        updated = copy.deepcopy(self.model)
        updated['name'] = 'New'
        updated['variants'][0]['revision'] = 'd' * 40
        for metadata, artifact in ((False, True), (True, False), (False, False)):
            risk, auto = model_risk(self.model, updated, {'metadataAutoPublish': metadata,
                                                         'unchangedArtifactAutoPublish': artifact})
            self.assertEqual('same-artifact', risk)
            self.assertFalse(auto)

    def test_staging_and_promotion_are_separate(self):
        self.new['sha'] = 'd' * 40
        self.prepare()
        api = Mock(return_value={'object': {'sha': 'base'}})
        with patch.object(proposal, 'create_pr', return_value={'html_url': 'https://github.com/o/r/pull/1'}) as create:
            proposal.publish(self.root, self.output, 'o/r', 'base', api)
        self.assertEqual(2, create.call_count)
        staging, promotion = create.call_args_list
        self.assertTrue(all(p.startswith('catalog/staging/') for p in staging.args[3]))
        self.assertTrue(all(p.startswith('catalog/production/') for p in promotion.args[3]))
        self.assertTrue(promotion.kwargs['auto'])

    def test_high_risk_only_opens_staging(self):
        self.new['sha'] = 'd' * 40
        self.new['siblings'][0]['lfs']['sha256'] = 'e' * 64
        self.prepare()
        with patch.object(proposal, 'create_pr', return_value={}) as create:
            proposal.publish(self.root, self.output, 'o/r', 'base', Mock(return_value={'object': {'sha': 'base'}}))
        self.assertEqual(1, create.call_count)
        self.assertNotIn('auto', create.call_args.kwargs)

    def test_stale_main_refuses_repository_writes(self):
        self.new['sha'] = 'd' * 40
        self.prepare()
        with patch.object(proposal, 'create_pr') as create, self.assertRaisesRegex(ValueError, 'main advanced'):
            proposal.publish(self.root, self.output, 'o/r', 'base', Mock(return_value={'object': {'sha': 'new'}}))
        create.assert_not_called()

    def test_existing_proposal_never_overwritten(self):
        api = Mock(return_value=[{'number': 5}])
        self.assertEqual({'number': 5}, proposal.create_pr('o/r', 'base', 'branch', {}, 'title', 'body', api=api))
        self.assertEqual(1, api.call_count)

    def test_access_mode_change_requires_review_even_if_historical_api_changed(self):
        self.old['gated'] = self.new['gated'] = 'auto'
        result = self.prepare()
        self.assertEqual('review', result['risk'])
        self.assertFalse(result['autoPublish'])

    def test_redirect_is_refused_before_forwarding_token(self):
        with self.assertRaisesRegex(ValueError, 'redirect refused'):
            monitor.NoRedirect().redirect_request(None, None, 302, None, None, 'https://elsewhere.invalid')

    def test_create_pr_requests_auto_merge_only_when_eligible(self):
        for eligible in (False, True):
            responses = [[], {'tree': {'sha': 'base-tree'}}, {'sha': 'tree'}, {'sha': 'commit'},
                         {}, {'node_id': 'pr-id', 'html_url': 'url'}]
            if eligible:
                responses.append({})
            api = Mock(side_effect=responses)
            proposal.create_pr('o/r', 'base', 'catalog/branch', {'catalog/production/index.json': '{}'},
                               'title', 'body', auto=eligible, api=api)
            self.assertEqual(7 if eligible else 6, api.call_count)
            if eligible:
                self.assertEqual('graphql', api.call_args.args[0])
                self.assertIn('enablePullRequestAutoMerge', api.call_args.args[1]['query'])

    def test_owner_approval_must_bind_current_head(self):
        import check_catalog_pr as gate
        pr = {'head': {'sha': 'head'}, 'user': {'login': 'bot'}}
        review = {'user': {'login': 'owner'}, 'state': 'APPROVED', 'commit_id': 'head'}
        for approved, change in ((True, {}), (False, {'commit_id': 'old'}), (False, {'state': 'DISMISSED'}),
                                 (False, {'user': {'login': 'stranger'}}), (False, {'user': {'login': 'bot'}})):
            with patch.object(gate, 'api_json', side_effect=[pr, [dict(review, **change)]]):
                self.assertEqual(approved, gate.owner_approved('o/r', '1', 'head', {'owner', 'bot'}, 'token'))

    def test_changed_artifact_cannot_reuse_protected_evidence(self):
        from check_catalog_pr import check_evidence_reuse
        trusted = self.root / 'trusted'
        shutil.copytree(self.root / 'catalog', trusted / 'catalog')
        self.variant.update(revision='d' * 40, sha256='e' * 64)
        self.write_catalog()
        with self.assertRaisesRegex(ValueError, 'exact commit'):
            check_evidence_reuse(self.root, trusted)

    def test_new_record_cannot_claim_an_unrelated_commit(self):
        from check_catalog_pr import check_evidence_reuse
        trusted = self.root / 'trusted'
        shutil.copytree(self.root / 'catalog', trusted / 'catalog')
        self.variant['revision'] = 'd' * 40
        self.write_catalog()
        self.record['artifact']['verifiedRevision'] = 'e' * 40
        monitor.write_json(self.evidence, self.record)
        with self.assertRaisesRegex(ValueError, 'retain protected evidence'):
            check_evidence_reuse(self.root, trusted)

    def test_low_risk_candidate_builds_verified_pages(self):
        from cryptography.hazmat.primitives.asymmetric import ec
        from build_catalog_site import build
        from sign_catalog import verify
        self.new['cardData']['model_name'] = 'Updated fixture'
        self.prepare()
        # Production sidecar records remain developer-owned and are not in Pages.
        shutil.copytree(self.root / 'catalog/evidence', self.output / 'evidence')
        site = self.root / 'pages'
        key = ec.generate_private_key(ec.SECP256R1())
        build(self.output / 'production', self.registry_path, self.root / 'catalog/schema/v1', site, key)
        catalog = site / 'catalog/v1'
        self.assertTrue(verify((catalog / 'index.json').read_bytes(), (catalog / 'index.sig').read_bytes(), key.public_key()))
        self.assertFalse((catalog / 'evidence').exists())

    def test_real_git_classifier_and_signed_payload_use_same_candidate(self):
        import classify_catalog_update as classifier

        def git(*args):
            return subprocess.run(['git', '-C', str(self.root), *args], check=True,
                                  capture_output=True, text=True).stdout

        git('init', '-q')
        git('add', 'catalog')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
            'commit', '-qm', 'fixture baseline')
        base = git('rev-parse', 'HEAD').strip()
        self.new['sha'] = 'd' * 40
        self.new['cardData']['model_name'] = 'Updated fixture'
        report = self.prepare()
        shutil.copytree(self.output / 'production', self.root / 'catalog/production', dirs_exist_ok=True)
        git('add', 'catalog/production')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
            'commit', '-qm', 'fixture candidate')
        with patch.object(classifier, 'git', side_effect=git):
            result = classifier.classify(base, 'HEAD')
        self.assertEqual(report['risk'], result['risk'])
        self.assertTrue(result['autoPublish'])
        self.assertEqual(2, result['candidateVersion'])
        self.registry['models'][0]['updatePolicy']['metadataAutoPublish'] = False
        monitor.write_json(self.registry_path, self.registry)
        git('add', 'catalog/registry')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
            'commit', '-qm', 'fixture policy change')
        with patch.object(classifier, 'git', side_effect=git):
            result = classifier.classify(base, 'HEAD')
        self.assertEqual('review', result['risk'])
        self.assertFalse(result['autoPublish'])


if __name__ == '__main__':
    unittest.main()
