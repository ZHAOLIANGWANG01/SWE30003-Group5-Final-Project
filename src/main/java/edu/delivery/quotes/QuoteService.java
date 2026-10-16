package edu.delivery.quotes;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryLifecycle;
import edu.delivery.contract.DeliveryStore;
import edu.delivery.contract.DeliveryData.*;
import edu.delivery.contract.StoreException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** Member B quote workflow: locks + validation + atomic status/event writes. */
@Service
public class QuoteService {
  private final DeliveryStore store;
  private final AccessGuard access;
  private final DeliveryLifecycle lifecycle;
  private final Clock clock;

  public QuoteService(DeliveryStore store, AccessGuard access, DeliveryLifecycle lifecycle, Clock clock) {
    this.store = store;
    this.access = access;
    this.lifecycle = lifecycle;
    this.clock = clock;
  }

  public List<QuoteData> listQuotes(long requestId) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    access.requireRequestRead(requestId);
    return store.findQuotesForRequest(requestId);
  }

  public QuoteData viewQuote(long quoteId) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    QuoteData quote = store.findQuote(quoteId)
        .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND, "Quote not found"));
    access.requireRequestRead(quote.requestId());
    return quote;
  }

  public QuoteData issueQuote(long requestId, long expectedRequestVersion, BigDecimal amount,
      OffsetDateTime pickupAt, OffsetDateTime deliveryAt, OffsetDateTime expiresAt) {
    access.requireRole(Role.OFFICE_STAFF);
    validateQuote(amount, pickupAt, deliveryAt, expiresAt, clock);
    return store.inTransaction(tx -> {
      tx.lockRequest(requestId);
      RequestData request = tx.findRequest(requestId)
          .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND, "Request not found"));
      if (request.version() != expectedRequestVersion) {
        throw new StoreException(StoreException.Code.STALE_DATA, "Request has changed; reload it");
      }
      if (request.status() != RequestStatus.REQUESTED && request.status() != RequestStatus.QUOTED) {
        throw new IllegalArgumentException("Only open requests can be quoted");
      }
      // Reject incomplete requests even when data was imported outside the B screens.
      edu.delivery.requests.RequestService.validateDetails(request.details());
      List<QuoteData> previous = tx.findQuotesForRequest(requestId);
      for (QuoteData old : previous) {
        if (old.status() == QuoteStatus.ISSUED) {
          if (old.expiresAt().isAfter(OffsetDateTime.now(clock))) {
            throw new IllegalArgumentException("An active quote already exists");
          }
          tx.updateQuoteStatus(old.id(), old.version(), QuoteStatus.EXPIRED);
        }
      }
      QuoteData quote = tx.createQuote(new QuoteDraft(requestId, amount, pickupAt, deliveryAt, expiresAt));
      EventDraft event = new EventDraft(EventType.QUOTED, OffsetDateTime.now(clock),
          access.current().userId(), "Quote #" + quote.id() + " issued");
      RequestStatus next = lifecycle.nextStatus(request, tx.findEvents(requestId), event);
      tx.saveStatusAndEvent(requestId, request.version(), next, event);
      return quote;
    });
  }

  public QuoteData decideQuote(long quoteId, long expectedVersion, long expectedRequestVersion,
      boolean accept) {
    access.requireRole(Role.CUSTOMER);
    QuoteData initially = viewQuote(quoteId);
    return store.inTransaction(tx -> {
      tx.lockRequest(initially.requestId());
      QuoteData quote = tx.findQuote(quoteId)
          .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND, "Quote not found"));
      RequestData request = tx.findRequest(quote.requestId())
          .orElseThrow(() -> new StoreException(StoreException.Code.NOT_FOUND, "Request not found"));
      access.requireCustomer(request.customerId());
      if (quote.version() != expectedVersion || request.version() != expectedRequestVersion) {
        throw new StoreException(StoreException.Code.STALE_DATA, "Record changed; reload before deciding");
      }
      if (request.status() != RequestStatus.QUOTED || quote.status() != QuoteStatus.ISSUED) {
        throw new IllegalArgumentException("This quote is no longer open");
      }
      var latest = tx.findQuotesForRequest(request.id());
      if (latest.isEmpty() || latest.get(0).id() != quote.id()) {
        throw new IllegalArgumentException("This quote has been superseded");
      }
      if (!quote.expiresAt().isAfter(OffsetDateTime.now(clock))) {
        // Persist EXPIRED without throwing inside this transaction (which would roll it back).
        return tx.updateQuoteStatus(quote.id(), quote.version(), QuoteStatus.EXPIRED);
      }
      if (!accept) return tx.updateQuoteStatus(quote.id(), quote.version(), QuoteStatus.DECLINED);
      EventDraft event = new EventDraft(EventType.QUOTE_ACCEPTED, OffsetDateTime.now(clock),
          access.current().userId(), "Quote #" + quote.id() + " accepted");
      RequestStatus next = lifecycle.nextStatus(request, tx.findEvents(request.id()), event);
      QuoteData accepted = tx.updateQuoteStatus(quote.id(), quote.version(), QuoteStatus.ACCEPTED);
      tx.saveStatusAndEvent(request.id(), request.version(), next, event);
      return accepted;
    });
  }

  public static void validateQuote(BigDecimal amount, OffsetDateTime pickupAt,
      OffsetDateTime deliveryAt, OffsetDateTime expiresAt, Clock clock) {
    if (amount == null || amount.signum() <= 0 || amount.scale() > 2 ||
        amount.compareTo(new BigDecimal("9999999999.99")) > 0) {
      throw new IllegalArgumentException("Amount must be positive with at most two decimals");
    }
    if (pickupAt == null || deliveryAt == null || expiresAt == null) {
      throw new IllegalArgumentException("Pickup, delivery and expiry times are required");
    }
    if (!pickupAt.isBefore(deliveryAt)) throw new IllegalArgumentException("Delivery must follow pickup");
    if (!expiresAt.isBefore(pickupAt)) throw new IllegalArgumentException("Quote must expire before pickup");
    if (!pickupAt.isAfter(OffsetDateTime.now(clock)) || !expiresAt.isAfter(OffsetDateTime.now(clock))) {
      throw new IllegalArgumentException("Pickup and expiry must be in the future");
    }
  }
}
