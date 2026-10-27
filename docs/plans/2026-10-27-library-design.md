# ライブラリ（端末での選曲）設計書

日付: 2026-10-27

## 目的

いまの Overhear は、スマホの Spotify から Spotify Connect で送られた曲しか再生できない。
端末自身で選曲できるようにする。利用者の Spotify のライブラリ（プレイリスト・保存したアルバム・
お気に入りの曲・フォロー中のアーティスト・最近再生）と検索を端末の画面で階層的にたどり、
選んだ曲から再生を始める。音は今まで通り Soloist から出る。

## 前提と検証済み事項（2026-10-27、ST15i 実機）

### Soloist の WebSocket API

- `{"type":"command","command":"play","uri":"<uri>"}` で、アルバム・プレイリスト・アーティスト・曲の
  URI を再生できる（`soloist ctl play [uri]` が送るものと同じ）。非アクティブ（`deactivate` 後）からでも
  Soloist が自分でアクティブになって再生する。不正な URI には `error`
  （"play requires a valid playable Spotify URI"）が返る。
- 開始位置の指定はできない。`skip_to`・`offset`・`index`・`track_uri` などは無視され、常に先頭から始まる。
  先に `set_shuffle` を送っておけば、シャッフルは引き継がれる。
- `play` の直後に、`spotify:meta:node_rules_placeholder` の行を含む `queue_changed` が一瞬だけ届く。
  また、直後の `context_changed` は decorations が空で、名前とジャケットは続く `playback_state` で埋まる。
- `get_queue` の `limit` を大きくすると、コンテキストの全曲が返る（66 曲のプレイリストを 5 曲目から
  再生したとき、`limit` 100 で upcoming が 65 曲）。

### Spotify Web API

- Android 4.3 の Java の TLS では accounts.spotify.com に接続できない（サーバーが GCM か ChaCha20 を
  必須にしている）。api.spotify.com には、同梱の CA を使い TLSv1.2 を強制すれば CBC のスイートで接続できるが、
  サーバーが CBC をやめた時点で使えなくなる。Go の静的バイナリ（同梱の CA、DNS の指定）なら両方に
  TLS 1.3 で接続できる。そのため **Web API の呼び出しとトークンの更新はすべて Go（overhear-fetch）で行う**。
- 新しく作った開発モードのアプリ（Client ID `efc26a16958343d0babe0e67afaa1a18`）で実測した結果:

| 機能 | 結果 |
|---|---|
| `/me/player/devices`、`PUT /me/player/play`（`device_id`、`context_uri`、`offset.position` / `offset.uri`） | 使える。ほかの人のプレイリストでも位置指定が効く |
| お気に入りの曲を `spotify:user:<id>:collection` と `offset` で再生 | 使える |
| `/me/playlists`、`/me/albums`、`/me/tracks`、`/albums/{id}/tracks` | 使える |
| 自分で作ったプレイリストの `/playlists/{id}/items` | 使える |
| ほかの人のプレイリストの `/playlists/{id}/items`・`/tracks` | 403。`/playlists/{id}` の応答にも曲の部分（`items`・`tracks`）が含まれない |
| `/me/following?type=artist`、`/artists/{id}`、`/artists/{id}/albums` | 使える |
| `/artists/{id}/top-tracks`、`/artists/{id}/related-artists` | 403 |
| `/me/player/recently-played` | 使える |
| `/search`（track、album、artist、playlist） | 使える（1 回の取得は最大 10 件） |
| Spotify 提供のプレイリスト（`37i9…`）の取得 | 404 |

- 端末上の Go バイナリから、リフレッシュトークンの更新（252ms）、デバイス一覧の取得、
  アルバムの 6 曲目からの再生指示（1.5 秒）まで完結することを確認した。更新してもリフレッシュトークンは変わらなかった。
- 開発モードの制約（公式、2026 年 2〜7 月の変更）: アプリの所有者が Premium であること、許可リストの最大 5 人まで、
  リフレッシュトークンは最初の認可から 6 か月で失効する（更新しても延びない）、リダイレクト URI は https か
  ループバックの IP（`localhost` は不可）。

### ログインの中継ページ

