package piuk.blockchain.android.coincore.impl.txEngine

import com.blockchain.koin.scopedInject
import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import info.blockchain.balance.Money
import io.reactivex.Completable
import io.reactivex.Single
import org.koin.core.KoinComponent
import piuk.blockchain.android.coincore.CryptoAccount
import piuk.blockchain.android.coincore.PendingTx
import piuk.blockchain.android.coincore.TransactionTarget
import piuk.blockchain.android.coincore.TxEngine
import piuk.blockchain.android.coincore.TxOption
import piuk.blockchain.android.coincore.TxOptionValue
import piuk.blockchain.android.coincore.TxResult
import piuk.blockchain.android.coincore.ValidationState
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager

class InterestDepositTxEngine(
    private val onChainTxEngine: OnChainTxEngineBase
) : TxEngine(), KoinComponent {

    private val custodialWalletManager: CustodialWalletManager by scopedInject()

    override fun start(
        sourceAccount: CryptoAccount,
        txTarget: TransactionTarget,
        exchangeRates: ExchangeRateDataManager
    ) {
        super.start(sourceAccount, txTarget, exchangeRates)
        onChainTxEngine.start(sourceAccount, txTarget, exchangeRates)
    }

    override fun doInitialiseTx(): Single<PendingTx> =
        onChainTxEngine.doInitialiseTx()
            .flatMap { pendingTx ->
                custodialWalletManager.getInterestLimits(asset).toSingle().map {
                    pendingTx.copy(minLimit = it.minDepositAmount)
                }
            }

    override fun doUpdateAmount(amount: Money, pendingTx: PendingTx): Single<PendingTx> =
        onChainTxEngine.doUpdateAmount(amount, pendingTx)

    override fun doBuildConfirmations(pendingTx: PendingTx): Single<PendingTx> =
        onChainTxEngine.doBuildConfirmations(pendingTx).map { pTx ->
            modifyEngineConfirmations(pTx)
        }

    private fun modifyEngineConfirmations(
        pendingTx: PendingTx,
        termsChecked: Boolean = false,
        agreementChecked: Boolean = false
    ): PendingTx =
        pendingTx.removeOption(TxOption.DESCRIPTION)
            .addOrReplaceOption(
                TxOptionValue.TxBooleanOption<Unit>(
                    option = TxOption.AGREEMENT_INTEREST_T_AND_C,
                    value = termsChecked
                )
            )
            .addOrReplaceOption(
                TxOptionValue.TxBooleanOption(
                    option = TxOption.AGREEMENT_INTEREST_TRANSFER,
                    data = pendingTx.amount,
                    value = agreementChecked
                )
            )

    override fun doOptionUpdateRequest(pendingTx: PendingTx, newOption: TxOptionValue): Single<PendingTx> =
        if (newOption.option in setOf(TxOption.AGREEMENT_INTEREST_T_AND_C, TxOption.AGREEMENT_INTEREST_TRANSFER)) {
            Single.just(pendingTx.addOrReplaceOption(newOption))
        } else {
            onChainTxEngine.doOptionUpdateRequest(pendingTx, newOption)
                .map { pTx ->
                    modifyEngineConfirmations(
                        pendingTx = pTx,
                        termsChecked = getTermsOptionValue(pendingTx),
                        agreementChecked = getAgreementOptionValue(pendingTx)
                    )
                }
        }

    override fun doValidateAmount(pendingTx: PendingTx): Single<PendingTx> =
        onChainTxEngine.doValidateAmount(pendingTx)
            .map {
                if (it.amount.isPositive && it.amount < it.minLimit!!) {
                    it.copy(validationState = ValidationState.UNDER_MIN_LIMIT)
                } else {
                    it
                }
            }

    override fun doValidateAll(pendingTx: PendingTx): Single<PendingTx> =
        onChainTxEngine.doValidateAll(pendingTx)
            .map {
                if (it.validationState == ValidationState.CAN_EXECUTE && !areOptionsValid(pendingTx)) {
                    it.copy(validationState = ValidationState.OPTION_INVALID)
                } else {
                    it
                }
            }

    private fun areOptionsValid(pendingTx: PendingTx): Boolean {
        val terms = getTermsOptionValue(pendingTx)
        val agreement = getAgreementOptionValue(pendingTx)
        return (terms && agreement)
    }

    private fun getTermsOptionValue(pendingTx: PendingTx): Boolean =
        pendingTx.getOption<TxOptionValue.TxBooleanOption<Unit>>(
            TxOption.AGREEMENT_INTEREST_T_AND_C
        )?.value ?: false

    private fun getAgreementOptionValue(pendingTx: PendingTx): Boolean =
        pendingTx.getOption<TxOptionValue.TxBooleanOption<Money>>(
            TxOption.AGREEMENT_INTEREST_TRANSFER
        )?.value ?: false

    override fun doExecute(pendingTx: PendingTx, secondPassword: String): Single<TxResult> =
        onChainTxEngine.doExecute(pendingTx, secondPassword)

    override fun doPostExecute(txResult: TxResult): Completable = txTarget.onTxCompleted(txResult)
}
