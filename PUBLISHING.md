# GitHub公開手順

## 1. 公開するファイル

以下をGitHubリポジトリへコミットします。

```text
.gitattributes
.gitignore
README.md
PUBLISHING.md
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/kirisamenanoha/hidslimebridge/*.kt
app/src/main/res/values/styles.xml
app/src/main/res/xml/device_filter.xml
build.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
settings.gradle.kts
```

`gradle-wrapper.jar`はビルド再現に必要なので公開対象です。

## 2. 公開しないファイル・フォルダ

以下はローカル環境固有、ビルド生成物、認証情報のためコミットしません。

```text
.gradle/
.idea/
.kotlin/
**/build/
local.properties
*.iml
*.apk
*.aab
*.jks
*.keystore
keystore.properties
signing.properties
captures/
.externalNativeBuild/
.cxx/
```

元のZIPに含まれていた次の項目は削除対象です。

```text
.gradle/
app/build/
build/
local.properties
```

特に`local.properties`には、開発PC上のAndroid SDKパスが記録されています。

## 3. 公開前の確認

1. `LICENSE`を選び、リポジトリ直下へ追加します。
2. READMEの内容と実際のHIDファームウェア仕様が一致するか確認します。
3. ソース内にAPIキー、パスワード、トークン、個人用IP、秘密鍵がないか確認します。
4. 固定MAC文字列を複数端末運用でどう扱うか決定します。
5. クリーンな環境でデバッグビルドを確認します。
6. 配布用APKを出す場合はリリース署名し、GitHub Releasesへ添付します。

## 4. GitHubへ初回push

リポジトリ直下で実行します。

```bash
git init
git add .
git status
git commit -m "Initial public release"
git branch -M main
git remote add origin https://github.com/OWNER/HidSlimeBridge.git
git push -u origin main
```

`OWNER`はGitHubユーザー名またはOrganization名へ置き換えてください。

GitHub上で先に空のリポジトリを作成する場合、READMEや`.gitignore`を自動生成せず、空の状態で作成すると競合を避けられます。

## 5. APKの配布

APKはソースリポジトリへ直接コミットせず、GitHubの`Releases`へ添付します。

デバッグAPKは開発確認用です。一般配布にはリリース署名したAPKを使用してください。

推奨するRelease構成:

```text
Tag: v1.0.0
Title: HID Slime Bridge v1.0.0
Assets:
- HID-Slime-Bridge-v1.0.0.apk
```

Release Notesには最低限、対応Androidバージョン、対応VID/PID、HIDフォーマット、既知の問題を記載してください。
