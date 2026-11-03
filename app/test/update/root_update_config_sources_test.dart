// See root_update_config_test.dart.

import 'package:dsh_app/update/update_config.dart';
import 'package:flutter_test/flutter_test.dart';

const expectedUpdateRepo = String.fromEnvironment('DSH_UPDATE_REPO', defaultValue: 'ccch1mneyyy/dsh-go');

void main() {
  final testName = expectedUpdateRepo == 'Rakanrkk/dsh-Su'
      ? 'every GitHub API, release and asset URL uses the fork'
      : 'every GitHub API, release and asset URL uses the configured repository';
  test(testName, () {
    expect(updateRepo, expectedUpdateRepo);
    expect(githubLatestApi.toString(), 'https://api.github.com/repos/$expectedUpdateRepo/releases/latest');
    expect(githubReleasesPage.toString(), 'https://github.com/$expectedUpdateRepo/releases/latest');
    expect(
      githubLatestAsset(updateManifestName).toString(),
      'https://github.com/$expectedUpdateRepo/releases/latest/download/update.json',
    );
    expect(
      githubReleaseAsset('v1.0.0', 'dsh-go-1.0.0-arm64.apk').toString(),
      'https://github.com/$expectedUpdateRepo/releases/download/v1.0.0/dsh-go-1.0.0-arm64.apk',
    );
  });
}
