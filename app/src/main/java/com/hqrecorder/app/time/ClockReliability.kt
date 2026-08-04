package com.hqrecorder.app.time

/** 時刻源の信頼性表示(9.7)。録音開始時のシステム時計とNTPサーバとの乖離判定結果。 */
enum class ClockReliability {
    /** NTPサーバとの差分が閾値以内 */
    RELIABLE,
    /** NTPサーバとの差分が閾値を超過 */
    UNVERIFIED,
    /** ネットワーク不通等でNTP確認自体ができなかった */
    UNKNOWN
}
