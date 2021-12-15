package bisq.custom.notifications;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

public class TradePhasesDTO {

    public static class TradePhaseItem {
        @Getter
        private long date;
        @Getter
        private String phase;

        public TradePhaseItem(long date, String phase) {
            this.date = date;
            this.phase = phase;
        }
    }

    @Getter
    private List<TradePhaseItem> phases = new ArrayList<>();
}
