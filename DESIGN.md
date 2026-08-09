# 高音質ステレオICレコーダー Android アプリ 詳細設計書 (v0.1)

対応する要求仕様は [SPEC.md](./SPEC.md) を参照。本書はその実装設計。

## 1. 全体アーキテクチャ

```
UI (Jetpack Compose)
  └─ ViewModel (AndroidViewModel)
        ├─ RecordingController ── bindService ──> RecordingService (Foreground Service)
        │                                              └─ StereoAudioRecorder (AudioRecordループ)
        │                                                    ├─ WavFileWriter
        │                                                    └─ AacFileWriter (MediaCodec+MediaMuxer)
        ├─ SettingsRepository (DataStore Preferences)
        ├─ RecordingRepository (JSON metadata index, app internal storage)
        └─ CertificateManager ── OkHttp ──> RFC3161 TSA
                       └─ TimestampClient (Bouncy Castle tsp)
```

- UI/ViewModel層は `AndroidViewModel` + `StateFlow` によるMVVM。
- 録音の実処理は **Foreground Service** に閉じ込め、画面回転・Activity破棄・画面オフに影響されず継続する。
- `RecordingController` はActivity/ViewModel側からServiceを bind し、`StateFlow<RecordingUiState>` を中継する薄いブリッジ。
- DIはHilt等を使わず、`Application`(`HqRecorderApp`)が保持する `AppContainer` による手動DIで簡素化。

## 2. パッケージ構成

```
com.hqrecorder.app
 ├─ HqRecorderApp.kt          Applicationクラス、AppContainer生成
 ├─ MainActivity.kt           権限リクエスト・NavHost起動
 ├─ core/AppContainer.kt      手動DIコンテナ
 ├─ audio/                    録音エンジン（Android依存だがUI非依存）
 ├─ service/                  Foreground Service・通知
 ├─ controller/                Service⇔UI ブリッジ
 ├─ storage/                  保存先(SAF)・録音メタデータ管理
 ├─ settings/                 ユーザー設定(DataStore)
 ├─ certificate/               RFC3161電子証明書
 └─ ui/                       Compose画面（theme/navigation/home/settings/list）
```

## 3. データモデル

### AudioQuality（audio/AudioQuality.kt）
録音フォーマット・音質を表す値オブジェクト。プリセット4種 + カスタム指定に対応。

| フィールド | 内容 |
|---|---|
| formatType | WAV / AAC |
| sampleRateHz | 44100 / 48000 / 96000 |
| bitDepth | WAVのみ有効。16 / 24 |
| aacBitrateBps | AACのみ有効 |

### RecordingMetadata（storage/RecordingMetadata.kt）
録音1件（分割ファイルはまとめて1件として扱う）のメタデータ。`recordings_index.json` にリストとして永続化（kotlinx.serialization、Roomは採用せず後述の理由で簡素化）。

```kotlin
data class RecordingMetadata(
  id: String, displayName: String,
  fileUri: String, folderUri: String,
  createdAtEpochMs: Long, durationMs: Long, fileSizeBytes: Long,
  formatType: String, sampleRateHz: Int, bitDepth: Int, aacBitrateBps: Int,
  certificateStatus: String,            // NONE / PENDING / ISSUED / FAILED
  certificateFileUri: String? = null,
  certificateIssuedAtEpochMs: Long? = null,
  certificateTsaUrl: String? = null
)
```

### AppSettings（settings/AppSettings.kt）
DataStore Preferencesに保存: 選択中の音質、保存先フォルダURI、電子証明書有効/無効、TSA URL、TSA認証ヘッダ、録音感度（ゲイン、`gainDb: Float`、デフォルト`0.0f`、格納可能範囲`-24.0f`〜`40.0f`。実際に適用される上限は録音開始時のソース選択に応じて`24.0f`/`40.0f`に制限される、4.9節参照）。TSA URLの初期値は`AppSettings.DEFAULT_TSA_URL`（FreeTSA.org、`https://freetsa.org/tsr`）とし、未設定のままでも電子証明書機能を有効化するだけで動作確認できるようにする（SPEC.md 3.5参照。設定画面から任意のTSAへ変更可能）。

