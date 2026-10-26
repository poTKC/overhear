# ST15i Player 設計書

日付: 2026-10-26（実装に合わせて更新: 2026-10-26）

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
  native/         fakepulse と libargkey（C、zig cc で arm-linux-gnueabihf.2.38 向けにビルド）
  helper/         st15i-fetch（Go、静的リンク）
  runtime/        同梱物の作成スクリプト（glibc 取得・パッチ、CA バンドル、ライセンス文）
  test/           JVM 上の単体テスト
  build.sh        javac + d8 + aapt2 + zipalign + apksigner で APK を作る
  docs/plans/     設計書・実装計画
  README.md, THIRD_PARTY_NOTICES.md
```

### アプリの構成要素

1. **RuntimeInstaller**
   APK の assets に入れた同梱物（glibc 一式、fakepulse、CA バンドル）をアプリの files ディレクトリへ展開する。
   Soloist 本体は公式 URL（`https://soloist-builds.spotifycdn.com/soloist_release_arm32.tar.gz`）から取得して展開する。
   Android 4.3 の HTTPS は最新サイトに接続できないことがあるため、取得には同梱の静的ヘルパー（Go 製、
   CA バンドル同梱）を使う。
2. **PlayerService**（フォアグラウンドサービス）
   Soloist を子プロセスとして起動し監視する。起動・再起動・断念の判断は純 Java の Supervisor が行い、
   PlayerService はその指示（Effects）を実行する（後述「Supervisor の方針」）。
   ロックの持ち方は後述「Wi-Fi とロック」。
   音声ソケットのサーバー（LocalServerSocket `st15i-player-audio`）と AudioTrack 再生を担当する。
3. **SoloistClient**
   `127.0.0.1` の WebSocket API に接続し、イベント（auth_state、playback_state、track_changed、playback_changed、
   volume_changed、position_sync、options_changed、queue_changed）を受けて状態モデルを更新する。
   コマンド（play、pause、skip_next、skip_prev、seek、set_volume、set_shuffle、set_repeat_context、
   set_repeat_track、get_queue）を送る。最小限の WebSocket クライアントを自前で実装する（RFC 6455、テキストフレームのみ）。
4. **画面**
   - 初期設定画面: API キーと端末名を入力し、Soloist の取得と起動状況を表示する。
   - 再生画面: 全画面表示のときだけ上部に時刻と電池の帯、中央にアートワーク（240×240、角丸）、
     曲名、アーティスト・アルバム、シークバー、シャッフル・⏮ ⏯ ⏭・リピート、最下段の左に接続状態（ステータス行、英語）、
     右にキュー・設定のボタン。音量バーは置かない（後述「音量」）。
   - キュー画面: 再生画面を上にスワイプするか右下のキューボタンで開き、次の曲を 10 曲ほど表示する。
   - 常駐通知: 曲名と ⏮ ⏯ ⏭、停止（✕）。
   - ハードウェアの音量キーは端末のメディア音量を操作する（Soloist の音量と連動する）。ヘッドホンのボタンで再生/一時停止する。
   - 端末起動時の自動起動（設定画面の「起動時・アプリを開いたときに自動で開始」でオン/オフ）。

### 音声の流れ

Soloist → fakepulse（float32 → s16le、ゲインは常に 1.0）→ 抽象 Unix ソケット `@st15i-player-audio` →
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

- WebSocket は `127.0.0.1` だけで待ち受ける（認証のない API のため外部に公開しない。後述「セキュリティ」）。
- アートワーク URL（`item.decorations.visual_identity.cover[]`）の画像は、同梱ヘルパー経由で取得してキャッシュする。
- 再生位置は `position_ms`・`timestamp_ms`・`speed` からローカルで補間する。

## エラー処理

| 事象 | 対応 |
|------|------|
| Soloist 異常終了 | 1s、2s、4s…最大 60s の間隔で再起動（詳細は「Supervisor の方針」） |
| 終了コード 10（失効） | Soloist を再取得して再起動する（24 時間に 3 回まで） |
| Wi-Fi 切断・復帰 | 切断中は起動しない。復帰（または別ネットワーク）でマルチキャストロックを取り直し、Soloist を再起動 |
| API キー無効・ログインエラー | ステータス行に `Check API key — tap`（または `Login error — tap`）を出し、タップで設定画面を開く |
| 音声ソケット切断 | fakepulse が再接続する。その間のデータは捨てる |
| WebSocket 切断 | 1s、2s、5s（以降 5s）の間隔で再接続。45s ごとに ping を送って接続を保つ |
| 空き容量不足 | ダウンロード前に 80MB の空きを確認し、足りなければ Soloist のキャッシュを消して 1 回だけやり直す |

