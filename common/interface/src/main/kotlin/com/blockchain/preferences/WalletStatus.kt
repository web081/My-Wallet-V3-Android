package com.blockchain.preferences

import info.blockchain.balance.CryptoCurrency

interface WalletStatus {
    var lastBackupTime: Long // Seconds since epoch
    val isWalletBackedUp: Boolean

    val isWalletFunded: Boolean
    fun setWalletFunded()

    var lastSwapTime: Long
    val hasSwapped: Boolean

    val hasMadeBitPayTransaction: Boolean
    fun setBitPaySuccess()

    fun setFeeTypeForAsset(cryptoCurrency: CryptoCurrency, type: Int)
    fun getFeeTypeForAsset(cryptoCurrency: CryptoCurrency): Int?
}