## 4. 主要シーケンス

### 4.1 録音開始〜停止〜保存

1. `HomeScreen` の「録音開始」→ `HomeViewModel.startRecording()` → `RecordingController.startRecording(quality, folderUri)`
2. `ContextCompat.startForegroundService` でサービス起動、`bind()` 完了を `CompletableDeferred` で待ってから `RecordingService.startRecording()` を呼ぶ
3. `RecordingService` は `startForeground()` で通知表示 → `PARTIAL_WAKE_LOCK` 取得 → `StereoAudioRecorder.start()`
4. `StereoAudioRecorder` はアプリ内部キャッシュ (`cacheDir/recording_tmp/`) に一時ファイルとして書き込む（理由は5節）
5. 停止時: `StereoAudioRecorder.stop()` → 一時ファイル確定 → `RecordingService.finalizeRecording()` が `SafStorageManager` 経由でユーザー指定フォルダへコピー → `RecordingRepository.addRecording()` でメタデータ登録 → 電子証明書が有効なら `CertificateManager.issueCertificate()` を非同期実行

### 4.2 音質切替
`SettingsScreen` でプリセット選択 → `SettingsRepository.updateQuality()` → DataStoreに保存 → 次回録音開始時に反映（録音中の動的切替は非対応、録音単位で固定という一般的なICレコーダーの挙動に合わせる）。

### 4.3 保存先指定 (SAF)
`ActivityResultContracts.OpenDocumentTree` でユーザーがフォルダ選択 → `SafStorageManager.persistPermission()` で永続化 → `SettingsRepository.updateSaveFolderUri()`。以後の録音はすべてこのフォルダへ保存。

### 4.4 電子証明書取得（オプション）
1. 録音確定後、設定で有効かつTSA URL設定済みなら `CertificateManager.issueCertificate(recording, tsaUrl, authHeader)` を実行
2. 内部でファイルをストリームで読みSHA-256ハッシュを算出（ファイル全体をメモリに載せない）
3. `TimestampClient`（Bouncy Castle `org.bouncycastle.tsp` API）でRFC3161 TSQを生成しTSAへPOST、TSRを検証
4. 取得した `TimeStampToken` を `<ファイル名>.tsr` として録音と同じSAFフォルダへ保存
5. 失敗時は `certificateStatus=FAILED` として記録し、録音一覧画面から手動再試行可能（自動リトライキュー/WorkManagerはMVPでは不採用、詳細は6節）

### 4.5 証明書検証
録音一覧で「検証」→ `CertificateVerifier.verify()` がファイルを再ハッシュ化し、`.tsr` 内の `messageImprintDigest` と比較。一致すれば発行時刻・TSA情報を表示、不一致なら改ざんの可能性を警告。

### 4.6 長時間録音時のファイル分割
WAVヘッダのサイズフィールドは32bitのため、1パートあたり約1.8GBに達したら `StereoAudioRecorder` が現在のパートを確定し `_part2`, `_part3`… として新規パートを開始。停止時にすべてのパートをまとめて1件の `RecordingMetadata`（`durationMs`/`fileSizeBytes` は合算）として保存する。

### 4.7 AudioFocus割り込み対応
1. `RecordingService.startRecording()` で `AudioManager.requestAudioFocus()`（`STREAM_MUSIC` / `AUDIOFOCUS_GAIN`）を取得し、`OnAudioFocusChangeListener` を登録する
2. フォーカス変化コールバックの `AudioManager` 定数（`AUDIOFOCUS_LOSS` / `AUDIOFOCUS_LOSS_TRANSIENT` / `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` / `AUDIOFOCUS_GAIN`）を、Android非依存の `audio/AudioFocusChangeType`（`LOST_PERMANENTLY` / `LOST_TRANSIENT` / `GAINED`）へ変換する
3. `audio/AudioFocusDecision.decide(changeType, policy, pausedByFocusLoss)` という純粋関数（ユニットテスト対象）が、ユーザー設定 `AudioFocusPolicy`（`PAUSE`/`CONTINUE`、デフォルト`PAUSE`）と現在の自動一時停止フラグから `PAUSE` / `RESUME` / `NONE` を判定する
4. `RecordingService` は判定結果に応じて `pauseRecording()` / `resumeRecording()` を呼び、`pausedByFocusLoss` フラグを更新する。ユーザーが通知/UIから手動で一時停止した場合はこのフラグを立てないため、フォーカス再獲得時に意図せず再開されることはない
5. `stopRecording()` / サービス破棄時に `abandonAudioFocus()` する
6. `AudioFocusPolicy` は `SettingsRepository` 経由でDataStoreに永続化し、設定画面から変更可能。録音中の変更も次回のフォーカス変化から反映される

