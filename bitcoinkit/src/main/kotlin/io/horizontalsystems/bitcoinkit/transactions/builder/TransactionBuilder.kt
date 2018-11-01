package io.horizontalsystems.bitcoinkit.transactions.builder

import io.horizontalsystems.bitcoinkit.core.RealmFactory
import io.horizontalsystems.bitcoinkit.managers.UnspentOutputProvider
import io.horizontalsystems.bitcoinkit.managers.UnspentOutputSelector
import io.horizontalsystems.bitcoinkit.models.PublicKey
import io.horizontalsystems.bitcoinkit.models.Transaction
import io.horizontalsystems.bitcoinkit.models.TransactionInput
import io.horizontalsystems.bitcoinkit.models.TransactionOutput
import io.horizontalsystems.bitcoinkit.scripts.ScriptBuilder
import io.horizontalsystems.bitcoinkit.scripts.ScriptType
import io.horizontalsystems.bitcoinkit.transactions.TransactionSizeCalculator
import io.horizontalsystems.bitcoinkit.utils.AddressConverter
import io.horizontalsystems.hdwalletkit.HDWallet

class TransactionBuilder(private val addressConverter: AddressConverter,
                         private val unspentOutputsSelector: UnspentOutputSelector,
                         private val unspentOutputProvider: UnspentOutputProvider,
                         private val scriptBuilder: ScriptBuilder,
                         private val transactionSizeCalculator: TransactionSizeCalculator,
                         private val inputSigner: InputSigner) {

    constructor(realmFactory: RealmFactory, addressConverter: AddressConverter, wallet: HDWallet)
            : this(addressConverter, UnspentOutputSelector(TransactionSizeCalculator()), UnspentOutputProvider(realmFactory), ScriptBuilder(), TransactionSizeCalculator(), InputSigner(wallet))

    fun fee(value: Int, feeRate: Int, senderPay: Boolean, address: String? = null): Int {
        val outputType = if (address == null) ScriptType.P2PKH else addressConverter.convert(address).scriptType

        val selectedOutputsInfo = unspentOutputsSelector.select(value = value, feeRate = feeRate, outputScriptType = outputType, senderPay = senderPay, unspentOutputs = unspentOutputProvider.allUnspentOutputs())

        val feeWithChangeOutput = if (senderPay) selectedOutputsInfo.fee + transactionSizeCalculator.outputSize(scripType = ScriptType.P2PKH) * feeRate else 0

        return if (selectedOutputsInfo.totalValue > value + feeWithChangeOutput) feeWithChangeOutput else selectedOutputsInfo.fee
    }

    fun buildTransaction(value: Int, toAddress: String, feeRate: Int, senderPay: Boolean, changePubKey: PublicKey, changeScriptType: Int = ScriptType.P2PKH): Transaction {

        val address = addressConverter.convert(toAddress)
        val selectedOutputsInfo = unspentOutputsSelector.select(value = value, feeRate = feeRate, outputScriptType = address.scriptType, senderPay = senderPay, unspentOutputs = unspentOutputProvider.allUnspentOutputs())

        val transaction = Transaction(version = 1, lockTime = 0)

        //add inputs
        for (output in selectedOutputsInfo.outputs) {
            val previousTx = checkNotNull(output.transaction) {
                throw TransactionBuilderException.NoPreviousTransaction()
            }
            val txInput = TransactionInput().apply {
                previousOutputHash = previousTx.hash
                previousOutputHexReversed = previousTx.hashHexReversed
                previousOutputIndex = output.index.toLong()
            }
            txInput.previousOutput = output
            transaction.inputs.add(txInput)
        }

        //add output
        transaction.outputs.add(TransactionOutput().apply {
            this.value = 0
            this.index = 0
            this.lockingScript = scriptBuilder.lockingScript(address)
            this.scriptType = address.scriptType
            this.address = address.string
            this.keyHash = address.hash
        })

        //calculate fee and add change output if needed
        check(senderPay || selectedOutputsInfo.fee < value) {
            throw TransactionBuilderException.FeeMoreThanValue()
        }

        val receivedValue = if (senderPay) value else value - selectedOutputsInfo.fee
        val sentValue = if (senderPay) value + selectedOutputsInfo.fee else value

        transaction.outputs[0]?.value = receivedValue.toLong()

        if (selectedOutputsInfo.totalValue > sentValue + transactionSizeCalculator.outputSize(scripType = changeScriptType) * feeRate) {
            val changeAddress = addressConverter.convert(changePubKey.publicKeyHash, changeScriptType)
            transaction.outputs.add(TransactionOutput().apply {
                this.value = selectedOutputsInfo.totalValue - sentValue
                this.index = 1
                this.lockingScript = scriptBuilder.lockingScript(changeAddress)
                this.scriptType = changeScriptType
                this.address = changeAddress.string
                this.keyHash = changeAddress.hash
                this.publicKey = changePubKey
            })
        }

        //sign inputs
        transaction.inputs.forEachIndexed { index, transactionInput ->
            val sigScriptData = inputSigner.sigScriptData(transaction, index)
            transactionInput?.sigScript = scriptBuilder.unlockingScript(sigScriptData)
        }

        transaction.status = Transaction.Status.NEW
        transaction.isMine = true
        transaction.setHashes()

        return transaction
    }

    open class TransactionBuilderException : Exception() {
        class NoPreviousTransaction : TransactionBuilderException()
        class FeeMoreThanValue : TransactionBuilderException()
    }

}