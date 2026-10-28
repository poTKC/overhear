# Overhear 0.1

古い Android 端末を Spotify Connect のスピーカー兼再生画面にするアプリの、最初の公開版です。

インストールと初回セットアップの手順: <https://potkc.github.io/overhear/>

## おもな機能

- Spotify 公式のヘッドレスクライアント **Soloist** で再生する Spotify Connect スピーカー
  （非公式クライアントは使わず、DRM の回避もしません。Soloist は端末上で Spotify から直接ダウンロードします）
- 再生画面: 曲名・アートワーク・シークバー・キュー、端末からの操作、通知とメディアキー
- 音量: 端末の音量キーとスマホの Spotify の音量が両方向に連動
- ライブラリ（任意、Spotify にログインしたとき）: 検索・お気に入りの曲・プレイリスト・アルバム・アーティスト・最近再生をたどり、
  順番どおり・シャッフル・選んだ曲から再生、お気に入りへの追加・削除
- スマホで認証: 端末の QR コードを同じ Wi-Fi のスマホで読み取り、Soloist API キーと Client ID の入力と Spotify のログインをスマホで行います
- 再生していない状態が続くとサービスを自動停止（既定 15 分。5 分・30 分・しない）。停止中は電源ボタンから再開
- 起動時・アプリを開いたときの自動開始、全画面表示（時刻と電池残量を表示）

## 必要なもの

- Android 4.1 以降（minSdk 16）の ARMv7 端末、Wi-Fi
- Spotify Premium（Soloist API キーの発行に必要）
- ライブラリを使うときは、自分で作った Spotify Developer のアプリの Client ID
  （Redirect URI `https://potkc.github.io/overhear-auth/callback/`。開発モードでは許可リストの最大 5 人まで、6 か月ごとにログインし直し）
- 動作確認は Xperia mini（ST15i、Android 4.3）だけです

## インストール

古い Android のブラウザは GitHub からダウンロードできないため、下の `overhear.apk` を PC かスマホでダウンロードし、
USB でコピーしてファイルマネージャーで開くか、`adb install -r overhear.apk` で入れてください。
詳しくは <https://potkc.github.io/overhear/> を見てください。

APK はこのプロジェクトの署名鍵で署名しています。今後のリリースも同じ鍵で署名するので、上書きインストールで更新でき、
API キーとログイン状態は残ります（自分でビルドした APK とは署名が違うため、相互には上書きできません）。

## ライセンス

MIT License。同梱のサードパーティ製コンポーネントは [THIRD_PARTY_NOTICES.md](https://github.com/poTKC/overhear/blob/main/THIRD_PARTY_NOTICES.md) を見てください。
Spotify は Spotify AB の商標です。このプロジェクトは Spotify とは関係ありません。