### 4.8 録音の削除

1. `RecordingListScreen`の`RecordingRow`に削除アイコンボタンを追加。タップ時は`AlertDialog`で確認し、確定時のみ`RecordingListViewModel.deleteRecording(recording)`を呼ぶ
2. `deleteRecording()`は`Dispatchers.IO`上で`SafStorageManager.deleteRecordingFiles(context, recording)`を実行する。この関数は以下のURI・ファイルをDocumentFile経由(`DocumentFile.fromSingleUri(...).delete()`)で削除する
   - 音声本体（`fileUri`）
   - `certificateFileUri`（存在する場合のみ）
   - 将来実装される`startCertificateFileUri`/`signatureFileUri`/`publicKeyFileUri`（9.1/9.2実装後、`RecordingMetadata`にフィールドが追加された時点で対象に含める）
   - 区間ハッシュチェーン（`.chain.json`）: `RecordingMetadata`はこのURIを保持していないため、`folderUri`配下を`DocumentFile.findFile("<音声ファイルのdisplayName>.chain.json")`で検索してから削除する
3. 各ファイルの削除結果を集計し、存在したファイルすべての削除に成功した場合のみ`RecordingRepository.removeRecording(id)`でメタデータを除去し、`CustodyLogManager.append(CustodyAction.DELETED, id, now)`を記録する（9.4節、`CustodyAction`に`DELETED`を追加）
4. 1件でも削除に失敗した場合（例: SAFプロバイダ側のエラー、9.6の読み取り専用化が将来有効な場合の書き込み拒否）はメタデータを残したまま`RecordingListViewModel`の`deleteError: StateFlow<String?>`にエラーを設定し、一覧画面にSnackbar等で表示してユーザーが再試行できるようにする
5. 削除中は対象行にインジケータを表示し、多重タップによる二重実行を防ぐ

### 4.9 入力ソース選択と録音感度（ゲイン）調整（SPEC.md 3.1/3.8）

