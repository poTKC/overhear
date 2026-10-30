# <img src="docs/icon.svg" width="44" height="44" alt="" align="top"> Overhear

**紹介とインストール手順: <https://potkc.github.io/overhear/>**

古い Android 端末を Spotify の音楽プレーヤーにするアプリです。

- **端末だけで選曲・再生:** Spotify にログインすれば、端末の画面で自分のライブラリ（検索・お気に入りの曲・
  プレイリスト・アルバム・アーティスト・最近再生）をたどり、順番どおり・シャッフル・選んだ曲から再生できます。
  曲をお気に入りに追加・削除することもできます。
- **再生画面:** 曲名・アートワーク・シークバー・キューを表示し、端末から操作できます。
- **スマホの Spotify から操作:** Spotify Connect の再生先にこの端末（既定の名前は端末の機種名）を選べば、
  スマホや PC の Spotify からリモコンのように選曲・操作できます。音量も端末と連動します。

対応するのは Android 4.1〜7.1 の ARMv7 端末です（動作確認は Android 4.3 の Xperia mini ST15i のみ）。
Android 8.0 以降はアプリの制限（seccomp）で Soloist が動きません。Android 7.0 以降なら Spotify の公式アプリが使えるので、
Overhear を使う必要はありません。

スマホの Spotify から操作するだけなら Soloist の API キーだけで動きます。ライブラリ（選曲・検索・お気に入り）を使うときは、
加えて自分で作った Spotify Developer のアプリの Client ID を「スマホで認証」で入れ、Spotify Web API にログインします
（Client ID は組み込まれていません）。Web API には音を鳴らす機能がないので、再生はいつも Soloist が行います。

再生は Spotify が開発者向けに配布している公式のヘッドレスクライアント **Soloist** が行います。
Overhear はそれを APK に同梱した glibc の上で動かすだけで、非公式クライアントは使わず、DRM の回避もしません。
Soloist は自分のアカウントで発行した API キーで、端末上で Spotify から直接ダウンロードします。

## しくみ

```
スマホの Spotify ── Spotify Connect（mDNS で発見）──▶ 端末

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

  LibraryActivity（ライブラリ・検索）  LoginActivity（QR でログイン）
     │                                  │
     ▼                                  ▼
  SpotifyApi / SpotifyAuth ── overhear-fetch api / token ──▶ api.spotify.com / accounts.spotify.com
                                                              │ 再生の指示（Spotify Connect）
                                                              ▼
                                                            Soloist
```

- **Soloist** は APK に入れず、初回セットアップ時に端末上で Spotify の CDN
  （`soloist-builds.spotifycdn.com`）から取得します。取得には同梱の Go 製ヘルパー
  `overhear-fetch` を使います（Android 4.3 の TLS では最新サイトに接続できないため）。
  同じヘルパーの `token`（accounts.spotify.com でのトークンの取得・更新）と `api`（api.spotify.com の呼び出し）
  サブコマンドで Spotify Web API も呼びます。トークンは環境変数か stdin で渡し、コマンドラインには載せません。
- **ライブラリ**は Spotify Web API で一覧を取り、再生も Web API でこの端末（Soloist）に指示します。
  再生の指示が失敗し（通信の失敗、ログインの失効、端末がデバイス一覧にない）、指示した曲が流れていなければ、
  Soloist の WebSocket で先頭から再生します。未ログインのときは、ライブラリには「Spotify にログイン」の行だけが出ます。
- **fakepulse** は Soloist が使う PulseAudio クライアント API を実装した代替 `libpulse.so.0`です。
  PCM を抽象 Unix ソケットでアプリへ送り、アプリは AudioTrack で再生します。
  音量は PCM に掛けず（常にフルスケール）、端末のメディア音量だけで調整します。
- **API キー**は環境変数 `OVERHEAR_SOLOIST_KEY` で渡し、`libargkey.so` が起動時に `-k` 引数へ移します。
  `/proc/<pid>/cmdline` にキーは出ません。
