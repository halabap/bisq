package bisq.custom.notifications;

import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.trade.TradeManager;
import bisq.core.trade.model.bisq_v1.Trade;

import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;

import org.bitcoinj.core.Coin;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.io.IOUtils;

import javafx.beans.property.SimpleObjectProperty;

import com.sun.javafx.collections.ObservableListWrapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.mockito.Mockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@Slf4j
public class TradeHandlerTest {

    TradeManager tradeManager;
    KeyRing keyRing;
    PubKeyRing pubKeyRing;
    Config config;
    Trade trade1;
    SimpleObjectProperty<Trade.Phase> trade1Phase;
    Offer trade1Offer;
    Price trade1Price;
    Coin trade1MakerFee;
    Coin trade1TakerFee;
    Volume trade1Volume;
    Coin tradeTxFee;
    TradeNotificationCliLocator cliLocator;

    @BeforeClass
    public static void beforeClass() {
    }

    @Before
    public void before() {
        tradeManager = Mockito.mock(TradeManager.class);
        keyRing = Mockito.mock(KeyRing.class);
        pubKeyRing = Mockito.mock(PubKeyRing.class);
        Mockito.when(keyRing.getPubKeyRing()).thenReturn(pubKeyRing);
        config = new Config();
        trade1 = Mockito.mock(Trade.class);
        trade1Phase = new SimpleObjectProperty<>(Trade.Phase.INIT);
        Mockito.when(trade1.statePhaseProperty()).thenReturn(trade1Phase);
        trade1Offer = Mockito.mock(Offer.class);
        Mockito.when(trade1.getOffer()).thenReturn(trade1Offer);
        trade1Price = Mockito.mock(Price.class);
        Mockito.when(trade1.getPrice()).thenReturn(trade1Price);
        trade1MakerFee = Mockito.mock(Coin.class);
        Mockito.when(trade1Offer.getMakerFee()).thenReturn(trade1MakerFee);
        trade1TakerFee = Mockito.mock(Coin.class);
        Mockito.when(trade1.getTakerFee()).thenReturn(trade1TakerFee);
        trade1Volume = Mockito.mock(Volume.class);
        Mockito.when(trade1.getVolume()).thenReturn(trade1Volume);
        tradeTxFee = Mockito.mock(Coin.class);
        Mockito.when(trade1.getTradeTxFee()).thenReturn(tradeTxFee);
        cliLocator = Mockito.mock(TradeNotificationCliLocator.class);
    }

    @After
    public void after() {
        config.appDataDir.delete();
    }

    @Test
    public void testTradeHandlerCreation() {
        File file = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/backup");
        Assert.assertFalse(file.exists());
        TradeHandler tradeHandler = new TradeHandler(tradeManager, keyRing, cliLocator);
        Assert.assertTrue(file.exists());
    }