1. `StereoAudioRecorder.createAudioRecord(sampleRate, wantFloat, preferUnprocessed)`は`preferUnprocessed`（＝録音開始時点の`AppSettings.certificateEnabled`）に応じてソース探索順を切り替える。`true`（電子証明書ON、証拠性優先）なら`UNPROCESSED → CAMCORDER → MIC`、`false`（通常時）なら`CAMCORDER → MIC → UNPROCESSED`の順で`AudioRecord`初期化を試み、最初に成功したソースを採用する（対応ソースが端末依存のため、いずれの優先順でも他ソースへ自動フォールバックする）
2. `GainProcessor.maxGainDb(preferUnprocessed)`が、実際に選ばれたソースの傾向に応じたゲイン上限（CAMCORDER想定: `MAX_GAIN_DB`=+24dB、UNPROCESSED想定: `MAX_GAIN_DB_UNPROCESSED`=+40dB）を返す。`StereoAudioRecorder.start()`はこの上限を`maxGainDb`として保持し、以後の`setGainDb()`呼び出しをこの範囲にクランプする。上限は音質プリセット同様、録音開始時（＝ソース確定時）に固定される
3. `SettingsScreen`のスライダーは`settings.certificateEnabled`を見て`valueRange`を動的に切り替え（`GainProcessor.maxGainDb(preferUnprocessed = settings.certificateEnabled)`）、UI上でも録音時に適用される上限が視覚的にわかるようにする。スライダー操作 → `SettingsViewModel.setGainDb(db)` → `SettingsRepository.updateGainDb()` で`floatPreferencesKey`によりDataStoreへ即時永続化する（保存値自体は両モードの上限のうち広い方`MAX_GAIN_DB_UNPROCESSED`でクランプし、実際の適用上限は2.の録音開始時の判定に委ねる）
4. `RecordingController`（または`HomeViewModel`）は`SettingsRepository.settingsFlow`の`gainDb`変化を購読し、値が変わるたびに`StereoAudioRecorder.setGainDb(db)`（内部的に`@Volatile`なフィールドを更新）を呼び出す。録音中でも次の`readLoop()`反復から新しいゲイン値が即座に適用される（4.2の音質切替が録音開始時に固定されるのとは異なり、値自体はリアルタイム変更を許容する設計とした。理由は5節参照。ただし適用上限は2.の通り録音開始時点で固定）
5. `StereoAudioRecorder.readLoop()`は読み取ったバッファに対し、`writer`への書き込みおよびレベルメーター計算(`computeLevelShort`/`computeLevelFloat`)の前段で`GainProcessor.applyGainShort(buffer, frames, channelCount, gainDb)` / `applyGainFloat(...)`（`audio/GainProcessor.kt`、Android非依存の純粋関数としてユニットテスト対象）を適用する。この関数はdB→線形係数変換（`10^(db/20)`）、係数の乗算、表現範囲（16bit: ±32767、float: ±1.0）でのハードクリップ、クリップ発生有無の判定を行い`clipped: Boolean`を返す
6. クリップ有無は`AudioLevel`に`clipped: Boolean`フィールドを追加して`RecorderListener.onLevel()`経由でUIへ伝搬し、`LevelMeterRow`が警告表示（赤枠等）を行う。警告は直近の`onLevel`コールバック（約100ms間隔）単位の簡易フラグとし、統計・履歴は保持しない（SPEC.md 3.8参照）
7. `GainProcessor.autoReduceGainDb(currentGainDb, clipped, stepDb = AUTO_REDUCTION_STEP_DB)`（純粋関数、ユニットテスト対象）が、`clipped=true`の場合のみ`currentGainDb`から`stepDb`（デフォルト3dB）を引いた値を`MIN_GAIN_DB`でクランプして返す。`StereoAudioRecorder.readLoop()`は`autoGainReductionEnabled`（`@Volatile`、`setAutoGainReductionEnabled()`で設定）が有効かつ`applyGainShort/Float`が`clipped=true`を返した場合にこの関数を呼び、戻り値が変化した場合のみ内部の`gainDb`を更新して`RecorderListener.onGainAutoReduced(newGainDb)`を通知する。連続する複数バッファでの過剰な追従を避けるため、直近の自動低減から`AUTO_REDUCTION_COOLDOWN_MS`（500ms）以内は再度の自動低減をスキップする（SPEC.md 3.8参照）
8. `RecordingService`（`RecorderListener`実装）は`onGainAutoReduced(newGainDb)`受信時に`SettingsRepository.updateGainDb(newGainDb)`を呼びDataStoreへ永続化する。これは4.の`settingsFlow`購読ループを経由して`SettingsScreen`のスライダー表示にも反映される（スライダー操作時と同じ経路をそのまま利用するため、UIとレコーダー間の状態は自動低減時も単一の真実源＝DataStoreに収束する）。自動低減の有効/無効自体も`AppSettings.autoGainReductionEnabled`として同じ`settingsFlow`購読ループで`StereoAudioRecorder.setAutoGainReductionEnabled()`に伝搬される

## 5. 設計判断・簡略化した点（MVPでの割り切り）