- Spotify のリダイレクト先には LAN のアドレスを登録できないので、公開リポジトリ `poTKC/overhear-auth` の
  GitHub Pages に中継ページ `https://potkc.github.io/overhear-auth/callback/` を置いた。
  `state`（`<プライベート IPv4>:<ポート>~<ワンタイム値>`）に書かれた端末のアドレスへ、クエリをそのまま転送する。
  転送先はプライベート IPv4（10/8、172.16/12、192.168/16）とポート 1024〜65535 に限る。外部のスクリプトは読み込まず、
  Referer も送らない。
- Android の Chrome 156、Mac の Chrome 155、Safari 26.2 から、LAN 内の待ち受けまで転送できることを確認した。
- Dashboard の Redirect URI に `https://potkc.github.io/overhear-auth/callback/` を登録する（末尾のスラッシュまで含める）。

## 画面

### 再生画面

- 下段の並びを「ステータス｜▤ ライブラリ｜≡ キュー｜⚙ 設定」にする。▤ で LibraryActivity を開く。
- キュー画面では `spotify:meta:node_rules_placeholder` の行を表示しない。

### ライブラリ（LibraryActivity）

タブは使わず、階層をたどる。上部は「← タイトル」と、取り直し用の「↻」。

```
ライブラリ（トップ）          アルバム                   Random Access Memories
┌────────────────────┐   ┌────────────────────┐   ┌────────────────────┐
│ ← ライブラリ        │   │ ← アルバム        ↻ │   │ ← Random Access M… │
│ 🔍 検索            ›│   │ [■] Blue Valentine ›│   │ ▶ 再生              │
│ ♥ お気に入りの曲    ›│ → │ [■] Heavy Serenade ›│ → │ 1 Give Life Back…   │
│ ▤ プレイリスト      ›│   │ [■] Random Access… ›│   │ 2 The Game of Love  │
│ ◉ アルバム         ›│   │ [■] Discovery      ›│   │ 3 Giorgio by Moro… │
│ ♪ アーティスト      ›│   │                     │   │ 4 Within           │
│ ↺ 最近再生         ›│   │                     │   │                     │
└────────────────────┘   └────────────────────┘   └────────────────────┘
```

- トップ: 検索、お気に入りの曲、プレイリスト、アルバム、アーティスト、最近再生。
- お気に入りの曲・アルバム・自分のプレイリスト → 曲一覧（先頭に「▶ 再生」）。曲をタップすると、そのコンテキストの
  その曲から再生する。
- ほかの人のプレイリスト（owner が自分でない）: 行の右端を「▶›」にする。開くと先頭から再生を始め、Soloist の
  キューから全曲を取って曲一覧を表示する（「ほかの人のプレイリスト」を参照）。
- アーティスト → アルバム一覧（先頭に「▶ このアーティストを再生」）→ 曲一覧。
- 最近再生: 曲をタップすると、元のコンテキスト（アルバム・プレイリスト）の中のその曲から再生する。
  コンテキストがないときは曲だけを再生する。
- 検索: 入力欄と、結果を曲・アルバム・アーティスト・プレイリストに分けた一覧。確定キーを押したときだけ検索する
  （入力のたびに呼ぶとクォータを使うため）。タップしたときの動きはライブラリと同じ。
- 再生を指示したら finish() して再生画面に戻る。ほかの人のプレイリストを開いたときだけは、曲一覧に留まる。
- 戻るキーで 1 つ上の階層へ。トップで戻ると再生画面へ。前回の階層を Prefs に保存し、次に開いたときはそこから再開する。
- 行の見た目はキューの行（item_queue.xml）を流用し、ジャケットは ArtworkLoader で小さい画像を読む。
- 未ログインのときは、トップに「Spotify にログイン」の行だけを出し、タップでログイン画面を開く。

### ログイン（LoginActivity）

設定画面に「Spotify にログイン」を加える。ログイン済みなら「ログイン中: <ユーザー ID>」と「ログアウト」を表示する。

1. PKCE の検証値と、ワンタイム値（32 バイトを base64url にしたもの）を作る。
2. Wi-Fi の IP を調べ、LAN 側のポート 8889 で待ち受ける（Java の ServerSocket）。待ち受けるのはこの画面を
   開いている間だけで、5 分で閉じる。受け付けるのは `GET /callback` だけで、state が一致しなければ 400 を返す。
3. 認可 URL を QR にして表示する。state は `<Wi-Fi の IP>:8889~<ワンタイム値>`。QR は Nayuki の qrcodegen
   （MIT、Java ファイル 1 つ）で作る。
