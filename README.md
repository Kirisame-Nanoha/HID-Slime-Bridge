# HID Slime Bridge

Android端末をUSB Hostとして使用し、USB HIDトラッカーから受信した姿勢・加速度データを、Wi-Fi経由でPC上のSlimeVR ServerへUDP送信するAndroidアプリです。

```text
USB HID Tracker / Receiver
          │ USB OTG
          ▼
      Android端末
  HID Slime Bridge
          │ Wi-Fi / UDP
          ▼
     SlimeVR Server
```

## 主な機能

- USB HIDの入力エンドポイントからレポートを読み取り
- 64バイトのHIDレポートを16バイト単位で解析
- クォータニオンと加速度をSlimeVR UDP形式へ変換して送信
- 複数のUSB HIDデバイスを同時処理（最大32台）
- トラッカーUIDごとのSensor IDを端末内に保存
- トラッカーごとにセンサー装着角度を0°・90°・180°・270°から設定
- 角度設定に応じてクォータニオンと加速度の両方を補正
- フォアグラウンドサービスとWakeLockによるバックグラウンド動作
- USBデバイス、受信値、パケット数、HIDステータスをアプリ画面に表示

## 動作要件

### Android端末

- Android 8.0（API 26）以上
- USB Host / USB OTG対応
- SlimeVR Serverを実行するPCと同じLANへ接続
- USB機器へ必要な電力を供給できること

スマートフォンからの給電が不足する場合は、セルフパワーUSBハブを使用してください。

### 開発環境

- Android Studio
- JDK 17
- Android SDK 35
- Gradle Wrapper同梱

プロジェクト設定は次のとおりです。

| 項目 | 値 |
|---|---:|
| `minSdk` | 26 |
| `targetSdk` | 35 |
| `compileSdk` | 35 |
| Java / Kotlin JVM Target | 17 |
| Application ID | `com.kirisamenanoha.hidslimebridge` |

## 対応USBデバイス

`app/src/main/res/xml/device_filter.xml`には、次のVID/PIDが登録されています。

| 種別 | VID | PID |
|---|---:|---:|
| SlimeVR HID Receiver | `0x1209` | `0x7690` |
| SlimeVR HID Tracker | `0x1209` | `0x7692` |

アプリ起動後のスキャンでは、HIDクラスかつ入力エンドポイントを持つデバイスも候補として検出します。ただし、USB接続時の自動起動対象を追加する場合は`device_filter.xml`へVID/PIDを追記してください。

```xml
<usb-device vendor-id="10進数のVID" product-id="10進数のPID" />
```

XMLでは16進数ではなく10進数で指定します。

## 対応HIDレポート形式

本リポジトリにはトラッカー側ファームウェアは含まれていません。接続するデバイスは、以下の形式でHID Input Reportを送信する必要があります。

- 1レポート: 64バイト
- 1パケット: 16バイト
- 1レポート内に最大4パケット
- 16ビット値: little-endian

### Packet Type 255: Receiver Register

| オフセット | サイズ | 内容 |
|---:|---:|---|
| 0 | 1 | Packet Type = `255` |
| 1 | 1 | Firmware Device ID |
| 2 | 6 | Tracker UID |
| 8–15 | 8 | 予約領域 |

UIDはSensor IDの永続的な割り当てに使用されます。

### Packet Type 0: Device Info

| オフセット | サイズ | 内容 |
|---:|---:|---|
| 0 | 1 | Packet Type = `0` |
| 1 | 1 | Firmware Device ID |
| 2 | 1 | Battery Percent |
| 3–7 | 5 | デバイス情報・予約領域 |
| 8 | 1 | IMU Type |
| 9–15 | 7 | 予約領域 |

### Packet Type 1: Quaternion + Acceleration

| オフセット | サイズ | 内容 |
|---:|---:|---|
| 0 | 1 | Packet Type = `1` |
| 1 | 1 | Firmware Device ID |
| 2–3 | 2 | Quaternion X: signed Q15 |
| 4–5 | 2 | Quaternion Y: signed Q15 |
| 6–7 | 2 | Quaternion Z: signed Q15 |
| 8–9 | 2 | Quaternion W: signed Q15 |
| 10–11 | 2 | Acceleration X: signed Q7 |
| 12–13 | 2 | Acceleration Y: signed Q7 |
| 14–15 | 2 | Acceleration Z: signed Q7 |

クォータニオンは正規化してから送信されます。加速度は`int16 / 128.0`で`Float`へ変換されます。

### Packet Type 3: Status

| オフセット | サイズ | 内容 |
|---:|---:|---|
| 0 | 1 | Packet Type = `3` |
| 1 | 1 | Firmware Device ID |
| 2 | 1 | Status |
| 3 | 1 | 予約領域 |
| 4 | 1 | Received Counter |
| 5 | 1 | Lost Counter |
| 6–15 | 10 | 予約領域 |

## SlimeVR UDP送信

デフォルトの送信先ポートは`6969`です。

アプリは次のSlimeVR UDPパケットを送信します。

| Packet Type | 用途 |
|---:|---|
| 3 | Handshake |
| 15 | Sensor Info |
| 17 | Rotation Data |
| 4 | Acceleration |

Rotation Dataには、補正後のクォータニオンを`X, Y, Z, W`の順で格納します。

## ビルド方法

### Android Studio

1. このリポジトリをクローンまたはZIPで取得します。
2. Android Studioでリポジトリ直下のフォルダを開きます。
3. Gradle Syncが完了するまで待ちます。
4. Android端末で「開発者向けオプション」と「USBデバッグ」を有効にします。
5. 実機を選択して`Run`を実行します。

### コマンドライン

Windows:

```bat
gradlew.bat assembleDebug
```

