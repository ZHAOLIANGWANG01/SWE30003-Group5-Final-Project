package edu.delivery.requests;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryData.*;
import edu.delivery.contract.StoreException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Member B website routes. Does not access SQL or persistence implementation. */
@Controller
public class RequestController {
  private final AccessGuard access;
  private final RequestService requests;

  public RequestController(AccessGuard access, RequestService requests) {
    this.access = access;
    this.requests = requests;
  }

  @GetMapping("/requests")
  public String list(@RequestParam(defaultValue = "0") int page, Model model) {
    var records = requests.listRequests(page);
    model.addAttribute("requests", records);
    model.addAttribute("page", page);
    model.addAttribute("hasNext", records.size() == 20);
    model.addAttribute("office", isOffice());
    return "requests/list";
  }

  @GetMapping("/requests/new")
  public String newRequest(Model model) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    model.addAttribute("office", isOffice());
    model.addAttribute("form", Map.of());
    model.addAttribute("submissionKey", UUID.randomUUID().toString());
    return "requests/new";
  }

  @PostMapping("/requests/new")
  public String submit(@RequestParam Map<String, String> form, Model model,
      RedirectAttributes flash) {
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    try {
      RequestData created = isOffice()
          ? requests.submitForCustomer(form.get("customerUsername"), form.get("submissionKey"), details(form))
          : requests.submitRequest(form.get("submissionKey"), details(form));
      flash.addFlashAttribute("successMessage", "Delivery request #" + created.id() + " saved successfully");
      return "redirect:/requests/" + created.id();
    } catch (IllegalArgumentException | StoreException ex) {
      model.addAttribute("errorMessage", ex.getMessage());
      model.addAttribute("form", form);
      model.addAttribute("office", isOffice());
      model.addAttribute("submissionKey", form.get("submissionKey"));
      return "requests/new";
    }
  }

  @GetMapping({"/requests/{id}", "/tracking/{id}"})
  public String detail(@PathVariable long id, Model model) {
    RequestData request = requests.viewRequest(id);
    model.addAttribute("request", request);
    model.addAttribute("office", isOffice());
    model.addAttribute("canEdit", request.status() == RequestStatus.REQUESTED
        && (isOffice() || request.customerId() == access.current().userId()));
    return "requests/detail";
  }

  @GetMapping("/requests/{id}/edit")
  public String edit(@PathVariable long id, Model model) {
    RequestData request = requests.viewRequest(id);
    access.requireRole(Role.CUSTOMER, Role.OFFICE_STAFF);
    access.requireCustomer(request.customerId());
    if (request.status() != RequestStatus.REQUESTED) {
      throw new IllegalArgumentException("This request can no longer be edited");
    }
    model.addAttribute("request", request);
    model.addAttribute("form", toMap(request.details()));
    return "requests/edit";
  }

  @PostMapping("/requests/{id}/edit")
  public String update(@PathVariable long id, @RequestParam long version,
      @RequestParam Map<String, String> form, Model model, RedirectAttributes flash) {
    try {
      requests.updateRequest(id, version, details(form));
      flash.addFlashAttribute("successMessage", "Request updated successfully");
      return "redirect:/requests/" + id;
    } catch (IllegalArgumentException | StoreException ex) {
      // Fetch the latest record for an accurate version and state; never silently overwrite stale edits.
      RequestData latest = requests.viewRequest(id);
      if (latest.status() != RequestStatus.REQUESTED) {
        flash.addFlashAttribute("errorMessage", "This request is now quoted and cannot be edited");
        return "redirect:/requests/" + id;
      }
      model.addAttribute("request", latest);
      model.addAttribute("form", form);
      model.addAttribute("errorMessage", ex.getMessage() + " Please check the latest record before resubmitting.");
      return "requests/edit";
    }
  }

  private boolean isOffice() {
    return access.current().role() == Role.OFFICE_STAFF;
  }

  static RequestDetails details(Map<String, String> f) {
    return new RequestDetails(f.get("customerName"), f.get("customerPhone"),
        f.get("pickupAddress"), f.get("deliveryAddress"), f.get("recipientName"),
        f.get("recipientPhone"), f.get("parcelDescription"), f.get("handlingNotes"));
  }

  private static Map<String, String> toMap(RequestDetails d) {
    Map<String, String> form = new HashMap<>();
    form.put("customerName", d.customerName());
    form.put("customerPhone", d.customerPhone());
    form.put("pickupAddress", d.pickupAddress());
    form.put("deliveryAddress", d.deliveryAddress());
    form.put("recipientName", d.recipientName());
    form.put("recipientPhone", d.recipientPhone());
    form.put("parcelDescription", d.parcelDescription());
    form.put("handlingNotes", d.handlingNotes() == null ? "" : d.handlingNotes());
    return form;
  }
}