4. スマホでログインすると、中継ページを経由して `/callback?code=…&state=…` が端末に届く。
5. `overhear-fetch token` で code をトークンに交換し、リフレッシュトークンを保存する。スマホには「ログインしました。
   このページは閉じてかまいません」を返す（Referrer-Policy: no-referrer）。端末の画面は完了表示に変わる。
6. `error=access_denied` が届いたら「キャンセルされました」と表示し、やり直せるようにする。

スコープ: `user-read-playback-state user-modify-playback-state user-library-read playlist-read-private
user-follow-read user-read-recently-played`。

## 構成

```
LibraryActivity（階層の一覧）  LoginActivity（QR）
        │                            │
        ▼                            ▼
  SpotifyApi（純 Java：要求の組み立て、応答の解析、トークンの管理）
        │ ApiTransport（テストでは差し替え）
        ▼
  overhear-fetch api / token（Go：TLS 1.3、同梱の CA、DNS の指定）
        │
        ▼
  api.spotify.com / accounts.spotify.com ── Connect ──▶ Soloist（今まで通り再生）
```

### Web API の呼び出し方

呼ぶたびに overhear-fetch を起動する（ジャケットの取得と同じ方法）。1 回 0.3〜0.6 秒ほどかかるが、一覧を 1 階層
進むたびに 1 回呼ぶだけなので許容する。常駐プロセスにはしない（Soloist と同じような起動・監視が増えるため）。
遅さが問題になったら常駐に替えても、Java 側の呼び出し口（ApiTransport）は変えない。

overhear-fetch に 2 つのサブコマンドを足す。

- `token`: accounts.spotify.com の `/api/token` に POST し、応答の JSON を stdout に出す。
  authorization_code（code、verifier、redirect_uri）と refresh_token の両方に使う。
- `api METHOD PATH`: api.spotify.com の `https://api.spotify.com/v1` 以下だけを呼ぶ。本文は stdin から読み、
  ステータスコードと応答の本文を stdout に出す。`Retry-After` も出す。

トークン・code・verifier・リフレッシュトークンは環境変数か stdin で渡し、コマンドラインに載せない
（Android 4.3 ではどのアプリも `/proc/<pid>/cmdline` を読めるため。API キーと同じ理由）。

### トークン

- リフレッシュトークンは Prefs（MODE_PRIVATE）に保存する。ログにも出さない。
- アクセストークンはメモリだけに持つ。期限の 1 分前か 401 が返ったときに更新する。
- 更新が `invalid_grant` で失敗したら（6 か月の失効など）、ログイン情報を消し、ステータス行とライブラリに
  `Spotify login — tap` を出す。タップでログイン画面を開く。
- Client ID は定数としてコードに埋め込む。ほかの人が使うには自分のアプリを作ってソースを書き換える
  （開発モードは許可した 5 人までのため）。README に書く。

### 再生の指示

1. `/me/player/devices` から、Prefs の端末名と一致するデバイスを探し、ID をキャッシュする。
2. `PUT /me/player/play?device_id=…` に `context_uri` と `offset`（曲の URI）を付けて送る。
3. Web API が使えないとき（未ログイン、通信の失敗、端末が一覧にない）は、Soloist の WebSocket の `play(uri)` に
   切り替え、トーストで「最初から再生します」と出す。

### ほかの人のプレイリスト

Web API では曲一覧が取れないので、Soloist のキューから取る。

1. 開くと、そのプレイリストを先頭から再生する（Web API、だめなら WebSocket）。
2. コンテキストがそのプレイリストに変わったら、`get_queue` を大きな `limit`（500）で送り、現在の曲と upcoming を
   曲一覧にする。並び順の復元（upcoming は残りの曲のあとに先頭へ戻る形で並ぶらしい）は実装時に確かめる。
3. シャッフルがオンのときは、シャッフル後の順で並ぶことを一覧の上に注記する。
4. 曲をタップすると、Web API の `offset.uri` でその曲から再生する。
5. 通常の `get_queue`（再生画面のキュー用、`limit` 10）とは別の要求として送り、応答の取り違えを防ぐ。

### キューの曲へのジャンプ（Web API）