## 実装での決定事項

### API キーの受け渡し

Soloist はキーを `-k <key>` でしか受け取らないが、Android 4.3 ではどのアプリも `/proc/<pid>/cmdline` を読める。
そこでコマンドラインには載せず、環境変数 `ST15I_SOLOIST_KEY` で渡す（`/proc/<pid>/environ` は同じ uid しか読めない）。
起動は `ld-linux-armhf.so.3 --preload libargkey.so --library-path DIR soloist ...` とし、
`libargkey.so`（native/argkey.c）が `__libc_start_main` をフックして、環境変数の値を消してから
ヒープ上の argv に `-k <key>` を差し込む。`/proc/<pid>/cmdline` は元の argv を見せるのでキーは出ない。
ログは SoloistCommand がキーらしい文字列を伏せ字にしてから保存する。キーは Prefs（MODE_PRIVATE）にだけ置き、ログに出さない。

### Supervisor の方針

- **世代:** 起動のたびに世代番号を振り、起動結果と終了通知は世代付きで届く。古い世代の通知は無視する。
  起動完了の報告より先に終了が届いた場合（即死）は、それを保持して（early-exit latch）完了報告のときに適用する。
- **バックオフ:** 要求していない終了は 1、2、4…最大 60 秒の間隔で再起動する。60 秒続いた実行でリセットする。
- **断念:** 10 秒未満で終わる実行が 10 回続くか、再試行可能な起動失敗が 10 回続くと断念（FAILED）する。
- **自動再試行:** 上の 2 つ（非致命的な断念）は 30 分後、または Wi-Fi が戻ったときに自分で再試行する。
- **致命的な断念:** 起動が恒久的に失敗した場合（API キーがない、不正）、Soloist がログイン・キーのエラーを報告したあとの
  短命終了、失効による再取得の上限（24 時間に 3 回）。これらは利用者の操作（ステータス行のタップ、設定の保存）を待つ。
  キーが原因の場合は `needsSetup()` が真になり、ステータス行が設定画面へ誘導する。
- **失効（終了コード 10）:** すぐに再取得して起動し、以降は同じバックオフ。再取得は 24 時間に 3 回まで。

### Wi-Fi とロック

- 接続状態の変化は 2 秒のデバウンス後に評価する（待つ間は短いウェイクロックで眠らない）。
- ネットワークの同一性は「SSID + IP アドレス」で判断する。BSSID は使わない（同じネットワーク内のローミングで再起動しないため）。
  別のネットワークに変わったときは、切断してから復帰したものとして Soloist を再起動する。
- **ウェイクロック（PARTIAL）:** Soloist が必要な間（起動中・実行中・再起動待ち・バックオフ中で、Wi-Fi が上がっている）だけ保持する。
- **Wi-Fi ロック（HIGH_PERF）とマルチキャストロック:** サービスが動いている間は保持する（Wi-Fi 切断中も。スリープ設定で Wi-Fi が切られないように）。
  断念（FAILED）したときは解放し、メディアボタンの登録も外す。再試行で取り直す。
- サービスの終了（onDestroy）ですべてのロックを解放する。

### 取り残された Soloist の後始末（stray kill）

Android 4.3 では、アプリの強制停止やクラッシュで子プロセスの Soloist が残る。残った Soloist は WebSocket のポートと
デバイス名を占有するので、Soloist を起動する前に `/proc` を走査し、同梱ローダーのパスで動いていて親がこのアプリのプロセスでない
ものを SIGKILL する。前のサービスインスタンスの Soloist は最大 3 秒、終了を待つ。

### ストレージ

- Soloist のキャッシュ（`-z`）は Soloist が受け付ける最小値の 100MB に固定する（/data は約 420MB しかない）。
  空きの半分を超えそうなときは警告をログに出す。
- Soloist のダウンロード前に 80MB の空きを確認する（tar.gz と展開後のコピーが旧版と並ぶため）。
  足りないときは、Soloist が動いていなければキャッシュを消してもう一度だけ試す。

### WebSocket

