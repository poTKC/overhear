# ST15i Player 設計書

日付: 2026-10-26

## 目的

Xperia mini ST15i（Android 4.3 / CyanogenMod 10.2、ARMv7 1GHz、RAM 512MB）を、
Spotify Connect のスピーカー兼再生画面として単体で使えるようにする専用アプリ。
スマホの Spotify から再生先「ST15i」を選ぶと ST15i から音が出て、
ST15i の画面に曲情報が表示され、ST15i から操作もできる。

## 前提と検証済み事項

- 非公式実装（librespot / go-librespot / cspot）は、このアカウントで audio key が拒否されるため使わない。
  DRM の回避は行わない。
- Spotify 公式の Soloist（ヘッドレス Spotify Connect クライアント）の arm32 版が、
  Debian trixie の glibc 2.41（armhf）を同梱して `ld-linux-armhf.so.3 --library-path` 経由で起動すれば、
  Android 4.3 上で動作する。実時間再生で CPU 約 26%。
- Android には `/etc/resolv.conf` がないため、同梱 libc.so.6 の `/etc/resolv.conf` を `./resolv.conf` に書き換える
  （同じ長さ、NUL 詰め）。作業ディレクトリに resolv.conf を置く。
- 証明書は `SSL_CERT_FILE` で同梱 CA バンドルを指定する。
- Soloist の音声出力は PulseAudio / PipeWire のみ。PulseAudio クライアント API のうち
  Soloist が dlsym する 48 関数を実装した代替 `libpulse.so.0`（fakepulse）で受ける。
  `pa_context_connect()` は戻る前に CONNECTING へ遷移させる必要がある（Soloist は直後に状態を確認する）。
- Soloist は再配布禁止で、ビルドは 90 日で失効する（終了コード 10）。
  アプリは同梱せず、公式 URL から取得する。
- Soloist の起動には、利用者本人の Premium アカウントで発行した API キーが必要。

## 構成

```
st15i-player/
  app/            Android アプリ（Java、minSdk 16、Gradle なし）
  native/         fakepulse（C、zig cc で arm-linux-gnueabihf.2.38 向けにビルド）
  runtime/        同梱物の作成スクリプト（glibc 取得・パッチ、CA バンドル）
  build.sh        javac + d8 + aapt2 + zipalign + apksigner で APK を作る
  docs/plans/     設計書・実装計画
```

### アプリの構成要素

1. **RuntimeInstaller**
   APK の assets に入れた同梱物（glibc 一式、fakepulse、CA バンドル）をアプリの files ディレクトリへ展開する。
   Soloist 本体は公式 URL（`https://soloist-builds.spotifycdn.com/soloist_release_arm32.tar.gz`）から取得して展開する。
   Android 4.3 の HTTPS は最新サイトに接続できないことがあるため、取得には同梱の静的ヘルパー（Go 製、
   CA バンドル同梱）を使う。
2. **PlayerService**（フォアグラウンドサービス）
   Soloist を子プロセスとして起動し監視する。WifiManager.MulticastLock と WakeLock（PARTIAL）、WifiLock を保持する。
   異常終了したら間隔を空けて再起動し、終了コード 10 なら Soloist を取り直す。
   音声ソケットのサーバー（LocalServerSocket `st15i-player-audio`）と AudioTrack 再生を担当する。
3. **SoloistClient**
   `127.0.0.1` の WebSocket API に接続し、イベント（auth_state、playback_state、track_changed、playback_changed、
   volume_changed、position_sync、options_changed、queue_changed）を受けて状態モデルを更新する。
   コマンド（play、pause、skip_next、skip_prev、seek、set_volume、set_shuffle、set_repeat_context、
   set_repeat_track、get_queue）を送る。最小限の WebSocket クライアントを自前で実装する（RFC 6455、テキストフレームのみ）。
4. **画面**
   - 初期設定画面: API キーと端末名を入力し、Soloist の取得と起動状況を表示する。
   - 再生画面: 中央にアートワーク（約 240×240）、曲名、アーティスト・アルバム、シークバー、
     ⏮ ⏯ ⏭、シャッフル・リピート、音量バー、接続状態。
   - キュー画面: 再生画面を上にスワイプするかボタンで開き、次の曲を 10 曲ほど表示する。
   - 常駐通知: 曲名と ⏮ ⏯ ⏭。
   - ハードウェアの音量キーで Soloist の音量を操作する。ヘッドホンのボタンで再生/一時停止する。
   - 端末起動時の自動起動（設定でオン/オフ）。

### 音声の流れ

Soloist → fakepulse（float32 → s16le、音量を適用）→ 抽象 Unix ソケット `@st15i-player-audio` →
PlayerService の音声スレッド → AudioTrack（44.1kHz・ステレオ・16bit・MODE_STREAM）。

フレーム形式（リトルエンディアン）: u8 種別、u32 長さ、ペイロード。

| 種別 | 内容 |
|------|------|
| `H` | u32 サンプルレート、u32 チャンネル数 |
| `P` | s16le の PCM |
| `F` | バッファ破棄（AudioTrack.flush） |
| `C` | u8 1=一時停止、0=再開 |

流量の調整は AudioTrack.write の待ちがソケット経由で fakepulse に伝わることでかかる。
fakepulse が報告する遅延（timing info の sink_usec）は、AudioTrack のバッファ長に合わせる。

### 曲情報の流れ

- WebSocket は `127.0.0.1` だけで待ち受ける（認証のない API のため外部に公開しない）。
- アートワーク URL（`item.decorations.visual_identity.cover[]`）の画像は、同梱ヘルパー経由で取得してキャッシュする。
- 再生位置は `position_ms`・`timestamp_ms`・`speed` からローカルで補間する。

## エラー処理

| 事象 | 対応 |
|------|------|
| Soloist 異常終了 | 1s、2s、4s…最大 60s の間隔で再起動 |
| 終了コード 10（失効） | Soloist を再取得して再起動し、通知で知らせる |
| Wi-Fi 切断・復帰 | マルチキャストロックを取り直し、Soloist を再起動 |
| API キー無効 | 初期設定画面へ戻す |
| 音声ソケット切断 | fakepulse が再接続する。その間のデータは捨てる |
| WebSocket 切断 | 1s 間隔で再接続 |

## テスト

- fakepulse: Mac 上で、PulseAudio の呼び出し手順をなぞるテストプログラムを動かす
  （接続の状態遷移、書き込みと流量調整、一時停止、バッファ破棄、timing info）。
- アプリの WebSocket 部分と状態モデル: JVM 上で単体テスト（録っておいた実イベント JSON を入力にする）。
- 実機の ST15i で通しの確認:
  - 音が出る
  - 曲情報とアートワークが表示される
  - 各操作が効き、スマホの Spotify と状態がそろう
  - 1 時間連続で再生できる
  - Soloist を kill すると復帰する

## 最初の版に含めないもの

選曲・検索・プレイリスト一覧（選曲はスマホの Spotify で行う）。
