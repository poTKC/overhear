# Overhear

古い Android 端末を Spotify Connect のスピーカー兼再生画面にするアプリです。
スマホの Spotify で再生先にこの端末（既定の名前は端末の機種名）を選ぶと、
端末から音が出て、画面に曲名・アートワーク・シークバー・キューが表示され、
端末からも操作できます。

Xperia mini ST15i（Android 4.3 / CyanogenMod 10.2、320×480）で開発・確認しています。

> 旧名は **ST15i Player**（パッケージ `jp.tkc.st15iplayer`）です。2026-10-27 に Overhear
> （パッケージ `com.potkc.overhear`）に改名しました。リポジトリのディレクトリ名
> `st15i-player/` は旧名のままです。

Spotify 公式のヘッドレスクライアント **Soloist** を、APK に同梱した glibc の上で動かします。
非公式クライアントは使わず、DRM の回避もしません。

## しくみ

```
スマホの Spotify ── Spotify Connect（mDNS で発見）──▶ 端末（ST15i など）

端末:
  Soloist（arm32、Spotify 公式。端末上でダウンロード）
    ld-linux-armhf.so.3 --preload libargkey.so（同梱 glibc 2.41 / libatomic / CA バンドル）
     │                                  │
     │ PulseAudio API                   │ WebSocket 127.0.0.1:8765
     ▼                                  ▼
  fakepulse（libpulse.so.0）          SoloistClient（曲情報・操作）
     │ 抽象ソケット @overhear-audio     │
     ▼                                  ▼
  PlayerService                        PlayerActivity / 通知 / メディアキー
    ├ AudioSink → AudioTrack（スピーカー）
    └ Supervisor（起動・再起動・Wi-Fi・ロック）
```

- **Soloist** は APK に入れず、初回セットアップ時に端末上で Spotify の CDN
  （`soloist-builds.spotifycdn.com`）から取得します。取得には同梱の Go 製ヘルパー
  `overhear-fetch` を使います（Android 4.3 の TLS では最新サイトに接続できないため）。
- **fakepulse** は Soloist が使う PulseAudio クライアント API を実装した代替 `libpulse.so.0`です。
  PCM を抽象 Unix ソケットでアプリへ送り、アプリは AudioTrack で再生します。
  音量は PCM に掛けず（常にフルスケール）、端末のメディア音量だけで調整します。
- **API キー**は環境変数 `OVERHEAR_SOLOIST_KEY` で渡し、`libargkey.so` が起動時に `-k` 引数へ移します。
  `/proc/<pid>/cmdline` にキーは出ません。
- 詳しくは [設計書](docs/plans/2026-10-26-st15i-player-design.md) と
  [実装計画](docs/plans/2026-10-26-st15i-player.md) を見てください。

```
app/        Android アプリ（Java、Gradle なし、API 18）
native/     fakepulse と libargkey（C、zig cc）
helper/     overhear-fetch（Go）
runtime/    同梱ランタイムの作成（glibc の取得とパッチ、CA バンドル、ライセンス文）
test/       JVM 上の単体テスト
tools/      raw2png.py（スクリーンショット変換）、make_icons.py（Remix Icon からアイコンの PNG を生成）、make_app_icon.py（ランチャーアイコン）、make_fonts.py（Rubik から数字フォントを生成）、make_shadow.py（アートワークの影の 9-patch）など
```

## 必要なもの

- Android SDK: build-tools **36.0.0**（aapt2, d8, zipalign, apksigner）と
  `platforms/android-18/android.jar`
  （場所は `ANDROID_HOME`、`BUILD_TOOLS`、`ANDROID_PLATFORM_JAR` で変えられます）
