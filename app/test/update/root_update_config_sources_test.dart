// Values compiled with DSH_UPDATE_REPO=Rakanrkk/dsh-Su; see
// root_update_config_test.dart.

import 'package:dsh_app/update/update_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('every GitHub API, release and asset URL uses the fork', () {
    expect(updateRepo, 'Rakanrkk/dsh-Su');
    expect(githubLatestApi.toString(), 'https://api.github.com/repos/Rakanrkk/dsh-Su/releases/latest');
    expect(githubReleasesPage.toString(), 'https://github.com/Rakanrkk/dsh-Su/releases/latest');
    expect(
      githubLatestAsset(updateManifestName).toString(),
      'https://github.com/Rakanrkk/dsh-Su/releases/latest/download/update.json',
    );
    expect(
      githubReleaseAsset('v1.0.0', 'dsh-go-1.0.0-arm64.apk').toString(),
      'https://github.com/Rakanrkk/dsh-Su/releases/download/v1.0.0/dsh-go-1.0.0-arm64.apk',
    );
  });
}
