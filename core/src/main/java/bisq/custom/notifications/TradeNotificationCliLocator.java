package bisq.custom.notifications;

import javax.inject.Singleton;

import java.io.File;

import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class TradeNotificationCliLocator {

    public String getTradeNotificationCliAbsolutePath() {
        String tradeNotificationCli = System.getenv("TRADE_NOTIFICATION_CLI");
        if (tradeNotificationCli == null) {
            return null;
        } else {
            if (new File(tradeNotificationCli).exists()) {
                log.info("Found trade notification cli='{}'",tradeNotificationCli);
                return tradeNotificationCli;
            } else {
                log.error("Trade notification cli binary does not exist, file={}", tradeNotificationCli);
                return null;
            }
        }
    }
}