macOS / Linux:

```bash
./gradlew assembleDebug
```

生成されるデバッグAPK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

APKはリポジトリへコミットせず、配布する場合はGitHub Releasesへ添付してください。

## 使用方法

1. PCでSlimeVR Serverを起動します。
2. Android端末をPCと同じWi-FiまたはLANへ接続します。
3. Android端末へUSB OTGアダプターまたはUSBハブを接続します。
4. HIDトラッカーまたはHIDレシーバーを接続します。
5. アプリを起動し、USBアクセス許可を承認します。
6. `PC IP Address`へSlimeVR Serverを実行しているPCのIPv4アドレスを入力します。
7. `SlimeVR UDP Port`へ通常は`6969`を入力します。
8. `SCAN`を押し、対象デバイスが`READY`または`PERMIT`として表示されることを確認します。
9. `START`を押します。
10. トラッカーが検出されたら、装着方向に合わせて各トラッカーの「センサーの角度」を選択します。
11. SlimeVR Server側でトラッカーが認識されていることを確認します。

停止する場合は`STOP`を押してください。

## センサーの角度

トラッカーごとに次の角度を設定できます。

- 0°
- 90°
- 180°
- 270°

この設定は、センサー基板をトラッカーへ取り付けた物理的な向きを補正するものです。補正はセンサーのローカルZ軸まわりに適用され、クォータニオンと加速度の両方へ反映されます。

設定はAndroid端末の`SharedPreferences`へSensor IDごとに保存され、次回起動時にも復元されます。

## Sensor IDの割り当て

Receiver RegisterパケットにUIDが含まれる場合、アプリはUIDとSensor IDの対応を端末内へ保存します。これにより、USB接続順が変わっても同じUIDへ同じSensor IDを割り当てやすくなります。

UIDを送信しないデバイスでは、USBデバイスの検出順を基準とするフォールバックIDが使用されます。

## プロジェクト構成

```text
HidSlimeBridge/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/kirisamenanoha/hidslimebridge/
│     │  ├─ AxisTransform.kt
│     │  ├─ BridgeForegroundService.kt
│     │  ├─ HidReader.kt
│     │  ├─ MainActivity.kt
│     │  └─ SlimeProtocol.kt
│     └─ res/
│        ├─ values/styles.xml
│        └─ xml/device_filter.xml
├─ gradle/wrapper/
│  ├─ gradle-wrapper.jar
│  └─ gradle-wrapper.properties
├─ .gitattributes
├─ .gitignore
├─ build.gradle.kts
├─ gradle.properties
├─ gradlew
├─ gradlew.bat
├─ settings.gradle.kts
└─ README.md
```

### 各ソースファイル

| ファイル | 役割 |
|---|---|
| `MainActivity.kt` | UI、USB権限要求、接続状態表示、送信先設定、角度設定 |
| `BridgeForegroundService.kt` | バックグラウンド処理、複数HID Reader管理、UDP Sender管理 |
| `HidReader.kt` | HIDレポート読取・16バイトパケット解析 |
| `SlimeProtocol.kt` | SlimeVR UDPパケット生成・送信 |
| `AxisTransform.kt` | センサー装着角度に応じた姿勢・加速度補正 |

## トラブルシューティング

### USBデバイスが表示されない

- Android端末がUSB Host / OTGに対応しているか確認してください。
- OTGアダプターの向きやUSBケーブルを確認してください。
- セルフパワーUSBハブを試してください。
- デバイスがHIDクラスのInput Endpointを公開しているか確認してください。
- 自動起動対象にする場合は`device_filter.xml`へVID/PIDを追加してください。

### `NO-PERM`と表示される

`START`を押してUSBアクセス許可を承認してください。許可ダイアログを拒否した場合は、USBデバイスを抜き差しして再試行してください。

### SlimeVR Serverに表示されない

- Android端末とPCが同一ネットワーク上にあるか確認してください。
- PCのIPv4アドレスが正しいか確認してください。
- UDPポートが通常の`6969`になっているか確認してください。
- Windows Defender FirewallなどでSlimeVR ServerのUDP通信が許可されているか確認してください。
- ゲストWi-FiやAP isolationにより端末間通信が遮断されていないか確認してください。

### 回転方向が合わない

まずアプリ内の「センサーの角度」を0°、90°、180°、270°で切り替えて確認してください。それでも合わない場合は、トラッカーファームウェアが出力する軸・符号と`HidReader.kt`の読み取り仕様が一致しているか確認してください。

### センサーIDが意図と異なる

UIDを含むPacket Type 255を送信してください。UIDがない場合はUSB検出順に依存します。

## 既知の注意点

- 本リポジトリには対応トラッカーファームウェアは含まれていません。
- HIDレポート形式が異なる機器では`HidReader.kt`の変更が必要です。
- `SlimeVRSender`のデフォルトMAC文字列は固定値です。複数のAndroid端末から同じSlimeVR Serverへ同時接続する場合は、端末ごとに一意の値を使用する実装へ変更してください。
- リリース署名設定は含まれていません。配布用APK/AABを作る場合は、自分のkeystoreをローカル環境で設定し、keystoreをGitへコミットしないでください。

## プライバシーと通信

このアプリは、ユーザーが指定したIPアドレスとUDPポートへ、トラッカーの姿勢・加速度データをLAN内で送信します。インターネット上の外部サーバーへデータを送信する機能は実装していません。

## ライセンス

公開前に`LICENSE`ファイルを追加してください。すべて自作コードで、第三者コードの利用条件に問題がない場合はMIT LicenseやApache License 2.0などを選択できます。既存コードを移植・参考利用している場合は、元プロジェクトのライセンスと表示義務を必ず確認してください。
