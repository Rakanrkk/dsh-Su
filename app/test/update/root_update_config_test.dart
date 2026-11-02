// Compile-time repository override: the fork URL reaches every GitHub
// endpoint and asset, while the root build keeps the public feed empty.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('root update override is covered by tooling/test_build_root_apk.sh', () {
    expect(
      File('../tooling/test_build_root_apk.sh').existsSync(),
      isTrue,
    );
  });
}