SoloistClient は切断時に 1s、2s、5s（以降 5s）の間隔で再接続する。接続中は 45 秒ごとに ping を送り、
応答がなければ切断として扱う。

### 停止と自動開始

- **停止:** 通知の ✕ ボタン（`ACTION_STOP`）、または再生画面のメニュー（Menu キー、または設定ボタンの長押し）の「停止」。
  Android 4.1〜4.3 の通知アクションは最大 3 個で、展開時にしか出ないため、通知はカスタムビューで ⏮ ⏯ ⏭ ✕ を 1 行に並べる
  （曲がないときは ✕ だけ）。停止するとサービスの onDestroy が Soloist に SIGTERM を送り（応答がなければ強制終了）、
  すべてのロックを解放する。START_STICKY でも stopSelf 後はシステムが再起動しない。
- 停止後の再生画面のステータス行は `Stopped — tap to start` になり、タップでサービスを開始する。メニューの項目も「開始」に変わる
  （判断は PlayerText にまとめ、ホストテストで確認する）。
- **自動開始:** 設定画面の「起動時・アプリを開いたときに自動で開始」（Prefs.autoStart、既定オン）。キー・デバイス名と一緒に 1 回の commit で保存する。
  オンなら BootReceiver が起動完了時に、PlayerActivity が作られたとき（アプリを開いたとき）にサービスを開始する。
  停止は「次に開くまで」の扱いで、停止後にアプリを開き直すと再び開始する（止めたままにする状態は持たない）。
  開き直しても止めたままにしたいときは自動開始をオフにする。

### 音量

音量は端末のメディア音量（`STREAM_MUSIC`）1 本にまとめる。

- **fakepulse はサンプルを減衰させない。** `pa_context_set_sink_input_volume` で Soloist が送る音量
  （40% なら平均 26214、線形）は保存して `pa_context_get_sink_input_info` と購読イベントで返すが、PCM には掛けない
  （s16 への丸めとクリップだけ）。Soloist 自身もサンプルを縮めないので、PCM は常にフルスケールで届く。
  フォーカスによるダッキング（AudioTrack の `setStereoVolume` 0.2）とフォーカス喪失時のミュートは従来どおり AudioTrack で行う。
- **VolumeSync（純 Java、ホストテストあり）** を PlayerService が持ち、サービスが動いている間だけ両方向に同期する。
  対応は max = `getStreamMaxVolume(STREAM_MUSIC)`（4.3 では 15）として
  percent → index = `round(percent / 100 × max)`、index → percent = `round(index / max × 100)`。
  - **Soloist → 端末:** スナップショットの音量が変わったら（`volume_changed`、`playback_state`）、対応する index が
    今の index と違えば `setStreamVolume(STREAM_MUSIC, index, 0)`（音量パネルは出さない）。
  - **端末 → Soloist:** 非公開だが 4.3 で全ストリームの変更ごとに送られる `android.media.VOLUME_CHANGED_ACTION`
    （`EXTRA_VOLUME_STREAM_TYPE` == 3）を受け、index は extra ではなく `getStreamVolume` で読み直す
    （配送が遅れても古い値で動かないように）。両者が最後に合意した index と違えば `setVolume(indexToPercent(index))` を送る。
    ハードウェアキー、システムの音量パネル、他のアプリによる変更のどれでも同じ。
  - **ループ防止:** 自分の `setStreamVolume` の通知は合意済みの index と同じなので送り返さない。Soloist は `set_volume` を
    `volume_changed` で返し、値が 1 ずれることがある（47 → 46）ので、送信中の index と **index で** 照合して読み捨てる
    （3 秒で忘れる）。キーを押し続けている間に遅れて届くエコーで端末の音量が戻ることはない。スマホが 45 を送ったとき
    端末は index 7（47%）になるが、45 は送り返さない（スマホは 45 のまま）。
    送信中のエコーと一致しない変更（スマホからの変更）が来たら送信中の一覧を捨て、以後は Soloist が報告する値に従う
    （Soloist に最後に書いた側が勝ち、両者は必ずそろう）。
  - **起動時:** ログイン済みの最初の状態で、端末の音量を正とし `setVolume(indexToPercent(index))` を送る
    （すでに同じ index に対応していれば送らない）。Soloist は再起動のたびに自分の既定値で始まるが、メディア音量は
    利用者が最後にこの端末で選んだ値だから。auth_state は playback_state より先に届くので、音量がまだ不明なまま採用した
    場合は、その後に初めて届く音量（送信前の古い値）を変更として扱わない。その後スマホから転送や操作で別の音量が来れば、そちらに従う。
  - **ヘッドホンの安全音量:** 安全音量が有効な端末・設定では、4.3 はヘッドホン出力で安全音量（この端末では index 10）を
    超える `setStreamVolume` を拒否する（UI なしだと保留されるだけ）。この ROM で有効なときは確認ダイアログが出ず、
    音量キーでも 10 を超えられなかった（端末の設定で無効にでき、そのときは拒否されない）。
    拒否されたら許される最大の index まで上げ、`FLAG_SHOW_UI` で音量パネルを出して（10 秒に 1 回まで）上限に達したことを見せ、
    Soloist には実際の index を返す（スマホのスライダーが実際の音量に戻る）。
  - **ミュート:** ミュート中は `getStreamVolume` が 0 を返す。そのとき（または上げる前が 0 のとき）は安全音量の処理をせず、
    0 をそのまま Soloist に返す。システムのミュートには逆らわない。
  - **出力の切り替え:** 出力（スピーカー、ヘッドホン）ごとにメディア音量は別々に保存されている。`ACTION_HEADSET_PLUG`
    （登録時の sticky 配信は無視）と `ACTION_AUDIO_BECOMING_NOISY`（一時停止のあと）で index を読み直し、
    すぐと 1.5 秒後（AudioService の切り替え後）に VolumeSync に渡して、スマホのスライダーを新しい出力の音量に合わせる。
  - サービスが止まっていればメディア音量はただのシステムの音量。
