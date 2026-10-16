package edu.delivery.quotes;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryData.*;
import edu.delivery.contract.StoreException;
import edu.delivery.requests.RequestService;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Member B quote screens and form actions. */
@Controller
public class QuoteController {
  private static final ZoneId MELBOURNE = ZoneId.of("Australia/Melbourne");
  private final AccessGuard access;
  private final RequestService requests;
  private final QuoteService quotes;
  private final Clock clock;

  public QuoteController(AccessGuard access, RequestService requests,
      QuoteService quotes, Clock clock) {
    this.access = access;
    this.requests = requests;
    this.quotes = quotes;
    this.clock = clock;
  }

  @GetMapping("/quotes")
  public String list(@RequestParam long requestId, Model model) {
    model.addAttribute("request", requests.viewRequest(requestId));
    model.addAttribute("quotes", quotes.listQuotes(requestId));
    model.addAttribute("office", isOffice());
    return "quotes/list";
  }

  @GetMapping("/quotes/new")
  public String newQuote(@RequestParam long requestId, Model model) {
    access.requireRole(Role.OFFICE_STAFF);
    model.addAttribute("request", requests.viewRequest(requestId));
    model.addAttribute("form", Map.of());
    return "quotes/new";
  }

  @PostMapping("/quotes/new")
  public String issue(@RequestParam Map<String, String> form,
      Model model, RedirectAttributes flash) {
    access.requireRole(Role.OFFICE_STAFF);
    long requestId = positiveId(form.get("requestId"));
    try {
      var quote = quotes.issueQuote(requestId, Long.parseLong(form.get("version")),
          new BigDecimal(form.get("amount")), localTime(form.get("pickupAt")),
          localTime(form.get("deliveryAt")), localTime(form.get("expiresAt")));
      flash.addFlashAttribute("successMessage", "Quote issued successfully");
      return "redirect:/quotes/" + quote.id();
    } catch (IllegalArgumentException | StoreException ex) {
      model.addAttribute("request", requests.viewRequest(requestId));
      model.addAttribute("form", form);
      model.addAttribute("errorMessage", "Could not create quote: " + ex.getMessage());
      return "quotes/new";
    }
  }

  @GetMapping("/quotes/{id}")
  public String detail(@PathVariable long id, Model model) {
    QuoteData quote = quotes.viewQuote(id);
    RequestData request = requests.viewRequest(quote.requestId());
    model.addAttribute("quote", quote);
    model.addAttribute("request", request);
    model.addAttribute("office", isOffice());
    model.addAttribute("expired", !quote.expiresAt().isAfter(OffsetDateTime.now(clock)));
    model.addAttribute("canDecide", access.current().role() == Role.CUSTOMER
        && quote.status() == QuoteStatus.ISSUED && request.status() == RequestStatus.QUOTED
        && quote.expiresAt().isAfter(OffsetDateTime.now(clock))
        && !quotes.listQuotes(request.id()).isEmpty()
        && quotes.listQuotes(request.id()).get(0).id() == id);
    return "quotes/detail";
  }

  @PostMapping("/quotes/{id}/decision")
  public String decide(@PathVariable long id, @RequestParam long quoteVersion,
      @RequestParam long requestVersion, @RequestParam String decision,
      RedirectAttributes flash) {
    access.requireRole(Role.CUSTOMER);
    if (!decision.equals("accept") && !decision.equals("decline")) {
      throw new IllegalArgumentException("Invalid quote decision");
    }
    try {
      QuoteData updated = quotes.decideQuote(id, quoteVersion, requestVersion,
          decision.equals("accept"));
      flash.addFlashAttribute("successMessage", "Quote status: " + updated.status());
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", ex.getMessage());
    }
    return "redirect:/quotes/" + id;
  }

  private boolean isOffice() { return access.current().role() == Role.OFFICE_STAFF; }

  static OffsetDateTime localTime(String raw) {
    try {
      LocalDateTime local = LocalDateTime.parse(raw);
      var offsets = MELBOURNE.getRules().getValidOffsets(local);
      if (offsets.size() != 1) {
        throw new IllegalArgumentException("Time is missing or ambiguous during Melbourne daylight saving change");
      }
      return OffsetDateTime.of(local, offsets.get(0));
    } catch (DateTimeParseException | NullPointerException ex) {
      throw new IllegalArgumentException("Enter a valid date and time", ex);
    }
  }

  private static long positiveId(String raw) {
    try {
      long id = Long.parseLong(raw);
      if (id > 0) return id;
    } catch (NumberFormatException ex) {
      // Validated below.
    }
    throw new IllegalArgumentException("Invalid request ID");
  }
}
