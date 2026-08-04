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
DataStore Preferencesに保存: 選択中の音質、保存先フォルダURI、電子証明書有効/無効、TSA URL、TSA認証ヘッダ。

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

## 5. 設計判断・簡略化した点（MVPでの割り切り）

| 項目 | 採用した設計 | 理由 |
|---|---|---|
| 録音時の書き込み先 | 一時的にアプリ内部キャッシュへ書き込み、停止後にSAF宛先へコピー | SAFのUriは提供元（特にクラウド系DocumentsProvider）によってはシーク不可なストリームしか得られず、WAVヘッダの後書き（サイズ確定）ができない。ローカルの`RandomAccessFile`で確定してからコピーする方式にして信頼性を優先した。 |
| 録音履歴の永続化 | Room DBではなくJSONファイル（kotlinx.serialization） | 件数が数千件規模になりにくいICレコーダー用途では過剰。KSP/アノテーション処理の依存を減らしビルドの堅牢性を優先。 |
| 証明書の自動リトライ | WorkManagerではなく、一覧画面からの手動再試行 | 初期リリースではネットワーク制約付きバックグラウンドジョブの複雑さを避けた。将来WorkManagerへの置き換えは容易な構造にしている。 |
| 通話等によるマイク割り込み処理 | `AudioManager`のAudioFocusコールバックのみで検知（4.7節） | AudioFocus APIは本来再生用だが、通話アプリ等は着信/通話開始時に`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`相当でフォーカスを要求するため、間接的な割り込み検知の手段として利用する。`PhoneStateListener`/`READ_PHONE_STATE`によるより厳密な通話状態検知や、Bluetooth接続変化の個別ブロードキャスト受信は権限スコープ拡大・実装コストの観点からスコープ外とし、将来の拡張ポイントとする。 |
| 24bit WAV | `AudioRecord`の`ENCODING_PCM_FLOAT`で取得しアプリ側で24bit整数へ変換。非対応機種は16bit/モノラルへ自動フォールバック | Android標準APIには24bit整数の直接指定がないため。 |

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
- ノイズリダクション/AGCの明示トグル（現状は端末依存でUNPROCESSEDを優先取得するのみ）
- タブレット/外付けマイク（USB-Audio）対応
- 証拠性強化機能（SPEC.md 3.6）→ 詳細は9節

## 9. Phase 3設計方針: 証拠性強化機能（SPEC.md 3.6対応）

証拠性強化機能は7項目のうち9.3・9.4を実装済み、残り（9.1, 9.2, 9.5〜9.7）は設計方針の整理に留め実装は今後のissueで行う。既存の`certificate/`パッケージを拡張する形とし、新規パッケージは設けない想定（`certificate/custody/`, `certificate/signing/` のサブパッケージ程度に留める。9.3は`certificate/chain/`）。

### 9.1 開始時刻証明
録音開始時（`RecordingService.startRecording()`直後）に乱数シード（`SecureRandom`生成のnonce）を生成し、そのハッシュを3.5と同じ`TimestampClient`経由でTSAへ送信・トークン化。`RecordingMetadata`に`startCertificateFileUri`として保持し、終了時証明（既存の`certificateFileUri`）とペアで「開始時刻〜終了時刻の間に生成された」ことを示す。

### 9.2 端末鍵による電子署名
`android.security.keystore.KeyGenParameterSpec`でECDSA鍵ペアをAndroid Keystoreに生成（`setIsStrongBoxBacked(true)`を端末対応時は優先指定、非対応時はTEEへフォールバック）。鍵はエクスポート不可（`setUserAuthenticationRequired`は録音の自動化を妨げるため要件からは外す）。録音確定時のファイルハッシュに対し`Signature`APIで署名し、`<ファイル名>.sig`として保存。署名検証用の公開鍵は`<ファイル名>.pub`として同時にエクスポート（第三者が端末にアクセスできなくても検証できるようにするため）。