- 再生画面に音量バーは置かず、音量キーも横取りしない（`setVolumeControlStream(STREAM_MUSIC)` で常にメディア音量と標準の音量パネル）。

### 画面のその他

- ステータス行（最下段の左）は短い英語で、タップで状態に応じた操作をする: 開始（`Stopped — tap to start`）、
  再試行（`Failed — tap to retry`）、設定（`Check API key — tap`、`Login error — tap`）。ほかに `Connected`
  （一時停止中は `Connected · Paused`）、`Select "<デバイス名>" in Spotify`、`Starting…`、`Updating…`、`Downloading 62%`、
  `Unpacking…`、`Restarting in 4 s`、`Wi-Fi off`、`Start failed`、`Reconnecting…`。サービスは通知用の日本語の文言と一緒に
  状態（PlayerText.Phase と数値）を持ち、画面は PlayerText がそれを英語に変える（純 Java でホストテストするため、文言はリソースではなく
  PlayerText の定数）。通知とメニューは日本語のまま。文字は灰色（#B3B3B3）で、押下時のハイライトは付けない（背景は透明。
  タップと、デバッグビルドの長押しはそのまま）。
- 再生画面の配置（320×480、スクロールなし、全画面でもそうでなくても）: 全画面のときだけ上部 20dp に時刻（左、
  `StatusStrip.clockText` が `H:mm`、12 時間制（`DateFormat.is24HourFormat` が偽）なら AM/PM なしの `h:mm`。
  システムの書式はこの端末では「午前/午後」を付けるが、数字フォントに文字がないため。`TIME_TICK`・`TIME_CHANGED`・`TIMEZONE_CHANGED`
  で更新）と電池（右、8dp の色付きの点と数字だけ。50% 以上は緑、20〜49% は琥珀、20% 未満は赤、充電中は水色。sticky の
  `ACTION_BATTERY_CHANGED`）を 13sp の灰色（#B8B8B8）で出す（`includeFontPadding` なしで 20dp に収める。受信は onResume〜onPause の間だけ。
  規則は純 Java の StatusStrip）。時刻・電池の数字とシークバーの時間は Rubik（SIL OFL 1.1）の可変フォントを
  Regular（wght 400）で固定し、数字・`:`・空白だけに切り出したフォント（`tools/make_fonts.py`、google/fonts の固定したコミットから
  取得して sha256 を確認、`assets/fonts/st15i-digits.ttf`、`Typeface.createFromAsset` で一度だけ読む）。Rubik は予約フォント名を
  宣言していないので、名前表の名前（Rubik Regular）と著作権・ライセンスの記載はそのまま。アートワークは 240dp、角は 6dp の丸め
  （BitmapShader と drawRoundRect の自前 Drawable RoundedArt。ハードウェアアクセラレーションでも効く。プレースホルダーも同じ、
  キューのサムネイルは 3dp）。その下に曲名・アーティスト、シークバー、操作ボタンの行（両端にシャッフルとリピート、⏮ ⏭ は 56dp、
  ⏯ は 60dp の白い円で強調）、最下段 36dp にステータス行とキュー・設定のボタン（36dp、小さいアイコン）。
  余白は重み付きのスペーサーで配分する。
