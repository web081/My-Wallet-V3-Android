package piuk.blockchain.android.coincore.xlm

import com.blockchain.fees.FeeType
import com.blockchain.sunriver.Memo
import com.blockchain.sunriver.SendDetails
import com.blockchain.sunriver.XlmDataManager
import com.blockchain.sunriver.XlmFeesFetcher
import info.blockchain.balance.AccountReference
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.Money
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.rxkotlin.Singles
import piuk.blockchain.android.coincore.CryptoAddress
import piuk.blockchain.android.coincore.FeeLevel
import piuk.blockchain.android.coincore.PendingTx
import piuk.blockchain.android.coincore.TxOption
import piuk.blockchain.android.coincore.TxOptionValue
import piuk.blockchain.android.coincore.TxResult
import piuk.blockchain.android.coincore.TxValidationFailure
import piuk.blockchain.android.coincore.ValidationState
import piuk.blockchain.android.coincore.impl.txEngine.OnChainTxEngineBase
import piuk.blockchain.android.coincore.updateTxValidity
import piuk.blockchain.androidcore.data.walletoptions.WalletOptionsDataManager
import piuk.blockchain.androidcore.utils.extensions.then

class XlmOnChainTxEngine(
    private val xlmDataManager: XlmDataManager,
    private val xlmFeesFetcher: XlmFeesFetcher,
    private val walletOptionsDataManager: WalletOptionsDataManager,
    requireSecondPassword: Boolean
) : OnChainTxEngineBase(requireSecondPassword) {

    private val targetXlmAddress: XlmAddress by lazy {
        txTarget as XlmAddress
    }

    override fun assertInputsValid() {
        require(txTarget is CryptoAddress)
        require((txTarget as CryptoAddress).asset == CryptoCurrency.XLM)
        require(asset == CryptoCurrency.XLM)
    }

    override fun doInitialiseTx(): Single<PendingTx> =
        Single.just(
            PendingTx(
                amount = CryptoValue.ZeroXlm,
                available = CryptoValue.ZeroXlm,
                fees = CryptoValue.ZeroXlm,
                feeLevel = FeeLevel.Regular,
                selectedFiat = userFiat
            )
        )

    override fun doUpdateAmount(amount: Money, pendingTx: PendingTx): Single<PendingTx> {
        require(amount is CryptoValue)
        require(amount.currency == CryptoCurrency.XLM)

        return Singles.zip(
            sourceAccount.actionableBalance.map { it as CryptoValue },
            absoluteFee()
        ) { available, fees ->
            pendingTx.copy(
                amount = amount,
                available = Money.max(available - fees, CryptoValue.ZeroXlm) as CryptoValue,
                fees = fees
            )
        }
    }

    private fun absoluteFee(): Single<CryptoValue> =
        xlmFeesFetcher.operationFee(FeeType.Regular)

    override fun doValidateAmount(pendingTx: PendingTx): Single<PendingTx> =
        validateAmounts(pendingTx)
            .then { validateSufficientFunds(pendingTx) }
            .updateTxValidity(pendingTx)

    private fun validateAmounts(pendingTx: PendingTx): Completable =
        Completable.fromCallable {
            if (pendingTx.amount <= CryptoValue.ZeroXlm) {
                throw TxValidationFailure(ValidationState.INVALID_AMOUNT)
            }
        }

    private fun validateSufficientFunds(pendingTx: PendingTx): Completable =
        Singles.zip(
            sourceAccount.actionableBalance,
            absoluteFee()
        ) { balance: Money, fee: Money ->
            if (fee + pendingTx.amount > balance) {
                throw TxValidationFailure(ValidationState.INSUFFICIENT_FUNDS)
            } else {
                true
            }
        }.ignoreElement()

    override fun doBuildConfirmations(pendingTx: PendingTx): Single<PendingTx> =
        Single.just(
            pendingTx.copy(
                options = listOf(
                    TxOptionValue.From(from = sourceAccount.label),
                    TxOptionValue.To(to = txTarget.label),
                    TxOptionValue.Fee(fee = pendingTx.fees,
                        exchange = pendingTx.fees.toFiat(exchangeRates, userFiat)),
                    TxOptionValue.FeedTotal(
                        amount = pendingTx.amount,
                        fee = pendingTx.fees,
                        exchangeFee = pendingTx.fees.toFiat(exchangeRates, userFiat),
                        exchangeAmount = pendingTx.amount.toFiat(exchangeRates, userFiat)
                    ),
                    TxOptionValue.Memo(
                        text = targetXlmAddress.memo,
                        isRequired = isMemoRequired(),
                        id = null
                    )
                )
            )
        )

    private fun String.containsMemo() = contains(":")

    private fun isMemoRequired(): Boolean =
        walletOptionsDataManager.isXlmAddressExchange(targetXlmAddress.address)

    private fun isMemoValid(memoOption: TxOptionValue.Memo): Boolean {
        return if (!isMemoRequired()) {
            true
        } else {
            !memoOption.text.isNullOrEmpty() && memoOption.text.length in 1..28 ||
                    memoOption.id != null
        }
    }

    private fun validateOptions(pendingTx: PendingTx): Completable =
        Completable.fromCallable {
            if (!isMemoValid(getMemoOption(pendingTx))) {
                throw TxValidationFailure(ValidationState.OPTION_INVALID)
            }
        }

    override fun doValidateAll(pendingTx: PendingTx): Single<PendingTx> =
        validateAddress()
            .then { validateAmounts(pendingTx) }
            .then { validateSufficientFunds(pendingTx) }
            .then { validateOptions(pendingTx) }
            .then { validateDryRun(pendingTx) }
            .updateTxValidity(pendingTx)

    private fun validateAddress() =
        Completable.fromCallable {
            if (!xlmDataManager.isAddressValid(targetXlmAddress.address)) {
                throw TxValidationFailure(ValidationState.INVALID_ADDRESS)
            }
        }

    private fun validateDryRun(pendingTx: PendingTx): Completable =
        createTransaction(pendingTx).flatMap { sendDetails ->
            xlmDataManager.dryRunSendFunds(
                sendDetails
            )
        }.map {
            when (it.errorCode) {
                UNKNOWN_ERROR -> throw TxValidationFailure(ValidationState.UNKNOWN_ERROR)
                BELOW_MIN_SEND -> throw TxValidationFailure(ValidationState.UNDER_MIN_LIMIT)
                BELOW_MIN_NEW_ACCOUNT -> throw TxValidationFailure(ValidationState.UNDER_MIN_LIMIT)
                INSUFFICIENT_FUNDS -> throw TxValidationFailure(ValidationState.INSUFFICIENT_FUNDS)
                BAD_DESTINATION_ACCOUNT_ID -> throw TxValidationFailure(ValidationState.INVALID_ADDRESS)
                SUCCESS -> {
                    // do nothing
                }
                else -> throw TxValidationFailure(ValidationState.UNKNOWN_ERROR)
            }
        }.ignoreElement()

    private fun getMemoOption(pendingTx: PendingTx) =
        pendingTx.getOption<TxOptionValue.Memo>(TxOption.MEMO) ?: throw IllegalStateException(
            "XLM memo option null")

    private fun TxOptionValue.Memo.toXlmMemo(): Memo =
        if (!this.text.isNullOrEmpty()) {
            Memo(this.text, Memo.MEMO_TYPE_TEXT)
        } else if (this.id != null) {
            Memo(this.id.toString(), Memo.MEMO_TYPE_ID)
        } else {
            Memo("")
        }

    override fun doExecute(pendingTx: PendingTx, secondPassword: String): Single<TxResult> =
        createTransaction(pendingTx).flatMap { sendDetails ->
            xlmDataManager.sendFunds(sendDetails)
        }.map {
            TxResult.HashedTxResult(it.txHash, pendingTx.amount)
        }

    private fun createTransaction(pendingTx: PendingTx): Single<SendDetails> =
        sourceAccount.receiveAddress.map { receiveAddress ->
            SendDetails(
                from = AccountReference.Xlm(
                    sourceAccount.label,
                    (receiveAddress as XlmAddress).address
                ),
                value = pendingTx.amount as CryptoValue,
                toAddress = targetXlmAddress.address,
                toLabel = "",
                fee = pendingTx.fees as CryptoValue,
                memo = getMemoOption(pendingTx).toXlmMemo()
            )
        }

    companion object {
        // These map 1:1 to FailureReason enum class in HorizonProxy

        const val SUCCESS = 0

        const val UNKNOWN_ERROR = 1

        /**
         * The amount attempted to be sent was below that which we allow.
         */
        const val BELOW_MIN_SEND = 2

        /**
         * The destination does exist and a send was attempted that did not fund it
         * with at least the minimum balance for an Horizon account.
         */
        const val BELOW_MIN_NEW_ACCOUNT = 3

        /**
         * The amount attempted to be sent would not leave the source account with at
         * least the minimum balance required for an Horizon account.
         */
        const val INSUFFICIENT_FUNDS = 4

        /**
         * The destination account id is not valid.
         */
        const val BAD_DESTINATION_ACCOUNT_ID = 5
    }
}