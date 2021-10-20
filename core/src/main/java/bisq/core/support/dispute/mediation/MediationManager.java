/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.support.dispute.mediation;

import bisq.core.btc.setup.WalletsSetup;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TradeWalletService;
import bisq.core.dao.DaoFacade;
import bisq.core.locale.Res;
import bisq.core.offer.OpenOffer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.support.SupportType;
import bisq.core.support.dispute.Dispute;
import bisq.core.support.dispute.DisputeManager;
import bisq.core.support.dispute.DisputeResult;
import bisq.core.support.dispute.agent.DisputeAgentLookupMap;
import bisq.core.support.dispute.messages.DisputeResultMessage;
import bisq.core.support.dispute.messages.OpenNewDisputeMessage;
import bisq.core.support.dispute.messages.PeerOpenedDisputeMessage;
import bisq.core.support.messages.ChatMessage;
import bisq.core.support.messages.SupportMessage;
import bisq.core.trade.TradeManager;
import bisq.core.trade.bisq_v1.ClosedTradableManager;
import bisq.core.trade.model.bisq_v1.Trade;
import bisq.core.trade.protocol.bisq_v1.DisputeProtocol;
import bisq.core.trade.protocol.bisq_v1.model.ProcessModel;

import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.config.Config;
import bisq.common.crypto.KeyRing;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import org.bitcoinj.core.Coin;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
@Singleton
public final class MediationManager extends DisputeManager<MediationDisputeList> {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MediationManager(P2PService p2PService,
                            TradeWalletService tradeWalletService,
                            BtcWalletService walletService,
                            WalletsSetup walletsSetup,
                            TradeManager tradeManager,
                            ClosedTradableManager closedTradableManager,
                            OpenOfferManager openOfferManager,
                            DaoFacade daoFacade,
                            KeyRing keyRing,
                            MediationDisputeListService mediationDisputeListService,
                            Config config,
                            PriceFeedService priceFeedService) {
        super(p2PService, tradeWalletService, walletService, walletsSetup, tradeManager, closedTradableManager,
                openOfferManager, daoFacade, keyRing, mediationDisputeListService, config, priceFeedService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Implement template methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public SupportType getSupportType() {
        return SupportType.MEDIATION;
    }

    @Override
    public void onSupportMessage(SupportMessage message) {
        if (canProcessMessage(message)) {
            log.info("Received {} with tradeId {} and uid {}",
                    message.getClass().getSimpleName(), message.getTradeId(), message.getUid());

            if (message instanceof OpenNewDisputeMessage) {
                onOpenNewDisputeMessage((OpenNewDisputeMessage) message);
            } else if (message instanceof PeerOpenedDisputeMessage) {
                onPeerOpenedDisputeMessage((PeerOpenedDisputeMessage) message);
            } else if (message instanceof ChatMessage) {
                onChatMessage((ChatMessage) message);
            } else if (message instanceof DisputeResultMessage) {
                onDisputeResultMessage((DisputeResultMessage) message);
            } else {
                log.warn("Unsupported message at dispatchMessage. message={}", message);
            }
        }
    }

    @Override
    protected Trade.DisputeState getDisputeStateStartedByPeer() {
        return Trade.DisputeState.MEDIATION_STARTED_BY_PEER;
    }

    @Override
    protected AckMessageSourceType getAckMessageSourceType() {
        return AckMessageSourceType.MEDIATION_MESSAGE;
    }

    @Override
    public void cleanupDisputes() {
        disputeListService.cleanupDisputes(tradeId -> {
            tradeManager.getTradeById(tradeId).filter(trade -> trade.getPayoutTx() != null)
                    .ifPresent(trade -> {
                        tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.MEDIATION_CLOSED);
                    });
        });
    }

    @Override
    protected String getDisputeInfo(Dispute dispute) {
        String role = Res.get("shared.mediator").toLowerCase();
        String roleContextMsg = Res.get("support.initialMediatorMsg",
                DisputeAgentLookupMap.getKeybaseLinkForAgent(getAgentNodeAddress(dispute).getFullAddress()));
        String link = "https://bisq.wiki/Dispute_resolution#Level_2:_Mediation";
        return Res.get("support.initialInfo", role, roleContextMsg, role, link);
    }

    @Override
    protected String getDisputeIntroForPeer(String disputeInfo) {
        return Res.get("support.peerOpenedDisputeForMediation", disputeInfo, Version.VERSION);
    }

    @Override
    protected String getDisputeIntroForDisputeCreator(String disputeInfo) {
        return Res.get("support.youOpenedDisputeForMediation", disputeInfo, Version.VERSION);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    // We get that message at both peers. The dispute object is in context of the trader
    public void onDisputeResultMessage(DisputeResultMessage disputeResultMessage) {
        DisputeResult disputeResult = disputeResultMessage.getDisputeResult();
        String tradeId = disputeResult.getTradeId();
        ChatMessage chatMessage = disputeResult.getChatMessage();
        checkNotNull(chatMessage, "chatMessage must not be null");
        Optional<Dispute> disputeOptional = findDispute(disputeResult);
        String uid = disputeResultMessage.getUid();
        if (!disputeOptional.isPresent()) {
            log.warn("We got a dispute result msg but we don't have a matching dispute. " +
                    "That might happen when we get the disputeResultMessage before the dispute was created. " +
                    "We try again after 2 sec. to apply the disputeResultMessage. TradeId = " + tradeId);
            if (!delayMsgMap.containsKey(uid)) {
                // We delay 2 sec. to be sure the comm. msg gets added first
                Timer timer = UserThread.runAfter(() -> onDisputeResultMessage(disputeResultMessage), 2);
                delayMsgMap.put(uid, timer);
            } else {
                log.warn("We got a dispute result msg after we already repeated to apply the message after a delay. " +
                        "That should never happen. TradeId = " + tradeId);
            }
            return;
        }

        Dispute dispute = disputeOptional.get();
        cleanupRetryMap(uid);
        if (!dispute.getChatMessages().contains(chatMessage)) {
            dispute.addAndPersistChatMessage(chatMessage);
        } else {
            log.warn("We got a dispute mail msg what we have already stored. TradeId = " + chatMessage.getTradeId());
        }
        dispute.setIsClosed();

        dispute.setDisputeResult(disputeResult);

        Optional<Trade> tradeOptional = tradeManager.getTradeById(tradeId);
        if (tradeOptional.isPresent()) {
            Trade trade = tradeOptional.get();
            if (trade.getDisputeState() == Trade.DisputeState.MEDIATION_REQUESTED ||
                    trade.getDisputeState() == Trade.DisputeState.MEDIATION_STARTED_BY_PEER) {
                trade.getProcessModel().setBuyerPayoutAmountFromMediation(disputeResult.getBuyerPayoutAmount().value);
                trade.getProcessModel().setSellerPayoutAmountFromMediation(disputeResult.getSellerPayoutAmount().value);

                trade.setDisputeState(Trade.DisputeState.MEDIATION_CLOSED);

                tradeManager.requestPersistence();
            }
        } else {
            Optional<OpenOffer> openOfferOptional = openOfferManager.getOpenOfferById(tradeId);
            openOfferOptional.ifPresent(openOffer -> openOfferManager.closeOpenOffer(openOffer.getOffer()));
        }
        sendAckMessage(chatMessage, dispute.getAgentPubKeyRing(), true, null);

        requestPersistence();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    @Override
    public NodeAddress getAgentNodeAddress(Dispute dispute) {
        return dispute.getContract().getMediatorNodeAddress();
    }

    public void onAcceptMediationResult(Trade trade,
                                        ResultHandler resultHandler,
                                        ErrorMessageHandler errorMessageHandler) {
        String tradeId = trade.getId();
        Optional<Dispute> optionalDispute = findDispute(tradeId);
        checkArgument(optionalDispute.isPresent(), "dispute must be present");
        DisputeResult disputeResult = optionalDispute.get().getDisputeResultProperty().get();
        Coin buyerPayoutAmount = disputeResult.getBuyerPayoutAmount();
        Coin sellerPayoutAmount = disputeResult.getSellerPayoutAmount();
        ProcessModel processModel = trade.getProcessModel();
        processModel.setBuyerPayoutAmountFromMediation(buyerPayoutAmount.value);
        processModel.setSellerPayoutAmountFromMediation(sellerPayoutAmount.value);
        DisputeProtocol tradeProtocol = (DisputeProtocol) tradeManager.getTradeProtocol(trade);

        trade.setMediationResultState(MediationResultState.MEDIATION_RESULT_ACCEPTED);
        tradeManager.requestPersistence();

        // If we have not got yet the peers signature we sign and send to the peer our signature.
        // Otherwise we sign and complete with the peers signature the payout tx.
        if (processModel.getTradePeer().getMediatedPayoutTxSignature() == null) {
            tradeProtocol.onAcceptMediationResult(() -> {
                if (trade.getPayoutTx() != null) {
                    tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.MEDIATION_CLOSED);
                }
                resultHandler.handleResult();
            }, errorMessageHandler);
        } else {
            tradeProtocol.onFinalizeMediationResultPayout(() -> {
                if (trade.getPayoutTx() != null) {
                    tradeManager.closeDisputedTrade(tradeId, Trade.DisputeState.MEDIATION_CLOSED);
                }
                resultHandler.handleResult();
            }, errorMessageHandler);
        }
    }

    public void rejectMediationResult(Trade trade) {
        trade.setMediationResultState(MediationResultState.MEDIATION_RESULT_REJECTED);
        tradeManager.requestPersistence();
    }
}