- アイコン: Remix Icon 4.5.0（Apache-2.0、npm の `remixicon@4.5.0` を unpkg から）を `tools/make_icons.py` が取得し
  （SVG ごとに sha256 を確認）、rsvg-convert で mdpi・hdpi・xhdpi の PNG にする（API 18 には VectorDrawable がない。PNG はコミット済み）。
  シャッフルは交差する曲線の `shuffle-line`。Remix の記号は枠いっぱいに描かれているので、ボタンの大きさは変えずに記号だけを
  縮めて重さをそろえる（⏮ ⏭ は 28dp、シャッフル・リピートは 24dp）。
  Spotify のアイコンやロゴは使わず、配色と作法だけ倣う: ⏯ は Remix の `play-circle-fill` / `pause-circle-fill` を白で 60dp のボタンいっぱいに
  （記号はくり抜きで背景が透ける。押下中は 70% の透明度の版に切り替えるセレクター。通知では円が重く見えるので普通の記号）、⏮ ⏭ は白の塗り、シャッフルとリピートは
  オフで灰色（#B3B3B3）、オンで緑（#1DB954）と下に 4dp の点（シャッフルは `state_activated` のセレクター、リピートは
  `PlayerText.repeatLevel` で切り替えるレベルリスト。色フィルターは使わない）。キュー・設定は小さな灰色、プレースホルダーは
  #282828 の角丸に灰色の音符。無効は透明度 40%、押下は薄い丸のハイライト。通知の行（⏮ ⏯ ⏭ ✕）とステータスバーの
  小アイコン（白い音符）も同じ素材。
- シークバー: 4dp の細い角丸のトラック（未再生 #4D4D4D、再生済みは白）と 12dp の白い丸のつまみ。ドラッグ中はつまみが 16dp に
  なり、再生済みが緑（#1DB954）になる（API 18 の SeekBar は進捗の状態リストを使わず、差し替えると範囲が崩れるので、
  onStart/StopTrackingTouch で進捗レイヤーに色フィルターを掛け外しする）。つまみは 2 状態とも 16dp の Drawable
  （小さい方は layer-list の 2dp の余白。API 18 の InsetDrawable は余白を固有サイズに数えない）。SeekBar は高さ 32dp で
  上下 8dp の余白を取り、内容の高さをつまみと同じ 16dp にして AbsSeekBar に 4dp のトラックを中央へ置かせる。左右 8dp
  （thumbOffset と同じ）でつまみが端で切れず、トラックの両端が下の経過時間・全体時間（11sp、#B3B3B3、左右寄せ）とそろう。
  無効時は他の操作と同じく透明度 40%。
- シャッフル・リピートの可否: Soloist は playback_state の `available_actions`（毎回リスト全体。ほかのイベントには来ない）に、
  使える文脈では `shuffle` と `set_repeat` を入れ、使えない文脈（ラジオや一部のアルバム再生など。実機でもそこでは
  set_shuffle が無視された）では入れない。PlayerState はそれを保持して（部分イベントでは変わらず、一度も来ていなければ可とみなす）
  `canShuffle()` / `canRepeat()` を返し、PlayerText.shuffleEnabled / repeatEnabled が操作可能かつ許可のときだけ有効にする。
  無効のボタンは透明度 40% でタップを受けず、オンの状態（緑と点）は薄くなったまま見える。Soloist 1.3.x のリピートは
  `set_repeat` ひとつで、文脈だけ・曲だけの許可はないので、許可された中だけを巡る処理は要らない。
- キュー画面は最下段の上まで（最下段はキューの上に描くので、開いていても切り替えられる）、
  全画面では時刻と電池の帯の下から。
- デバッグメニュー: `DEBUGGABLE=1` でビルドしたときだけ、ステータス行の長押しで開く（ログ表示、Soloist 再起動、
  音声トレース付き再起動、強制終了、サービス停止）。