| 項目 | 採用した設計 | 理由 |
|---|---|---|
| 録音時の書き込み先 | 一時的にアプリ内部キャッシュへ書き込み、停止後にSAF宛先へコピー | SAFのUriは提供元（特にクラウド系DocumentsProvider）によってはシーク不可なストリームしか得られず、WAVヘッダの後書き（サイズ確定）ができない。ローカルの`RandomAccessFile`で確定してからコピーする方式にして信頼性を優先した。 |
| 録音履歴の永続化 | Room DBではなくJSONファイル（kotlinx.serialization） | 件数が数千件規模になりにくいICレコーダー用途では過剰。KSP/アノテーション処理の依存を減らしビルドの堅牢性を優先。 |
| 証明書の自動リトライ | WorkManagerではなく、一覧画面からの手動再試行 | 初期リリースではネットワーク制約付きバックグラウンドジョブの複雑さを避けた。将来WorkManagerへの置き換えは容易な構造にしている。 |
| 通話等によるマイク割り込み処理 | `AudioManager`のAudioFocusコールバックのみで検知（4.7節） | AudioFocus APIは本来再生用だが、通話アプリ等は着信/通話開始時に`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`相当でフォーカスを要求するため、間接的な割り込み検知の手段として利用する。`PhoneStateListener`/`READ_PHONE_STATE`によるより厳密な通話状態検知や、Bluetooth接続変化の個別ブロードキャスト受信は権限スコープ拡大・実装コストの観点からスコープ外とし、将来の拡張ポイントとする。 |
| 24bit WAV | `AudioRecord`の`ENCODING_PCM_FLOAT`で取得しアプリ側で24bit整数へ変換。非対応機種は16bit/モノラルへ自動フォールバック | Android標準APIには24bit整数の直接指定がないため。 |
| 録音削除時の部分失敗 | 音声本体・サイドカーの一部でも削除に失敗したらメタデータは削除せず全体を失敗扱いにする（部分成功を許容しない） | メタデータだけ消えて実ファイルがSAF上に孤立して残る状態（"孤児ファイル"）を避け、ユーザーが一覧から再試行できるようにするため。 |
| ゲイン設定の反映タイミング | 音質プリセット（4.2、録音開始時に固定）とは異なり、録音中でも設定変更を即座に反映する | `AudioRecord`の再初期化を伴わないソフトウェア的な係数適用のため実装コストが低く、録音しながら感度を微調整したいICレコーダーとしての利用シーンを優先した。 |
| クリッピング検知の粒度 | バッファ単位（`onLevel`コールバック、約100ms間隔）での簡易フラグのみとし、クリップ回数やピーク値の詳細な統計・ログは保持しない | MVPではユーザーへの即時警告（レベルを下げる判断材料）で十分であり、詳細な統計はユースケースに対して過剰と判断した。 |
| 入力ソース優先順位の切替トリガー | 独立した「生音優先/動画マイク優先」トグルを新設せず、既存の`AppSettings.certificateEnabled`（電子証明書付与ON/OFF）をそのままソース選択のトリガーに流用する | 電子証明書は証拠性を重視するユーザーが有効化する設定であり、「証拠性のためAGC非適用の生音が欲しい」というニーズと一致する。新規トグルによるUIの複雑化・設定間の不整合（証明書はONだがCAMCORDER優先、等）を避けた。 |
| クリッピング時の自動ゲイン低減の追従方式 | クリップ検出のたびに固定ステップ(3dB)で下げるシンプルな比例なしの制御とし、500msのクールダウンのみで過剰追従を防ぐ。ゲインを自動で上げ直す機構は持たない | 本格的なAGC（ピーク検出の平滑化、リリースタイムを持つ復帰制御等）は実装・検証コストが高く、MVPでは「音割れの致命的な悪化を防ぐ」という目的に対して過剰と判断した。上げ直しを自動化しないのは、無音区間明けの急な大声等で意図せずゲインが上がり再度クリップする振動を避けるため。ユーザーが手動で上げ直す操作は引き続き可能（SPEC.md 3.8参照）。 |

## 6. 権限とマニフェスト

