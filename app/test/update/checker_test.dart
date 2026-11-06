import 'dart:convert';
import 'package:dsh_app/update/checker.dart';
import 'package:dsh_app/update/update_config.dart';
import 'package:dsh_app/update/version.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support.dart';

const _mirrors = [
  UpdateMirror.github,
  UpdateMirror(id: 'm1', label: 'mirror-one.example', prefix: 'https://mirror-one.example/'),
  UpdateMirror(id: 'm2', label: 'mirror-two.example', prefix: 'https://mirror-two.example/'),
];

const _api = 'https://api.github.com/repos/ccch1mneyyy/dsh-go/releases/latest';
const _manifest = 'https://github.com/ccch1mneyyy/dsh-go/releases/latest/download/update.json';
const _m1 = 'https://mirror-one.example/$_manifest';
const _m2 = 'https://mirror-two.example/$_manifest';
const _feed = 'https://update.example.com/dsh/update.json';

void main() {
  group('source order', () {
    const sources = UpdateSources(mirrors: _mirrors);

    test('metadata: update.json through GitHub and each mirror, then the API', () {
      expect(sources.metadataEndpoints().map((e) => e.url.toString()), [_manifest, _m1, _m2, _api]);
      expect(sources.metadataEndpoints().map((e) => e.id), ['manifest:github', 'manifest:m1', 'manifest:m2', 'api']);
    });

    test('a self-hosted feed goes first', () {
      final withFeed = UpdateSources(feed: Uri.parse(_feed), mirrors: _mirrors);
      expect(withFeed.metadataEndpoints().first.url.toString(), _feed);
      expect(withFeed.metadataEndpoints(), hasLength(5));
    });

    test('whatever answered last time is tried first, the rest keep their order; the API stays last', () {
      expect(sources.metadataEndpoints(preferred: 'manifest:m2').map((e) => e.id), [
        'manifest:m2',
        'manifest:github',
        'manifest:m1',
        'api',
      ]);
      expect(sources.metadataEndpoints(preferred: 'gone').map((e) => e.id).first, 'manifest:github');
      expect(
        sources.metadataEndpoints(preferred: 'api').map((e) => e.id),
        sources.metadataEndpoints().map((e) => e.id),
      );
    });

    test('downloads: each GitHub URL through every mirror, preferred mirror first, others as they are', () {
      final asset = Uri.parse('https://github.com/ccch1mneyyy/dsh-go/releases/download/v1.2.0/a.apk');
      final cdn = Uri.parse('https://cdn.example.com/a.apk');
      final urls = sources.downloadUrls([cdn, asset, asset], preferredMirror: 'm1');
      expect(urls.map((u) => u.url.toString()), [
        'https://cdn.example.com/a.apk',
        'https://mirror-one.example/$asset',
        '$asset',
        'https://mirror-two.example/$asset',
      ]);
      expect(urls.map((u) => u.mirrorId), [null, 'm1', 'github', 'm2']);
      expect(urls[1].label, 'mirror-one.example');
    });
  });

  group('checker', () {
    test('GitHub update.json unreachable → first mirror answers; the API is not asked', () async {
      final http = FakeHttp({
        _api: (_, _) => throw StateError('must not get this far'),
        _m1: (_, _) => textResponse(fixture('update.json')),
        _m2: (_, _) => throw StateError('must not get this far'),
      });
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check();
      expect(http.urls, [_manifest, _m1]);
      expect(result, isA<CheckFound>());
      final found = result as CheckFound;
      expect(found.release.version.toString(), '1.2.0+7');
      expect(found.endpoint.id, 'manifest:m1');
      expect(found.endpoint.mirrorId, 'm1');
    });

    test('the API, once every update.json failed: GitHub headers are sent', () async {
      final http = FakeHttp({_api: (_, _) => textResponse(fixture('github_release.json'))});
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check();
      expect(result, isA<CheckFound>());
      expect(http.urls, [_manifest, _m1, _m2, _api]);
      expect(http.requests.last.$2['accept'], 'application/vnd.github+json');
      expect(http.requests.first.$2['accept'], 'application/json');
    });

    test('API rate limited (403 with the limit spent) after every update.json failed', () async {
      final http = FakeHttp({
        _api: (_, _) =>
            textResponse('{"message":"API rate limit exceeded"}', status: 403, headers: {'x-ratelimit-remaining': '0'}),
      });
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check();
      expect((result as CheckFailed).failure, CheckFailure.rateLimited);
      expect(result.attempts.map((a) => a.$1), ['manifest:github', 'manifest:m1', 'manifest:m2', 'api']);
    });

    test('starts with the preferred endpoint', () async {
      final http = FakeHttp({_m2: (_, _) => textResponse(fixture('update.json'))});
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check(preferred: 'manifest:m2');
      expect(http.urls, [_m2]);
      expect((result as CheckFound).endpoint.id, 'manifest:m2');
    });

    test('a source stuck on an older release does not hide a newer one', () async {
      // The beta channel still says 1.0.4 while GitHub has 1.2.0 (the shape of
      // the 1.0.4 "you are up to date" reports).
      const stale = '{"schema":1,"version":"1.0.4+10310405","tag":"v1.0.4",'
          '"assets":{"arm64":{"name":"dsh-go-1.0.4-arm64.apk","size":14,'
          '"sha256":"3a8498bcf7d895bac5c9f2f02829c9ec7064cd8bc7ef9f1792f28277d44839f2"}}}';
      final http = FakeHttp({
        _m1: (_, _) => textResponse(stale),
        _api: (_, _) => textResponse(fixture('github_release.json')),
      });
      final result = await UpdateChecker(http: http, sources: const UpdateSources(mirrors: _mirrors)).check(
        preferred: 'manifest:m1',
        installed: AppVersion.tryParse('1.0.4+10310405'),
      );
      expect(result, isA<CheckFound>());
      expect((result as CheckFound).release.version.toString(), startsWith('1.2.0'));
      expect(http.urls.first, _m1, reason: 'the preferred source is still asked first');
      expect(http.urls, contains(_api));
    });

    test('nothing newer anywhere: the newest answer is returned, not a failure', () async {
      const stale = '{"schema":1,"version":"1.0.4+10310405","tag":"v1.0.4",'
          '"assets":{"arm64":{"name":"dsh-go-1.0.4-arm64.apk","size":14,'
          '"sha256":"3a8498bcf7d895bac5c9f2f02829c9ec7064cd8bc7ef9f1792f28277d44839f2"}}}';
      final http = FakeHttp({
        _api: (_, _) => textResponse(stale),
        _manifest: (_, _) => textResponse(stale),
      });
      final result = await UpdateChecker(http: http, sources: const UpdateSources()).check(
        installed: AppVersion.tryParse('1.0.4+10310405'),
      );
      expect(result, isA<CheckFound>());
      expect((result as CheckFound).release.version.toString(), '1.0.4+10310405');
    });

    test('a mirror answering 200 with an HTML page is skipped', () async {
      final http = FakeHttp({
        _api: (_, _) => textResponse('', status: 502),
        _manifest: (_, _) => textResponse('<html>blocked</html>'),
        _m1: (_, _) => textResponse(fixture('update.json')),
      });
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check();
      expect((result as CheckFound).endpoint.id, 'manifest:m1');
    });

    test('no release yet / private repository: 404 everywhere', () async {
      final notFound = textResponse('{"message":"Not Found"}', status: 404);
      final http = FakeHttp({_api: (_, _) => notFound, _manifest: (_, _) => notFound});
      final result = await UpdateChecker(http: http, sources: const UpdateSources()).check();
      expect(result, isA<CheckFailed>());
      expect((result as CheckFailed).failure, CheckFailure.noRelease);
      expect(result.attempts.map((a) => a.$1), ['manifest:github', 'api']);
    });

    test('rate limiting is reported over network errors', () async {
      final http = FakeHttp({_api: (_, _) => textResponse('', status: 429)});
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check();
      expect((result as CheckFailed).failure, CheckFailure.rateLimited);
      expect(http.urls, [_manifest, _m1, _m2, _api]);
    });

    test('offline: every endpoint unreachable', () async {
      final http = FakeHttp();
      final result = await UpdateChecker(
        http: http,
        sources: const UpdateSources(mirrors: _mirrors),
      ).check();
      expect((result as CheckFailed).failure, CheckFailure.network);
      expect(http.urls, hasLength(4));
    });

    test('a 403 without the rate-limit header is not called rate limiting', () async {
      final http = FakeHttp({_api: (_, _) => textResponse('{"message":"Forbidden"}', status: 403)});
      final result = await UpdateChecker(http: http, sources: const UpdateSources()).check();
      expect((result as CheckFailed).failure, CheckFailure.badResponse);
    });
  group('fork prerelease discovery', () {
    const sources = UpdateSources(mirrors: _mirrors, releasesList: true);
    const listApi = 'https://api.github.com/repos/ccch1mneyyy/dsh-go/releases';
    String sha(String c) => 'sha256:${c * 64}';
    Map<String, Object> entry(String tag, {bool draft = false, required String digest, required String file}) => {
      'tag_name': tag,
      'draft': draft,
      'assets': [
        {
          'name': file,
          'browser_download_url': 'https://github.com/ccch1mneyyy/dsh-go/releases/download/$tag/$file',
          'size': 14,
          'digest': digest,
        },
      ],
    };
    final releases = [
      entry('v1.2.1', digest: sha('1'), file: 'dsh-go-1.2.1-arm64.apk'),
      entry('v1.2.2-dev.1', digest: sha('2'), file: 'dsh-go-1.2.2-dev.1-arm64.apk'),
      entry('v1.2.3', draft: true, digest: sha('3'), file: 'dsh-go-1.2.3-arm64.apk'),
    ];

    test('the releases list is asked first, before update.json and the API', () {
      expect(sources.metadataEndpoints().map((e) => e.id), [
        'api:releases',
        'manifest:github',
        'manifest:m1',
        'manifest:m2',
        'api',
      ]);
    });

    test('a prerelease newer than stable is found through the list; drafts do not shadow it', () async {
      final http = FakeHttp({
        listApi: (_, _) => textResponse(jsonEncode([...releases, {'tag_name': 'roadmap', 'draft': false, 'assets': []}])),
        _api: (_, _) => throw StateError('must not get this far'),
        _manifest: (_, _) => throw StateError('must not get this far'),
      });
      final result = await UpdateChecker(http: http, sources: sources).check(installed: AppVersion.tryParse('1.2.0+7'));
      expect(result, isA<CheckFound>());
      expect((result as CheckFound).release.version.toString(), '1.2.2-dev.1');
      expect(http.urls, [listApi]);
      expect(http.requests.single.$2['accept'], 'application/vnd.github+json');
    });

    test('a stable release newer than the newest prerelease wins the list', () async {
      final stableLast = [
        releases[1],
        entry('v1.2.2', digest: sha('4'), file: 'dsh-go-1.2.2-arm64.apk'),
        {'tag_name': 'roadmap', 'draft': false, 'assets': []},
      ];
      final http = FakeHttp({listApi: (_, _) => textResponse(jsonEncode(stableLast))});
      final result = await UpdateChecker(http: http, sources: sources).check(installed: AppVersion.tryParse('1.2.1'));
      expect((result as CheckFound).release.version.toString(), '1.2.2');
    });

    test('a stale list does not hide a newer release from the manifest', () async {
      final oldList = [entry('v1.0.9', digest: sha('9'), file: 'dsh-go-1.0.9-arm64.apk')];
      final http = FakeHttp({
        listApi: (_, _) => textResponse(jsonEncode(oldList)),
        _manifest: (_, _) => textResponse(fixture('update.json')),
      });
      final result = await UpdateChecker(http: http, sources: sources).check(installed: AppVersion.tryParse('1.1.0'));
      expect(result, isA<CheckFound>());
      expect((result as CheckFound).release.version.toString(), '1.2.0+7');
      expect(http.urls, [listApi, _manifest]);
    });
  });
  });
}
