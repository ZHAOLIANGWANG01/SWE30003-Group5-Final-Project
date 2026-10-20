package edu.delivery.delivery;

import edu.delivery.contract.DeliveryData.EventType;
import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.StoreException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DeliveryController {
  private final DeliveryService deliveries;
  private final AccessGuard access;

  public DeliveryController(DeliveryService deliveries, AccessGuard access) {
    this.deliveries = deliveries;
    this.access = access;
  }

  @GetMapping("/delivery/tasks")
  public String tasks(Model model) {
    model.addAttribute("tasks", deliveries.myTasks());
    model.addAttribute("isOffice", access.current().role() == edu.delivery.contract.DeliveryData.Role.OFFICE_STAFF);
    return "delivery/tasks";
  }

  @GetMapping("/delivery/tasks/{requestId}")
  public String task(@PathVariable long requestId, Model model) {
    model.addAttribute("task", deliveries.task(requestId));
    return "delivery/task";
  }

  @PostMapping("/delivery/tasks/{requestId}/events")
  public String record(@PathVariable long requestId, @RequestParam String type,
      @RequestParam(defaultValue = "") String note, RedirectAttributes flash) {
    try {
      deliveries.record(requestId, EventType.valueOf(type), note);
      flash.addFlashAttribute("successMessage", "Delivery progress saved.");
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", safeMessage(ex));
    }
    return "redirect:/delivery/tasks/" + requestId;
  }

  private static String safeMessage(RuntimeException error) {
    if (error instanceof StoreException storeError
        && (storeError.code() == StoreException.Code.STORAGE_ERROR
            || storeError.code() == StoreException.Code.TRANSACTION_REQUIRED)) {
      return "The operation could not be completed. Please try again or contact the team.";
    }
    return error.getMessage();
  }
}