再生画面のキューで曲をタップしたとき、次の条件をすべて満たせば、Web API の `context_uri` と `offset.uri`
（その曲の URI）で直接その曲から再生する（1 回の指示で約 1.5 秒、途中の曲を無音にする必要もない）。

- 行の `source` が `context`
- シャッフルがオフ
- コンテキストがアルバム、プレイリスト、お気に入りの曲（`spotify:user:<id>:collection`）のどれか
- ログイン済みで、Web API の呼び出しが成功した

それ以外（手動でキューに追加した曲（`queue`）、自動再生（`autoplay`）、アーティストのコンテキスト、未ログイン、
Web API の失敗）は、今までどおり QueueJump（`skip_next` を 1 回ずつ送る）で飛ぶ。

実装の最初に実機で確かめること:

- シャッフル中に `offset.uri` で飛んだとき、シャッフルし直されるか。並びが保たれるなら、シャッフル中も Web API で飛ぶ。
- 手動でキューに追加した曲が残っているときにコンテキストを再生し直すと、それらの曲が次に流れるか
  （QueueJump は飛ぶ途中でそれらを消化する）。流れるなら、手動の曲が目標より前にあるときは QueueJump を使う。
- 同じ曲が 2 回入っているときは、`offset.uri` が最初の方を選ぶので、目標が 2 回目以降なら QueueJump を使う。

### 一覧の取得

- LibraryActivity の中でページを積み重ねる。ページは 8 種類: トップ、プレイリスト一覧、アルバム一覧、
  アーティスト一覧、アーティストのアルバム一覧、曲一覧（アルバム、自分のプレイリスト、お気に入りの曲、
  ほかの人のプレイリスト）、最近再生、検索。
- 1 回の取得は 50 件（検索は 10 件）。最後までスクロールしたら次の分を自動で読み込む。
- 取得した内容はプロセスが生きている間だけメモリに置き、戻ったときは取り直さない。ディスクには保存しない。
  「↻」で取り直す。
- ほかの人のプレイリストかどうかは、owner の ID が自分（`/me` の ID）かどうかで判断する。

## エラー処理

| 状況 | 動き |
|---|---|
| 通信の失敗、タイムアウト | 一覧の位置に「読み込めませんでした — タップで再試行」 |
| 401 | トークンを 1 回更新して再試行。それでも 401 なら未ログインとして扱う |
| `invalid_grant` | ログイン情報を消し、`Spotify login — tap` |
| 429 | 「混み合っています。しばらくしてから」。`Retry-After` の秒数までは自動で再試行しない |
| 未ログインでライブラリを開いた | トップに「Spotify にログイン」の行だけ |
| 再生の指示に失敗した | WebSocket の `play(uri)` に切り替え、「最初から再生します」 |
| Soloist の `play` に `error` が返った | 既存の onError でトースト |

## セキュリティ

- ログイン用の待ち受け（LAN のポート 8889）はログイン画面を開いている間だけ、最大 5 分。`GET /callback` 以外と、
  state が一致しない要求は拒否する。
- 中継ページを通る code は、端末だけが持つ PKCE の検証値がないとトークンに交換できない。
- リフレッシュトークンとアクセストークンは Prefs かメモリだけに置き、コマンドラインとログに出さない。

## テスト

- ホストテスト（JUnit）:
  - SpotifyApi: 応答の解析（実測の JSON をフィクスチャにする）、ページ送り、トークン更新と 401 時の再試行、
    `invalid_grant`、429。
  - OAuth のコールバック: state の照合、不正な要求の拒否、`error` の扱い。
  - ページの積み重ねと、前回の階層の保存・復元。
  - キューのプレースホルダー行の除外、ほかの人のプレイリストのキューからの曲一覧の復元。
- Go: `token` と `api` のサブコマンドを main_test.go で確かめる（httptest のサーバー）。
- 実機: QR でのログイン（iPhone と Android）、各階層の表示、途中の曲からの再生、ほかの人のプレイリスト、
  Web API が使えないときの切り替え、失効の模擬（リフレッシュトークンを壊す）。

## 最初の版に含めないもの

- 再生画面の「＋」（ライブラリへの保存）。Spotify のライブラリ自体を表示するため。
- 常駐の Web API プロセス、一覧のディスクキャッシュ。
- アーティストの人気曲・関連アーティスト（API が 403）。
- Spotify 提供のプレイリスト（API が 404）。