- `RECORD_AUDIO` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`(API29+で宣言、API34+で実効的に必須) / `POST_NOTIFICATIONS`(API33+) / `WAKE_LOCK` / `INTERNET`（証明書オプション時のみ通信）
- `RecordingService` は `android:foregroundServiceType="microphone"` を宣言

## 7. 使用ライブラリ

| ライブラリ | 用途 |
|---|---|
| Jetpack Compose (BOM 2024.06) + Material3 | UI |
| Navigation Compose | 画面遷移 |
| AndroidX DataStore Preferences | 設定永続化 |
| kotlinx.serialization (JSON) | 録音メタデータ永続化 |
| kotlinx.coroutines | 非同期処理 |
| AndroidX DocumentFile | SAF操作 |
| OkHttp | TSA通信 |
| Bouncy Castle (`bcpkix-jdk18on`) | RFC3161 TSQ/TSR (ASN.1) 生成・解析 |

## 8. 今後の拡張ポイント

- WorkManagerによる証明書取得の自動バックグラウンドリトライ
- `PhoneStateListener`/`READ_PHONE_STATE`による通話状態の直接検知、Bluetooth接続変化（`ACTION_ACL_CONNECTED`等）の個別ブロードキャスト受信によるAudioFocus割り込み検知の精度向上（現状はAudioFocusコールバックのみ、4.7節）
- ノイズリダクション/AGCの明示トグル（現状は電子証明書ON/OFFに連動したCAMCORDER/UNPROCESSED自動切替のみ、4.9節）
- タブレット/外付けマイク（USB-Audio）対応
- 証拠性強化機能（SPEC.md 3.6）→ 詳細は9節
- 読み取り専用化（9.6、issue #10）実装後、削除操作が書き込み拒否で失敗するケースの扱い見直し（読み取り専用解除の可否確認、不可時のユーザー案内文言の整備）

## 9. Phase 3設計方針: 証拠性強化機能（SPEC.md 3.6対応）

証拠性強化機能は7項目のうち9.1〜9.4を実装済み、残り（9.5〜9.7）は設計方針の整理に留め実装は今後のissueで行う。既存の`certificate/`パッケージを拡張する形とし、新規パッケージは設けない想定（`certificate/custody/`, `certificate/signing/` のサブパッケージ程度に留める。9.3は`certificate/chain/`）。

### 9.1 開始時刻証明（実装済み）
録音開始時（`RecordingService.startRecording()`直後）に乱数シード（`SecureRandom`生成のnonce）を生成し、そのハッシュを3.5と同じ`TimestampClient`経由でTSAへ送信・トークン化。`RecordingMetadata`に`startCertificateFileUri`として保持し、終了時証明（既存の`certificateFileUri`）とペアで「開始時刻〜終了時刻の間に生成された」ことを示す。TSAへの送信は録音開始と同時に非同期で開始し、録音停止時の`finalizeRecording()`で結果を待ち合わせて`RecordingMetadata`へ反映する（証明書機能が無効、またはTSA通信失敗時は`startCertificateFileUri=null`のまま終了時刻証明のみで運用）。開始/終了の順序整合性判定は`certificate/StartEndTimeValidator`に純粋関数として分離しユニットテスト対象とし、`CertificateVerifier.verifyStartEndOrder()`が実際のTSRペアからTSA発行時刻を取り出して用いる。一覧画面の「検証」操作時に自動で突き合わせる。

### 9.2 端末鍵による電子署名（実装済み）
`android.security.keystore.KeyGenParameterSpec`でECDSA鍵ペアをAndroid Keystoreに生成（`setIsStrongBoxBacked(true)`を端末対応時は優先指定、非対応時は`StrongBoxUnavailableException`をキャッチしてTEEへフォールバック。`certificate/signing/DeviceKeyManager`）。鍵はKeystore外へエクスポート不可（`setUserAuthenticationRequired`は録音の自動化を妨げるため要件からは外す）。証明書機能ON（既存の`certificateEnabled`設定を流用）かつ録音確定時、`RecordingService.finalizeRecording()`でファイルハッシュに対し`Signature`APIで署名し、`<ファイル名>.sig`として保存（`certificate/signing/DeviceSigningManager`）。署名検証用の公開鍵は`<ファイル名>.pub`（X.509エンコード）として同時にエクスポートし、第三者が端末にアクセスできなくても検証できるようにする。検証ロジック自体は標準JCA APIのみに依存する`certificate/signing/DeviceSignatureVerifier`に純粋関数として分離し（AndroidKeystore非依存のためユニットテスト対象）、一覧画面の「検証」操作時にも自動で突き合わせる。鍵生成・署名自体はAndroid Keystore依存が強くInstrumentedテスト中心。

### 9.3 区間ハッシュチェーン（実装済み）
`StereoAudioRecorder`が音声読み取りスレッドとは別の専用スレッド（`StereoAudioRecorder-Chain`、優先度`MIN_PRIORITY`）で1秒間隔にポーリングし、一定間隔（デフォルト30秒、`chainIntervalMs`で変更可）ごとに前回チェックポイント以降の新規区間だけをファイルから読み直してSHA-256を算出する（`audio/IntervalHashChainRecorder`）。各区間ハッシュは「前区間のハッシュ＋今区間の内容ハッシュ」を連結して再ハッシュする方式（簡易Merkle chain、`certificate/chain/IntervalHashChainBuilder`、純粋ロジックとしてユニットテスト対象）とし、パート確定時に`<ファイル名>.chain.json`へ区間ごとの `{ index, offsetBytes, hash }` のリストとして保存、SAF保存先フォルダへ音声ファイルとあわせてコピーされる。これにより録音全体を送らずとも特定区間のみの差し替えを検知可能。

WAVはヘッダのサイズフィールドを`stop()`時に書き戻すため、ヘッダ領域を区間ハッシュの対象に含めると「ヘッダ確定」自体が誤検知の原因になる。これを避けるため、`openNewPart()`で`writer.start()`直後（まだ音声データを書き込む前）のオフセットを起点とし、ヘッダ領域はチェーンの対象外とする。

### 9.4 Chain of Custodyログ（実装済み）
録音メタデータとは別に`custody_log.jsonl`（追記専用JSON Lines、app内部ストレージ）を導入（`certificate/custody/CustodyLogManager`）。各エントリは `{ timestampEpochMs, action, actor, targetRecordingId, prevEntryHash, entryHash }` を持ち、`entryHash`は自分以外の全フィールド+`prevEntryHash`のSHA-256とすることで改ざん時に連鎖が破綻し検知できるようにする（連結・検証ロジックは`certificate/custody/CustodyLogChain`として純粋関数に分離しユニットテスト対象）。`action`は `CREATED / COPIED / SHARED / VERIFIED / EXPORTED / DELETED` の6種を定義し、現状は録音確定時（`RecordingService.finalizeRecording()`）に`CREATED`、証明書検証時（`RecordingListViewModel.verify()`）に`VERIFIED`、個別削除成功時（`RecordingListViewModel.deleteRecording()`、4.8節）に`DELETED`を記録する。`COPIED/SHARED/EXPORTED`は共有・エクスポート機能実装時に追加する。`actor`は現状は端末のInstallation ID相当（Firebase等は使わず自前でUUID生成しSharedPreferencesに保持）のみで、マルチユーザー識別は将来検討。

### 9.5 TSA発行者証明書チェーンの検証
`CertificateVerifier.verify()`を拡張し、`TimeStampToken.getTimeStampInfo()`の照合に加えて、`SignerInformationVerifier`（Bouncy Castle）でTSA署名証明書の署名検証・有効期限・失効情報（CRL/OCSP、対応TSAが提供する場合）を確認する。信頼するルートCA証明書はアプリ内にプリセット同梱＋設定画面でユーザー追加可能とする。

### 9.6 保存後の読み取り専用化
SAF経由の`DocumentFile`は直接パーミッションビットを持たないため、`DocumentsContract`がサポートするプロバイダでは書き込みFlagを落とす操作を試み、非対応プロバイダ（多くのクラウドDocumentsProvider含む）では「読み取り専用化不可」の旨をUIに明示するに留める。WORM対応が必要な場合は、外部の証跡管理システムへのエクスポート運用をユーザーに委ねる（アプリ側での完全な保証は困難なため過度な約束はしない）。

### 9.7 時刻源の信頼性表示
`SntpClient`（AndroidX等の軽量実装、または自前実装）で録音開始時に主要NTPサーバとシステム時刻の差分を確認し、閾値（例: ±2秒）を超える場合は`RecordingMetadata`に`clockReliability = UNVERIFIED`等のフラグを記録。ネットワーク不通時はチェックをスキップし`clockReliability = UNKNOWN`とする。録音一覧・証明書詳細画面でこのフラグを表示し、時刻証明の信頼性判断材料とする。