- JDK 9 以降（`javac`, `java`, `keytool`）
- [zig](https://ziglang.org/)（fakepulse と libargkey のクロスコンパイル）
- Go（`runtime/build.sh` が `GOTOOLCHAIN=go1.27.3` でツールチェーンを固定します）
- `bsdtar`、`ar`、`python3`、`curl`、`shasum`、`zip`
- 実機: Android 4.1 以降の ARMv7 端末と `adb`（確認済みは Xperia mini ST15i、Android 4.3）

## ビルド

```sh
./build.sh                  # build/overhear.apk を作る（runtime/build.sh も実行）
SKIP_RUNTIME=1 ./build.sh   # build/assets をそのまま使う（ランタイムを作り直さない）
DEBUGGABLE=1 ./build.sh     # android:debuggable 付き（run-as とデバッグメニューが使える）
```

`runtime/build.sh` は Debian のパッケージをファイル名と sha256 で固定して `build/cache/` に
キャッシュします。ライセンス文も SPDX license-list-data のタグと sha256 で固定しています。

## テスト

```sh
test/run.sh                  # JVM 上の JUnit テスト（junit / org.json は sha256 固定で lib/ に取得）
native/test/run.sh           # fakepulse と libargkey のホストテスト
cd helper && go test ./...   # overhear-fetch のテスト
```

## インストール

```sh
adb install -r build/overhear.apk
```

`-r` で上書きすれば、API キーとログイン状態は残ります。

> **署名鍵に注意:** APK は `keys/debug.jks` で署名します（初回ビルドで自動生成、`.gitignore` 済み）。
> この鍵をなくすと上書きインストールができず、アンインストールが必要になります。
> アンインストールすると、保存した API キーと Spotify のログインも消えます。鍵はバックアップしてください。

## 初回セットアップ

1. <https://developer.spotify.com/dashboard/soloist> で、自分の Spotify Premium アカウントの
   Soloist API キーを発行します。
2. 端末で Overhear を開き、設定画面に API キーを貼り付けます。デバイス名（既定は端末の機種名、
   取れなければ「Overhear」）を決め、
   「起動時・アプリを開いたときに自動で開始」を選んで「保存して開始」を押します。
   ランタイムの展開と Soloist のダウンロード（約 12 MB）が終わると再生画面に移ります。
3. スマホの Spotify で再生先にそのデバイス名を選びます。

## 使い方

- **再生画面:** 左下のステータス行（短い英語）をタップすると、状態に応じて開始・再試行・設定を開きます
  （例: `Stopped — tap to start`、`Failed — tap to retry`、`Check API key — tap`）。
  右下のボタンでキュー（次に再生）と設定を開きます。キューはアートワークを上にフリックしても開きます。
  キューの曲をタップすると、その曲まで無音で飛び、再生します（`skip_next` を 1 曲ずつ。一時停止中から飛んでも再生で終わります）。
  全画面表示のときは、上端に時刻と電池残量（色付きの点と数字、充電中は水色）を小さく表示します。
  12 時間制の設定でも午前/午後は付けません。
- **音量:** 端末の音量キーでメディア音量を変えます。スマホの Spotify でこの端末の音量を変えると、
  端末のメディア音量も変わります（両方向に連動。サービスが動いている間だけ）。画面に音量バーはありません。
  起動時は端末のメディア音量を Spotify 側へ反映します。
- **停止:** 通知の ✕ ボタン、または再生画面のメニュー（Menu キー、または設定ボタンの長押し）で
  「停止」を選びます。Soloist を止め、ウェイクロック・Wi-Fi ロック・マルチキャストロックをすべて解放します。
- **自動開始:** 設定画面の「起動時・アプリを開いたときに自動で開始」がオンなら、端末の起動完了後と、
  アプリ（再生画面）を開いたときにサービスを開始します。停止したあとでもアプリを開き直すと再び開始します。
  開き直しても止めたままにしたい場合は、このチェックを外してください。
- **全画面表示:** 設定画面の「再生画面を全画面表示」がオン（既定）なら、再生画面ではステータスバーを隠します。
  設定画面はいつも通常表示です。保存して再生画面に戻るとすぐ反映されます。
  Android 4.3 には隠したステータスバーを引き出す操作がないため、全画面中は再生画面から通知を引き下ろせません
  （ホームに戻るか、このチェックを外してください）。
- **ライセンス:** 設定画面の下の「ライセンス」で、同梱物のライセンス表示を見られます。

### デバッグメニュー

`DEBUGGABLE=1` でビルドしたときだけ、再生画面のステータス行を長押しするとデバッグメニューが開きます。
ログ表示、Soloist の再起動（音声トレース付きも可）、強制終了（再起動の試験用）、サービス停止があります。
ログは API キーを伏せ字にしています。

## 既知の注意点

- 既知の注意点の ROM 固有の記述は、確認に使った ST15i（CyanogenMod 10.2）でのものです。
- アプリをアンインストールしたり強制終了したりすると、Android 4.3 は子プロセスの Soloist を残します。
  残った Soloist は次にサービスが起動したときに終了させます。
- この ROM では `BOOT_COMPLETED` が起動から約 2 分半後に届くため、自動開始もその後になります。
- 設定画面は `FLAG_SECURE` を付けていますが、この ROM の `screencap`（シェル）は無視して撮影できます。
- Soloist のビルドは 90 日で失効します（終了コード 10）。アプリは自動で取り直します（24 時間に 3 回まで）。
- 端末の設定で Android の安全音量（ヘッドホン）が有効なときは、ヘッドホン出力のメディア音量は 15 段階中 10 までしか
  上がりません（この ROM では確認ダイアログも出ません）。その場合、スマホでそれより大きくしても端末は 10 に留まって
  音量パネルを出し（10 秒に 1 回まで）、スマホのスライダーは 67% に戻ります。設定で安全音量を無効にしていれば制限はありません。
- メディア音量はスピーカーとヘッドホンで別々に保存されます。ヘッドホンを抜き差しすると、スマホのスライダーは
  切り替わった出力の音量に合わせて動きます。
- 音量はメディア音量の 16 段階（0〜15）に丸められます。スマホで 45% にすると端末は 47% 相当で鳴り、
  スマホの表示は 45% のままです。

## Soloist について

Soloist は Spotify の規約に従って、利用者の端末上で Spotify から直接ダウンロードします。
**Soloist をこのリポジトリにコミットしたり、APK に同梱したりしてはいけません。**
API キーも同様です。

## ライセンス

このプロジェクト自体のコード（アプリ、fakepulse、libargkey、overhear-fetch、ビルドスクリプトとツール）は
[MIT License](LICENSE) です（Copyright (c) 2026 poTKC）。

同梱しているサードパーティ製コンポーネント（glibc、libatomic、Mozilla CA バンドル、Go 標準ライブラリ、
PulseAudio ヘッダー、Remix Icon、AOSP の Holo 画像、Rubik フォント）はそれぞれのライセンスに従います。詳しくは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を見てください。
同じ内容に全文のライセンス文を加えたものが APK に入り、アプリの「ライセンス」から読めます。