    @Test
    public void testTradeListenerOnTakerFeePublished() {
        mockTrade1();

        TradeHandler tradeHandler = new TradeHandler(tradeManager, keyRing, cliLocator);
        tradeHandler.onAllServicesInitialized();
        trade1Phase.set(Trade.Phase.TAKER_FEE_PUBLISHED);

        Mockito.verify(trade1, Mockito.times(1)).isPayoutPublished();

        File tradeFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.trade.json");
        Assert.assertTrue(tradeFile.exists());

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            TradeDTO tradeDTO = objectMapper.readValue(tradeFile, TradeDTO.class);
            assertEquals("trade1Id", tradeDTO.getId());
            assertEquals(Trade.Phase.TAKER_FEE_PUBLISHED, tradeDTO.getTradePhase());
            assertEquals(123L, tradeDTO.getTakeOfferDate());
            assertFalse(tradeDTO.isBuyOffer());
            assertTrue(tradeDTO.isMyOffer());
            assertEquals(700_000L, tradeDTO.getAmountSatoshi());
            assertEquals(10_000L, tradeDTO.getPriceValue());
            assertEquals("EUR", tradeDTO.getPriceCurrencyCode());
            assertEquals(10_000L * 700_000L, tradeDTO.getVolume());
            assertEquals(444L, tradeDTO.getMakerFee());
            assertTrue(tradeDTO.isCurrencyForMakerFeeBtc());
            assertEquals(445L, tradeDTO.getTakerFee());
            assertTrue(tradeDTO.isCurrencyForTakerFeeBtc());
            assertEquals(500L, tradeDTO.getTradeTxFee());

        } catch (IOException e) {
            e.printStackTrace();
            Assert.fail("unexpected IOException");
        }
    }

    @Test
    public void testTradePhaseLog() {
        mockTrade1();

        TradeHandler tradeHandler = new TradeHandler(tradeManager, keyRing, cliLocator);
        tradeHandler.onAllServicesInitialized();
        trade1.setState(Trade.State.PREPARATION);
        trade1Phase.set(Trade.Phase.TAKER_FEE_PUBLISHED);
        trade1Phase.set(Trade.Phase.DEPOSIT_PUBLISHED);
        trade1Phase.set(Trade.Phase.DEPOSIT_CONFIRMED);

        Mockito.verify(trade1, Mockito.times(1)).isPayoutPublished();

        File phasesFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.phases.json");
        Assert.assertTrue(phasesFile.exists());

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            TradePhasesDTO phases = objectMapper.readValue(phasesFile, TradePhasesDTO.class);
            Assert.assertEquals(3, phases.getPhases().size());
            Assert.assertEquals("TAKER_FEE_PUBLISHED", phases.getPhases().get(0).getPhase());
            Assert.assertEquals("DEPOSIT_PUBLISHED", phases.getPhases().get(1).getPhase());
            Assert.assertEquals("DEPOSIT_CONFIRMED", phases.getPhases().get(2).getPhase());

        } catch (IOException e) {
            e.printStackTrace();
            Assert.fail("unexpected IOException");
        }
    }

    @Test
    public void testBackupOnPayoutPublished() {

        mockTrade1();
        mockCliLocator(0);

        TradeHandler tradeHandler = new TradeHandler(tradeManager, keyRing, cliLocator);
        tradeHandler.onAllServicesInitialized();
        trade1Phase.set(Trade.Phase.TAKER_FEE_PUBLISHED);
        trade1Phase.set(Trade.Phase.PAYOUT_PUBLISHED);

        File tradeFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.trade.json");
        File tradeBackupFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/backup/trade1Id.trade.json");
        File phasesFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.phases.json");
        File phasesBackupFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/backup/trade1Id.phases.json");
        File cliOutputFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.cli-output.json");
        File cliOutputBackupFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/backup/trade1Id.cli-output.json");
        Assert.assertFalse(tradeFile.exists());
        Assert.assertTrue(tradeBackupFile.exists());
        Assert.assertFalse(phasesFile.exists());
        Assert.assertTrue(phasesBackupFile.exists());
        Assert.assertFalse(cliOutputFile.exists());
        Assert.assertTrue(cliOutputBackupFile.exists());
    }

    @Test
    public void testTradeNotificationCli() throws IOException {

        mockTrade1();
        mockCliLocator(0);

        TradeHandler tradeHandler = new TradeHandler(tradeManager, keyRing, cliLocator);
        tradeHandler.onAllServicesInitialized();
        trade1Phase.set(Trade.Phase.TAKER_FEE_PUBLISHED);

        File tradeFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.trade.json");
        File cliOutputFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.cli-output.json");
        Assert.assertTrue(IOUtils.contentEquals(new FileInputStream(tradeFile), new FileInputStream(cliOutputFile)));
    }

    @Test
    public void testTradeNotificationCliTimeout() {

        mockTrade1();
        mockCliLocator(2);

        TradeHandler tradeHandler = new TradeHandler(tradeManager, keyRing, cliLocator);
        tradeHandler.onAllServicesInitialized();
        trade1Phase.set(Trade.Phase.TAKER_FEE_PUBLISHED);

        File cliOutputFile = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/trade1Id.cli-output.json");
        Assert.assertFalse(cliOutputFile.exists());
        try {
            Thread.sleep(2_000);
            Assert.assertTrue(cliOutputFile.exists());
        } catch (InterruptedException e) {
            e.printStackTrace();
            Assert.fail("Unexpected exception");
        }
    }

    private void mockCliLocator(int sleepSeconds) {

        Mockito.when(cliLocator.getTradeNotificationCliAbsolutePath()).then(a -> {
            File file = new File(Config.appDataDir().getAbsolutePath() + "/trade_data/cli.sh");
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write("#!/bin/bash\n");
                writer.write("sleep " + sleepSeconds + "\n");
                writer.write("cat \"$1\" > \"$2\"\n");
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
                fail("unexpected IOException");
            }
            file.setExecutable(true);
            return file.getAbsolutePath();
        });
    }

    private void mockTrade1() {
        Mockito.when(tradeManager.getObservableList()).thenReturn(new ObservableListWrapper<>(List.of(trade1)));
        Mockito.when(trade1.isPayoutPublished()).thenReturn(false);
        Mockito.when(trade1.getId()).thenReturn("trade1Id");
        Mockito.when(trade1.getTradePhase()).thenReturn(Trade.Phase.TAKER_FEE_PUBLISHED);
        Mockito.when(trade1.getTakeOfferDate()).thenReturn(123L);
        Mockito.when(trade1.getOffer().isBuyOffer()).thenReturn(false);
        Mockito.when(trade1.getOffer().isMyOffer(keyRing)).thenReturn(true);
        Mockito.when(trade1.getOffer().getPubKeyRing()).thenReturn(pubKeyRing);
        Mockito.when(trade1.getAmountAsLong()).thenReturn(700_000L);
        Mockito.when(trade1.getPrice().getValue()).thenReturn(10_000L);
        Mockito.when(trade1.getPrice().getCurrencyCode()).thenReturn("EUR");
        Mockito.when(trade1.getVolume().getValue()).thenReturn(10_000L * 700_000L);
        Mockito.when(trade1.getOffer().getMakerFee().getValue()).thenReturn(444L);
        Mockito.when(trade1.getOffer().isCurrencyForMakerFeeBtc()).thenReturn(true);
        Mockito.when(trade1.getTakerFeeAsLong()).thenReturn(445L);
        Mockito.when(trade1.isCurrencyForTakerFeeBtc()).thenReturn(true);
        Mockito.when(trade1.getTradeTxFeeAsLong()).thenReturn(500L);
    }
}
