#!/bin/bash
# 中身は空。wave（xyz.mooma0750.wave）とウィジェットの配布設定を Xcode に作らせるためだけのプロジェクト
# GUI のセッションで、wave とウィジェットの配布用の設定（provisioning profile）を Xcode に作らせる
cd "$(dirname "$0")"
xcodebuild -project Prov.xcodeproj -scheme wave -configuration Debug -destination 'generic/platform=iOS' -allowProvisioningUpdates -allowProvisioningDeviceRegistration build > prov.log 2>&1
echo "exit=$?" >> prov.log
