package com.tabula.tabula_backend.conversation.service;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.model.*;
import com.tabula.tabula_backend.conversation.repository.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway.Binding;
import com.tabula.tabula_backend.integration.service.IntegrationService;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ConversationMetadataService {
    private final ConversationRepository conversations;
    private final ConversationTurnRepository turns;
    private final PortfolioService portfolios;
    private final IntegrationService integrations;
    private final ConversationJson json;

    public ConversationMetadataService(ConversationRepository conversations, ConversationTurnRepository turns,
                                       PortfolioService portfolios, IntegrationService integrations, ConversationJson json) {
        this.conversations = conversations;
        this.turns = turns;
        this.portfolios = portfolios;
        this.integrations = integrations;
        this.json = json;
    }

    public Ticket open(AccountPrincipal actor, String projectId, Open input, String key) {
        var project = portfolios.requireProjectOperation(actor, projectId, false);
        String hash = ConversationJson.hash(json.encode(input));
        var existing = conversations.findByProjectIdAndOpenKey(projectId, key);
        if (existing.isPresent()) {
            if (!existing.get().getOpenHash().equals(hash)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Chave reutilizada para outro atendimento.");
            }
            return ticket(existing.get(), project.portfolioId(), false);
        }
        var target = integrations.targetForConversation(actor, input.targetId().toString());
        if (!projectId.equals(target.projectId())) {
            throw notFound();
        }
        var binding = IbmConversationGateway.bind(input.mode().name(), target.configuration()).withConnection(integrations.connectionReference(target.integrationId()));
        var c = conversations.saveAndFlush(new Conversation(projectId, target.id(), actor.id(), input.mode().name(), json.encode(binding), key, hash));
        return ticket(c, project.portfolioId(), true);
    }

    public View activate(String id, String sessionId) {
        var c = conversations.findLocked(id).orElseThrow(ConversationMetadataService::notFound);
        if (!"OPENING".equals(c.getState()) && !"OPEN_UNKNOWN".equals(c.getState())) {
            throw conflict("CONVERSATION_STATE_CHANGED", "Estado do atendimento foi alterado.");
        }
        c.activate(sessionId);
        return View.from(c);
    }

    public void openingFailure(String id, String code, boolean unknown) {
        var c = conversations.findLocked(id).orElseThrow(ConversationMetadataService::notFound);
        if ("OPENING".equals(c.getState()) || "OPEN_UNKNOWN".equals(c.getState())) {
            c.state(unknown ? "OPEN_UNKNOWN" : "OPEN_FAILED", code);
        }
    }

    @Transactional(readOnly = true)
    public Ticket read(AccountPrincipal actor, String id) {
        var c = conversations.findById(id).orElseThrow(ConversationMetadataService::notFound);
        return ticket(c, portfolios.project(actor, c.getProjectId()).portfolioId(), false);
    }

    @Transactional(readOnly = true)
    public PageView<View> list(AccountPrincipal actor, String projectId, int page, int size) {
        portfolios.project(actor, projectId);
        return PageView.from(conversations.findByProjectId(projectId, paging(page, size, "id")).map(View::from));
    }

    public TurnTicket reserveTurn(AccountPrincipal actor, String id, String key, String hash) {
        var c = operate(actor, id, false);
        var existing = turns.findByConversationIdAndRequestKey(id, key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw conflict("IDEMPOTENCY_CONFLICT", "Chave reutilizada com outra mensagem.");
            }
            return turnTicket(actor, c, existing.get(), false);
        }
        if (!"ACTIVE".equals(c.getState())) {
            throw conflict("CONVERSATION_NOT_ACTIVE", "Atendimento não está ativo. Consulte o estado ou encerre-o.");
        }
        if (c.getActiveTurnId() != null) {
            throw conflict("CONVERSATION_BUSY", "Existe uma mensagem em processamento ou aguardando reconciliação.");
        }
        integrations.targetForConversation(actor, c.getTargetId());
        var turn = turns.saveAndFlush(new ConversationTurn(c, key, hash));
        return turnTicket(actor, c, turn, true);
    }

    public void markSending(String turnId) {
        var locked = lockTurn(turnId);
        if (!"RESERVED".equals(locked.turn().getState()) || !turnId.equals(locked.conversation().getActiveTurnId())) {
            throw conflict("TURN_STATE_CHANGED", "A tentativa não está disponível para envio.");
        }
        locked.turn().sending();
    }

    public void completeTurn(String turnId, String responseHash) {
        var locked = lockTurn(turnId);
        var t = locked.turn();
        var c = locked.conversation();
        if ("COMPLETED".equals(t.getState())) {
            if (!responseHash.equals(t.getResponseHash())) {
                throw conflict("TURN_RESPONSE_CONFLICT", "Checksum da resposta difere da confirmação.");
            }
            return;
        }
        if (!turnId.equals(c.getActiveTurnId())) {
            throw conflict("TURN_ORDER_CONFLICT", "Não é possível confirmar uma mensagem fora de ordem.");
        }
        t.complete(responseHash);
        c.finishTurn(turnId, true);
    }

    public void failTurn(String turnId, String code, boolean unknown, boolean sessionExpired) {
        var locked = lockTurn(turnId);
        var t = locked.turn();
        var c = locked.conversation();
        if ("COMPLETED".equals(t.getState())) {
            return;
        }
        // A delayed failure for a finalized attempt cannot change another active attempt.
        if (!turnId.equals(c.getActiveTurnId())) {
            return;
        }
        t.fail(code, unknown);
        if (unknown) {
            if ("ACTIVE".equals(c.getState()) || "BLOCKED".equals(c.getState())) {
                c.state("BLOCKED", code);
            }
        } else {
            c.finishTurn(turnId, false);
            if (sessionExpired && !"CLOSED".equals(c.getState()) && !"CLOSING".equals(c.getState())) {
                c.state("SESSION_EXPIRED", code);
            }
        }
    }

    public TurnTicket turn(AccountPrincipal actor, String id, String turnId, boolean operate) {
        var c = operate ? operate(actor, id, true) : conversations.findById(id).orElseThrow(ConversationMetadataService::notFound);
        var t = turns.findById(turnId).orElseThrow(ConversationMetadataService::notFound);
        if (!id.equals(t.getConversationId())) {
            throw notFound();
        }
        return turnTicket(actor, c, t, false);
    }

    @Transactional(readOnly = true)
    public PageView<TurnInfo> turns(AccountPrincipal actor, String id, int page, int size) {
        read(actor, id);
        return PageView.from(turns.findByConversationId(id, paging(page, size, "sequence")).map(TurnInfo::from));
    }

    public void expireTurn(String turnId) {
        var locked = lockTurn(turnId);
        var t = locked.turn();
        if ("COMPLETED".equals(t.getState()) || "FAILED".equals(t.getState()) || "UNKNOWN".equals(t.getState())) {
            return;
        }
        if (t.getLeaseUntil() > System.currentTimeMillis()) {
            throw conflict("TURN_IN_PROGRESS", "A tentativa ainda pode estar em andamento.");
        }
        boolean unknown = !"RESERVED".equals(t.getState());
        failTurn(turnId, unknown ? "IBM_RESULT_UNKNOWN" : "TURN_NOT_DISPATCHED", unknown, false);
    }

    public Ticket reserveClose(AccountPrincipal actor, String id) {
        var c = operate(actor, id, true);
        if ("CLOSED".equals(c.getState()) || "CLOSING".equals(c.getState())) {
            return ticketFor(actor, c, false);
        }
        if ("OPENING".equals(c.getState())) {
            throw conflict("CONVERSATION_OPENING", "Reconcilie a abertura antes de encerrar.");
        }
        if (c.getActiveTurnId() != null) {
            var t = turns.findById(c.getActiveTurnId()).orElseThrow(ConversationMetadataService::notFound);
            if ("RESERVED".equals(t.getState()) || "SENDING".equals(t.getState())) {
                throw conflict("CONVERSATION_BUSY", "Aguarde ou reconcilie a mensagem antes de encerrar.");
            }
        }
        if ("V1".equals(c.getMode()) || c.getSessionId() == null) {
            if ("OPEN_UNKNOWN".equals(c.getState())) {
                c.state("OPEN_UNKNOWN", "REMOTE_SESSION_UNCONFIRMED");
            }
            c.closed();
            return ticketFor(actor, c, false);
        }
        c.state("CLOSING", null);
        c.renewLease();
        return ticketFor(actor, c, true);
    }

    public View closed(String id) {
        var c = conversations.findLocked(id).orElseThrow(ConversationMetadataService::notFound);
        c.closed();
        return View.from(c);
    }

    public void closingFailure(String id, String code) {
        var c = conversations.findLocked(id).orElseThrow(ConversationMetadataService::notFound);
        if (!"CLOSED".equals(c.getState())) {
            c.state("CLOSE_UNKNOWN", code);
        }
    }

    public View reconcileLifecycle(AccountPrincipal actor, String id) {
        var c = operate(actor, id, true);
        if ("OPENING".equals(c.getState()) || "CLOSING".equals(c.getState())) {
            if (c.getLeaseUntil() > System.currentTimeMillis()) {
                throw conflict("CONVERSATION_IN_PROGRESS", "Operação ainda pode estar em andamento.");
            }
            c.state("OPENING".equals(c.getState()) ? "OPEN_UNKNOWN" : "CLOSE_UNKNOWN", "IBM_LIFECYCLE_UNKNOWN");
        }
        return View.from(c);
    }

    private Conversation operate(AccountPrincipal actor, String id, boolean allowArchived) {
        String projectId = conversations.findProjectId(id).orElseThrow(ConversationMetadataService::notFound);
        portfolios.requireProjectOperation(actor, projectId, allowArchived);
        return conversations.findLocked(id).orElseThrow(ConversationMetadataService::notFound);
    }

    private Ticket ticketFor(AccountPrincipal actor, Conversation c, boolean created) {
        return ticket(c, portfolios.project(actor, c.getProjectId()).portfolioId(), created);
    }

    private TurnTicket turnTicket(AccountPrincipal actor, Conversation c, ConversationTurn t, boolean created) {
        return new TurnTicket(ticketFor(actor, c, false), TurnInfo.from(t), created);
    }

    private Ticket ticket(Conversation c, String portfolioId, boolean created) {
        return new Ticket(View.from(c), portfolioId, json.decode(c.getBindingJson(), Binding.class), c.getSessionId(), c.getLastTurnId(), created);
    }

    private record Locked(Conversation conversation, ConversationTurn turn) {
    }

    private Locked lockTurn(String id) {
        String conversationId = turns.findConversationId(id).orElseThrow(ConversationMetadataService::notFound);
        var c = conversations.findLocked(conversationId).orElseThrow(ConversationMetadataService::notFound);
        return new Locked(c, turns.findById(id).orElseThrow(ConversationMetadataService::notFound));
    }

    private static Pageable paging(int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ConversationException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Paginação inválida.");
        }
        return PageRequest.of(page, size, Sort.by(sort));
    }

    private static ConversationException notFound() {
        return new ConversationException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Atendimento ou mensagem não encontrado.");
    }

    private static ConversationException conflict(String code, String message) {
        return new ConversationException(HttpStatus.CONFLICT, code, message);
    }
}
