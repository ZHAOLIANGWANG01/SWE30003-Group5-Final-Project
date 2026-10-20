package edu.delivery.availability;

import edu.delivery.auth.AccessGuard;
import edu.delivery.contract.DeliveryData.AvailabilityKind;
import edu.delivery.contract.DeliveryData.AvailabilityData;
import edu.delivery.contract.DeliveryData.StaffData;
import edu.delivery.contract.StoreException;
import edu.delivery.scheduling.MelbourneTimeParser;
import java.util.List;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AvailabilityController {
  private final AvailabilityService availability;
  private final AccessGuard access;

  public AvailabilityController(AvailabilityService availability, AccessGuard access) {
    this.availability = availability;
    this.access = access;
  }

  @GetMapping("/availability")
  public String list(@RequestParam(required = false) Long staffId, Model model) {
    List<StaffData> staff = availability.staff();
    Long selected = staffId;
    if (selected == null && staff.size() == 1) {
      selected = staff.get(0).id();
    }
    model.addAttribute("staff", staff);
    model.addAttribute("selectedStaffId", selected);
    model.addAttribute("periods", selected == null ? List.of() : availability.list(selected).stream()
        .map(AvailabilityController::view).toList());
    return "availability/list";
  }

  @PostMapping("/availability/save")
  public String save(@RequestParam long staffId,
      @RequestParam(required = false) Long availabilityId,
      @RequestParam(required = false) Long version,
      @RequestParam String startAt, @RequestParam String endAt,
      @RequestParam AvailabilityKind kind, RedirectAttributes flash) {
    try {
      availability.save(staffId, availabilityId, version,
          MelbourneTimeParser.parse(startAt), MelbourneTimeParser.parse(endAt), kind);
      flash.addFlashAttribute("successMessage", "Availability saved.");
    } catch (IllegalArgumentException | StoreException ex) {
      flash.addFlashAttribute("errorMessage", safeMessage(ex));
    }
    return "redirect:/availability?staffId=" + staffId;
  }

  private static String safeMessage(RuntimeException error) {
    if (error instanceof StoreException storeError
        && (storeError.code() == StoreException.Code.STORAGE_ERROR
            || storeError.code() == StoreException.Code.TRANSACTION_REQUIRED)) {
      return "The operation could not be completed. Please try again or contact the team.";
    }
    return error.getMessage();
  }

  private static PeriodView view(AvailabilityData period) {
    DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    return new PeriodView(period, period.startAt().toLocalDateTime().format(format),
        period.endAt().toLocalDateTime().format(format));
  }

  public record PeriodView(AvailabilityData period, String startLocal, String endLocal) {}
}
