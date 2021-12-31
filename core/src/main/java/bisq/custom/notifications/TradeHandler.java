package bisq.custom.notifications;

import bisq.core.trade.TradeManager;
import bisq.core.trade.model.bisq_v1.Trade;

import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import javax.inject.Singleton;

import javafx.collections.ListChangeListener;

import java.io.File;
import java.io.IOException;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

@Slf4j
@Singleton
public class TradeHandler {

    private final TradeManager tradeManager;
    private final KeyRing keyRing;
    private final File tradeDataDir;
    private final File tradeDataBackupDir;
    private final String tradeNotificationCli;

    @Inject
    public TradeHandler(TradeManager tradeManager, KeyRing keyRing, TradeNotificationCliLocator cliLocator) {

        log.info("Instantiating TradeHandler class");

        this.tradeManager = tradeManager;
        this.keyRing = keyRing;
        String tradeDataDir = Config.appDataDir().getAbsolutePath() + "/trade_data";
        this.tradeDataDir = new File(tradeDataDir);
        if (!this.tradeDataDir.exists()) {
            this.tradeDataDir.mkdir();
        }
        String tradeDataBackupDir = tradeDataDir + "/backup";
        this.tradeDataBackupDir = new File(tradeDataBackupDir);
        if (!this.tradeDataBackupDir.exists()) {
            this.tradeDataBackupDir.mkdir();
        }
        this.tradeNotificationCli = cliLocator.getTradeNotificationCliAbsolutePath();
    }

    public void onAllServicesInitialized() {
        tradeManager.getObservableList().addListener((ListChangeListener<Trade>) c -> {
            c.next();
            if (c.wasAdded()) {
                c.getAddedSubList().forEach(this::setTradePhaseListener);
            }
        });
        tradeManager.getObservableList().forEach(this::setTradePhaseListener);
    }

    private void setTradePhaseListener(Trade trade) {
        log.info("We got a new trade. id={}", trade.getId());
        if (!trade.isPayoutPublished()) {
            trade.statePhaseProperty().addListener((observable, oldValue, newValue) -> {
                if (persistTradeData(trade)) {
                    // success
                    notifyTradeCli(trade);
                }
                persistTradePhase(trade);
                if (newValue == Trade.Phase.PAYOUT_PUBLISHED) {
                    backupFile(getTradeDataFile(trade));
                    backupFile(getTradePhasesFile(trade));
                    backupFile(getCliOutputFile(trade));
                }
            });
        }
    }

    private void backupFile(File file) {
        if (file.exists()) {
            file.renameTo(new File(tradeDataBackupDir.getAbsolutePath() + "/" + file.getName()));
        }
    }

    private boolean persistTradeData(Trade trade) {
        File tradeDataFile = getTradeDataFile(trade);
        TradeDTO tradeDTO = new TradeDTO(
                trade.getId(),
                trade.getTradePhase(),
                trade.getTakeOfferDate(),
                trade.getOffer().isBuyOffer(),
                trade.getOffer().isMyOffer(keyRing),
                trade.getAmountAsLong(),
                trade.getPrice().getValue(),
                trade.getPrice().getCurrencyCode(),
                trade.getVolume() == null ? -1 : trade.getVolume().getValue(),
                trade.getOffer().getMakerFee().getValue(),
                trade.getOffer().isCurrencyForMakerFeeBtc(),
                trade.getOffer().getTxFee().getValue(),
                trade.getTakerFeeAsLong(),
                trade.isCurrencyForTakerFeeBtc(),
                trade.getTradeTxFeeAsLong()
        );
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            objectMapper.writeValue(tradeDataFile, tradeDTO);
            return true;
        } catch (IOException ex) {
            log.error("Unable to persist trade data file='{}'", tradeDataFile.getAbsolutePath());
            return false;
        }
    }

    private void notifyTradeCli(Trade trade) {
        if (tradeNotificationCli == null) {
            return;
        }
        File tradeDataFile = getTradeDataFile(trade);
        if (!tradeDataFile.exists()) {
            log.error("Trade data file does not exist, file='{}'", tradeDataFile.getAbsolutePath());
            return;
        }
        Process p;
        try {
            p = Runtime.getRuntime().exec(tradeNotificationCli + " " + tradeDataFile.getAbsolutePath() + " " + getCliOutputFile(trade).getAbsolutePath());
        } catch (IOException e) {
            log.error("Error executing trade notification cli binary='{}'", tradeNotificationCli);
            return;
        }
        try {
            boolean finished = p.waitFor(1, TimeUnit.SECONDS);
            if (finished) {
                log.info("Trade notification cli binary='{}' finished successfully", tradeNotificationCli);
            } else {
                log.error("Timed out while waiting for finish of trade notification cli binary='{}'", tradeNotificationCli);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for finish of trade notification cli binary='{}'", tradeNotificationCli);
        }

    }

    private void persistTradePhase(Trade trade) {
        File tradePhasesFile = getTradePhasesFile(trade);
        ObjectMapper objectMapper = new ObjectMapper();
        TradePhasesDTO phases = null;
        if (tradePhasesFile.exists()) {
            // Read trade phases from file
            try {
                phases = objectMapper.readValue(tradePhasesFile, TradePhasesDTO.class);
            } catch (IOException e) {
                log.error("Unable to read trade phases file='{}'", tradePhasesFile.getAbsolutePath());
            }
        }
        if (phases == null) {
            phases = new TradePhasesDTO();
        }
        phases.getPhases().add(new TradePhasesDTO.TradePhaseItem(new Date().getTime(), trade.statePhaseProperty().getValue().name()));
        try {
            objectMapper.writeValue(tradePhasesFile, phases);
        } catch (IOException e) {
            log.error("Unable to persist trade phases file='{}'", tradePhasesFile.getAbsolutePath());
        }
    }

    @NotNull
    private File getTradeFile(Trade trade, String suffix) {
        return new File(tradeDataDir.getAbsolutePath() + "/" + trade.getId() + "." + suffix);
    }

    @NotNull
    private File getTradeDataFile(Trade trade) {
        return getTradeFile(trade, "trade.json");
    }

    @NotNull
    private File getTradePhasesFile(Trade trade) {
        return getTradeFile(trade, "phases.json");
    }

    @NotNull
    private File getCliOutputFile(Trade trade) {
        return getTradeFile(trade, "cli-output.json");
    }
}