- 詳しくは [設計書](docs/plans/2026-10-26-st15i-player-design.md) と
  [実装計画](docs/plans/2026-10-26-st15i-player.md)、ライブラリについては
  [設計書](docs/plans/2026-10-27-library-design.md) と [実装計画](docs/plans/2026-10-27-library.md) を見てください。

```
app/        Android アプリ（Java、Gradle なし、API 18）
native/     fakepulse と libargkey（C、zig cc）
helper/     overhear-fetch（Go）
runtime/    同梱ランタイムの作成（glibc の取得とパッチ、CA バンドル、ライセンス文）
test/       JVM 上の単体テスト
docs/       紹介ページ（GitHub Pages: index.html、icon.svg）、リリースノート、設計書と実装計画（plans/）
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
- 実機: Android 4.1〜7.1 の ARMv7 端末と `adb`

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

ビルド済みの APK は [Releases](https://github.com/poTKC/overhear/releases) にあります
（最新版: <https://github.com/poTKC/overhear/releases/latest/download/overhear.apk>）。
古い Android のブラウザは GitHub からダウンロードできないので、PC かスマホでダウンロードしてから端末に移し、
ファイルマネージャーで開いてください（「提供元不明のアプリ」の許可が必要です）。
手順は[紹介ページ](https://potkc.github.io/overhear/#install)にもあります。

自分でビルドした APK は adb で入れられます。

```sh
adb install -r build/overhear.apk
```

`-r` で上書きすれば、API キーとログイン状態は残ります。署名の違う APK（Releases の APK と自分でビルドした APK など）は
上書きできません。

> **署名鍵に注意:** `keys/release.jks` があればその鍵で署名します（Releases の APK の鍵。パスワードは
> 環境変数 `OVERHEAR_KS_PASS` か macOS キーチェーンの `overhear-release-keystore` から読みます）。
> ない場合は `keys/debug.jks` を初回ビルドで自動生成して使います（どちらも `.gitignore` 済み）。
> 鍵をなくすと上書きインストールができず、アンインストールが必要になります。
> アンインストールすると、保存した API キーと Spotify のログインも消えます。鍵はバックアップしてください。
>
> 0.1.2 から Releases の署名鍵を切り替えたため、0.1 / 0.1.1 からは一度アンインストールが必要です。

## 初回セットアップ

API キーや Client ID は端末では入力しません。設定画面の「スマホで認証」で QR コードを出し、スマホで入力します。

1. <https://developer.spotify.com/dashboard/soloist> で、自分の Spotify Premium アカウントの
   Soloist API キーを発行します。ライブラリも使うなら、Spotify のアプリも用意します（次の「Spotify のアプリの準備」）。
2. 端末で Overhear を開き、設定画面の「アカウント」の「スマホで認証」を押します。
   端末と**同じ Wi-Fi** につないだスマホで QR コードを読み取り、開いたページに Soloist API キーと Client ID を貼り付けて
   「保存してログイン」を押すと、続けて Spotify にログインします（ライブラリを使わないなら Client ID は空欄、
   「Spotify にログインする」を外して「保存」）。
3. 設定画面に戻り、デバイス名（既定は端末の機種名、取れなければ「Overhear」）を「再生」で決め、
   「起動時・アプリを開いたときに自動で開始」を選んで「保存して開始」を押します。
   ランタイムの展開と Soloist のダウンロード（約 12 MB）が終わると再生画面に移ります。
4. 再生画面の右下の円盤のアイコン（または戻るキー）でライブラリを開き、選曲して再生します。
   スマホの Spotify から操作するときは、再生先にそのデバイス名を選びます。

## Spotify へのログイン（ライブラリ）

### Spotify のアプリの準備

ライブラリは Spotify Web API を使うので、自分の Spotify Developer のアプリ（Client ID）が必要です。
アプリには Client ID が組み込まれていません。次の手順でアプリを作り、その Client ID を「スマホで認証」で
保存してください。Client ID がない間は、ログインもライブラリも使えません。
Client ID を変えるとログアウトするので、ログインし直してください。

1. <https://developer.spotify.com/dashboard> でアプリを作り、Web API を有効にします。
   アプリの Settings に出る Client ID（32 文字の16進数）を使います。
2. Redirect URI に `https://potkc.github.io/overhear-auth/callback/` を登録します（**末尾のスラッシュまで含めます**）。
   これはログインの結果を LAN 内の端末へ転送するだけの中継ページです（ソースは
   [poTKC/overhear-auth](https://github.com/poTKC/overhear-auth)。転送先はプライベート IPv4 のアドレスに限ります）。
3. 開発モードの制約: アプリの所有者が Spotify Premium であること、使えるのは User Management の許可リストに
   登録した最大 5 人までです。自分以外が使うときは、その人の Spotify のメールアドレスを登録してください。

### スマホで認証

1. 設定画面の「アカウント」の「スマホで認証」を押すと（ライブラリの「スマホで認証してログイン」の行からも開きます）、
   QR コードが表示されます。
2. 端末と**同じ Wi-Fi** につないだスマホで QR コードを読み取ります。開いたページで Soloist API キーと Client ID を
   入力します。空欄の項目は保存済みのまま変わりません。
3. 「Spotify にログインする」に印があれば、「保存してログイン」で Spotify のログインと許可に進みます。
   端末の画面が「完了」に変わり、スマホには中継ページの「ログインしました。このページは閉じてかまいません。」が出ます。

端末はこの画面を開いている間だけ（最大 5 分）、Wi-Fi のアドレスのポート 8889 で待ち受けます。QR コードには
推測できない一回限りの値が入っていて、それがないアクセスは拒否し、保存が済むと同じ QR コードでは保存できません。
設定画面には API キー・Client ID が保存済みかどうかとログイン中のユーザー ID が表示され、「ログアウト」でログインを消せます。

**6 か月ごとにログインし直してください。** 開発モードでは、リフレッシュトークンが最初の認可から 6 か月で失効します
（使っても延びません）。失効すると、ライブラリに「Spotify のログインが切れました — タップしてスマホで認証」が出ます。
ログインし直すだけなら、ページの入力欄は両方空欄のまま「保存してログイン」を押します。

## 使い方

画面の見かたや操作は[紹介ページの「使い方」](https://potkc.github.io/overhear/#usage)を見てください。

## デバッグメニュー

`DEBUGGABLE=1` でビルドしたときだけ、再生画面のステータス行を長押しするとデバッグメニューが開きます。
ログ表示、Soloist の再起動（音声トレース付きも可）、強制終了（再起動の試験用）、サービス停止があります。
ログは API キーを伏せ字にしています。

## 既知の不具合

- サービスの開始直後に、前回の曲の再生に戻れない（▶ を押しても始まらない）ことがあります。
  そのときは設定画面の「再生サービス」で「停止」→「開始」と Soloist を起動し直すか、
  スマホの Spotify で再生先にこの端末を選んで再生を始めてみてください。
- ほかの人のプレイリストの再生は実験的な機能です。曲一覧は Soloist のキューから組み立てるので、
  一覧が出なかったり、順番が実際と合わなかったりすることがあります。

## 既知の注意点

- アプリをアンインストールしたり強制終了したりすると、Android 4.3 は子プロセスの Soloist を残します。
  残った Soloist は次にサービスが起動したときに終了させます。
- 自動開始は `BOOT_COMPLETED` が届いてからです。ROM によっては起動から数分かかります。
- Soloist のビルドは 90 日で失効します（終了コード 10）。アプリは自動で取り直します（24 時間に 3 回まで）。

## 要望・バグ報告

X の [@todayporton](https://x.com/todayporton) までどうぞ。

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