- **全画面表示:** 設定画面の「再生画面を全画面表示」（Prefs.fullscreen、既定オン）。自動開始などと同じ 1 回の commit で保存する
  （値の正規化と既定値は純 Java の SetupValues、ホストテストあり）。再生画面だけが `FLAG_FULLSCREEN` で
  ステータスバーを隠す（API 18 に immersive はなく、ST15i はハードウェアキーでナビゲーションバーもない）。最初のレイアウトの前と
  毎回の onResume で `addFlags`/`clearFlags` するので、設定から戻るとすぐ反映される。増えた約 25px は重み付きスペーサーが
  吸収し、アートワークは 240dp のまま。設定画面は入力欄があるので常に通常表示。
  4.3 には隠したステータスバーを端のスワイプで出す仕組みがなく（4.4 から）、実機でも全画面中は上端からの引き下ろしで
  何も出なかった（通常表示では同じ操作で通知シェードが開く）。通知を見るにはホームに戻るか、設定をオフにする。
- 設定画面の下の「ライセンス」で、APK 同梱の THIRD_PARTY_NOTICES（全文のライセンス文付き）を表示する。
  約 140KB あるため、段落ごとの ListView で表示する（1 つの TextView では ST15i で約 20 秒固まった）。

## セキュリティ

- **WebSocket API:** Soloist の API は認証がなく、`127.0.0.1` で待ち受ける。外部からは届かないが、
  INTERNET 権限を持つ端末内のアプリなら誰でも再生操作できる。専用端末での利用を前提に受け入れる。
- **ポートの先取り:** ほかの端末内アプリが先に `127.0.0.1:8765` で待ち受けると、Soloist は起動できず（短命終了の扱いで断念に至る）、
  SoloistClient はそのアプリにつながってイベントをそのまま信じる。曲情報やアートワーク URL も偽装できる。
  これも専用端末の前提で受け入れるリスクとする。
- **音声ソケット:** 抽象 Unix ソケット `@st15i-player-audio` には誰でも接続できる。AudioSink は接続元の uid を確認し、
  このアプリの uid（Soloist は子プロセス）以外を拒否する。逆に fakepulse は接続先のサーバーを確認しない。
  ほかのアプリが先にこの名前で待ち受けると音声を受け取れるが、流れるのは再生中の音声だけで、受け入れるリスクとする。
- **API キー:** Prefs（MODE_PRIVATE）に保存し、コマンドラインにもログにも出さない（「API キーの受け渡し」）。
  設定画面は FLAG_SECURE を付け、保存済みのキーは入力欄に戻さない。
- **バックアップ:** `android:allowBackup="false"`。`adb backup` で API キーや Soloist のログイン情報が持ち出されないようにする。
- **デバッグ:** リリースビルドは debuggable にしない（`DEBUGGABLE=1` のときだけ。run-as と jdwp を閉じる）。
- **アートワーク:** 画像の URL は Soloist のイベントから来る。st15i-fetch（30 秒で打ち切り）で取得してアプリのキャッシュに保存し、
  縮小デコードして表示するだけで、ほかの用途には使わない。URL の宛先は検証しない（Soloist を信頼する）。
  上の「ポートの先取り」により、この信頼は実際には 8765 番ポートを握っている相手全体に及ぶ。
  st15i-fetch は https の URL だけを受け付け、リダイレクト先も https に限る（平文 HTTP には落ちない）。

## テスト

- fakepulse: Mac 上で、PulseAudio の呼び出し手順をなぞるテストプログラムを動かす
  （接続の状態遷移、書き込みと流量調整、一時停止、バッファ破棄、timing info、音量を設定しても PCM がフルスケールのままで、
  `get_sink_input_info` が設定値を返し、購読イベントが届くこと。s16・s32 のストリームも無変換で出ること）。
- VolumeSync: 対応表、起動時の採用、エコーの読み捨て、安全音量での拒否、振動せずに収束すること（模擬した Soloist と AudioManager で。
  キー操作とそのエコーの間にスマホの変更が割り込む場合も、Soloist が適用する順序の両方で）。
- アプリの WebSocket 部分と状態モデル: JVM 上で単体テスト（録っておいた実イベント JSON を入力にする）。
- 実機の ST15i で通しの確認:
  - 音が出る
  - 曲情報とアートワークが表示される
  - 各操作が効き、スマホの Spotify と状態がそろう
  - 1 時間連続で再生できる
  - Soloist を kill すると復帰する

## 最初の版に含めないもの

選曲・検索・プレイリスト一覧（選曲はスマホの Spotify で行う）。
