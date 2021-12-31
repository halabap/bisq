package bisq.custom.notifications;

import bisq.core.trade.model.bisq_v1.Trade;

import lombok.Getter;

public class TradeDTO {

    @Getter
    private String id;

    @Getter
    private Trade.Phase tradePhase;

    @Getter
    private long takeOfferDate;

    @Getter
    private boolean buyOffer;

    @Getter
    private boolean myOffer;

    /**
     * Amount in Satoshi.
     */
    @Getter
    private long amountSatoshi;

    /**
     * Price in currency for 1 BTC.
     */
    @Getter
    private long priceValue;

    @Getter
    private String priceCurrencyCode;

    /**
     * Trade volume in currency as amount * price rounded to nearest 1.
     */
    @Getter
    private long volume;

    @Getter
    private long makerFee;

    @Getter
    private boolean currencyForMakerFeeBtc;

    @Getter
    private long makerTxFee;

    @Getter
    private long takerFee;

    @Getter
    private boolean currencyForTakerFeeBtc;

    @Getter
    private long takerTxFee;

    // empty constructor for JSON deserialization
    public TradeDTO() {

    }

    public TradeDTO(String id,
                    Trade.Phase tradePhase,
                    long takeOfferDate,
                    boolean buyOffer,
                    boolean myOffer,
                    long amountSatoshi,
                    long priceValue,
                    String priceCurrencyCode,
                    long volume,
                    long makerFee,
                    boolean currencyForMakerFeeBtc,
                    long makerTxFee,
                    long takerFee,
                    boolean currencyForTakerFeeBtc,
                    long takerTxFee) {
        this.id = id;
        this.tradePhase = tradePhase;
        this.takeOfferDate = takeOfferDate;
        this.buyOffer = buyOffer;
        this.myOffer = myOffer;
        this.amountSatoshi = amountSatoshi;
        this.priceValue = priceValue;
        this.priceCurrencyCode = priceCurrencyCode;
        this.volume = volume;
        this.makerFee = makerFee;
        this.currencyForMakerFeeBtc = currencyForMakerFeeBtc;
        this.makerTxFee = makerTxFee;
        this.takerFee = takerFee;
        this.currencyForTakerFeeBtc = currencyForTakerFeeBtc;
        this.takerTxFee = takerTxFee;
    }
}
