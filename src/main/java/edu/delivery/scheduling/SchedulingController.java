package edu.delivery.scheduling;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryData.Role;
import edu.delivery.contract.StoreException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SchedulingController {
  private final SchedulingService schedules;
  private final AccessGuard access;

  public SchedulingController(SchedulingService schedules, AccessGuard access) {
    this.schedules = schedules;
    this.access = access;
  }

  @GetMapping("/scheduling")
  public String board(Model model) {
    model.addAttribute("candidates", schedules.acceptedRequestsToSchedule());
    model.addAttribute("staff", schedules.staff());
    model.addAttribute("schedules", schedules.schedules());
    return "scheduling/board";
  }

  @PostMapping("/scheduling/assign")
  public String assign(@RequestParam long requestId, @RequestParam long staffId,
      RedirectAttributes flash) {
    try {
      schedules.assign(requestId, staffId);
      flash.addFlashAttribute("successMessage", "Delivery assigned using the customer's accepted quote time.");
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", safeMessage(ex));
    }
    return "redirect:/scheduling";
  }

  @PostMapping("/scheduling/{requestId}/reassign")
  public String reassign(@PathVariable long requestId, @RequestParam long staffId,
      RedirectAttributes flash) {
    try {
      schedules.reassign(requestId, staffId);
      flash.addFlashAttribute("successMessage", "Delivery staff reassigned.");
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", safeMessage(ex));
    }
    return "redirect:/scheduling";
  }

  @PostMapping("/scheduling/{requestId}/time-change")
  public String proposeTimeChange(@PathVariable long requestId,
      @RequestParam long scheduleVersion,
      @RequestParam String pickupAt, @RequestParam String deliveryAt,
      RedirectAttributes flash) {
    try {
      schedules.proposeTimeChange(requestId, scheduleVersion,
          MelbourneTimeParser.parse(pickupAt), MelbourneTimeParser.parse(deliveryAt));
      flash.addFlashAttribute("successMessage", "Time change sent to the customer. The current schedule remains in place until confirmation.");
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", safeMessage(ex));
    }
    return "redirect:/scheduling";
  }

  @GetMapping("/schedule-changes")
  public String changes(Model model) {
    if (access.current().role() == Role.CUSTOMER) {
      model.addAttribute("changes", schedules.pendingChangesForCustomer());
      model.addAttribute("customerSchedules", schedules.schedulesForCustomer());
      return "schedule-changes/customer";
    }
    model.addAttribute("changes", schedules.pendingChangesForOffice());
    return "schedule-changes/office";
  }

  @PostMapping("/schedule-changes/{requestId}/decision")
  public String decide(@PathVariable long requestId, @RequestParam long changeId,
      @RequestParam long version, @RequestParam boolean accept,
      RedirectAttributes flash) {
    try {
      SchedulingService.DecisionResult result = schedules.decideTimeChange(requestId, changeId, version, accept);
      String message = switch (result) {
        case CONFIRMED -> "New delivery time confirmed.";
        case DECLINED -> "Proposal declined. Your current delivery time is unchanged.";
        case STALE -> "The original schedule changed. This proposal is no longer valid; contact the office for a new one.";
      };
      flash.addFlashAttribute(result == SchedulingService.DecisionResult.STALE ? "errorMessage" : "successMessage", message);
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", safeMessage(ex));
    }
    return "redirect:/schedule-changes";
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
