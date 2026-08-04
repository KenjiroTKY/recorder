package com.hqrecorder.app.certificate.chain

import com.hqrecorder.app.certificate.toHex
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class ChainEntry(
    val index: Int,
    val offsetBytes: Long,
    val hash: String
)

sealed class ChainVerificationResult {
    object Valid : ChainVerificationResult()
    object Empty : ChainVerificationResult()
    data class EntryCountMismatch(val expected: Int, val actual: Int) : ChainVerificationResult()
    data class Tampered(val firstDivergentIndex: Int) : ChainVerificationResult()
}

/**
 * 区間ハッシュチェーン（簡易Merkle chain）を構築・検証する純粋ロジック(SPEC.md 3.6 / DESIGN.md 9.3)。
 * 各エントリのhashは「前区間のhash + 今区間の内容ハッシュ」をSHA-256で連結したもの。
 * 検証時は該当区間の内容ハッシュだけ再計算すればよく、録音全体を送らずとも特定区間の
 * 差し替えを検知できる。
 */
class IntervalHashChainBuilder {
    private val entries = mutableListOf<ChainEntry>()
    private var previousHash = ByteArray(0)

    fun append(offsetBytes: Long, chunkContentHash: ByteArray): ChainEntry {
        val chained = chainHash(previousHash, chunkContentHash)
        val entry = ChainEntry(index = entries.size + 1, offsetBytes = offsetBytes, hash = chained.toHex())
        entries.add(entry)
        previousHash = chained
        return entry
    }

    fun entries(): List<ChainEntry> = entries.toList()

    companion object {
        fun chainHash(previousHash: ByteArray, chunkContentHash: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(previousHash)
            digest.update(chunkContentHash)
            return digest.digest()
        }

        /** 各区間の内容ハッシュを与え、保存済みのentriesと付き合わせて改ざんの有無・区間を判定する。 */
        fun verify(entries: List<ChainEntry>, chunkContentHashes: List<ByteArray>): ChainVerificationResult {
            if (entries.isEmpty()) return ChainVerificationResult.Empty
            if (entries.size != chunkContentHashes.size) {
                return ChainVerificationResult.EntryCountMismatch(entries.size, chunkContentHashes.size)
            }
            var previousHash = ByteArray(0)
            for ((i, entry) in entries.withIndex()) {
                val recomputed = chainHash(previousHash, chunkContentHashes[i])
                if (recomputed.toHex() != entry.hash) {
                    return ChainVerificationResult.Tampered(entry.index)
                }
                previousHash = recomputed
            }
            return ChainVerificationResult.Valid
        }
    }
}