### 9.3 区間ハッシュチェーン（実装済み）
`StereoAudioRecorder`が音声読み取りスレッドとは別の専用スレッド（`StereoAudioRecorder-Chain`、優先度`MIN_PRIORITY`）で1秒間隔にポーリングし、一定間隔（デフォルト30秒、`chainIntervalMs`で変更可）ごとに前回チェックポイント以降の新規区間だけをファイルから読み直してSHA-256を算出する（`audio/IntervalHashChainRecorder`）。各区間ハッシュは「前区間のハッシュ＋今区間の内容ハッシュ」を連結して再ハッシュする方式（簡易Merkle chain、`certificate/chain/IntervalHashChainBuilder`、純粋ロジックとしてユニットテスト対象）とし、パート確定時に`<ファイル名>.chain.json`へ区間ごとの `{ index, offsetBytes, hash }` のリストとして保存、SAF保存先フォルダへ音声ファイルとあわせてコピーされる。これにより録音全体を送らずとも特定区間のみの差し替えを検知可能。

WAVはヘッダのサイズフィールドを`stop()`時に書き戻すため、ヘッダ領域を区間ハッシュの対象に含めると「ヘッダ確定」自体が誤検知の原因になる。これを避けるため、`openNewPart()`で`writer.start()`直後（まだ音声データを書き込む前）のオフセットを起点とし、ヘッダ領域はチェーンの対象外とする。

### 9.4 Chain of Custodyログ（実装済み）
録音メタデータとは別に`custody_log.jsonl`（追記専用JSON Lines、app内部ストレージ）を導入（`certificate/custody/CustodyLogManager`）。各エントリは `{ timestampEpochMs, action, actor, targetRecordingId, prevEntryHash, entryHash }` を持ち、`entryHash`は自分以外の全フィールド+`prevEntryHash`のSHA-256とすることで改ざん時に連鎖が破綻し検知できるようにする（連結・検証ロジックは`certificate/custody/CustodyLogChain`として純粋関数に分離しユニットテスト対象）。`action`は `CREATED / COPIED / SHARED / VERIFIED / EXPORTED` の5種を定義し、現状は録音確定時（`RecordingService.finalizeRecording()`）に`CREATED`、証明書検証時（`RecordingListViewModel.verify()`）に`VERIFIED`を記録する。`COPIED/SHARED/EXPORTED`は共有・エクスポート機能実装時に追加する。`actor`は現状は端末のInstallation ID相当（Firebase等は使わず自前でUUID生成しSharedPreferencesに保持）のみで、マルチユーザー識別は将来検討。

### 9.5 TSA発行者証明書チェーンの検証
`CertificateVerifier.verify()`を拡張し、`TimeStampToken.getTimeStampInfo()`の照合に加えて、`SignerInformationVerifier`（Bouncy Castle）でTSA署名証明書の署名検証・有効期限・失効情報（CRL/OCSP、対応TSAが提供する場合）を確認する。信頼するルートCA証明書はアプリ内にプリセット同梱＋設定画面でユーザー追加可能とする。

### 9.6 保存後の読み取り専用化
SAF経由の`DocumentFile`は直接パーミッションビットを持たないため、`DocumentsContract`がサポートするプロバイダでは書き込みFlagを落とす操作を試み、非対応プロバイダ（多くのクラウドDocumentsProvider含む）では「読み取り専用化不可」の旨をUIに明示するに留める。WORM対応が必要な場合は、外部の証跡管理システムへのエクスポート運用をユーザーに委ねる（アプリ側での完全な保証は困難なため過度な約束はしない）。

### 9.7 時刻源の信頼性表示
`SntpClient`（AndroidX等の軽量実装、または自前実装）で録音開始時に主要NTPサーバとシステム時刻の差分を確認し、閾値（例: ±2秒）を超える場合は`RecordingMetadata`に`clockReliability = UNVERIFIED`等のフラグを記録。ネットワーク不通時はチェックをスキップし`clockReliability = UNKNOWN`とする。録音一覧・証明書詳細画面でこのフラグを表示し、時刻証明の信頼性判断材料とする。
