package net.consensys.pantheon.ethereum.blockcreation;

import net.consensys.pantheon.ethereum.ProtocolContext;
import net.consensys.pantheon.ethereum.core.Address;
import net.consensys.pantheon.ethereum.core.Block;
import net.consensys.pantheon.ethereum.core.BlockBody;
import net.consensys.pantheon.ethereum.core.BlockHeader;
import net.consensys.pantheon.ethereum.core.BlockHeaderBuilder;
import net.consensys.pantheon.ethereum.core.Hash;
import net.consensys.pantheon.ethereum.core.MutableAccount;
import net.consensys.pantheon.ethereum.core.MutableWorldState;
import net.consensys.pantheon.ethereum.core.PendingTransactions;
import net.consensys.pantheon.ethereum.core.ProcessableBlockHeader;
import net.consensys.pantheon.ethereum.core.SealableBlockHeader;
import net.consensys.pantheon.ethereum.core.Wei;
import net.consensys.pantheon.ethereum.core.WorldUpdater;
import net.consensys.pantheon.ethereum.mainnet.BodyValidation;
import net.consensys.pantheon.ethereum.mainnet.DifficultyCalculator;
import net.consensys.pantheon.ethereum.mainnet.MainnetBlockProcessor.TransactionReceiptFactory;
import net.consensys.pantheon.ethereum.mainnet.ProtocolSchedule;
import net.consensys.pantheon.ethereum.mainnet.TransactionProcessor;
import net.consensys.pantheon.util.bytes.BytesValue;
import net.consensys.pantheon.util.uint.UInt256;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractBlockCreator<C> implements AsyncBlockCreator {

  public interface ExtraDataCalculator {

    BytesValue get(final BlockHeader parent);
  }

  private static final Logger LOG = LogManager.getLogger();

  protected final Address coinbase;

  private final Function<Long, Long> gasLimitCalculator;

  private final ExtraDataCalculator extraDataCalculator;
  private final PendingTransactions pendingTransactions;
  private final ProtocolContext<C> protocolContext;
  private final ProtocolSchedule<C> protocolSchedule;
  private final Wei minTransactionGasPrice;
  private final Address miningBeneficiary;
  private final BlockHeader parentHeader;

  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  public AbstractBlockCreator(
      final Address coinbase,
      final ExtraDataCalculator extraDataCalculator,
      final PendingTransactions pendingTransactions,
      final ProtocolContext<C> protocolContext,
      final ProtocolSchedule<C> protocolSchedule,
      final Function<Long, Long> gasLimitCalculator,
      final Wei minTransactionGasPrice,
      final Address miningBeneficiary,
      final BlockHeader parentHeader) {
    this.coinbase = coinbase;
    this.extraDataCalculator = extraDataCalculator;
    this.pendingTransactions = pendingTransactions;
    this.protocolContext = protocolContext;
    this.protocolSchedule = protocolSchedule;
    this.gasLimitCalculator = gasLimitCalculator;
    this.minTransactionGasPrice = minTransactionGasPrice;
    this.miningBeneficiary = miningBeneficiary;
    this.parentHeader = parentHeader;
  }

  /**
   * Create block will create a new block at the head of the blockchain specified in the
   * protocolContext.
   *
   * <p>It will select transactions from the PendingTransaction list for inclusion in the Block
   * body, and will supply an empty Ommers list.
   *
   * <p>Once transactions have been selected and applied to a disposable/temporary world state, the
   * block reward is paid to the relevant coinbase, and a sealable header is constucted.
   *
   * <p>The sealableHeader is then provided to child instances for sealing (i.e. proof of work or
   * otherwise).
   *
   * <p>The constructed block is then returned.
   *
   * @return a block with appropriately selected transactions, seals and ommers.
   */
  @Override
  public Block createBlock(final long timestamp) {
    try {
      final ProcessableBlockHeader processableBlockHeader = createPendingBlockHeader(timestamp);

      throwIfStopped();

      final MutableWorldState disposableWorldState = duplicateWorldStateAtParent();

      throwIfStopped();

      final List<BlockHeader> ommers = selectOmmers();

      throwIfStopped();

      final BlockTransactionSelector.TransactionSelectionResults transactionResults =
          selectTransactions(processableBlockHeader, disposableWorldState);

      throwIfStopped();

      final Wei blockReward =
          protocolSchedule.getByBlockNumber(processableBlockHeader.getNumber()).getBlockReward();

      if (!rewardBeneficiary(disposableWorldState, processableBlockHeader, ommers, blockReward)) {
        LOG.trace("Failed to apply mining reward, exiting.");
        throw new RuntimeException("Failed to apply mining reward.");
      }

      throwIfStopped();

      final BlockHeader parentHeader =
          protocolContext
              .getBlockchain()
              .getBlockHeader(processableBlockHeader.getParentHash())
              .get();

      final SealableBlockHeader sealableBlockHeader =
          BlockHeaderBuilder.create()
              .populateFrom(processableBlockHeader)
              .ommersHash(BodyValidation.ommersHash(ommers))
              .stateRoot(disposableWorldState.rootHash())
              .transactionsRoot(
                  BodyValidation.transactionsRoot(transactionResults.getTransactions()))
              .receiptsRoot(BodyValidation.receiptsRoot(transactionResults.getReceipts()))
              .logsBloom(BodyValidation.logsBloom(transactionResults.getReceipts()))
              .gasUsed(transactionResults.getCumulativeGasUsed())
              .extraData(extraDataCalculator.get(parentHeader))
              .buildSealableBlockHeader();

      final BlockHeader blockHeader = createFinalBlockHeader(sealableBlockHeader);

      return new Block(blockHeader, new BlockBody(transactionResults.getTransactions(), ommers));

    } catch (final CancellationException ex) {
      LOG.trace("Attempt to create block was interrupted.");
      throw ex;
    } catch (final Exception ex) {
      // TODO(tmm): How are we going to know this has exploded, and thus restart it?
      LOG.trace("Block creation failed unexpectedly. Will restart on next block added to chain.");
      throw ex;
    }
  }

  private BlockTransactionSelector.TransactionSelectionResults selectTransactions(
      final ProcessableBlockHeader processableBlockHeader,
      final MutableWorldState disposableWorldState)
      throws RuntimeException {
    final long blockNumber = processableBlockHeader.getNumber();

    final TransactionProcessor transactionProcessor =
        protocolSchedule.getByBlockNumber(blockNumber).getTransactionProcessor();

    final TransactionReceiptFactory transactionReceiptFactory =
        protocolSchedule.getByBlockNumber(blockNumber).getTransactionReceiptFactory();

    final BlockTransactionSelector selector =
        new BlockTransactionSelector(
            transactionProcessor,
            protocolContext.getBlockchain(),
            disposableWorldState,
            pendingTransactions,
            processableBlockHeader,
            transactionReceiptFactory,
            minTransactionGasPrice,
            isCancelled::get,
            miningBeneficiary);

    return selector.buildTransactionListForBlock();
  }

  private MutableWorldState duplicateWorldStateAtParent() {
    final Hash parentStateRoot = parentHeader.getStateRoot();
    final MutableWorldState worldState =
        protocolContext.getWorldStateArchive().getMutable(parentStateRoot);

    return worldState.copy();
  }

  private List<BlockHeader> selectOmmers() {
    return Lists.newArrayList();
  }

  private ProcessableBlockHeader createPendingBlockHeader(final long timestamp) {
    final long newBlockNumber = parentHeader.getNumber() + 1;
    final long gasLimit = gasLimitCalculator.apply(parentHeader.getGasLimit());

    final DifficultyCalculator<C> difficultyCalculator =
        protocolSchedule.getByBlockNumber(newBlockNumber).getDifficultyCalculator();
    final BigInteger difficulty =
        difficultyCalculator.nextDifficulty(timestamp, parentHeader, protocolContext);

    return BlockHeaderBuilder.create()
        .parentHash(parentHeader.getHash())
        .coinbase(coinbase)
        .difficulty(UInt256.of(difficulty))
        .number(newBlockNumber)
        .gasLimit(gasLimit)
        .timestamp(timestamp)
        .buildProcessableBlockHeader();
  }

  @Override
  public void cancel() {
    isCancelled.set(true);
  }

  protected void throwIfStopped() throws CancellationException {
    if (isCancelled.get()) {
      throw new CancellationException();
    }
  }

  /* Copied from BlockProcessor (with modifications). */
  private boolean rewardBeneficiary(
      final MutableWorldState worldState,
      final ProcessableBlockHeader header,
      final List<BlockHeader> ommers,
      final Wei blockReward) {

    // TODO(tmm): Added to make this work, should come from blockProcessor.
    final int MAX_GENERATION = 6;
    if (blockReward.isZero()) {
      return true;
    }
    final Wei coinbaseReward = blockReward.plus(blockReward.times(ommers.size()).dividedBy(32));
    final WorldUpdater updater = worldState.updater();
    final MutableAccount beneficiary = updater.getOrCreate(miningBeneficiary);

    beneficiary.incrementBalance(coinbaseReward);
    for (final BlockHeader ommerHeader : ommers) {
      if (ommerHeader.getNumber() - header.getNumber() > MAX_GENERATION) {
        LOG.trace(
            "Block processing error: ommer block number {} more than {} generations current block number {}",
            ommerHeader.getNumber(),
            MAX_GENERATION,
            header.getNumber());
        return false;
      }

      final MutableAccount ommerCoinbase = updater.getOrCreate(ommerHeader.getCoinbase());
      final long distance = header.getNumber() - ommerHeader.getNumber();
      final Wei ommerReward = blockReward.minus(blockReward.times(distance).dividedBy(8));
      ommerCoinbase.incrementBalance(ommerReward);
    }

    updater.commit();

    return true;
  }

  protected abstract BlockHeader createFinalBlockHeader(
      final SealableBlockHeader sealableBlockHeader);
}
